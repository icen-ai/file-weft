package ai.icen.fw.workflow.api

/**
 * One typed, ordered predicate input binding.
 *
 * [sourceReference] is an opaque reference interpreted only by the selected source-kind provider;
 * it is never executable expression text. Unknown source kinds must fail closed unless registered.
 */
class WorkflowPredicateInputMapping private constructor(
    inputName: String,
    val sourceKind: WorkflowPredicateInputSourceKind,
    sourceReference: String,
) {
    val inputName: String = WorkflowContractSupport.requireMachineCode(
        inputName,
        "Workflow predicate input name is invalid.",
    )
    val sourceReference: String = WorkflowContractSupport.requireText(
        sourceReference,
        WorkflowContractSupport.MAX_REFERENCE_ID_UTF8_BYTES,
        "Workflow predicate input source reference is invalid.",
    )
    val contentDigest: String = WorkflowContractSupport.digest(
        WorkflowContractSupport.PREDICATE_INPUT_DIGEST_DOMAIN,
    )
        .text(this.inputName)
        .text(sourceKind.code)
        .text(this.sourceReference)
        .finish()

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is WorkflowPredicateInputMapping &&
            inputName == other.inputName &&
            sourceKind == other.sourceKind &&
            sourceReference == other.sourceReference

    override fun hashCode(): Int {
        var result = inputName.hashCode()
        result = 31 * result + sourceKind.hashCode()
        result = 31 * result + sourceReference.hashCode()
        return result
    }

    override fun toString(): String = "WorkflowPredicateInputMapping(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            inputName: String,
            sourceKind: WorkflowPredicateInputSourceKind,
            sourceReference: String,
        ): WorkflowPredicateInputMapping = WorkflowPredicateInputMapping(
            inputName,
            sourceKind,
            sourceReference,
        )
    }
}
