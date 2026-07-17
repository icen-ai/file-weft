package ai.icen.fw.workflow.web.api

/**
 * Versioned definition source accepted by the draft route. The application layer owns codec
 * lookup, semantic linting and tenant binding; this DTO cannot name a tenant or executable class.
 */
class WorkflowDefinitionDraftCommand @JvmOverloads constructor(
    key: String,
    version: String,
    title: String,
    codecId: String,
    codecVersion: String,
    definitionSource: String,
    sourceDigest: String,
    description: String? = null,
) {
    val key: String = requiredText(key, "Workflow definition key", 256)
    val version: String = requiredText(version, "Workflow definition version", 128)
    val title: String = requiredText(title, "Workflow definition title", 512)
    val codecId: String = requiredText(codecId, "Workflow definition codec id", 128)
    val codecVersion: String = requiredText(codecVersion, "Workflow definition codec version", 128)
    val definitionSource: String = requiredText(
        definitionSource,
        "Workflow definition source",
        MAX_DEFINITION_SOURCE_BYTES,
        multiline = true,
    )
    val sourceDigest: String = sha256(sourceDigest, "Workflow definition source digest")
    val description: String? = optionalText(
        description,
        "Workflow definition description",
        16_384,
        multiline = true,
    )

    override fun toString(): String = "WorkflowDefinitionDraftCommand(<redacted>)"

    companion object {
        const val MAX_DEFINITION_SOURCE_BYTES: Int = 1_048_576
    }
}

class WorkflowDefinitionLifecycleCommand @JvmOverloads constructor(reasonCode: String? = null) {
    val reasonCode: String? = reasonCode?.let {
        requiredCode(it, "Workflow definition lifecycle reason", 96)
    }
}

class WorkflowDefinitionSummaryDto(
    id: String,
    key: String,
    version: String,
    status: String,
    title: String,
    contentDigest: String,
    val recordVersion: Long,
    val createdAt: Long,
    val updatedAt: Long,
) {
    val id: String = requiredText(id, "Workflow definition id", 512)
    val key: String = requiredText(key, "Workflow definition key", 256)
    val version: String = requiredText(version, "Workflow definition version", 128)
    val status: String = requiredCode(status, "Workflow definition status", 96)
    val title: String = requiredText(title, "Workflow definition title", 512)
    val contentDigest: String = sha256(contentDigest, "Workflow definition content digest")

    init {
        require(recordVersion >= 0L) { "Workflow definition record version must not be negative." }
        require(createdAt >= 0L && updatedAt >= createdAt) { "Workflow definition timestamps are invalid." }
    }
}

class WorkflowDefinitionDetailDto(
    val summary: WorkflowDefinitionSummaryDto,
    codecId: String,
    codecVersion: String,
    definitionSource: String,
    sourceDigest: String,
    diagnostics: Collection<WorkflowDefinitionDiagnosticDto>,
) {
    val codecId: String = requiredText(codecId, "Workflow definition codec id", 128)
    val codecVersion: String = requiredText(codecVersion, "Workflow definition codec version", 128)
    val definitionSource: String = requiredText(
        definitionSource,
        "Workflow definition source",
        WorkflowDefinitionDraftCommand.MAX_DEFINITION_SOURCE_BYTES,
        multiline = true,
    )
    val sourceDigest: String = sha256(sourceDigest, "Workflow definition source digest")
    val diagnostics: List<WorkflowDefinitionDiagnosticDto> = immutableList(
        diagnostics,
        "Workflow definition diagnostics",
        500,
    )

    override fun toString(): String = "WorkflowDefinitionDetailDto(<redacted>)"
}

/** Stable, non-secret lint result; raw provider exceptions are never public diagnostics. */
class WorkflowDefinitionDiagnosticDto @JvmOverloads constructor(
    code: String,
    severity: String,
    nodeId: String? = null,
) {
    val code: String = requiredCode(code, "Workflow definition diagnostic code", 96)
    val severity: String = requiredCode(severity, "Workflow definition diagnostic severity", 32)
    val nodeId: String? = optionalText(nodeId, "Workflow definition diagnostic node id", 512)
}
