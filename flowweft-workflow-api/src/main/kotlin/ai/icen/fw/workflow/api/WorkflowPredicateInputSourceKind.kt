package ai.icen.fw.workflow.api

/**
 * Extensible code identifying a typed source for one predicate input.
 *
 * The code carries no path evaluator or expression. Unknown values require a registered runtime
 * provider and otherwise fail closed.
 */
class WorkflowPredicateInputSourceKind private constructor(code: String) {
    val code: String = WorkflowContractSupport.requireMachineCode(
        code,
        "Workflow predicate input source kind is invalid.",
    )

    override fun equals(other: Any?): Boolean =
        this === other || other is WorkflowPredicateInputSourceKind && code == other.code

    override fun hashCode(): Int = code.hashCode()

    override fun toString(): String = "WorkflowPredicateInputSourceKind(<redacted>)"

    companion object {
        @JvmField
        val WORKFLOW_VARIABLE = WorkflowPredicateInputSourceKind("workflow-variable")

        @JvmField
        val SUBJECT_ATTRIBUTE = WorkflowPredicateInputSourceKind("subject-attribute")

        @JvmField
        val CONTEXT_VALUE = WorkflowPredicateInputSourceKind("context-value")

        @JvmField
        val ACTOR_ATTRIBUTE = WorkflowPredicateInputSourceKind("actor-attribute")

        @JvmStatic
        fun of(code: String): WorkflowPredicateInputSourceKind = when (code) {
            WORKFLOW_VARIABLE.code -> WORKFLOW_VARIABLE
            SUBJECT_ATTRIBUTE.code -> SUBJECT_ATTRIBUTE
            CONTEXT_VALUE.code -> CONTEXT_VALUE
            ACTOR_ATTRIBUTE.code -> ACTOR_ATTRIBUTE
            else -> WorkflowPredicateInputSourceKind(code)
        }
    }
}
