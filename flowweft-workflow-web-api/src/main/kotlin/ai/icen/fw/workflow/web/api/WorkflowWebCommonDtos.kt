package ai.icen.fw.workflow.web.api

/** Validated route identifier. It remains tenant-relative and is never an authorization proof. */
class WorkflowWebResourceId private constructor(value: String) {
    val value: String = requiredText(value, "Workflow route resource id", 512)

    override fun toString(): String = "WorkflowWebResourceId(<redacted>)"

    companion object {
        @JvmStatic
        fun of(value: String): WorkflowWebResourceId = WorkflowWebResourceId(value)
    }
}

/** Minimum public receipt for a committed or replayed mutation. */
class WorkflowWebCommandReceiptDto(
    resourceType: String,
    resourceId: String,
    val resourceVersion: Long,
    state: String,
) {
    val resourceType: String = requiredCode(resourceType, "Workflow receipt resource type", 64)
    val resourceId: String = requiredText(resourceId, "Workflow receipt resource id", 512)
    val state: String = requiredCode(state, "Workflow receipt state", 96)

    init {
        require(resourceVersion >= 0L) { "Workflow receipt version must not be negative." }
    }
}

/** Authorized host-subject projection. It never carries tenant or storage/vendor coordinates. */
class WorkflowSubjectDto(
    type: String,
    id: String,
    revision: String,
    digest: String,
) {
    val type: String = requiredText(type, "Workflow subject type", 64)
    val id: String = requiredText(id, "Workflow subject id", 512)
    val revision: String = requiredText(revision, "Workflow subject revision", 256)
    val digest: String = sha256(digest, "Workflow subject digest")
}

/** Explicit target of delegation/add-sign. This is not the authenticated actor. */
class WorkflowPrincipalTargetCommand(type: String, id: String) {
    val type: String = requiredText(type, "Workflow target principal type", 64)
    val id: String = requiredText(id, "Workflow target principal id", 512)
}

/** Safe installed-capability projection. It never exposes implementation or bean names. */
class WorkflowWebCapabilityDto(
    capabilityId: String,
    val supported: Boolean,
    reasonCode: String?,
) {
    val capabilityId: String = requiredText(capabilityId, "Workflow capability id", 128)
    val reasonCode: String? = reasonCode?.let {
        requiredCode(it, "Workflow capability reason code", 96)
    }

    init {
        require(supported == (this.reasonCode == null)) {
            "Supported capabilities cannot have a failure reason and unsupported capabilities require one."
        }
    }
}

class WorkflowWebCapabilitiesDto(capabilities: Collection<WorkflowWebCapabilityDto>) {
    val capabilities: List<WorkflowWebCapabilityDto> = immutableList(
        capabilities,
        "Workflow capabilities",
        256,
    ).also { values ->
        require(values.map { it.capabilityId }.toSet().size == values.size) {
            "Workflow capability ids must be unique."
        }
    }
}
