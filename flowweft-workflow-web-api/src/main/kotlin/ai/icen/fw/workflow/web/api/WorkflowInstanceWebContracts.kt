package ai.icen.fw.workflow.web.api

class WorkflowInstanceStartCommand @JvmOverloads constructor(
    definitionKey: String,
    definitionVersion: String,
    val subject: WorkflowSubjectDto,
    canonicalInput: String? = null,
    inputDigest: String? = null,
) {
    val definitionKey: String = requiredText(definitionKey, "Workflow start definition key", 256)
    val definitionVersion: String = requiredText(definitionVersion, "Workflow start definition version", 128)
    val canonicalInput: String? = optionalText(
        canonicalInput,
        "Workflow start canonical input",
        MAX_CANONICAL_INPUT_BYTES,
        multiline = true,
    )
    val inputDigest: String? = inputDigest?.let { sha256(it, "Workflow start input digest") }

    init {
        require((this.canonicalInput == null) == (this.inputDigest == null)) {
            "Workflow canonical input and digest must be supplied together."
        }
    }

    override fun toString(): String = "WorkflowInstanceStartCommand(<redacted>)"

    companion object {
        const val MAX_CANONICAL_INPUT_BYTES: Int = 262_144
    }
}

class WorkflowInstanceControlCommand @JvmOverloads constructor(reasonCode: String, note: String? = null) {
    val reasonCode: String = requiredCode(reasonCode, "Workflow instance control reason", 96)
    val note: String? = optionalText(note, "Workflow instance control note", 4_096, multiline = true)

    override fun toString(): String = "WorkflowInstanceControlCommand(<redacted>)"
}

class WorkflowInstanceControlOperation private constructor(code: String) {
    val code: String = requiredCode(code, "Workflow instance control operation", 64)

    companion object {
        @JvmField val SUSPEND: WorkflowInstanceControlOperation = WorkflowInstanceControlOperation("SUSPEND")
        @JvmField val RESUME: WorkflowInstanceControlOperation = WorkflowInstanceControlOperation("RESUME")
        @JvmField val CANCEL: WorkflowInstanceControlOperation = WorkflowInstanceControlOperation("CANCEL")
        @JvmField val TERMINATE: WorkflowInstanceControlOperation = WorkflowInstanceControlOperation("TERMINATE")
    }
}

class WorkflowInstanceDto(
    id: String,
    definitionId: String,
    definitionVersion: String,
    definitionDigest: String,
    val subject: WorkflowSubjectDto,
    state: String,
    val recordVersion: Long,
    val createdAt: Long,
    val updatedAt: Long,
) {
    val id: String = requiredText(id, "Workflow instance id", 512)
    val definitionId: String = requiredText(definitionId, "Workflow instance definition id", 512)
    val definitionVersion: String = requiredText(definitionVersion, "Workflow instance definition version", 128)
    val definitionDigest: String = sha256(definitionDigest, "Workflow instance definition digest")
    val state: String = requiredCode(state, "Workflow instance state", 96)

    init {
        require(recordVersion >= 0L) { "Workflow instance record version must not be negative." }
        require(createdAt >= 0L && updatedAt >= createdAt) { "Workflow instance timestamps are invalid." }
    }
}

/** History deliberately omits raw actor ids, payloads, variables and provider evidence. */
class WorkflowHistoryEventDto @JvmOverloads constructor(
    val sequence: Long,
    eventType: String,
    state: String,
    val occurredAt: Long,
    val performedByCurrentUser: Boolean,
    resourceId: String? = null,
    reasonCode: String? = null,
) {
    val eventType: String = requiredCode(eventType, "Workflow history event type", 96)
    val state: String = requiredCode(state, "Workflow history state", 96)
    val resourceId: String? = optionalText(resourceId, "Workflow history resource id", 512)
    val reasonCode: String? = reasonCode?.let { requiredCode(it, "Workflow history reason code", 96) }

    init {
        require(sequence >= 0L) { "Workflow history sequence must not be negative." }
        require(occurredAt >= 0L) { "Workflow history time must not be negative." }
    }
}
