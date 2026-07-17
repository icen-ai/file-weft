package ai.icen.fw.workflow.templates

import ai.icen.fw.workflow.api.WorkflowApprovalMode
import ai.icen.fw.workflow.api.WorkflowHumanTaskEvidenceBinding
import ai.icen.fw.workflow.api.WorkflowNodeDefinition
import ai.icen.fw.workflow.api.WorkflowNodeKind
import ai.icen.fw.workflow.api.WorkflowParticipantSelector
import ai.icen.fw.workflow.api.WorkflowParticipantSelectorKind
import ai.icen.fw.workflow.api.WorkflowPredicateInputMapping
import ai.icen.fw.workflow.api.WorkflowPredicateInputSourceKind
import ai.icen.fw.workflow.api.WorkflowPredicateRef
import ai.icen.fw.workflow.api.WorkflowPrincipalRef
import ai.icen.fw.workflow.api.WorkflowTransitionDefinition
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class WorkflowReferenceTemplatesTest {
    @Test
    fun `four factories produce fixed draft templates without personnel ids`() {
        val bindings = bindings()
        val templates = listOf(
            WorkflowReferenceTemplates.leave("tenant-a", "leave-definition", bindings),
            WorkflowReferenceTemplates.expense("tenant-a", "expense-definition", bindings),
            WorkflowReferenceTemplates.knowledgeDocument("tenant-a", "knowledge-definition", bindings),
            WorkflowReferenceTemplates.legalDocument("tenant-a", "legal-definition", bindings),
        )

        assertEquals(
            listOf(
                "flowweft.leave",
                "flowweft.expense",
                "flowweft.knowledge-document",
                "flowweft.legal-document",
            ),
            templates.map { it.templateId },
        )
        templates.forEach { template ->
            assertEquals(WorkflowReferenceTemplates.TEMPLATE_VERSION, template.templateVersion)
            assertEquals(WorkflowReferenceTemplates.TEMPLATE_VERSION, template.definition.version)
            assertEquals(template.definition.contentDigest, template.definition.ref.digest)
            assertEquals(64, template.templateDigest.length)
            assertTrue(WorkflowReferenceTemplateLinter.lint(template).valid)
            assertTrue(template.definition.nodes.filter { it.kind == WorkflowNodeKind.HUMAN_TASK }.all { node ->
                node.humanTaskPolicy?.separationOfDuties?.initiatorExcluded == true
            })
            assertFalse(template.definition.nodes.any { node ->
                node.humanTaskPolicy?.participantRules?.any { rule ->
                    rule.selector.kind == WorkflowParticipantSelectorKind.USER ||
                        rule.selector.kind == WorkflowParticipantSelectorKind.EXACT_USER
                } == true
            })
        }
        assertEquals(4, templates.map { it.templateDigest }.toSet().size)
    }

    @Test
    fun `templates retain distinct approval and revision semantics`() {
        val bindings = bindings()
        val leave = WorkflowReferenceTemplates.leave("tenant-a", "leave", bindings)
        val firstManager = leave.definition.nodes.first { it.nodeId == "manager-level-1" }
            .humanTaskPolicy!!.participantRules.single().selector
        val secondManager = leave.definition.nodes.first { it.nodeId == "manager-level-2" }
            .humanTaskPolicy!!.participantRules.single().selector
        assertEquals(1, firstManager.minimumManagerLevel)
        assertEquals(1, firstManager.maximumManagerLevel)
        assertEquals(2, secondManager.minimumManagerLevel)
        assertEquals(2, secondManager.maximumManagerLevel)

        val expense = WorkflowReferenceTemplates.expense("tenant-a", "expense", bindings)
        val finance = expense.definition.nodes.first { it.nodeId == "finance-review" }.humanTaskPolicy!!
        assertEquals(WorkflowApprovalMode.QUORUM, finance.participantRules.single().approvalPolicy.mode)
        assertEquals(2, finance.participantRules.single().approvalPolicy.requiredApprovals)

        val knowledge = WorkflowReferenceTemplates.knowledgeDocument("tenant-a", "knowledge", bindings)
        assertEquals(3, knowledge.cycleBudgets.single().maximumIterations)
        assertEquals(WorkflowNodeKind.SERVICE_TASK, knowledge.definition.nodes.first {
            it.nodeId == "subject-revision"
        }.kind)

        val legal = WorkflowReferenceTemplates.legalDocument("tenant-a", "legal", bindings)
        val leaders = legal.definition.nodes.first { it.nodeId == "leader-countersign" }.humanTaskPolicy!!
        assertEquals(WorkflowApprovalMode.ALL, leaders.participantRules.single().approvalPolicy.mode)
        assertTrue(leaders.capabilities.addSignEnabled)
        val counsel = legal.definition.nodes.first { it.nodeId == "legal-counsel-review" }.humanTaskPolicy!!
        assertEquals(WorkflowApprovalMode.QUORUM, counsel.participantRules.single().approvalPolicy.mode)
    }

    @Test
    fun `simulation never reports execution success and fails closed without providers`() {
        val template = WorkflowReferenceTemplates.legalDocument("tenant-a", "legal", bindings())
        val simulationCase = template.simulationCase("legal-revision-then-approved")

        val unsupported = WorkflowTemplateSimulationPlanner.plan(
            template,
            simulationCase,
            WorkflowTemplateSimulationCapabilities.none(),
        )
        assertEquals(WorkflowTemplateSimulationStatus.UNSUPPORTED, unsupported.status)
        assertTrue(unsupported.missingCapabilities.contains(
            WorkflowTemplateSimulationCapabilityCodes.SUBJECT_RESOLUTION,
        ))
        assertTrue(unsupported.missingCapabilities.contains(
            WorkflowTemplateSimulationCapabilityCodes.PROVIDER_NODE,
        ))
        assertTrue(unsupported.missingCapabilities.contains(
            WorkflowTemplateSimulationCapabilityCodes.DURABLE_CYCLE_BUDGET,
        ))

        val readyPlan = WorkflowTemplateSimulationPlanner.plan(
            template,
            simulationCase,
            WorkflowTemplateSimulationCapabilities.allReferenceCapabilities(),
        )
        assertEquals(WorkflowTemplateSimulationStatus.PLAN_READY, readyPlan.status)
        assertEquals(simulationCase.expectedNodeTrace, readyPlan.steps.map { step -> step.nodeId })
        assertFalse(
            WorkflowTemplateSimulationStatus::class.java.fields.any { field ->
                field.name.contains("PASSED") || field.name.contains("SUCCESS")
            },
        )
    }

    @Test
    fun `raw graph lint detects unreachable terminal and unbudgeted cycle`() {
        val start = WorkflowNodeDefinition.of("start", WorkflowNodeKind.START, "开始", null)
        val end = WorkflowNodeDefinition.of("end", WorkflowNodeKind.END, "结束", null)
        val orphan = WorkflowNodeDefinition.serviceTask("orphan", "孤立循环", null, DIGEST_A, DIGEST_B)
        val issues = WorkflowReferenceTemplateLinter.lintGraph(
            listOf(start, end, orphan),
            listOf(
                WorkflowTransitionDefinition.unconditional("start-end", "start", "end"),
                WorkflowTransitionDefinition.unconditional("orphan-loop", "orphan", "orphan"),
            ),
            emptyList(),
        )
        assertTrue(issues.any { it.code == "graph-node-unreachable" && it.nodeId == "orphan" })
        assertTrue(issues.any { it.code == "graph-no-terminal-path" && it.nodeId == "orphan" })
        assertTrue(issues.any { it.code == "cycle-budget-missing-or-ambiguous" && it.nodeId == "orphan" })

        val withoutTerminal = WorkflowReferenceTemplateLinter.lintGraph(
            listOf(start, orphan),
            listOf(
                WorkflowTransitionDefinition.unconditional("start-orphan", "start", "orphan"),
                WorkflowTransitionDefinition.unconditional("orphan-loop", "orphan", "orphan"),
            ),
            listOf(WorkflowTemplateCycleBudget.of("bounded", "orphan", 2)),
        )
        assertTrue(withoutTerminal.any { it.code == "graph-terminal-missing" })
        assertFalse(withoutTerminal.any { it.code == "cycle-budget-missing-or-ambiguous" })
    }

    @Test
    fun `factory rejects fixed user dangerous default`() {
        val safe = bindings()
        val selectors = HashMap(safe.selectors)
        selectors[WorkflowReferenceTemplateSlots.EXPENSE_FINANCE_REVIEWER] =
            WorkflowParticipantSelector.user(WorkflowPrincipalRef.of("user", "fixed-user-fixture"))
        val dangerous = WorkflowReferenceTemplateBindings.of(
            safe.subject,
            selectors,
            safe.evidenceBindings,
            safe.predicates,
            safe.providerMaterials,
            safe.profiles,
        )

        assertFailsWith<IllegalArgumentException> {
            WorkflowReferenceTemplates.expense("tenant-a", "expense", dangerous)
        }
    }

    private fun bindings(): WorkflowReferenceTemplateBindings {
        val subjectProfile = profile(WorkflowTemplateProfileKind.SUBJECT, "subject-registry", DIGEST_A)
        val subject = WorkflowTemplateSubjectBinding.of("business-object", subjectProfile)
        val profiles = mapOf(
            WorkflowReferenceTemplateSlots.ORGANIZATION_PROFILE to
                profile(WorkflowTemplateProfileKind.ORGANIZATION, "organization-registry", DIGEST_B),
            WorkflowReferenceTemplateSlots.FORM_PROFILE to
                profile(WorkflowTemplateProfileKind.FORM, "form-registry", DIGEST_C),
            WorkflowReferenceTemplateSlots.PREDICATE_PROFILE to
                profile(WorkflowTemplateProfileKind.PREDICATE, "predicate-registry", DIGEST_D),
            WorkflowReferenceTemplateSlots.CALENDAR_PROFILE to
                profile(WorkflowTemplateProfileKind.BUSINESS_CALENDAR, "calendar-registry", DIGEST_E),
            WorkflowReferenceTemplateSlots.REVISION_SERVICE_PROFILE to
                profile(WorkflowTemplateProfileKind.SERVICE_NODE, "revision-service-registry", DIGEST_F),
        )
        val selectors = mapOf(
            WorkflowReferenceTemplateSlots.EXPENSE_COST_CENTER_OWNER to WorkflowParticipantSelector.custom("cost-center-owner"),
            WorkflowReferenceTemplateSlots.EXPENSE_FINANCE_REVIEWER to WorkflowParticipantSelector.role("finance-reviewer"),
            WorkflowReferenceTemplateSlots.EXPENSE_HIGH_VALUE_REVIEWER to WorkflowParticipantSelector.role("high-value-reviewer"),
            WorkflowReferenceTemplateSlots.KNOWLEDGE_CONTENT_OWNER to WorkflowParticipantSelector.variableUser("content-owner"),
            WorkflowReferenceTemplateSlots.KNOWLEDGE_MANAGER to WorkflowParticipantSelector.role("knowledge-manager"),
            WorkflowReferenceTemplateSlots.KNOWLEDGE_SECURITY_REVIEWER to WorkflowParticipantSelector.role("security-reviewer"),
            WorkflowReferenceTemplateSlots.LEGAL_BUSINESS_OWNER to WorkflowParticipantSelector.variableUser("business-owner"),
            WorkflowReferenceTemplateSlots.LEGAL_COUNSEL to WorkflowParticipantSelector.role("legal-counsel"),
            WorkflowReferenceTemplateSlots.LEGAL_EXPERT to WorkflowParticipantSelector.custom("legal-expert"),
            WorkflowReferenceTemplateSlots.LEGAL_DEPARTMENT_LEADERS to WorkflowParticipantSelector.departmentLeaders("legal-department"),
        )
        val evidence = evidence()
        val evidenceBindings = mapOf(
            WorkflowReferenceTemplateSlots.LEAVE_EVIDENCE to evidence,
            WorkflowReferenceTemplateSlots.EXPENSE_EVIDENCE to evidence,
            WorkflowReferenceTemplateSlots.KNOWLEDGE_EVIDENCE to evidence,
            WorkflowReferenceTemplateSlots.LEGAL_EVIDENCE to evidence,
        )
        val predicates = mapOf(
            WorkflowReferenceTemplateSlots.LEAVE_SECOND_MANAGER_PREDICATE to predicate("leave-second-level", DIGEST_A),
            WorkflowReferenceTemplateSlots.EXPENSE_HIGH_VALUE_PREDICATE to predicate("expense-high-value", DIGEST_B),
            WorkflowReferenceTemplateSlots.KNOWLEDGE_SECURITY_PREDICATE to predicate("knowledge-security", DIGEST_C),
            WorkflowReferenceTemplateSlots.LEGAL_EXPERT_PREDICATE to predicate("legal-expert", DIGEST_D),
        )
        val providerMaterials = mapOf(
            WorkflowReferenceTemplateSlots.KNOWLEDGE_REVISION_SERVICE to
                WorkflowTemplateProviderMaterial.of(DIGEST_E, DIGEST_F),
            WorkflowReferenceTemplateSlots.LEGAL_REVISION_SERVICE to
                WorkflowTemplateProviderMaterial.of(DIGEST_F, DIGEST_E),
        )
        return WorkflowReferenceTemplateBindings.of(
            subject,
            selectors,
            evidenceBindings,
            predicates,
            providerMaterials,
            profiles,
        )
    }

    private fun profile(
        kind: WorkflowTemplateProfileKind,
        id: String,
        digest: String,
    ): WorkflowTemplateProfileRef = WorkflowTemplateProfileRef.of(kind, id, "1.0.0", digest)

    private fun evidence(): WorkflowHumanTaskEvidenceBinding = WorkflowHumanTaskEvidenceBinding.of(
        "approval-form",
        "1.0.0",
        DIGEST_A,
        "approval-rule",
        "1.0.0",
        DIGEST_B,
    )

    private fun predicate(id: String, digest: String): WorkflowPredicateRef = WorkflowPredicateRef.of(
        "reference-rules",
        id,
        "1.0.0",
        digest,
        listOf(
            WorkflowPredicateInputMapping.of(
                "input",
                WorkflowPredicateInputSourceKind.WORKFLOW_VARIABLE,
                id,
            ),
        ),
    )

    private companion object {
        const val DIGEST_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val DIGEST_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val DIGEST_C = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
        const val DIGEST_D = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
        const val DIGEST_E = "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee"
        const val DIGEST_F = "ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff"
    }
}
