package ai.icen.fw.agent.web.runtime

import ai.icen.fw.agent.web.api.AgentConfigurationWebApplicationPort
import ai.icen.fw.agent.web.api.AgentWebApplicationResult
import ai.icen.fw.agent.web.api.AgentWebCapabilityStatus
import ai.icen.fw.agent.web.api.AgentWebDoctorReportDto
import ai.icen.fw.agent.web.api.AgentWebPage
import ai.icen.fw.agent.web.api.AgentWebPageQuery
import ai.icen.fw.agent.web.api.AgentWebProviderCapabilityDto
import ai.icen.fw.agent.web.api.AgentWebProviderConfigurationCommand
import ai.icen.fw.agent.web.api.AgentWebProviderConfigurationDto
import ai.icen.fw.agent.web.api.AgentWebTrustedContext
import ai.icen.fw.agent.web.api.AgentWebWritePreconditions
import ai.icen.fw.core.id.Identifier

class DefaultAgentConfigurationWebApplication(
    private val security: AgentWebApplicationSecurity,
    private val transactions: AgentWebTransactionBoundary,
    private val mutations: AgentWebMutationJournal,
    private val outbox: AgentWebOutboxPort,
    private val capabilities: AgentWebProviderCapabilityInventoryPort,
    private val references: AgentWebProviderReferenceInventoryPort,
    private val configurations: AgentWebProviderConfigurationRepository,
    private val doctor: AgentWebDoctorPort,
) : AgentConfigurationWebApplicationPort {

    override fun listProviderCapabilities(
        context: AgentWebTrustedContext,
        query: AgentWebPageQuery,
    ): AgentWebApplicationResult<AgentWebPage<AgentWebProviderCapabilityDto>> = agentWebApplicationCall {
        val authorized = security.authorize(
            context,
            AgentWebAuthorizationAction.PROVIDER_READ,
            AgentWebAuthorizationTarget("agent.provider.collection", context.tenantId),
        )
        val page = capabilities.list(authorized.scope, query)
        AgentWebApplicationResult.success(AgentWebPage(page.items, page.nextCursor))
    }

    override fun listConfigurations(
        context: AgentWebTrustedContext,
        query: AgentWebPageQuery,
    ): AgentWebApplicationResult<AgentWebPage<AgentWebProviderConfigurationDto>> = agentWebApplicationCall {
        val authorized = security.authorize(
            context,
            AgentWebAuthorizationAction.CONFIG_READ,
            AgentWebAuthorizationTarget("agent.provider-configuration.collection", context.tenantId),
        )
        val page = configurations.list(authorized.scope, query)
        if (page.items.any { it.tenantId != context.tenantId }) throw AgentWebHiddenException()
        AgentWebApplicationResult.success(AgentWebPage(page.items.map { it.projection }, page.nextCursor))
    }

    override fun getConfiguration(
        context: AgentWebTrustedContext,
        profileId: Identifier,
    ): AgentWebApplicationResult<AgentWebProviderConfigurationDto> = agentWebApplicationCall {
        val authorized = security.authorize(
            context,
            AgentWebAuthorizationAction.CONFIG_READ,
            AgentWebAuthorizationTarget("agent.provider-configuration", profileId),
        )
        val record = configurations.find(authorized.scope, profileId) ?: throw AgentWebHiddenException()
        if (record.tenantId != context.tenantId || record.projection.profileId != profileId) {
            throw AgentWebHiddenException()
        }
        AgentWebApplicationResult.success(record.projection)
    }

    override fun putConfiguration(
        context: AgentWebTrustedContext,
        profileId: Identifier,
        preconditions: AgentWebWritePreconditions,
        command: AgentWebProviderConfigurationCommand,
    ): AgentWebApplicationResult<AgentWebProviderConfigurationDto> = agentWebApplicationCall {
        val authorized = security.authorize(
            context,
            AgentWebAuthorizationAction.CONFIG_WRITE,
            AgentWebAuthorizationTarget(
                "agent.provider-configuration",
                profileId,
                preconditions.versionTag.expectedVersion.toString(),
            ),
        )
        val current = configurations.find(authorized.scope, profileId)
        if (current != null && (current.tenantId != context.tenantId ||
                current.projection.profileId != profileId)
        ) throw AgentWebHiddenException()
        if (current == null && preconditions.versionTag.expectedVersion != 0L ||
            current != null && current.projection.stateVersion != preconditions.versionTag.expectedVersion
        ) throw AgentWebPreconditionException()

        val descriptor = capabilities.find(authorized.scope, command.providerId)
            ?: throw AgentWebUnavailableException()
        if (descriptor.providerId != command.providerId ||
            descriptor.status != AgentWebCapabilityStatus.AVAILABLE ||
            descriptor.expiresAt <= authorized.authorizedAt ||
            !descriptor.capabilities.containsAll(command.capabilities) ||
            command.modelId != null && command.modelId !in descriptor.models
        ) throw AgentWebUnavailableException()
        val connection = references.connection(command.connectionProfileReference)
            ?: throw AgentWebHiddenException()
        requireReference(context, command.providerId, command.connectionProfileReference, connection)
        val credential = command.credentialReference?.let { reference ->
            references.credential(reference)?.also { binding ->
                requireReference(context, command.providerId, reference, binding)
            } ?: throw AgentWebHiddenException()
        }
        val commandDigest = configurationCommandDigest(command, connection, credential)
        val mutation = AgentWebMutationScope.bind(
            context,
            preconditions.idempotencyKey,
            AgentWebAuthorizationAction.CONFIG_WRITE,
            profileId,
            commandDigest,
        )
        val operationId = security.nextId("agent-web-configuration-operation")
        transactions.inTransaction(AgentWebTransactionWork {
            val reserved = mutations.reserveBound(authorized.scope, mutation, operationId, authorized.authorizedAt)
            when (reserved.status) {
                AgentWebMutationReserveStatus.CONFLICT -> throw AgentWebConflictException()
                AgentWebMutationReserveStatus.REPLAY -> replayConfiguration(authorized.scope, reserved.record)
                AgentWebMutationReserveStatus.CREATED -> {
                    val nextVersion = Math.addExact(preconditions.versionTag.expectedVersion, 1L)
                    val createdAt = current?.projection?.createdAt ?: authorized.authorizedAt
                    val projection = AgentWebProviderConfigurationDto(
                        profileId,
                        command.providerId,
                        command.connectionProfileReference,
                        command.credentialReference,
                        command.modelId,
                        command.capabilities,
                        command.enabled,
                        commandDigest,
                        nextVersion,
                        createdAt,
                        authorized.authorizedAt,
                    )
                    val record = AgentWebProviderConfigurationRecord(context.tenantId, projection)
                    val write = configurations.put(
                        authorized.scope,
                        preconditions.versionTag.expectedVersion,
                        record,
                    )
                    if (write.status != AgentWebRepositoryWriteStatus.APPLIED &&
                        write.status != AgentWebRepositoryWriteStatus.REPLAYED
                    ) {
                        if (write.status == AgentWebRepositoryWriteStatus.VERSION_CONFLICT) {
                            throw AgentWebPreconditionException()
                        }
                        throw AgentWebConflictException()
                    }
                    write.requireExact(record)
                    appendOutbox(
                        authorized.scope,
                        operationId,
                        profileId,
                        "agent.provider-configuration.updated",
                        commandDigest,
                        authorized.authorizedAt,
                    )
                    mutations.transitionBound(
                        authorized.scope,
                        AgentWebMutationTransition(
                            mutation, operationId, AgentWebMutationStatus.RESERVED,
                            AgentWebMutationStatus.SUCCEEDED, profileId, nextVersion, null,
                            authorized.authorizedAt,
                        ),
                    )
                    AgentWebApplicationResult.success(projection)
                }
            }
        })
    }

    override fun doctor(context: AgentWebTrustedContext): AgentWebApplicationResult<AgentWebDoctorReportDto> =
        agentWebApplicationCall {
            val authorized = security.authorize(
                context,
                AgentWebAuthorizationAction.DOCTOR_READ,
                AgentWebAuthorizationTarget("agent.doctor", context.tenantId),
            )
            val checks = doctor.checks(authorized.authorizedAt)
            val report = doctor.report(checks, authorized.authorizedAt)
            if (report.observedAt != authorized.authorizedAt ||
                report.checks.any { it.observedAt > authorized.authorizedAt }
            ) throw AgentWebUnavailableException()
            AgentWebApplicationResult.success(report)
        }

    private fun replayConfiguration(
        scope: AgentWebAuthorizedPersistenceScope,
        record: AgentWebMutationRecord,
    ): AgentWebApplicationResult<AgentWebProviderConfigurationDto> = when (record.status) {
        AgentWebMutationStatus.SUCCEEDED -> {
            val resultId = requireNotNull(record.resultResourceId)
            if (resultId != record.scope.aggregateId) throw AgentWebOutcomeUnknownException()
            val current = configurations.find(scope, resultId)
                ?: throw AgentWebOutcomeUnknownException()
            if (current.tenantId != scope.tenantId || current.projection.profileId != resultId ||
                current.projection.stateVersion != record.resultVersion
            ) throw AgentWebOutcomeUnknownException()
            AgentWebApplicationResult.success(current.projection, true)
        }
        AgentWebMutationStatus.RESERVED,
        AgentWebMutationStatus.OUTCOME_UNKNOWN -> throw AgentWebOutcomeUnknownException()
        AgentWebMutationStatus.FAILED -> throw AgentWebConflictException()
    }

    private fun requireReference(
        context: AgentWebTrustedContext,
        providerId: ai.icen.fw.agent.api.ProviderId,
        expectedReferenceId: Identifier,
        reference: AgentWebProviderReferenceBinding,
    ) {
        if (reference.referenceId != expectedReferenceId || reference.tenantId != context.tenantId ||
            reference.providerId != providerId || !reference.enabled
        ) {
            throw AgentWebHiddenException()
        }
    }

    private fun configurationCommandDigest(
        command: AgentWebProviderConfigurationCommand,
        connection: AgentWebProviderReferenceBinding,
        credential: AgentWebProviderReferenceBinding?,
    ): String {
        val digest = AgentWebRuntimeDigest("flowweft.agent.web.runtime.provider-configuration.v1")
            .add(command.providerId.value)
            .add(command.connectionProfileReference.value)
            .add(connection.revision)
            .add(command.credentialReference?.value ?: "-")
            .add(credential?.revision ?: "-")
            .add(command.modelId?.value ?: "-")
            .add(command.enabled)
            .add(command.capabilities.size)
        command.capabilities.sortedBy { it.value }.forEach { digest.add(it.value) }
        return digest.finish()
    }

    private fun appendOutbox(
        scope: AgentWebAuthorizedPersistenceScope,
        operationId: Identifier,
        aggregateId: Identifier,
        eventType: String,
        payloadDigest: String,
        atTime: Long,
    ) {
        outbox.append(
            scope,
            AgentWebOutboxEvent(
                security.nextId("agent-web-outbox"), scope.tenantId, operationId, aggregateId,
                eventType, payloadDigest, atTime,
            ),
        )
    }
}
