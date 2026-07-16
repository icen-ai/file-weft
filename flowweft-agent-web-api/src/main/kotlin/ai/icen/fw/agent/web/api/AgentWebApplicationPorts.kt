package ai.icen.fw.agent.web.api

import ai.icen.fw.agent.evaluation.AgentEvaluationDatasetReference
import ai.icen.fw.core.id.Identifier

/**
 * Public application boundaries consumed by future HTTP controllers and the same-origin BFF.
 *
 * Implementations call existing Agent application/runtime use cases only. They must perform fresh
 * authorization for every call and idempotent replay, return [AgentWebApplicationResult.hidden]
 * for cross-tenant or non-visible resources, and never expose a repository, durable store,
 * provider client, raw domain mutation interface or exception text.
 */
interface AgentConversationWebApplicationPort {
    fun create(
        context: AgentWebTrustedContext,
        preconditions: AgentWebWritePreconditions,
        command: AgentWebConversationCreateCommand,
    ): AgentWebApplicationResult<AgentWebConversationDto>

    fun list(
        context: AgentWebTrustedContext,
        query: AgentWebPageQuery,
    ): AgentWebApplicationResult<AgentWebPage<AgentWebConversationSummaryDto>>

    fun get(
        context: AgentWebTrustedContext,
        conversationId: Identifier,
    ): AgentWebApplicationResult<AgentWebConversationDto>

    fun startRun(
        context: AgentWebTrustedContext,
        conversationId: Identifier,
        preconditions: AgentWebWritePreconditions,
        command: AgentWebRunCreateCommand,
    ): AgentWebApplicationResult<AgentWebRunDto>

    fun listRuns(
        context: AgentWebTrustedContext,
        conversationId: Identifier,
        query: AgentWebPageQuery,
    ): AgentWebApplicationResult<AgentWebPage<AgentWebRunDto>>
}

interface AgentRunWebApplicationPort {
    fun get(
        context: AgentWebTrustedContext,
        runId: Identifier,
    ): AgentWebApplicationResult<AgentWebRunDto>

    fun listMessages(
        context: AgentWebTrustedContext,
        runId: Identifier,
        query: AgentWebPageQuery,
    ): AgentWebApplicationResult<AgentWebDurablePage<AgentWebVisibleMessageDto>>

    /** Durable frames may be serialized as polling, long-poll or SSE; this is never a Flow API. */
    fun listEvents(
        context: AgentWebTrustedContext,
        runId: Identifier,
        query: AgentWebPageQuery,
    ): AgentWebApplicationResult<AgentWebDurablePage<AgentWebRunEventDto>>

    fun cancel(
        context: AgentWebTrustedContext,
        runId: Identifier,
        preconditions: AgentWebWritePreconditions,
        command: AgentWebRunCancelCommand,
    ): AgentWebApplicationResult<AgentWebCommandReceiptDto>

    /** Every item must carry current authoritative authorization plus pre-filter evidence. */
    fun listCitations(
        context: AgentWebTrustedContext,
        runId: Identifier,
        query: AgentWebPageQuery,
    ): AgentWebApplicationResult<AgentWebPage<AgentWebCitationEvidenceDto>>
}

interface AgentToolConfirmationWebApplicationPort {
    fun inbox(
        context: AgentWebTrustedContext,
        query: AgentWebPageQuery,
    ): AgentWebApplicationResult<AgentWebPage<AgentWebToolConfirmationSummaryDto>>

    fun get(
        context: AgentWebTrustedContext,
        requestId: Identifier,
    ): AgentWebApplicationResult<AgentWebToolConfirmationDetailDto>

    /** Must require APPROVED and atomically consume the current-principal request exactly once. */
    fun approve(
        context: AgentWebTrustedContext,
        requestId: Identifier,
        preconditions: AgentWebWritePreconditions,
        command: AgentWebToolConfirmationDecisionCommand,
    ): AgentWebApplicationResult<AgentWebToolConfirmationDecisionDto>

    /** Must require REJECTED and atomically consume the current-principal request exactly once. */
    fun reject(
        context: AgentWebTrustedContext,
        requestId: Identifier,
        preconditions: AgentWebWritePreconditions,
        command: AgentWebToolConfirmationDecisionCommand,
    ): AgentWebApplicationResult<AgentWebToolConfirmationDecisionDto>
}

interface AgentConfigurationWebApplicationPort {
    fun listProviderCapabilities(
        context: AgentWebTrustedContext,
        query: AgentWebPageQuery,
    ): AgentWebApplicationResult<AgentWebPage<AgentWebProviderCapabilityDto>>

    fun listConfigurations(
        context: AgentWebTrustedContext,
        query: AgentWebPageQuery,
    ): AgentWebApplicationResult<AgentWebPage<AgentWebProviderConfigurationDto>>

    fun getConfiguration(
        context: AgentWebTrustedContext,
        profileId: Identifier,
    ): AgentWebApplicationResult<AgentWebProviderConfigurationDto>

    fun putConfiguration(
        context: AgentWebTrustedContext,
        profileId: Identifier,
        preconditions: AgentWebWritePreconditions,
        command: AgentWebProviderConfigurationCommand,
    ): AgentWebApplicationResult<AgentWebProviderConfigurationDto>

    fun doctor(context: AgentWebTrustedContext): AgentWebApplicationResult<AgentWebDoctorReportDto>
}

interface AgentEvaluationWebApplicationPort {
    fun listDatasets(
        context: AgentWebTrustedContext,
        query: AgentWebPageQuery,
    ): AgentWebApplicationResult<AgentWebPage<AgentWebEvaluationDatasetSummaryDto>>

    fun getDataset(
        context: AgentWebTrustedContext,
        dataset: AgentEvaluationDatasetReference,
    ): AgentWebApplicationResult<AgentWebEvaluationDatasetDto>

    fun trigger(
        context: AgentWebTrustedContext,
        preconditions: AgentWebWritePreconditions,
        command: AgentWebEvaluationTriggerCommand,
    ): AgentWebApplicationResult<AgentWebEvaluationRunDto>

    fun listRuns(
        context: AgentWebTrustedContext,
        query: AgentWebPageQuery,
    ): AgentWebApplicationResult<AgentWebPage<AgentWebEvaluationRunDto>>

    fun getRun(
        context: AgentWebTrustedContext,
        evaluationId: Identifier,
    ): AgentWebApplicationResult<AgentWebEvaluationRunDto>

    fun getResult(
        context: AgentWebTrustedContext,
        evaluationId: Identifier,
    ): AgentWebApplicationResult<AgentWebEvaluationResultDto>
}
