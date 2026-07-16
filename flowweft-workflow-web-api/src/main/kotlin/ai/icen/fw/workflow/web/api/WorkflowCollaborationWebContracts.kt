package ai.icen.fw.workflow.web.api

class WorkflowCommentTokenCommand private constructor(
    kind: String,
    text: String?,
    target: WorkflowPrincipalTargetCommand?,
) {
    val kind: String = requiredCode(kind, "Workflow comment token kind", 32)
    val text: String? = text
    val target: WorkflowPrincipalTargetCommand? = target

    init {
        when (this.kind) {
            "TEXT" -> require(this.text != null && this.target == null) {
                "Workflow TEXT tokens require text only."
            }
            "MENTION" -> require(this.text == null && this.target != null) {
                "Workflow MENTION tokens require a principal target only."
            }
            else -> throw IllegalArgumentException("Workflow comment token kind is unsupported.")
        }
    }

    companion object {
        @JvmStatic
        fun text(value: String): WorkflowCommentTokenCommand = WorkflowCommentTokenCommand(
            "TEXT",
            requiredText(value, "Workflow comment text", 8_192, multiline = true),
            null,
        )

        @JvmStatic
        fun mention(target: WorkflowPrincipalTargetCommand): WorkflowCommentTokenCommand =
            WorkflowCommentTokenCommand("MENTION", null, target)
    }
}

class WorkflowCommentDocumentCommand(tokens: Collection<WorkflowCommentTokenCommand>) {
    val tokens: List<WorkflowCommentTokenCommand> = immutableList(
        tokens,
        "Workflow comment tokens",
        256,
    ).also { values ->
        require(values.isNotEmpty()) { "Workflow comments require at least one token." }
    }

    override fun toString(): String = "WorkflowCommentDocumentCommand(<redacted>)"
}

class WorkflowCommentTokenDto private constructor(
    kind: String,
    text: String?,
    principalType: String?,
    principalId: String?,
    displayNameSnapshot: String?,
) {
    val kind: String = requiredCode(kind, "Workflow comment token kind", 32)
    val text: String? = text
    val principalType: String? = principalType
    val principalId: String? = principalId
    val displayNameSnapshot: String? = displayNameSnapshot

    init {
        when (this.kind) {
            "TEXT" -> require(this.text != null && this.principalId == null && this.principalType == null &&
                this.displayNameSnapshot == null
            ) { "Workflow TEXT projections require text only." }
            "MENTION" -> require(this.text == null && this.principalId != null && this.principalType != null &&
                this.displayNameSnapshot != null
            ) { "Workflow MENTION projections require a resolved principal snapshot." }
            else -> throw IllegalArgumentException("Workflow comment token kind is unsupported.")
        }
    }

    companion object {
        @JvmStatic
        fun text(value: String): WorkflowCommentTokenDto = WorkflowCommentTokenDto(
            "TEXT",
            requiredText(value, "Workflow comment text", 8_192, multiline = true),
            null,
            null,
            null,
        )

        @JvmStatic
        fun mention(type: String, id: String, displayNameSnapshot: String): WorkflowCommentTokenDto =
            WorkflowCommentTokenDto(
                "MENTION",
                null,
                requiredText(type, "Workflow mentioned principal type", 64),
                requiredText(id, "Workflow mentioned principal id", 512),
                requiredText(displayNameSnapshot, "Workflow mention display name", 512),
            )
    }
}

class WorkflowCommentDto(
    id: String,
    instanceId: String,
    val revision: Long,
    tokens: Collection<WorkflowCommentTokenDto>,
    val authoredByCurrentUser: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
) {
    val id: String = requiredText(id, "Workflow comment id", 512)
    val instanceId: String = requiredText(instanceId, "Workflow comment instance id", 512)
    val tokens: List<WorkflowCommentTokenDto> = immutableList(tokens, "Workflow comment tokens", 256)

    init {
        require(revision >= 0L) { "Workflow comment revision must not be negative." }
        require(createdAt >= 0L && updatedAt >= createdAt) { "Workflow comment timestamps are invalid." }
    }
}
