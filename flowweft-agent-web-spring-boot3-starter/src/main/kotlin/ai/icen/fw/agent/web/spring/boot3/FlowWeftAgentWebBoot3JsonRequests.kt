package ai.icen.fw.agent.web.spring.boot3

import ai.icen.fw.agent.api.AgentBudget
import ai.icen.fw.agent.api.AgentCapabilityId
import ai.icen.fw.agent.api.AgentEvaluationProviderSnapshot
import ai.icen.fw.agent.api.ModelId
import ai.icen.fw.agent.api.ProviderId
import ai.icen.fw.agent.evaluation.AgentEvaluationDatasetReference
import ai.icen.fw.agent.evaluation.AgentEvaluationEvaluatorReference
import ai.icen.fw.agent.web.api.AgentWebConversationCreateCommand
import ai.icen.fw.agent.web.api.AgentWebEvaluationTriggerCommand
import ai.icen.fw.agent.web.api.AgentWebProviderConfigurationCommand
import ai.icen.fw.agent.web.api.AgentWebRunCancelCommand
import ai.icen.fw.agent.web.api.AgentWebRunCreateCommand
import ai.icen.fw.agent.web.api.AgentWebToolConfirmationDecisionCommand
import ai.icen.fw.agent.web.api.AgentWebUserMessageCommand
import ai.icen.fw.core.id.Identifier

internal class AgentWebBudgetJson {
    var maximumInputTokens: Long? = null
    var maximumOutputTokens: Long? = null
    var maximumModelCalls: Int? = null
    var maximumToolCalls: Int? = null
    var maximumDurationMillis: Long? = null
    var maximumCostMicros: Long? = null

    fun toDto(): AgentBudget = AgentBudget(
        required(maximumInputTokens),
        required(maximumOutputTokens),
        required(maximumModelCalls),
        required(maximumToolCalls),
        required(maximumDurationMillis),
        maximumCostMicros ?: 0L,
    )
}

internal class AgentWebConversationCreateJson {
    var capabilityId: String? = null
    var defaultBudget: AgentWebBudgetJson? = null
    var title: String? = null

    fun toCommand(): AgentWebConversationCreateCommand = AgentWebConversationCreateCommand(
        AgentCapabilityId(required(capabilityId)),
        required(defaultBudget).toDto(),
        title,
    )
}

internal class AgentWebUserMessageJson {
    var clientMessageId: String? = null
    var authorizedDisplayText: String? = null

    fun toCommand(): AgentWebUserMessageCommand = AgentWebUserMessageCommand(
        Identifier(required(clientMessageId)),
        required(authorizedDisplayText),
    )
}

internal class AgentWebRunCreateJson {
    var capabilityId: String? = null
    var message: AgentWebUserMessageJson? = null
    var budget: AgentWebBudgetJson? = null
    var deadlineAt: Long? = null

    fun toCommand(): AgentWebRunCreateCommand = AgentWebRunCreateCommand(
        AgentCapabilityId(required(capabilityId)),
        required(message).toCommand(),
        required(budget).toDto(),
        required(deadlineAt),
    )
}

internal class AgentWebRunCancelJson {
    var reasonCode: String? = null
    fun toCommand(): AgentWebRunCancelCommand = AgentWebRunCancelCommand(required(reasonCode))
}

internal class AgentWebToolConfirmationDecisionJson {
    var proposalId: String? = null
    var argumentsDigest: String? = null
    var requestEvidenceDigest: String? = null
    var submissionNonce: String? = null
    var reasonCode: String? = null

    fun approve(requestId: Identifier): AgentWebToolConfirmationDecisionCommand =
        AgentWebToolConfirmationDecisionCommand.approve(
            requestId,
            Identifier(required(proposalId)),
            required(argumentsDigest),
            required(requestEvidenceDigest),
            required(submissionNonce),
            reasonCode,
        )

    fun reject(requestId: Identifier): AgentWebToolConfirmationDecisionCommand =
        AgentWebToolConfirmationDecisionCommand.reject(
            requestId,
            Identifier(required(proposalId)),
            required(argumentsDigest),
            required(requestEvidenceDigest),
            required(submissionNonce),
            required(reasonCode),
        )
}

internal class AgentWebProviderConfigurationJson {
    var providerId: String? = null
    var connectionProfileReference: String? = null
    var credentialReference: String? = null
    var modelId: String? = null
    var capabilities: List<String>? = null
    var enabled: Boolean? = null

    fun toCommand(): AgentWebProviderConfigurationCommand = AgentWebProviderConfigurationCommand(
        ProviderId(required(providerId)),
        Identifier(required(connectionProfileReference)),
        credentialReference?.let(::Identifier),
        modelId?.let(::ModelId),
        required(capabilities).map(::AgentCapabilityId),
        enabled ?: true,
    )
}

internal class AgentWebEvaluationDatasetReferenceJson {
    var suiteId: String? = null
    var version: String? = null
    var suiteDigest: String? = null

    fun toDto(): AgentEvaluationDatasetReference = AgentEvaluationDatasetReference(
        Identifier(required(suiteId)),
        required(version),
        required(suiteDigest),
    )
}

internal class AgentWebEvaluationProviderSnapshotJson {
    var providerId: String? = null
    var implementationVersion: String? = null
    var capabilities: List<String>? = null
    var descriptorDigest: String? = null
    var capturedAt: Long? = null
    var expiresAt: Long? = null

    fun toDto(): AgentEvaluationProviderSnapshot = AgentEvaluationProviderSnapshot(
        ProviderId(required(providerId)),
        required(implementationVersion),
        required(capabilities).map(::AgentCapabilityId),
        required(descriptorDigest),
        required(capturedAt),
        required(expiresAt),
    )
}

internal class AgentWebEvaluationEvaluatorReferenceJson {
    var evaluatorId: String? = null
    var implementationVersion: String? = null
    var descriptorBindingDigest: String? = null

    fun toDto(): AgentEvaluationEvaluatorReference = AgentEvaluationEvaluatorReference(
        ProviderId(required(evaluatorId)),
        required(implementationVersion),
        required(descriptorBindingDigest),
    )
}

internal class AgentWebEvaluationTriggerJson {
    var dataset: AgentWebEvaluationDatasetReferenceJson? = null
    var providerSnapshot: AgentWebEvaluationProviderSnapshotJson? = null
    var evaluator: AgentWebEvaluationEvaluatorReferenceJson? = null
    var deadlineAt: Long? = null
    var maximumAttempts: Int? = null

    fun toCommand(): AgentWebEvaluationTriggerCommand = AgentWebEvaluationTriggerCommand(
        required(dataset).toDto(),
        required(providerSnapshot).toDto(),
        required(evaluator).toDto(),
        required(deadlineAt),
        required(maximumAttempts),
    )
}

private fun <T : Any> required(value: T?): T =
    value ?: throw IllegalArgumentException("Agent Web JSON property is required.")
