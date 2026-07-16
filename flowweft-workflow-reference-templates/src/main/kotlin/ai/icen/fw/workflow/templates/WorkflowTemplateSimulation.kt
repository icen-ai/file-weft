package ai.icen.fw.workflow.templates

import ai.icen.fw.workflow.api.WorkflowNodeDefinition
import ai.icen.fw.workflow.api.WorkflowNodeKind
import java.util.Collections
import java.util.LinkedHashMap
import java.util.LinkedHashSet

/** External capabilities a real simulation harness must provide; these are not execution permits. */
object WorkflowTemplateSimulationCapabilityCodes {
    const val SUBJECT_RESOLUTION = "workflow.subject-resolution"
    const val PARTICIPANT_RESOLUTION = "workflow.participant-resolution"
    const val FORM_RULE_VALIDATION = "workflow.form-rule-validation"
    const val PREDICATE_EVALUATION = "workflow.predicate-evaluation"
    const val BUSINESS_CALENDAR = "workflow.business-calendar"
    const val PROVIDER_NODE = "workflow.provider-node"
    const val DURABLE_CYCLE_BUDGET = "workflow.durable-cycle-budget"
}

class WorkflowTemplateSimulationChoice private constructor(
    nodeId: String,
    val occurrence: Int,
    transitionId: String,
) {
    val nodeId: String = ReferenceTemplateContractSupport.code(nodeId, "Simulation choice node is invalid.")
    val transitionId: String = ReferenceTemplateContractSupport.code(
        transitionId,
        "Simulation choice transition is invalid.",
    )
    val contentDigest: String

    init {
        require(occurrence >= 1) { "Simulation choice occurrence is invalid." }
        contentDigest = ReferenceTemplateContractSupport.digest(
            "flowweft-workflow-template-simulation-choice-v1",
            this.nodeId,
            occurrence.toString(),
            this.transitionId,
        )
    }

    override fun toString(): String = "WorkflowTemplateSimulationChoice(<redacted>)"

    companion object {
        @JvmStatic
        fun of(nodeId: String, occurrence: Int, transitionId: String): WorkflowTemplateSimulationChoice =
            WorkflowTemplateSimulationChoice(nodeId, occurrence, transitionId)
    }
}

/** Deterministic route assertion. It contains no provider output or fabricated authorization evidence. */
class WorkflowTemplateSimulationCase private constructor(
    caseId: String,
    choices: Collection<WorkflowTemplateSimulationChoice>,
    expectedNodeTrace: Collection<String>,
    val maximumSteps: Int,
) {
    val caseId: String = ReferenceTemplateContractSupport.code(caseId, "Simulation case id is invalid.")
    val choices: List<WorkflowTemplateSimulationChoice> = ReferenceTemplateContractSupport.immutableList(
        choices,
        ReferenceTemplateContractSupport.MAX_BINDINGS,
        "Simulation choices are invalid or exceed the limit.",
    )
    val expectedNodeTrace: List<String> = ReferenceTemplateContractSupport.immutableList(
        expectedNodeTrace.map { nodeId ->
            ReferenceTemplateContractSupport.code(nodeId, "Simulation expected node is invalid.")
        },
        ReferenceTemplateContractSupport.MAX_SIMULATION_STEPS,
        "Simulation expected trace is invalid or exceeds the limit.",
    )
    val contentDigest: String

    init {
        require(maximumSteps in 1..ReferenceTemplateContractSupport.MAX_SIMULATION_STEPS) {
            "Simulation maximum steps are invalid."
        }
        require(this.expectedNodeTrace.isNotEmpty()) { "Simulation expected trace is required." }
        require(this.choices.map { choice -> choice.nodeId to choice.occurrence }.toSet().size == this.choices.size) {
            "Simulation choices must be unique by node occurrence."
        }
        val values = ArrayList<String>()
        values.add(this.caseId)
        values.add(maximumSteps.toString())
        values.add("choices:${this.choices.size}")
        this.choices.forEach { values.add(it.contentDigest) }
        values.add("trace:${this.expectedNodeTrace.size}")
        this.expectedNodeTrace.forEach(values::add)
        contentDigest = ReferenceTemplateContractSupport.digest(
            "flowweft-workflow-template-simulation-case-v1",
            values,
        )
    }

    override fun toString(): String = "WorkflowTemplateSimulationCase(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            caseId: String,
            choices: Collection<WorkflowTemplateSimulationChoice>,
            expectedNodeTrace: Collection<String>,
            maximumSteps: Int,
        ): WorkflowTemplateSimulationCase = WorkflowTemplateSimulationCase(
            caseId,
            choices,
            expectedNodeTrace,
            maximumSteps,
        )
    }
}

class WorkflowTemplateSimulationCapabilities private constructor(codes: Collection<String>) {
    val codes: Set<String> = Collections.unmodifiableSet(LinkedHashSet(codes.map { code ->
        ReferenceTemplateContractSupport.code(code, "Simulation capability code is invalid.")
    }))

    fun supports(code: String): Boolean = codes.contains(code)

    override fun toString(): String = "WorkflowTemplateSimulationCapabilities(<redacted>)"

    companion object {
        @JvmStatic
        fun none(): WorkflowTemplateSimulationCapabilities = WorkflowTemplateSimulationCapabilities(emptyList())

        @JvmStatic
        fun of(codes: Collection<String>): WorkflowTemplateSimulationCapabilities =
            WorkflowTemplateSimulationCapabilities(codes)

        @JvmStatic
        fun allReferenceCapabilities(): WorkflowTemplateSimulationCapabilities = of(
            listOf(
                WorkflowTemplateSimulationCapabilityCodes.SUBJECT_RESOLUTION,
                WorkflowTemplateSimulationCapabilityCodes.PARTICIPANT_RESOLUTION,
                WorkflowTemplateSimulationCapabilityCodes.FORM_RULE_VALIDATION,
                WorkflowTemplateSimulationCapabilityCodes.PREDICATE_EVALUATION,
                WorkflowTemplateSimulationCapabilityCodes.BUSINESS_CALENDAR,
                WorkflowTemplateSimulationCapabilityCodes.PROVIDER_NODE,
                WorkflowTemplateSimulationCapabilityCodes.DURABLE_CYCLE_BUDGET,
            ),
        )
    }
}

class WorkflowTemplateSimulationStatus private constructor(code: String) {
    val code: String = ReferenceTemplateContractSupport.code(code, "Simulation status is invalid.")

    override fun equals(other: Any?): Boolean =
        this === other || other is WorkflowTemplateSimulationStatus && code == other.code

    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "WorkflowTemplateSimulationStatus(<redacted>)"

    companion object {
        /** Route and bindings are ready for a real harness; this never means execution passed. */
        @JvmField val PLAN_READY = WorkflowTemplateSimulationStatus("plan-ready")
        @JvmField val UNSUPPORTED = WorkflowTemplateSimulationStatus("unsupported")
        @JvmField val TRACE_MISMATCH = WorkflowTemplateSimulationStatus("trace-mismatch")
        @JvmField val INVALID = WorkflowTemplateSimulationStatus("invalid")

        @JvmStatic
        fun of(code: String): WorkflowTemplateSimulationStatus = when (code) {
            PLAN_READY.code -> PLAN_READY
            UNSUPPORTED.code -> UNSUPPORTED
            TRACE_MISMATCH.code -> TRACE_MISMATCH
            INVALID.code -> INVALID
            else -> WorkflowTemplateSimulationStatus(code)
        }
    }
}

class WorkflowTemplateSimulationStep private constructor(
    val index: Int,
    nodeId: String,
    val nodeKind: WorkflowNodeKind,
    selectedTransitionId: String?,
) {
    val nodeId: String = ReferenceTemplateContractSupport.code(nodeId, "Simulation step node is invalid.")
    val selectedTransitionId: String? = selectedTransitionId?.let { transitionId ->
        ReferenceTemplateContractSupport.code(transitionId, "Simulation step transition is invalid.")
    }

    override fun toString(): String = "WorkflowTemplateSimulationStep(<redacted>)"

    companion object {
        @JvmSynthetic
        internal fun create(
            index: Int,
            nodeId: String,
            nodeKind: WorkflowNodeKind,
            selectedTransitionId: String?,
        ): WorkflowTemplateSimulationStep = WorkflowTemplateSimulationStep(
            index,
            nodeId,
            nodeKind,
            selectedTransitionId,
        )
    }
}

class WorkflowTemplateSimulationPlan private constructor(
    val status: WorkflowTemplateSimulationStatus,
    val templateDigest: String,
    val caseDigest: String,
    steps: Collection<WorkflowTemplateSimulationStep>,
    requiredCapabilities: Collection<String>,
    missingCapabilities: Collection<String>,
    failureCode: String?,
) {
    val steps: List<WorkflowTemplateSimulationStep> = Collections.unmodifiableList(ArrayList(steps))
    val requiredCapabilities: List<String> = Collections.unmodifiableList(requiredCapabilities.sorted())
    val missingCapabilities: List<String> = Collections.unmodifiableList(missingCapabilities.sorted())
    val failureCode: String? = failureCode?.let { code ->
        ReferenceTemplateContractSupport.code(code, "Simulation failure code is invalid.")
    }

    init {
        require(status != WorkflowTemplateSimulationStatus.PLAN_READY ||
            this.missingCapabilities.isEmpty() && this.failureCode == null
        ) { "Ready simulation plans cannot contain missing capabilities or a failure." }
        require(status != WorkflowTemplateSimulationStatus.UNSUPPORTED || this.missingCapabilities.isNotEmpty()) {
            "Unsupported simulation plans require missing capabilities."
        }
        require(status == WorkflowTemplateSimulationStatus.PLAN_READY || this.failureCode != null) {
            "Non-ready simulation plans require a stable failure code."
        }
    }

    override fun toString(): String = "WorkflowTemplateSimulationPlan(<redacted>)"

    companion object {
        @JvmSynthetic
        internal fun create(
            status: WorkflowTemplateSimulationStatus,
            templateDigest: String,
            caseDigest: String,
            steps: Collection<WorkflowTemplateSimulationStep>,
            requiredCapabilities: Collection<String>,
            missingCapabilities: Collection<String>,
            failureCode: String?,
        ): WorkflowTemplateSimulationPlan = WorkflowTemplateSimulationPlan(
            status,
            templateDigest,
            caseDigest,
            steps,
            requiredCapabilities,
            missingCapabilities,
            failureCode,
        )
    }
}

/**
 * Builds a deterministic symbolic execution plan. It deliberately does not call the Domain engine:
 * human outcomes, predicate choices and provider effects require trusted external evidence.
 */
class WorkflowTemplateSimulationPlanner private constructor() {
    companion object {
        @JvmStatic
        fun plan(
            template: WorkflowReferenceTemplate,
            simulationCase: WorkflowTemplateSimulationCase,
            capabilities: WorkflowTemplateSimulationCapabilities,
        ): WorkflowTemplateSimulationPlan {
            if (template.simulationCases.none { candidate -> candidate.contentDigest == simulationCase.contentDigest }) {
                return result(
                    template,
                    simulationCase,
                    WorkflowTemplateSimulationStatus.INVALID,
                    emptyList(),
                    emptySet(),
                    emptySet(),
                    "simulation-case-not-bound",
                )
            }

            val nodes = template.definition.nodes.associateBy { node -> node.nodeId }
            val outgoing = template.definition.transitions.groupBy { transition -> transition.fromNodeId }
            val start = template.definition.nodes.singleOrNull { node -> node.kind == WorkflowNodeKind.START }
                ?: return result(
                    template,
                    simulationCase,
                    WorkflowTemplateSimulationStatus.INVALID,
                    emptyList(),
                    emptySet(),
                    emptySet(),
                    "simulation-start-invalid",
                )

            val choiceByOccurrence = simulationCase.choices.associateBy { choice -> choice.nodeId to choice.occurrence }
            val consumedChoices = LinkedHashSet<Pair<String, Int>>()
            val occurrences = LinkedHashMap<String, Int>()
            val required = LinkedHashSet<String>()
            required.add(WorkflowTemplateSimulationCapabilityCodes.SUBJECT_RESOLUTION)
            val steps = ArrayList<WorkflowTemplateSimulationStep>()
            var current: WorkflowNodeDefinition = start
            var terminal = false

            while (steps.size < simulationCase.maximumSteps) {
                val occurrence = (occurrences[current.nodeId] ?: 0) + 1
                occurrences[current.nodeId] = occurrence
                addRequirements(template, current, required)

                if (current.kind == WorkflowNodeKind.PARALLEL_SPLIT ||
                    current.kind == WorkflowNodeKind.PARALLEL_JOIN
                ) {
                    return result(
                        template,
                        simulationCase,
                        WorkflowTemplateSimulationStatus.UNSUPPORTED,
                        steps,
                        required,
                        setOf("workflow.parallel-simulation"),
                        "simulation-parallel-unsupported",
                    )
                }

                val routes = outgoing[current.nodeId].orEmpty()
                if (current.kind == WorkflowNodeKind.END) {
                    steps.add(WorkflowTemplateSimulationStep.create(steps.size, current.nodeId, current.kind, null))
                    terminal = true
                    break
                }
                if (routes.isEmpty()) {
                    return result(
                        template,
                        simulationCase,
                        WorkflowTemplateSimulationStatus.INVALID,
                        steps,
                        required,
                        emptySet(),
                        "simulation-route-missing",
                    )
                }

                val choiceKey = current.nodeId to occurrence
                val selected = if (routes.size == 1) {
                    routes.single()
                } else {
                    val requested = choiceByOccurrence[choiceKey]
                        ?: return result(
                            template,
                            simulationCase,
                            WorkflowTemplateSimulationStatus.INVALID,
                            steps,
                            required,
                            emptySet(),
                            "simulation-choice-missing",
                        )
                    consumedChoices.add(choiceKey)
                    routes.firstOrNull { transition -> transition.transitionId == requested.transitionId }
                        ?: return result(
                            template,
                            simulationCase,
                            WorkflowTemplateSimulationStatus.INVALID,
                            steps,
                            required,
                            emptySet(),
                            "simulation-choice-invalid",
                        )
                }
                steps.add(
                    WorkflowTemplateSimulationStep.create(
                        steps.size,
                        current.nodeId,
                        current.kind,
                        selected.transitionId,
                    ),
                )
                current = nodes[selected.toNodeId]
                    ?: return result(
                        template,
                        simulationCase,
                        WorkflowTemplateSimulationStatus.INVALID,
                        steps,
                        required,
                        emptySet(),
                        "simulation-target-missing",
                    )
            }

            if (!terminal) {
                return result(
                    template,
                    simulationCase,
                    WorkflowTemplateSimulationStatus.INVALID,
                    steps,
                    required,
                    emptySet(),
                    "simulation-step-budget-exhausted",
                )
            }
            if (consumedChoices.size != simulationCase.choices.size) {
                return result(
                    template,
                    simulationCase,
                    WorkflowTemplateSimulationStatus.INVALID,
                    steps,
                    required,
                    emptySet(),
                    "simulation-choice-unused",
                )
            }
            val actualTrace = steps.map { step -> step.nodeId }
            if (actualTrace != simulationCase.expectedNodeTrace) {
                return result(
                    template,
                    simulationCase,
                    WorkflowTemplateSimulationStatus.TRACE_MISMATCH,
                    steps,
                    required,
                    emptySet(),
                    "simulation-expected-trace-mismatch",
                )
            }
            val missing = required.filterNot(capabilities::supports).toSet()
            return if (missing.isEmpty()) {
                result(
                    template,
                    simulationCase,
                    WorkflowTemplateSimulationStatus.PLAN_READY,
                    steps,
                    required,
                    emptySet(),
                    null,
                )
            } else {
                result(
                    template,
                    simulationCase,
                    WorkflowTemplateSimulationStatus.UNSUPPORTED,
                    steps,
                    required,
                    missing,
                    "simulation-capability-missing",
                )
            }
        }

        private fun addRequirements(
            template: WorkflowReferenceTemplate,
            node: WorkflowNodeDefinition,
            required: MutableSet<String>,
        ) {
            when (node.kind) {
                WorkflowNodeKind.HUMAN_TASK -> {
                    required.add(WorkflowTemplateSimulationCapabilityCodes.PARTICIPANT_RESOLUTION)
                    required.add(WorkflowTemplateSimulationCapabilityCodes.FORM_RULE_VALIDATION)
                    required.add(WorkflowTemplateSimulationCapabilityCodes.BUSINESS_CALENDAR)
                }
                WorkflowNodeKind.EXCLUSIVE_GATEWAY -> {
                    val hasPredicate = template.definition.transitions.any { transition ->
                        transition.fromNodeId == node.nodeId && transition.predicate != null
                    }
                    if (hasPredicate) required.add(WorkflowTemplateSimulationCapabilityCodes.PREDICATE_EVALUATION)
                }
                WorkflowNodeKind.SERVICE_TASK,
                WorkflowNodeKind.DECISION,
                WorkflowNodeKind.TIMER_WAIT,
                WorkflowNodeKind.SUBPROCESS,
                WorkflowNodeKind.EXTENSION -> required.add(WorkflowTemplateSimulationCapabilityCodes.PROVIDER_NODE)
                else -> required.add(WorkflowTemplateSimulationCapabilityCodes.PROVIDER_NODE)
            }
            if (template.cycleBudgets.any { budget -> budget.entryNodeId == node.nodeId }) {
                required.add(WorkflowTemplateSimulationCapabilityCodes.DURABLE_CYCLE_BUDGET)
            }
        }

        private fun result(
            template: WorkflowReferenceTemplate,
            simulationCase: WorkflowTemplateSimulationCase,
            status: WorkflowTemplateSimulationStatus,
            steps: Collection<WorkflowTemplateSimulationStep>,
            required: Collection<String>,
            missing: Collection<String>,
            failureCode: String?,
        ): WorkflowTemplateSimulationPlan = WorkflowTemplateSimulationPlan.create(
            status,
            template.templateDigest,
            simulationCase.contentDigest,
            steps,
            required,
            missing,
            failureCode,
        )
    }
}
