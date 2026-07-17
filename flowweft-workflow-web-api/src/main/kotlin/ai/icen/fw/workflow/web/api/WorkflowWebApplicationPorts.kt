package ai.icen.fw.workflow.web.api

/**
 * Narrow application-facing ports consumed by future HTTP controllers.
 *
 * Implementations must obtain current authorization for every call, including idempotent replay;
 * must return [WorkflowWebApplicationResult.hidden] for cross-tenant or non-visible resources; and
 * must never expose repositories, persistence, vendor SDKs or raw runtime mutation interfaces.
 */
interface WorkflowDefinitionWebApplicationPort {
    fun list(
        context: WorkflowWebTrustedContext,
        query: WorkflowWebPageQuery,
    ): WorkflowWebApplicationResult<WorkflowWebPage<WorkflowDefinitionSummaryDto>>

    fun get(
        context: WorkflowWebTrustedContext,
        definitionId: WorkflowWebResourceId,
    ): WorkflowWebApplicationResult<WorkflowDefinitionDetailDto>

    fun putDraft(
        context: WorkflowWebTrustedContext,
        definitionId: WorkflowWebResourceId,
        preconditions: WorkflowWebWritePreconditions,
        command: WorkflowDefinitionDraftCommand,
    ): WorkflowWebApplicationResult<WorkflowWebCommandReceiptDto>

    fun publish(
        context: WorkflowWebTrustedContext,
        definitionId: WorkflowWebResourceId,
        preconditions: WorkflowWebWritePreconditions,
        command: WorkflowDefinitionLifecycleCommand,
    ): WorkflowWebApplicationResult<WorkflowWebCommandReceiptDto>

    fun retire(
        context: WorkflowWebTrustedContext,
        definitionId: WorkflowWebResourceId,
        preconditions: WorkflowWebWritePreconditions,
        command: WorkflowDefinitionLifecycleCommand,
    ): WorkflowWebApplicationResult<WorkflowWebCommandReceiptDto>
}

interface WorkflowInstanceWebApplicationPort {
    fun start(
        context: WorkflowWebTrustedContext,
        preconditions: WorkflowWebWritePreconditions,
        command: WorkflowInstanceStartCommand,
    ): WorkflowWebApplicationResult<WorkflowWebCommandReceiptDto>

    fun get(
        context: WorkflowWebTrustedContext,
        instanceId: WorkflowWebResourceId,
    ): WorkflowWebApplicationResult<WorkflowInstanceDto>

    fun control(
        context: WorkflowWebTrustedContext,
        instanceId: WorkflowWebResourceId,
        action: WorkflowInstanceControlOperation,
        preconditions: WorkflowWebWritePreconditions,
        command: WorkflowInstanceControlCommand,
    ): WorkflowWebApplicationResult<WorkflowWebCommandReceiptDto>

    fun history(
        context: WorkflowWebTrustedContext,
        instanceId: WorkflowWebResourceId,
        query: WorkflowWebPageQuery,
    ): WorkflowWebApplicationResult<WorkflowWebPage<WorkflowHistoryEventDto>>
}

interface WorkflowTaskWebApplicationPort {
    fun list(
        context: WorkflowWebTrustedContext,
        query: WorkflowWebPageQuery,
    ): WorkflowWebApplicationResult<WorkflowWebPage<WorkflowTaskSummaryDto>>

    fun get(
        context: WorkflowWebTrustedContext,
        taskId: WorkflowWebResourceId,
    ): WorkflowWebApplicationResult<WorkflowTaskDetailDto>

    fun claim(
        context: WorkflowWebTrustedContext,
        taskId: WorkflowWebResourceId,
        preconditions: WorkflowWebWritePreconditions,
        command: WorkflowTaskClaimCommand,
    ): WorkflowWebApplicationResult<WorkflowWebCommandReceiptDto>

    fun decide(
        context: WorkflowWebTrustedContext,
        taskId: WorkflowWebResourceId,
        preconditions: WorkflowWebWritePreconditions,
        command: WorkflowTaskDecisionCommand,
    ): WorkflowWebApplicationResult<WorkflowWebCommandReceiptDto>

    fun delegate(
        context: WorkflowWebTrustedContext,
        taskId: WorkflowWebResourceId,
        preconditions: WorkflowWebWritePreconditions,
        command: WorkflowTaskDelegateCommand,
    ): WorkflowWebApplicationResult<WorkflowWebCommandReceiptDto>

    fun addSign(
        context: WorkflowWebTrustedContext,
        taskId: WorkflowWebResourceId,
        preconditions: WorkflowWebWritePreconditions,
        command: WorkflowTaskAddSignCommand,
    ): WorkflowWebApplicationResult<WorkflowWebCommandReceiptDto>

    fun returnTask(
        context: WorkflowWebTrustedContext,
        taskId: WorkflowWebResourceId,
        preconditions: WorkflowWebWritePreconditions,
        command: WorkflowTaskReturnCommand,
    ): WorkflowWebApplicationResult<WorkflowWebCommandReceiptDto>

    fun getForm(
        context: WorkflowWebTrustedContext,
        taskId: WorkflowWebResourceId,
    ): WorkflowWebApplicationResult<WorkflowTaskFormDto>

    fun submitForm(
        context: WorkflowWebTrustedContext,
        taskId: WorkflowWebResourceId,
        preconditions: WorkflowWebWritePreconditions,
        command: WorkflowFormSubmissionCommand,
    ): WorkflowWebApplicationResult<WorkflowWebCommandReceiptDto>
}

interface WorkflowCollaborationWebApplicationPort {
    fun listComments(
        context: WorkflowWebTrustedContext,
        instanceId: WorkflowWebResourceId,
        query: WorkflowWebPageQuery,
    ): WorkflowWebApplicationResult<WorkflowWebPage<WorkflowCommentDto>>

    fun createComment(
        context: WorkflowWebTrustedContext,
        instanceId: WorkflowWebResourceId,
        preconditions: WorkflowWebWritePreconditions,
        command: WorkflowCommentDocumentCommand,
    ): WorkflowWebApplicationResult<WorkflowWebCommandReceiptDto>
}

interface WorkflowOperationsWebApplicationPort {
    fun listIncidents(
        context: WorkflowWebTrustedContext,
        query: WorkflowIncidentQuery,
    ): WorkflowWebApplicationResult<WorkflowWebPage<WorkflowIncidentDto>>

    fun getIncident(
        context: WorkflowWebTrustedContext,
        incidentId: WorkflowWebResourceId,
    ): WorkflowWebApplicationResult<WorkflowIncidentDto>

    fun actOnIncident(
        context: WorkflowWebTrustedContext,
        incidentId: WorkflowWebResourceId,
        action: WorkflowIncidentOperation,
        preconditions: WorkflowWebWritePreconditions,
        command: WorkflowIncidentActionCommand,
    ): WorkflowWebApplicationResult<WorkflowWebCommandReceiptDto>

    fun dryRunMigration(
        context: WorkflowWebTrustedContext,
        preconditions: WorkflowWebWritePreconditions,
        command: WorkflowMigrationCommand,
    ): WorkflowWebApplicationResult<WorkflowMigrationResultDto>

    fun executeMigration(
        context: WorkflowWebTrustedContext,
        preconditions: WorkflowWebWritePreconditions,
        command: WorkflowMigrationCommand,
    ): WorkflowWebApplicationResult<WorkflowMigrationResultDto>

    fun getMigration(
        context: WorkflowWebTrustedContext,
        migrationId: WorkflowWebResourceId,
    ): WorkflowWebApplicationResult<WorkflowMigrationResultDto>

    fun doctor(context: WorkflowWebTrustedContext): WorkflowWebApplicationResult<WorkflowDoctorReportDto>
}

/** Allows routes and consoles to distinguish absent capabilities from empty business results. */
interface WorkflowWebCapabilityApplicationPort {
    fun listCapabilities(
        context: WorkflowWebTrustedContext,
    ): WorkflowWebApplicationResult<WorkflowWebCapabilitiesDto>
}
