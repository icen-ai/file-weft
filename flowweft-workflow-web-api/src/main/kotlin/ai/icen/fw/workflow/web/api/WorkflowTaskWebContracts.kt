package ai.icen.fw.workflow.web.api

class WorkflowTaskSummaryDto @JvmOverloads constructor(
    id: String,
    instanceId: String,
    name: String,
    state: String,
    val recordVersion: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val claimantIsCurrentUser: Boolean = false,
    val actionableByCurrentUser: Boolean = false,
    val dueAt: Long? = null,
) {
    val id: String = requiredText(id, "Workflow task id", 512)
    val instanceId: String = requiredText(instanceId, "Workflow task instance id", 512)
    val name: String = requiredText(name, "Workflow task name", 512)
    val state: String = requiredCode(state, "Workflow task state", 96)

    init {
        require(recordVersion >= 0L) { "Workflow task record version must not be negative." }
        require(createdAt >= 0L && updatedAt >= createdAt) { "Workflow task timestamps are invalid." }
        require(dueAt == null || dueAt >= 0L) { "Workflow task due time must not be negative." }
    }
}

class WorkflowTaskDetailDto(
    val task: WorkflowTaskSummaryDto,
    val subject: WorkflowSubjectDto,
    allowedActions: Collection<String>,
    formId: String?,
    formVersion: String?,
) {
    val allowedActions: List<String> = immutableList(
        allowedActions.map { requiredCode(it, "Workflow task allowed action", 96) },
        "Workflow task allowed actions",
        32,
    ).also { values ->
        require(values.toSet().size == values.size) { "Workflow task allowed actions must be unique." }
    }
    val formId: String? = optionalText(formId, "Workflow task form id", 512)
    val formVersion: String? = optionalText(formVersion, "Workflow task form version", 128)

    init {
        require((this.formId == null) == (this.formVersion == null)) {
            "Workflow task form id and version must be supplied together."
        }
    }
}

/** Claim always targets the current trusted principal, never a body-supplied user. */
class WorkflowTaskClaimCommand

class WorkflowTaskDecisionCommand @JvmOverloads constructor(
    action: String,
    comment: WorkflowCommentDocumentCommand? = null,
    formSubmissionId: String? = null,
    formSubmissionDigest: String? = null,
) {
    val action: String = requiredCode(action, "Workflow task decision action", 96).also {
        require(it in DECISION_ACTIONS) { "Workflow task decision action is unsupported." }
    }
    val comment: WorkflowCommentDocumentCommand? = comment
    val formSubmissionId: String? = optionalText(formSubmissionId, "Workflow task form submission id", 512)
    val formSubmissionDigest: String? = formSubmissionDigest?.let {
        sha256(it, "Workflow task form submission digest")
    }

    init {
        require((this.formSubmissionId == null) == (this.formSubmissionDigest == null)) {
            "Workflow form submission id and digest must be supplied together."
        }
    }

    override fun toString(): String = "WorkflowTaskDecisionCommand(<redacted>)"

    companion object {
        private val DECISION_ACTIONS = setOf("APPROVE", "REJECT", "REQUEST_CHANGES")
    }
}

class WorkflowTaskDelegateCommand(
    val target: WorkflowPrincipalTargetCommand,
    mode: String,
    reasonCode: String,
) {
    val mode: String = requiredCode(mode, "Workflow task delegation mode", 64).also {
        require(it == "DELEGATE" || it == "TRANSFER") { "Workflow delegation mode is unsupported." }
    }
    val reasonCode: String = requiredCode(reasonCode, "Workflow task delegation reason", 96)
}

class WorkflowTaskAddSignCommand(
    targets: Collection<WorkflowPrincipalTargetCommand>,
    position: String,
    reasonCode: String,
) {
    val targets: List<WorkflowPrincipalTargetCommand> = immutableList(
        targets,
        "Workflow add-sign targets",
        50,
    ).also { values ->
        require(values.isNotEmpty()) { "Workflow add-sign requires at least one target." }
        require(values.map { it.type + "\u0000" + it.id }.toSet().size == values.size) {
            "Workflow add-sign targets must be unique."
        }
    }
    val position: String = requiredCode(position, "Workflow add-sign position", 64).also {
        require(it in POSITIONS) { "Workflow add-sign position is unsupported." }
    }
    val reasonCode: String = requiredCode(reasonCode, "Workflow add-sign reason", 96)

    companion object {
        private val POSITIONS = setOf("BEFORE", "AFTER", "PARALLEL")
    }
}

class WorkflowTaskReturnCommand(
    targetNodeId: String,
    mode: String,
    reasonCode: String,
) {
    val targetNodeId: String = requiredText(targetNodeId, "Workflow return target node id", 512)
    val mode: String = requiredCode(mode, "Workflow return mode", 96).also {
        require(it in RETURN_MODES) { "Workflow return mode is unsupported." }
    }
    val reasonCode: String = requiredCode(reasonCode, "Workflow return reason", 96)

    companion object {
        private val RETURN_MODES = setOf("RETURN_WITHOUT_SUBJECT_CHANGE", "REQUEST_SUBJECT_REVISION")
    }
}

class WorkflowTaskFormDto @JvmOverloads constructor(
    formId: String,
    version: String,
    schemaDialect: String,
    schemaDocument: String,
    schemaDigest: String,
    uiSchemaDocument: String? = null,
    uiSchemaDigest: String? = null,
    projectedData: String? = null,
) {
    val formId: String = requiredText(formId, "Workflow form id", 512)
    val version: String = requiredText(version, "Workflow form version", 128)
    val schemaDialect: String = requiredText(schemaDialect, "Workflow form schema dialect", 128)
    val schemaDocument: String = requiredText(
        schemaDocument,
        "Workflow form schema",
        MAX_FORM_DOCUMENT_BYTES,
        multiline = true,
    )
    val schemaDigest: String = sha256(schemaDigest, "Workflow form schema digest")
    val uiSchemaDocument: String? = optionalText(
        uiSchemaDocument,
        "Workflow form UI schema",
        MAX_FORM_DOCUMENT_BYTES,
        multiline = true,
    )
    val uiSchemaDigest: String? = uiSchemaDigest?.let { sha256(it, "Workflow form UI schema digest") }
    val projectedData: String? = optionalText(
        projectedData,
        "Workflow form projected data",
        MAX_FORM_DATA_BYTES,
        multiline = true,
    )

    init {
        require((this.uiSchemaDocument == null) == (this.uiSchemaDigest == null)) {
            "Workflow UI schema and digest must be supplied together."
        }
    }

    companion object {
        const val MAX_FORM_DOCUMENT_BYTES: Int = 524_288
        const val MAX_FORM_DATA_BYTES: Int = 262_144
    }
}

class WorkflowFormSubmissionCommand(
    formId: String,
    formVersion: String,
    canonicalData: String,
    dataDigest: String,
) {
    val formId: String = requiredText(formId, "Workflow submission form id", 512)
    val formVersion: String = requiredText(formVersion, "Workflow submission form version", 128)
    val canonicalData: String = requiredText(
        canonicalData,
        "Workflow submission canonical data",
        WorkflowTaskFormDto.MAX_FORM_DATA_BYTES,
        multiline = true,
    )
    val dataDigest: String = sha256(dataDigest, "Workflow submission data digest")

    override fun toString(): String = "WorkflowFormSubmissionCommand(<redacted>)"
}
