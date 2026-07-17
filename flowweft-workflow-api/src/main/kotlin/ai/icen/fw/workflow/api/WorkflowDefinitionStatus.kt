package ai.icen.fw.workflow.api

/**
 * Extensible lifecycle declaration carried by a workflow definition value.
 *
 * This value is caller-constructible metadata. In particular, [PUBLISHED] is not a publish
 * receipt, a deployment permit, or proof that a runtime has accepted the definition.
 */
class WorkflowDefinitionStatus private constructor(code: String) {
    val code: String = WorkflowContractSupport.requireMachineCode(
        code,
        "Workflow definition status is invalid.",
    )

    override fun equals(other: Any?): Boolean =
        this === other || other is WorkflowDefinitionStatus && code == other.code

    override fun hashCode(): Int = code.hashCode()

    override fun toString(): String = "WorkflowDefinitionStatus(<redacted>)"

    companion object {
        @JvmField
        val DRAFT = WorkflowDefinitionStatus("draft")

        @JvmField
        val PUBLISHED = WorkflowDefinitionStatus("published")

        @JvmField
        val RETIRED = WorkflowDefinitionStatus("retired")

        @JvmStatic
        fun of(code: String): WorkflowDefinitionStatus = when (code) {
            DRAFT.code -> DRAFT
            PUBLISHED.code -> PUBLISHED
            RETIRED.code -> RETIRED
            else -> WorkflowDefinitionStatus(code)
        }
    }
}
