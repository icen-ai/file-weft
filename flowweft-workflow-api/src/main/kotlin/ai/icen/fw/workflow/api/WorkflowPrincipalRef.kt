package ai.icen.fw.workflow.api

/**
 * Stable, case-sensitive, tenant-relative reference to a host-owned principal.
 *
 * Values are preserved exactly: [type] is bounded to 64 UTF-8 bytes and [id] to 512 UTF-8 bytes.
 * This caller-constructible value is an identifier, never authentication or authorization proof.
 */
class WorkflowPrincipalRef private constructor(type: String, id: String) {
    val type: String = WorkflowContractSupport.requireText(
        type,
        WorkflowContractSupport.MAX_TYPE_UTF8_BYTES,
        "Workflow principal type is invalid.",
    )
    val id: String = WorkflowContractSupport.requireText(
        id,
        WorkflowContractSupport.MAX_REFERENCE_ID_UTF8_BYTES,
        "Workflow principal identifier is invalid.",
    )

    override fun equals(other: Any?): Boolean =
        this === other || other is WorkflowPrincipalRef && type == other.type && id == other.id

    override fun hashCode(): Int = 31 * type.hashCode() + id.hashCode()

    override fun toString(): String = "WorkflowPrincipalRef(<redacted>)"

    companion object {
        @JvmStatic
        fun of(type: String, id: String): WorkflowPrincipalRef = WorkflowPrincipalRef(type, id)
    }
}
