package ai.icen.fw.workflow.api

/**
 * Tenant-relative instance handle, bounded to 512 UTF-8 bytes, bound to an observed version.
 * It is an optimistic-concurrency precondition, not a cache identity or authorization proof.
 */
class WorkflowInstanceRef private constructor(id: String, val expectedVersion: Long) {
    val id: String = WorkflowContractSupport.requireText(
        id,
        WorkflowContractSupport.MAX_REFERENCE_ID_UTF8_BYTES,
        "Workflow instance identifier is invalid.",
    )

    init {
        require(expectedVersion >= 0L) { "Workflow instance expected version must not be negative." }
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is WorkflowInstanceRef && id == other.id && expectedVersion == other.expectedVersion

    override fun hashCode(): Int = 31 * id.hashCode() + expectedVersion.hashCode()

    override fun toString(): String = "WorkflowInstanceRef(<redacted>)"

    companion object {
        @JvmStatic
        fun of(id: String, expectedVersion: Long): WorkflowInstanceRef =
            WorkflowInstanceRef(id, expectedVersion)
    }
}
