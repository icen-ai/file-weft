package ai.icen.fw.workflow.templates

import ai.icen.fw.workflow.api.WorkflowDefinitionStatus
import ai.icen.fw.workflow.api.WorkflowNodeDefinition
import ai.icen.fw.workflow.api.WorkflowNodeKind
import ai.icen.fw.workflow.api.WorkflowParticipantMembershipStrategy
import ai.icen.fw.workflow.api.WorkflowParticipantResolutionStage
import ai.icen.fw.workflow.api.WorkflowParticipantSelectorKind
import ai.icen.fw.workflow.api.WorkflowTransitionDefinition
import ai.icen.fw.workflow.api.WorkflowTransitionTrigger
import java.util.ArrayDeque
import java.util.Collections
import java.util.LinkedHashMap
import java.util.LinkedHashSet

class WorkflowTemplateLintIssue private constructor(
    code: String,
    nodeId: String?,
) {
    val code: String = ReferenceTemplateContractSupport.code(code, "Workflow template lint code is invalid.")
    val nodeId: String? = nodeId?.let { value ->
        ReferenceTemplateContractSupport.code(value, "Workflow template lint node is invalid.")
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is WorkflowTemplateLintIssue && code == other.code && nodeId == other.nodeId

    override fun hashCode(): Int = 31 * code.hashCode() + (nodeId?.hashCode() ?: 0)
    override fun toString(): String = "WorkflowTemplateLintIssue(code=$code,node=${nodeId ?: "definition"})"

    companion object {
        @JvmSynthetic
        internal fun create(code: String, nodeId: String?): WorkflowTemplateLintIssue =
            WorkflowTemplateLintIssue(code, nodeId)
    }
}

class WorkflowTemplateLintReport private constructor(
    val templateDigest: String,
    val definitionDigest: String,
    issues: Collection<WorkflowTemplateLintIssue>,
) {
    val issues: List<WorkflowTemplateLintIssue> = Collections.unmodifiableList(
        issues.distinct().sortedWith(compareBy({ it.code }, { it.nodeId ?: "" })),
    )
    val valid: Boolean
        get() = issues.isEmpty()

    fun hasIssue(code: String): Boolean = issues.any { issue -> issue.code == code }

    override fun toString(): String = "WorkflowTemplateLintReport(valid=$valid,issues=${issues.size})"

    companion object {
        @JvmSynthetic
        internal fun create(
            templateDigest: String,
            definitionDigest: String,
            issues: Collection<WorkflowTemplateLintIssue>,
        ): WorkflowTemplateLintReport = WorkflowTemplateLintReport(templateDigest, definitionDigest, issues)
    }
}

/** Strict reference-template lint. Provider availability and publication remain separate gates. */
class WorkflowReferenceTemplateLinter private constructor() {
    companion object {
        @JvmStatic
        fun lint(template: WorkflowReferenceTemplate): WorkflowTemplateLintReport {
            val issues = ArrayList<WorkflowTemplateLintIssue>()
            issues.addAll(lintGraph(template.definition.nodes, template.definition.transitions, template.cycleBudgets))
            lintIdentity(template, issues)
            lintHumanBindings(template, issues)
            lintPredicateBindings(template, issues)
            lintProviderBindings(template, issues)
            lintSimulationCases(template, issues)
            return WorkflowTemplateLintReport.create(
                template.templateDigest,
                template.definition.contentDigest,
                issues,
            )
        }

        /** Public raw-graph entry used before constructing [ai.icen.fw.workflow.api.WorkflowDefinition]. */
        @JvmStatic
        fun lintGraph(
            nodes: Collection<WorkflowNodeDefinition>,
            transitions: Collection<WorkflowTransitionDefinition>,
            cycleBudgets: Collection<WorkflowTemplateCycleBudget>,
        ): List<WorkflowTemplateLintIssue> {
            val issues = ArrayList<WorkflowTemplateLintIssue>()
            val nodesById = LinkedHashMap<String, WorkflowNodeDefinition>()
            nodes.forEach { node ->
                if (nodesById.put(node.nodeId, node) != null) issue(issues, "graph-node-duplicate", node.nodeId)
            }
            val starts = nodes.filter { node -> node.kind == WorkflowNodeKind.START }
            val ends = nodes.filter { node -> node.kind == WorkflowNodeKind.END }
            if (starts.size != 1) issue(issues, "graph-start-count-invalid")
            if (ends.isEmpty()) issue(issues, "graph-terminal-missing")

            val outgoing = LinkedHashMap<String, MutableList<String>>()
            val incoming = LinkedHashMap<String, MutableList<String>>()
            nodesById.keys.forEach { nodeId ->
                outgoing[nodeId] = ArrayList()
                incoming[nodeId] = ArrayList()
            }
            val transitionIds = LinkedHashSet<String>()
            transitions.forEach { transition ->
                if (!transitionIds.add(transition.transitionId)) {
                    issue(issues, "graph-transition-duplicate", transition.fromNodeId)
                }
                if (!nodesById.containsKey(transition.fromNodeId) || !nodesById.containsKey(transition.toNodeId)) {
                    issue(issues, "graph-transition-endpoint-missing", transition.fromNodeId)
                } else {
                    outgoing.getValue(transition.fromNodeId).add(transition.toNodeId)
                    incoming.getValue(transition.toNodeId).add(transition.fromNodeId)
                }
            }

            if (starts.size == 1) {
                val reachable = reachable(starts.single().nodeId, outgoing)
                nodesById.keys.filterNot(reachable::contains).forEach { nodeId ->
                    issue(issues, "graph-node-unreachable", nodeId)
                }
            }
            if (ends.isNotEmpty()) {
                val reachesTerminal = reachableMany(ends.map { node -> node.nodeId }, incoming)
                nodesById.keys.filterNot(reachesTerminal::contains).forEach { nodeId ->
                    issue(issues, "graph-no-terminal-path", nodeId)
                }
            }

            lintCycleBudgets(nodesById.keys, outgoing, cycleBudgets, issues)
            return Collections.unmodifiableList(
                issues.distinct().sortedWith(compareBy({ it.code }, { it.nodeId ?: "" })),
            )
        }

        private fun lintIdentity(
            template: WorkflowReferenceTemplate,
            issues: MutableList<WorkflowTemplateLintIssue>,
        ) {
            if (template.definition.status != WorkflowDefinitionStatus.DRAFT) {
                issue(issues, "dangerous-template-not-draft")
            }
            if (template.definition.version != template.templateVersion) {
                issue(issues, "version-template-definition-mismatch")
            }
            if (template.definition.ref.digest != template.definition.contentDigest) {
                issue(issues, "digest-definition-reference-mismatch")
            }
            if (template.templateDigest.length != 64) issue(issues, "digest-template-invalid")
            if (template.subject.profile.kind != WorkflowTemplateProfileKind.SUBJECT) {
                issue(issues, "subject-profile-kind-invalid")
            }
            val expectedKey = when (template.templateId) {
                "flowweft.leave" -> "flowweft-reference-leave"
                "flowweft.expense" -> "flowweft-reference-expense"
                "flowweft.knowledge-document" -> "flowweft-reference-knowledge-document"
                "flowweft.legal-document" -> "flowweft-reference-legal-document"
                else -> null
            }
            if (expectedKey == null || template.definition.key != expectedKey) {
                issue(issues, "version-template-identity-mismatch")
            }
        }

        private fun lintHumanBindings(
            template: WorkflowReferenceTemplate,
            issues: MutableList<WorkflowTemplateLintIssue>,
        ) {
            val humanNodes = template.definition.nodes.filter { node -> node.kind == WorkflowNodeKind.HUMAN_TASK }
            val expectedSelectorKeys = LinkedHashSet<Pair<String, Int>>()
            humanNodes.forEach { node ->
                val policy = node.humanTaskPolicy
                if (policy == null) {
                    issue(issues, "human-policy-missing", node.nodeId)
                    return@forEach
                }
                if (!policy.separationOfDuties.initiatorExcluded) {
                    issue(issues, "dangerous-initiator-not-excluded", node.nodeId)
                }
                if (policy.evidenceBinding.isBuiltinNone) {
                    issue(issues, "form-binding-builtin-none", node.nodeId)
                }
                val formBindings = template.formBindings.filter { binding -> binding.nodeId == node.nodeId }
                if (formBindings.size != 1 ||
                    formBindings.singleOrNull()?.evidence?.contentDigest != policy.evidenceBinding.contentDigest
                ) {
                    issue(issues, "form-binding-missing-or-mismatch", node.nodeId)
                }
                val sla = template.slaMetadata.filter { metadata -> metadata.nodeId == node.nodeId }
                if (sla.size != 1) issue(issues, "sla-binding-missing-or-duplicate", node.nodeId)

                policy.participantRules.forEachIndexed { ruleIndex, rule ->
                    expectedSelectorKeys.add(node.nodeId to ruleIndex)
                    val selectorBinding = template.selectorBindings.singleOrNull { binding ->
                        binding.nodeId == node.nodeId && binding.ruleIndex == ruleIndex
                    }
                    if (selectorBinding == null || selectorBinding.selectorDigest != rule.selector.digest) {
                        issue(issues, "selector-binding-missing-or-mismatch", node.nodeId)
                    }
                    if (rule.selector.kind == WorkflowParticipantSelectorKind.EXACT_USER ||
                        rule.selector.kind == WorkflowParticipantSelectorKind.USER
                    ) {
                        issue(issues, "dangerous-fixed-user-selector", node.nodeId)
                    }
                    if (rule.selector.kind == WorkflowParticipantSelectorKind.INITIATOR) {
                        issue(issues, "dangerous-initiator-selector", node.nodeId)
                    }
                    if (rule.membershipStrategy == WorkflowParticipantMembershipStrategy.CURRENT_MEMBERSHIP &&
                        !policy.resolutionStages.containsAll(
                            listOf(
                                WorkflowParticipantResolutionStage.QUERY,
                                WorkflowParticipantResolutionStage.CLAIM,
                                WorkflowParticipantResolutionStage.DECISION,
                            ),
                        )
                    ) {
                        issue(issues, "selector-current-membership-stages-missing", node.nodeId)
                    }
                }
            }
            template.selectorBindings.filterNot { binding ->
                expectedSelectorKeys.contains(binding.nodeId to binding.ruleIndex)
            }.forEach { binding -> issue(issues, "selector-binding-extra", binding.nodeId) }
            template.formBindings.filter { binding -> humanNodes.none { node -> node.nodeId == binding.nodeId } }
                .forEach { binding -> issue(issues, "form-binding-extra", binding.nodeId) }
            template.slaMetadata.filter { metadata -> humanNodes.none { node -> node.nodeId == metadata.nodeId } }
                .forEach { metadata -> issue(issues, "sla-binding-extra", metadata.nodeId) }
        }

        private fun lintPredicateBindings(
            template: WorkflowReferenceTemplate,
            issues: MutableList<WorkflowTemplateLintIssue>,
        ) {
            val predicateTransitions = template.definition.transitions.filter { transition -> transition.predicate != null }
            predicateTransitions.forEach { transition ->
                val binding = template.predicateBindings.singleOrNull { item ->
                    item.transitionId == transition.transitionId
                }
                if (binding == null || binding.predicateBindingDigest != transition.predicate?.bindingDigest) {
                    issue(issues, "predicate-binding-missing-or-mismatch", transition.fromNodeId)
                }
            }
            template.predicateBindings.filter { binding ->
                predicateTransitions.none { transition -> transition.transitionId == binding.transitionId }
            }.forEach { binding -> issue(issues, "predicate-binding-extra") }
        }

        private fun lintProviderBindings(
            template: WorkflowReferenceTemplate,
            issues: MutableList<WorkflowTemplateLintIssue>,
        ) {
            val providerNodes = template.definition.nodes.filter(::isProviderNode)
            providerNodes.forEach { node ->
                val binding = template.providerNodeBindings.singleOrNull { item -> item.nodeId == node.nodeId }
                if (binding == null || binding.nodeKind != node.kind ||
                    binding.descriptorDigest != node.descriptorDigest || binding.payloadDigest != node.payloadDigest
                ) {
                    issue(issues, "provider-binding-missing-or-mismatch", node.nodeId)
                }
            }
            template.providerNodeBindings.filter { binding ->
                providerNodes.none { node -> node.nodeId == binding.nodeId }
            }.forEach { binding -> issue(issues, "provider-binding-extra", binding.nodeId) }
            template.definition.nodes.filterNot(::isKnownNodeKind).forEach { node ->
                issue(issues, "profile-unknown-node-kind", node.nodeId)
            }
            template.definition.transitions.filterNot { transition ->
                transition.trigger == WorkflowTransitionTrigger.COMPLETED ||
                    transition.trigger == WorkflowTransitionTrigger.APPROVED ||
                    transition.trigger == WorkflowTransitionTrigger.REJECTED
            }.forEach { transition -> issue(issues, "profile-unknown-transition-trigger", transition.fromNodeId) }
        }

        private fun lintSimulationCases(
            template: WorkflowReferenceTemplate,
            issues: MutableList<WorkflowTemplateLintIssue>,
        ) {
            if (template.simulationCases.map { simulationCase -> simulationCase.caseId }.toSet().size !=
                template.simulationCases.size
            ) {
                issue(issues, "simulation-case-duplicate")
            }
            template.simulationCases.forEach { simulationCase ->
                val plan = WorkflowTemplateSimulationPlanner.plan(
                    template,
                    simulationCase,
                    WorkflowTemplateSimulationCapabilities.allReferenceCapabilities(),
                )
                if (plan.status != WorkflowTemplateSimulationStatus.PLAN_READY) {
                    issue(issues, "simulation-plan-invalid")
                }
            }
        }

        private fun lintCycleBudgets(
            nodeIds: Collection<String>,
            outgoing: Map<String, List<String>>,
            budgets: Collection<WorkflowTemplateCycleBudget>,
            issues: MutableList<WorkflowTemplateLintIssue>,
        ) {
            if (budgets.map { budget -> budget.cycleId }.toSet().size != budgets.size) {
                issue(issues, "cycle-budget-id-duplicate")
            }
            val components = stronglyConnectedComponents(nodeIds, outgoing)
            val cyclicComponents = components.filter { component ->
                component.size > 1 || component.singleOrNull()?.let { nodeId ->
                    outgoing[nodeId].orEmpty().contains(nodeId)
                } == true
            }
            cyclicComponents.forEach { component ->
                val matching = budgets.filter { budget -> component.contains(budget.entryNodeId) }
                if (matching.size != 1) {
                    component.sorted().forEach { nodeId -> issue(issues, "cycle-budget-missing-or-ambiguous", nodeId) }
                }
            }
            budgets.filter { budget -> cyclicComponents.none { component -> component.contains(budget.entryNodeId) } }
                .forEach { budget -> issue(issues, "cycle-budget-extra", budget.entryNodeId) }
        }

        private fun stronglyConnectedComponents(
            nodeIds: Collection<String>,
            outgoing: Map<String, List<String>>,
        ): List<Set<String>> {
            var nextIndex = 0
            val indexes = LinkedHashMap<String, Int>()
            val lowLinks = LinkedHashMap<String, Int>()
            val stack = ArrayDeque<String>()
            val onStack = LinkedHashSet<String>()
            val result = ArrayList<Set<String>>()

            fun visit(nodeId: String) {
                indexes[nodeId] = nextIndex
                lowLinks[nodeId] = nextIndex
                nextIndex += 1
                stack.push(nodeId)
                onStack.add(nodeId)
                outgoing[nodeId].orEmpty().forEach { target ->
                    if (!indexes.containsKey(target)) {
                        visit(target)
                        lowLinks[nodeId] = minOf(lowLinks.getValue(nodeId), lowLinks.getValue(target))
                    } else if (onStack.contains(target)) {
                        lowLinks[nodeId] = minOf(lowLinks.getValue(nodeId), indexes.getValue(target))
                    }
                }
                if (lowLinks.getValue(nodeId) == indexes.getValue(nodeId)) {
                    val component = LinkedHashSet<String>()
                    var member: String
                    do {
                        member = stack.pop()
                        onStack.remove(member)
                        component.add(member)
                    } while (member != nodeId)
                    result.add(Collections.unmodifiableSet(component))
                }
            }

            nodeIds.forEach { nodeId -> if (!indexes.containsKey(nodeId)) visit(nodeId) }
            return result
        }

        private fun reachable(start: String, edges: Map<String, List<String>>): Set<String> =
            reachableMany(listOf(start), edges)

        private fun reachableMany(starts: Collection<String>, edges: Map<String, List<String>>): Set<String> {
            val seen = LinkedHashSet<String>()
            val queue = ArrayDeque<String>()
            starts.forEach { start -> if (seen.add(start)) queue.add(start) }
            while (queue.isNotEmpty()) {
                edges[queue.remove()].orEmpty().forEach { target ->
                    if (seen.add(target)) queue.add(target)
                }
            }
            return seen
        }

        private fun isProviderNode(node: WorkflowNodeDefinition): Boolean =
            node.kind == WorkflowNodeKind.SERVICE_TASK || node.kind == WorkflowNodeKind.DECISION ||
                node.kind == WorkflowNodeKind.TIMER_WAIT || node.kind == WorkflowNodeKind.SUBPROCESS ||
                node.kind == WorkflowNodeKind.EXTENSION || !isKnownNodeKind(node)

        private fun isKnownNodeKind(node: WorkflowNodeDefinition): Boolean =
            node.kind == WorkflowNodeKind.START || node.kind == WorkflowNodeKind.END ||
                node.kind == WorkflowNodeKind.HUMAN_TASK || node.kind == WorkflowNodeKind.SERVICE_TASK ||
                node.kind == WorkflowNodeKind.DECISION || node.kind == WorkflowNodeKind.EXCLUSIVE_GATEWAY ||
                node.kind == WorkflowNodeKind.PARALLEL_SPLIT || node.kind == WorkflowNodeKind.PARALLEL_JOIN ||
                node.kind == WorkflowNodeKind.TIMER_WAIT || node.kind == WorkflowNodeKind.SUBPROCESS ||
                node.kind == WorkflowNodeKind.EXTENSION

        private fun issue(
            issues: MutableList<WorkflowTemplateLintIssue>,
            code: String,
            nodeId: String? = null,
        ) {
            issues.add(WorkflowTemplateLintIssue.create(code, nodeId))
        }
    }
}
