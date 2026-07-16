package ai.icen.fw.workflow.web.api

class WorkflowIncidentQuery @JvmOverloads constructor(
    state: String? = null,
    cursor: String? = null,
    limit: Int = WorkflowWebPageQuery.DEFAULT_LIMIT,
) {
    val state: String? = state?.let { requiredCode(it, "Workflow incident state", 96) }
    val page: WorkflowWebPageQuery = WorkflowWebPageQuery(cursor, limit)
}

/** Public incident view intentionally excludes payloads, exceptions, endpoints and lease data. */
class WorkflowIncidentDto @JvmOverloads constructor(
    id: String,
    instanceId: String,
    incidentType: String,
    state: String,
    failureCode: String,
    val recordVersion: Long,
    val createdAt: Long,
    val updatedAt: Long,
    activityId: String? = null,
    repairHintCode: String? = null,
) {
    val id: String = requiredText(id, "Workflow incident id", 512)
    val instanceId: String = requiredText(instanceId, "Workflow incident instance id", 512)
    val incidentType: String = requiredCode(incidentType, "Workflow incident type", 96)
    val state: String = requiredCode(state, "Workflow incident state", 96)
    val failureCode: String = requiredCode(failureCode, "Workflow incident failure code", 96)
    val activityId: String? = optionalText(activityId, "Workflow incident activity id", 512)
    val repairHintCode: String? = repairHintCode?.let {
        requiredCode(it, "Workflow incident repair hint", 96)
    }

    init {
        require(recordVersion >= 0L) { "Workflow incident record version must not be negative." }
        require(createdAt >= 0L && updatedAt >= createdAt) { "Workflow incident timestamps are invalid." }
    }
}

class WorkflowIncidentActionCommand @JvmOverloads constructor(
    reasonCode: String,
    repairPlanId: String? = null,
) {
    val reasonCode: String = requiredCode(reasonCode, "Workflow incident action reason", 96)
    val repairPlanId: String? = optionalText(repairPlanId, "Workflow incident repair plan id", 512)
}

class WorkflowIncidentOperation private constructor(code: String) {
    val code: String = requiredCode(code, "Workflow incident operation", 64)

    companion object {
        @JvmField val RETRY: WorkflowIncidentOperation = WorkflowIncidentOperation("RETRY")
        @JvmField val SKIP: WorkflowIncidentOperation = WorkflowIncidentOperation("SKIP")
        @JvmField val REPAIR: WorkflowIncidentOperation = WorkflowIncidentOperation("REPAIR")
    }
}

class WorkflowMigrationInstanceCommand(instanceId: String, val expectedVersion: Long) {
    val instanceId: String = requiredText(instanceId, "Workflow migration instance id", 512)

    init {
        require(expectedVersion >= 0L) { "Workflow migration instance version must not be negative." }
    }
}

class WorkflowMigrationNodeMappingCommand(sourceNodeId: String, targetNodeId: String) {
    val sourceNodeId: String = requiredText(sourceNodeId, "Workflow migration source node id", 512)
    val targetNodeId: String = requiredText(targetNodeId, "Workflow migration target node id", 512)
}

/** Explicit bounded migration plan. No arbitrary executable transform or URL is accepted. */
class WorkflowMigrationCommand @JvmOverloads constructor(
    sourceDefinitionId: String,
    sourceDefinitionVersion: String,
    targetDefinitionId: String,
    targetDefinitionVersion: String,
    instances: Collection<WorkflowMigrationInstanceCommand>,
    nodeMappings: Collection<WorkflowMigrationNodeMappingCommand>,
    variableTransformDescriptorId: String? = null,
) {
    val sourceDefinitionId: String = requiredText(sourceDefinitionId, "Workflow migration source definition id", 512)
    val sourceDefinitionVersion: String = requiredText(
        sourceDefinitionVersion,
        "Workflow migration source definition version",
        128,
    )
    val targetDefinitionId: String = requiredText(targetDefinitionId, "Workflow migration target definition id", 512)
    val targetDefinitionVersion: String = requiredText(
        targetDefinitionVersion,
        "Workflow migration target definition version",
        128,
    )
    val instances: List<WorkflowMigrationInstanceCommand> = immutableList(
        instances,
        "Workflow migration instances",
        1_000,
    ).also { values ->
        require(values.isNotEmpty()) { "Workflow migrations require at least one instance." }
        require(values.map { it.instanceId }.toSet().size == values.size) {
            "Workflow migration instances must be unique."
        }
    }
    val nodeMappings: List<WorkflowMigrationNodeMappingCommand> = immutableList(
        nodeMappings,
        "Workflow migration node mappings",
        500,
    ).also { values ->
        require(values.map { it.sourceNodeId }.toSet().size == values.size) {
            "Workflow migration source nodes must be unique."
        }
    }
    val variableTransformDescriptorId: String? = optionalText(
        variableTransformDescriptorId,
        "Workflow migration variable transform descriptor id",
        256,
    )
}

class WorkflowMigrationResultDto(
    id: String,
    state: String,
    val recordVersion: Long,
    val instanceCount: Int,
    val compatibleCount: Int,
    val migratedCount: Int,
    val failedCount: Int,
    val dryRun: Boolean,
    val createdAt: Long,
    val updatedAt: Long,
) {
    val id: String = requiredText(id, "Workflow migration id", 512)
    val state: String = requiredCode(state, "Workflow migration state", 96)

    init {
        require(recordVersion >= 0L) { "Workflow migration record version must not be negative." }
        require(instanceCount in 1..1_000) { "Workflow migration instance count is invalid." }
        require(compatibleCount in 0..instanceCount) { "Workflow migration compatible count is invalid." }
        require(migratedCount in 0..instanceCount) { "Workflow migration migrated count is invalid." }
        require(failedCount in 0..instanceCount) { "Workflow migration failed count is invalid." }
        require(migratedCount + failedCount <= instanceCount) { "Workflow migration result counts are invalid." }
        require(dryRun || compatibleCount >= migratedCount) { "Workflow migration compatibility count is invalid." }
        require(createdAt >= 0L && updatedAt >= createdAt) { "Workflow migration timestamps are invalid." }
    }
}

/** Doctor output contains only bounded codes, counts, buckets and safe repair-hint codes. */
class WorkflowDoctorCheckDto @JvmOverloads constructor(
    component: String,
    status: String,
    code: String,
    val affectedCount: Long,
    bucket: String? = null,
    repairHintCode: String? = null,
) {
    val component: String = requiredCode(component, "Workflow Doctor component", 96)
    val status: String = requiredCode(status, "Workflow Doctor status", 32)
    val code: String = requiredCode(code, "Workflow Doctor code", 96)
    val bucket: String? = bucket?.let { requiredCode(it, "Workflow Doctor bucket", 96) }
    val repairHintCode: String? = repairHintCode?.let {
        requiredCode(it, "Workflow Doctor repair hint", 96)
    }

    init {
        require(affectedCount >= 0L) { "Workflow Doctor affected count must not be negative." }
    }
}

class WorkflowDoctorReportDto(
    status: String,
    checks: Collection<WorkflowDoctorCheckDto>,
    val generatedAt: Long,
) {
    val status: String = requiredCode(status, "Workflow Doctor report status", 32)
    val checks: List<WorkflowDoctorCheckDto> = immutableList(
        checks,
        "Workflow Doctor checks",
        256,
    ).also { values ->
        require(values.map { it.component + "\u0000" + it.code + "\u0000" + (it.bucket ?: "") }
            .toSet().size == values.size
        ) { "Workflow Doctor checks must be unique." }
    }

    init {
        require(generatedAt >= 0L) { "Workflow Doctor report time must not be negative." }
    }
}
