package ai.icen.fw.workflow.api

/**
 * Stable, case-sensitive, tenant-relative reference to a host-owned workflow subject.
 *
 * Values are preserved exactly: [type] is bounded to 64 UTF-8 bytes and [id] to 512 UTF-8 bytes.
 * A trusted runtime must resolve it inside an authenticated tenant before use.
 */
class WorkflowSubjectRef private constructor(type: String, id: String) {
    val type: String = WorkflowContractSupport.requireText(
        type,
        WorkflowContractSupport.MAX_TYPE_UTF8_BYTES,
        "Workflow subject type is invalid.",
    )
    val id: String = WorkflowContractSupport.requireText(
        id,
        WorkflowContractSupport.MAX_REFERENCE_ID_UTF8_BYTES,
        "Workflow subject identifier is invalid.",
    )

    override fun equals(other: Any?): Boolean =
        this === other || other is WorkflowSubjectRef && type == other.type && id == other.id

    override fun hashCode(): Int = 31 * type.hashCode() + id.hashCode()

    override fun toString(): String = "WorkflowSubjectRef(<redacted>)"

    companion object {
        @JvmStatic
        fun of(type: String, id: String): WorkflowSubjectRef = WorkflowSubjectRef(type, id)
    }
}
