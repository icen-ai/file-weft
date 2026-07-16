package ai.icen.fw.testkit.reliability

import ai.icen.fw.reliability.api.ReliabilityAction
import ai.icen.fw.reliability.api.ReliabilityPrincipalRef
import ai.icen.fw.reliability.api.ReliabilityProviderDescriptor
import ai.icen.fw.reliability.api.ReliabilityProviderSpi
import ai.icen.fw.reliability.api.ReliabilityPurpose
import ai.icen.fw.reliability.api.ReliabilityVersionFence
import ai.icen.fw.reliability.runtime.ReliabilityAuthorizedCallFactory
import ai.icen.fw.reliability.runtime.ReliabilityCreateCommand
import ai.icen.fw.reliability.runtime.ReliabilityProviderRegistry
import ai.icen.fw.reliability.runtime.ReliabilityRecoveryPolicySource
import ai.icen.fw.reliability.runtime.ReliabilityRegisteredProvider
import ai.icen.fw.reliability.runtime.ReliabilityRunRepository
import ai.icen.fw.reliability.runtime.ReliabilityRuntimeMetrics
import ai.icen.fw.reliability.runtime.ReliabilitySubmissionResult
import ai.icen.fw.reliability.runtime.ReliabilitySubmissionService
import ai.icen.fw.reliability.runtime.ReliabilityTopologySource
import ai.icen.fw.reliability.runtime.ReliabilityTrustedInvocation
import ai.icen.fw.reliability.runtime.ReliabilityWorker
import ai.icen.fw.reliability.runtime.ReliabilityWorkerCommand
import ai.icen.fw.reliability.runtime.ReliabilityWorkerMode
import ai.icen.fw.reliability.runtime.ReliabilityWorkerResult
import java.util.concurrent.CompletionStage
import java.util.function.BooleanSupplier

/**
 * Reusable composition root for external host, provider, and repository contract tests. Every
 * instance is isolated and exposes only synthetic trusted invocations.
 */
class ReliabilityRuntimeContractHarness private constructor(
    val topology: ReliabilityContractTopology,
    val repository: ReliabilityRunRepository,
    val providerDescriptor: ReliabilityProviderDescriptor,
    val clock: DeterministicReliabilityClock,
    val identifiers: DeterministicReliabilityIds,
    val authorization: StrictReliabilityAuthorizationFixture,
    val providerProbe: ReliabilityProviderProbe,
    val faults: ReliabilityFaultFixture,
) {
    val calls: ReliabilityAuthorizedCallFactory = ReliabilityAuthorizedCallFactory(authorization, identifiers)
    private val registry = ReliabilityProviderRegistry { providerId ->
        if (providerId == providerDescriptor.providerId) {
            ReliabilityRegisteredProvider.of(providerDescriptor, providerProbe)
        } else {
            null
        }
    }
    private val topologySource = ReliabilityTopologySource { request -> topology.snapshotFor(request.environment) }
    private val policySource = ReliabilityRecoveryPolicySource { topology.objectives }
    val submission: ReliabilitySubmissionService = ReliabilitySubmissionService(
        calls,
        identifiers,
        topologySource,
        policySource,
        registry,
        repository,
        ReliabilityRuntimeMetrics.NOOP,
        faults,
    )

    fun worker(): ReliabilityWorker = ReliabilityWorker(
        calls,
        identifiers,
        clock,
        topologySource,
        policySource,
        registry,
        repository,
        ReliabilityRuntimeMetrics.NOOP,
        faults,
    )

    @JvmOverloads
    fun operationInvocation(
        rawIdempotencyKey: String,
        requestedAtEpochMilli: Long = clock.nowEpochMilli(),
        tenantId: String = topology.tenantId,
        principal: ReliabilityPrincipalRef = authorization.operationPrincipal,
    ): ReliabilityTrustedInvocation = ReliabilityTrustedInvocation.of(
        tenantId,
        principal,
        ReliabilityPurpose.CREATE_BACKUP,
        ReliabilityAction.CREATE_BACKUP,
        topology.source.resource,
        rawIdempotencyKey,
        requestedAtEpochMilli,
        Math.addExact(requestedAtEpochMilli, 1_000L),
    )

    @JvmOverloads
    fun reconciliationInvocation(
        rawIdempotencyKey: String,
        requestedAtEpochMilli: Long = clock.nowEpochMilli(),
    ): ReliabilityTrustedInvocation = ReliabilityTrustedInvocation.of(
        topology.tenantId,
        authorization.reconciliationPrincipal,
        ReliabilityPurpose.RECONCILE,
        ReliabilityAction.RECONCILE_OPERATION,
        topology.source.resource,
        rawIdempotencyKey,
        requestedAtEpochMilli,
        Math.addExact(requestedAtEpochMilli, 1_000L),
    )

    @JvmOverloads
    fun submitCreate(
        rawIdempotencyKey: String,
        executionDeadlineEpochMilli: Long = Math.addExact(clock.nowEpochMilli(), 400_000L),
    ): ReliabilitySubmissionResult {
        val invocation = operationInvocation(rawIdempotencyKey)
        val command = ReliabilityCreateCommand.of(
            invocation,
            topology.source,
            ReliabilityVersionFence.of(
                topology.source.resource,
                1L,
                ReliabilityContractAssertions.digest('c'),
            ),
            providerDescriptor.providerId,
            executionDeadlineEpochMilli,
        )
        return submission.submitCreate(command)
    }

    @JvmOverloads
    fun advance(
        runId: String,
        ownerId: String,
        rawIdempotencyKey: String,
        requestedAtEpochMilli: Long = clock.nowEpochMilli(),
    ): CompletionStage<ReliabilityWorkerResult> = worker().runOne(
        ReliabilityWorkerCommand.of(
            operationInvocation(rawIdempotencyKey, requestedAtEpochMilli),
            runId,
            ownerId,
            ReliabilityWorkerMode.ADVANCE,
        ),
    )

    @JvmOverloads
    fun reconcile(
        runId: String,
        ownerId: String,
        rawIdempotencyKey: String,
        requestedAtEpochMilli: Long = clock.nowEpochMilli(),
    ): CompletionStage<ReliabilityWorkerResult> = worker().runOne(
        ReliabilityWorkerCommand.of(
            reconciliationInvocation(rawIdempotencyKey, requestedAtEpochMilli),
            runId,
            ownerId,
            ReliabilityWorkerMode.ADVANCE,
        ),
    )

    companion object {
        @JvmStatic
        @JvmOverloads
        fun of(
            topology: ReliabilityContractTopology,
            repository: ReliabilityRunRepository,
            provider: ReliabilityProviderSpi,
            providerDescriptor: ReliabilityProviderDescriptor,
            transactionActive: BooleanSupplier = BooleanSupplier { false },
        ): ReliabilityRuntimeContractHarness {
            val start = Math.addExact(topology.sourceSnapshot.observedAtEpochMilli, 10_000L)
            return ReliabilityRuntimeContractHarness(
                topology,
                repository,
                providerDescriptor,
                DeterministicReliabilityClock.startingAt(start),
                DeterministicReliabilityIds.create(),
                StrictReliabilityAuthorizationFixture.forTenant(topology.tenantId),
                ReliabilityProviderProbe.wrapping(provider, transactionActive),
                ReliabilityFaultFixture.create(),
            )
        }

        @JvmStatic
        @JvmOverloads
        fun inMemory(
            topology: ReliabilityContractTopology = ReliabilityTopologyFixtures.singleDatabase("tenant-contract"),
        ): ReliabilityRuntimeContractHarness {
            val repository = InMemoryReliabilityRunRepository.create()
            return of(
                topology,
                repository,
                DeterministicReliabilityProvider.forDescriptor(topology.providerDescriptor),
                topology.providerDescriptor,
                BooleanSupplier { repository.isTransactionActive() },
            )
        }
    }
}
