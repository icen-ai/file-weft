package ai.icen.fw.capacity.runtime

import ai.icen.fw.capacity.api.CapacityAdmissionOutcome
import ai.icen.fw.capacity.api.CapacityMetricCode
import ai.icen.fw.capacity.api.CapacityMetricEvidence
import ai.icen.fw.capacity.api.CapacityMetricSink
import ai.icen.fw.capacity.api.CapacityPolicyResolution
import ai.icen.fw.capacity.api.CapacityPressureLevel
import ai.icen.fw.capacity.api.CapacityProviderDescriptor
import ai.icen.fw.capacity.api.CapacityProviderResult
import ai.icen.fw.capacity.api.CapacityProviderSpi
import ai.icen.fw.capacity.api.CapacityPurpose
import ai.icen.fw.capacity.api.CapacityScopeLevel
import ai.icen.fw.capacity.api.CapacitySnapshotRequest
import ai.icen.fw.capacity.api.CapacityTrustedContext
import ai.icen.fw.capacity.api.CapacityTrustedContextProvider
import ai.icen.fw.capacity.api.CapacityUsageSnapshot
import ai.icen.fw.capacity.api.ResourceScope
import ai.icen.fw.capacity.api.WorkloadKind
import ai.icen.fw.core.id.Identifier

internal class CapacityRuntimeFailure(
    val code: CapacityRuntimeErrorCode,
    val unknownOutcomeReference: CapacityUnknownOutcomeReference? = null,
) : RuntimeException(code.value, null, false, false)

internal data class CapacityBoundProvider(
    val provider: CapacityProviderSpi,
    val descriptor: CapacityProviderDescriptor,
    val revisionDigest: String,
)

internal class CapacityRuntimeKernel(
    private val trustedContexts: CapacityTrustedContextProvider,
    private val policies: CapacityPolicySource,
    private val providers: CapacityProviderRegistry,
    val outcomeReconciliation: CapacityOutcomeReconciliationPort,
    private val externalCalls: CapacityExternalCallBoundary,
    val degradationSafety: CapacityDegradationSafetyPolicy,
    private val metrics: CapacityMetricSink,
    private val afterCommit: CapacityAfterCommitSignalPort,
    val clock: CapacityRuntimeClock,
    val identifiers: CapacityRuntimeIdGenerator,
) {
    fun now(): Long = try {
        clock.currentTimeMillis().also { current ->
            if (current < 0L) fail(CapacityRuntimeErrorCode.INTERNAL_FAILURE)
        }
    } catch (failure: CapacityRuntimeFailure) {
        throw failure
    } catch (_: Exception) {
        fail(CapacityRuntimeErrorCode.INTERNAL_FAILURE)
    }

    fun requireBoundary(operation: String) {
        try {
            externalCalls.requireOutsideTransaction(requireRuntimeCode(operation, "Capacity external operation"))
        } catch (failure: CapacityRuntimeFailure) {
            throw failure
        } catch (_: Exception) {
            fail(CapacityRuntimeErrorCode.TRANSACTION_BOUNDARY_VIOLATION)
        }
    }

    fun context(
        purpose: CapacityPurpose,
        target: ResourceScope,
        atTime: Long,
    ): CapacityTrustedContext {
        val context = try {
            trustedContexts.currentContext(purpose)
                ?: fail(CapacityRuntimeErrorCode.UNAUTHENTICATED)
        } catch (failure: CapacityRuntimeFailure) {
            throw failure
        } catch (_: Exception) {
            fail(CapacityRuntimeErrorCode.AUTHORIZATION_UNAVAILABLE)
        }
        try {
            context.requirePurpose(purpose)
            context.requireFresh(atTime)
            require(target.level == CapacityScopeLevel.SYSTEM || target.tenantId == context.tenantId)
            require(context.authorizedScope.appliesTo(target))
        } catch (_: Exception) {
            fail(CapacityRuntimeErrorCode.AUTHORIZATION_REVOKED)
        }
        return context
    }

    fun refreshedContext(
        previous: CapacityTrustedContext,
        purpose: CapacityPurpose,
        target: ResourceScope,
        atTime: Long,
    ): CapacityTrustedContext {
        val refreshed = context(purpose, target, atTime)
        if (!samePrincipal(previous, refreshed)) {
            fail(CapacityRuntimeErrorCode.AUTHORIZATION_REVOKED)
        }
        return refreshed
    }

    fun ensureSamePrincipal(first: CapacityTrustedContext, second: CapacityTrustedContext) {
        if (!samePrincipal(first, second)) {
            fail(CapacityRuntimeErrorCode.AUTHORIZATION_REVOKED)
        }
    }

    private fun samePrincipal(first: CapacityTrustedContext, second: CapacityTrustedContext): Boolean =
        first.tenantId == second.tenantId &&
            first.principalId == second.principalId &&
            first.principalType == second.principalType

    fun provider(providerId: Identifier, target: ResourceScope, atTime: Long): CapacityBoundProvider {
        validateProviderTarget(providerId, target)
        val provider = try {
            providers.find(providerId) ?: fail(CapacityRuntimeErrorCode.PROVIDER_UNSUPPORTED)
        } catch (failure: CapacityRuntimeFailure) {
            throw failure
        } catch (_: Exception) {
            fail(CapacityRuntimeErrorCode.PROVIDER_UNAVAILABLE)
        }
        val descriptor = descriptor(provider, "capacity.provider.describe")
        validateDescriptor(descriptor, providerId, atTime)
        return CapacityBoundProvider(provider, descriptor, providerRevisionDigest(descriptor))
    }

    fun verifyProvider(
        bound: CapacityBoundProvider,
        atTime: Long,
        mutationMayHaveCompleted: Boolean,
        unknownOutcomeReference: CapacityUnknownOutcomeReference? = null,
    ) {
        val current = try {
            descriptor(bound.provider, "capacity.provider.revalidate")
        } catch (failure: CapacityRuntimeFailure) {
            if (mutationMayHaveCompleted) failUnknown(requireNotNull(unknownOutcomeReference))
            throw failure
        }
        try {
            validateDescriptor(current, bound.descriptor.providerId, atTime)
        } catch (failure: CapacityRuntimeFailure) {
            if (mutationMayHaveCompleted) failUnknown(requireNotNull(unknownOutcomeReference))
            throw failure
        } catch (_: Exception) {
            if (mutationMayHaveCompleted) failUnknown(requireNotNull(unknownOutcomeReference))
            fail(CapacityRuntimeErrorCode.PROVIDER_UNAVAILABLE)
        }
        if (providerRevisionDigest(current) != bound.revisionDigest) {
            if (mutationMayHaveCompleted) failUnknown(requireNotNull(unknownOutcomeReference))
            fail(CapacityRuntimeErrorCode.PROVIDER_REVISION_DRIFT)
        }
    }

    private fun descriptor(
        provider: CapacityProviderSpi,
        operation: String,
    ): CapacityProviderDescriptor {
        requireBoundary(operation)
        return try {
            provider.descriptor()
        } catch (_: Exception) {
            fail(CapacityRuntimeErrorCode.PROVIDER_UNAVAILABLE)
        }
    }

    private fun validateDescriptor(
        descriptor: CapacityProviderDescriptor,
        expectedProviderId: Identifier,
        atTime: Long,
    ) {
        if (descriptor.providerId != expectedProviderId || !descriptor.isCurrent(atTime)) {
            fail(CapacityRuntimeErrorCode.PROVIDER_REVISION_DRIFT)
        }
    }

    fun resolvePolicy(
        context: CapacityTrustedContext,
        providerId: Identifier,
        target: ResourceScope,
        workload: WorkloadKind,
        observedAt: Long,
        deadlineAt: Long,
    ): CapacityPolicyResolution {
        val request = try {
            CapacityPolicySourceRequest(
                context,
                providerId,
                target,
                workload,
                observedAt,
                deadlineAt,
            )
        } catch (_: Exception) {
            fail(CapacityRuntimeErrorCode.AUTHORIZATION_REVOKED)
        }
        requireBoundary("capacity.policy.resolve")
        val snapshot = try {
            policies.snapshot(request)
        } catch (_: Exception) {
            fail(CapacityRuntimeErrorCode.POLICY_UNAVAILABLE)
        }
        try {
            require(snapshot.request.requestDigest == request.requestDigest)
            require(snapshot.coveredLevels.containsAll(requiredPolicyLevels(target)))
            require(snapshot.observedAt == observedAt && snapshot.expiresAt >= deadlineAt)
            if (snapshot.policies.isEmpty()) {
                fail(CapacityRuntimeErrorCode.POLICY_UNAVAILABLE)
            }
            val resolution = CapacityPolicyResolution.resolve(
                target,
                workload,
                snapshot.policies,
                observedAt,
            )
            require(resolution.isCurrent(observedAt) && resolution.expiresAt >= deadlineAt)
            return resolution
        } catch (failure: CapacityRuntimeFailure) {
            throw failure
        } catch (_: Exception) {
            fail(CapacityRuntimeErrorCode.POLICY_INVALID)
        }
    }

    fun snapshot(
        bound: CapacityBoundProvider,
        context: CapacityTrustedContext,
        target: ResourceScope,
        workload: WorkloadKind,
        requestedAt: Long,
        deadlineAt: Long,
    ): CapacityUsageSnapshot {
        val request = try {
            CapacitySnapshotRequest(
                nextId("snapshot"),
                context,
                target,
                workload,
                requestedAt,
                deadlineAt,
            )
        } catch (_: Exception) {
            fail(CapacityRuntimeErrorCode.INVALID_REQUEST)
        }
        requireBoundary("capacity.provider.snapshot")
        val providerResult: CapacityProviderResult<CapacityUsageSnapshot> = try {
            bound.provider.snapshot(request)
        } catch (_: Exception) {
            fail(CapacityRuntimeErrorCode.PROVIDER_UNAVAILABLE)
        }
        val snapshot = providerResult.value
            ?: fail(mapProviderReadError(providerResult.errorCode))
        val completedAt = now()
        try {
            require(snapshot.providerId == bound.descriptor.providerId)
            require(snapshot.target == target && snapshot.workload == workload)
            require(snapshot.observedAt in requestedAt until deadlineAt)
            require(snapshot.isCurrent(completedAt))
        } catch (_: Exception) {
            fail(CapacityRuntimeErrorCode.PROVIDER_UNAVAILABLE)
        }
        return snapshot
    }

    fun nextId(kind: String): Identifier = try {
        requireRuntimeIdentifier(identifiers.nextId(kind), "Capacity runtime generated identifier")
    } catch (_: Exception) {
        fail(CapacityRuntimeErrorCode.INTERNAL_FAILURE)
    }

    fun metric(
        providerId: Identifier,
        metric: CapacityMetricCode,
        scopeLevel: CapacityScopeLevel,
        workload: WorkloadKind,
        sourceDigest: String,
        observedAt: Long,
        outcome: CapacityAdmissionOutcome? = null,
        pressure: CapacityPressureLevel? = null,
    ) {
        try {
            val evidence = CapacityMetricEvidence(
                nextId("metric"),
                providerId,
                metric,
                scopeLevel,
                workload,
                outcome,
                pressure,
                sourceDigest,
                observedAt,
            )
            afterCommit.afterCommit(CapacityDeferredSignal { metrics.observe(evidence) })
        } catch (_: Exception) {
            // Capacity decisions are authoritative; optional telemetry must never change them.
        }
    }

    fun validateSnapshotPolicy(
        snapshot: CapacityUsageSnapshot,
        resolution: CapacityPolicyResolution,
    ) {
        if (snapshot.policyResolution.resolutionDigest != resolution.resolutionDigest) {
            fail(CapacityRuntimeErrorCode.POLICY_CHANGED)
        }
    }

    fun fail(code: CapacityRuntimeErrorCode): Nothing = throw CapacityRuntimeFailure(code)

    fun failUnknown(reference: CapacityUnknownOutcomeReference): Nothing =
        throw CapacityRuntimeFailure(CapacityRuntimeErrorCode.OUTCOME_UNKNOWN, reference)

    private fun validateProviderTarget(providerId: Identifier, target: ResourceScope) {
        if (target.providerId != null && target.providerId != providerId) {
            fail(CapacityRuntimeErrorCode.INVALID_REQUEST)
        }
    }

    private fun requiredPolicyLevels(target: ResourceScope): Set<CapacityScopeLevel> {
        val required = linkedSetOf(CapacityScopeLevel.SYSTEM)
        if (target.level != CapacityScopeLevel.SYSTEM) required.add(CapacityScopeLevel.TENANT)
        if (target.providerId != null) required.add(CapacityScopeLevel.PROVIDER)
        if (target.level == CapacityScopeLevel.RESOURCE) required.add(CapacityScopeLevel.RESOURCE)
        return required
    }

    private fun providerRevisionDigest(descriptor: CapacityProviderDescriptor): String =
        CapacityRuntimeDigest("flowweft.capacity.runtime.provider-revision.v1")
            .add(descriptor.providerId.value)
            .add(descriptor.contractVersion)
            .add(descriptor.configurationDigest)
            .also { digest ->
                descriptor.capabilities.sorted().forEach { capability -> digest.add(capability.value) }
            }
            .finish()
}
