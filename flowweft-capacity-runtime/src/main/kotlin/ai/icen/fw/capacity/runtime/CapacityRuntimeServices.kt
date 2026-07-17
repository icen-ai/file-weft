package ai.icen.fw.capacity.runtime

import ai.icen.fw.capacity.api.CapacityAdmissionDecision
import ai.icen.fw.capacity.api.CapacityAdmissionOutcome
import ai.icen.fw.capacity.api.CapacityAdmissionRequest
import ai.icen.fw.capacity.api.CapacityDoctorRequest
import ai.icen.fw.capacity.api.CapacityDoctorReport
import ai.icen.fw.capacity.api.CapacityLeaseReleaseReceipt
import ai.icen.fw.capacity.api.CapacityLeaseReleaseRequest
import ai.icen.fw.capacity.api.CapacityLeaseRenewalReceipt
import ai.icen.fw.capacity.api.CapacityLeaseRenewalRequest
import ai.icen.fw.capacity.api.CapacityMetricCode
import ai.icen.fw.capacity.api.CapacityMetricSink
import ai.icen.fw.capacity.api.CapacityProviderCapability
import ai.icen.fw.capacity.api.CapacityProviderResult
import ai.icen.fw.capacity.api.CapacityPurpose
import ai.icen.fw.capacity.api.CapacityTrustedContext
import ai.icen.fw.capacity.api.CapacityTrustedContextProvider
import ai.icen.fw.capacity.api.CapacityWritePrecondition

/**
 * Provider-neutral composition root. Every external call is guarded against an ambient database
 * transaction, and mutation services invoke the provider exactly once per call.
 */
class CapacityRuntime @JvmOverloads constructor(
    trustedContexts: CapacityTrustedContextProvider,
    policies: CapacityPolicySource,
    providers: CapacityProviderRegistry,
    outcomeReconciliation: CapacityOutcomeReconciliationPort,
    externalCalls: CapacityExternalCallBoundary,
    degradationSafety: CapacityDegradationSafetyPolicy =
        CapacityDegradationSafetyPolicy.STANDARD_CAPACITY_ONLY,
    metrics: CapacityMetricSink = CapacityMetricSink.NOOP,
    afterCommit: CapacityAfterCommitSignalPort = CapacityAfterCommitSignalPort.DISCARD,
    clock: CapacityRuntimeClock = CapacityRuntimeClock.SYSTEM,
    identifiers: CapacityRuntimeIdGenerator = CapacityRuntimeIdGenerator.UUID,
) {
    private val kernel = CapacityRuntimeKernel(
        trustedContexts,
        policies,
        providers,
        outcomeReconciliation,
        externalCalls,
        degradationSafety,
        metrics,
        afterCommit,
        clock,
        identifiers,
    )

    @JvmField val admission: CapacityAdmissionRuntimeService = CapacityAdmissionRuntimeService(kernel)
    @JvmField val leases: CapacityLeaseRuntimeService = CapacityLeaseRuntimeService(kernel)
    @JvmField val observation: CapacityObservationRuntimeService = CapacityObservationRuntimeService(kernel)
    @JvmField val doctor: CapacityDoctorRuntimeService = CapacityDoctorRuntimeService(kernel)
    @JvmField val reconciliation: CapacityOutcomeReconciliationRuntimeService =
        CapacityOutcomeReconciliationRuntimeService(kernel)
}

class CapacityAdmissionRuntimeService internal constructor(
    private val kernel: CapacityRuntimeKernel,
) {
    /** Raw idempotency material is hashed in the first statement and never crosses a port. */
    fun admit(
        command: CapacityGuardCommand,
        rawIdempotencyKey: CharSequence,
    ): CapacityRuntimeResult<CapacityGuardReceipt> {
        val keyDigest = tryHash(rawIdempotencyKey)
            ?: return CapacityRuntimeResult.failure(CapacityRuntimeErrorCode.INVALID_REQUEST)
        return runCapacity {
            kernel.requireBoundary("capacity.admission.begin")
            rejectUnsafeDegradations(command)
            val startedAt = kernel.now()
            val admissionRootContext = kernel.context(CapacityPurpose.ADMISSION, command.target, startedAt)
            val observeContext = kernel.context(CapacityPurpose.OBSERVE, command.target, startedAt)
            kernel.ensureSamePrincipal(admissionRootContext, observeContext)
            val observeDeadline = runtimeDeadline(
                startedAt,
                command.maximumDurationMillis,
                minOf(
                    admissionRootContext.authorizationExpiresAt,
                    observeContext.authorizationExpiresAt,
                ),
            )
            val bound = kernel.provider(command.providerId, command.target, startedAt)
            val observedUsage = kernel.snapshot(
                bound,
                observeContext,
                command.target,
                command.workload,
                startedAt,
                observeDeadline,
            )
            val policyDeadline = minOf(
                observeDeadline,
                observedUsage.expiresAt,
                observedUsage.policyResolution.expiresAt,
            )
            if (policyDeadline <= observedUsage.observedAt) {
                kernel.fail(CapacityRuntimeErrorCode.POLICY_CHANGED)
            }
            val resolution = kernel.resolvePolicy(
                observeContext,
                command.providerId,
                command.target,
                command.workload,
                observedUsage.observedAt,
                policyDeadline,
            )
            kernel.validateSnapshotPolicy(observedUsage, resolution)
            if (command.demands.any { demand -> resolution.limitFor(demand.dimension) == null }) {
                kernel.fail(CapacityRuntimeErrorCode.POLICY_UNAVAILABLE)
            }

            val admittedAt = kernel.now()
            if (!observedUsage.isCurrent(admittedAt) || !resolution.isCurrent(admittedAt)) {
                kernel.fail(CapacityRuntimeErrorCode.POLICY_CHANGED)
            }
            val admissionContext = kernel.refreshedContext(
                admissionRootContext,
                CapacityPurpose.ADMISSION,
                command.target,
                admittedAt,
            )
            kernel.ensureSamePrincipal(observeContext, admissionContext)
            val admissionDeadline = runtimeBoundedDeadline(
                admittedAt,
                command.maximumDurationMillis,
                admissionContext.authorizationExpiresAt,
                policyDeadline,
            )
            kernel.verifyProvider(bound, admittedAt, false)
            val request = CapacityAdmissionRequest(
                kernel.nextId("admission"),
                admissionContext,
                command.target,
                command.workload,
                command.demands,
                command.permittedDegradations,
                CapacityWritePrecondition.admission(
                    keyDigest,
                    observedUsage.stateVersion,
                    resolution.resolutionDigest,
                ),
                admittedAt,
                admissionDeadline,
            )
            val unknownOutcome = unknownOutcomeReference(
                "capacity.admit",
                command.providerId,
                request.context,
                request.target,
                request.workload,
                request.bindingDigest,
                request.idempotencyScope.scopeDigest,
                request.idempotencyBindingDigest,
            )
            kernel.requireBoundary("capacity.provider.admit")
            val providerResult: CapacityProviderResult<CapacityAdmissionDecision> = try {
                bound.provider.admit(request)
            } catch (_: Exception) {
                kernel.failUnknown(unknownOutcome)
            }
            val decision = mutationValue(providerResult, unknownOutcome, kernel)
            validateDecision(request, command.providerId, decision, providerResult.replayed, unknownOutcome)
            val completedAt = kernel.now()
            validateCurrentDecision(decision, completedAt)
            if (providerResult.replayed) {
                kernel.refreshedContext(
                    admissionContext,
                    CapacityPurpose.ADMISSION,
                    command.target,
                    completedAt,
                )
            }
            kernel.verifyProvider(bound, completedAt, true, unknownOutcome)
            kernel.metric(
                command.providerId,
                CapacityMetricCode.ADMISSION_DECISION,
                command.target.level,
                command.workload,
                decision.decisionDigest,
                completedAt,
                decision.outcome,
            )
            CapacityRuntimeResult.success(
                CapacityGuardReceipt(decision, bound.descriptor.descriptorDigest, completedAt),
                providerResult.replayed,
            )
        }
    }

    private fun rejectUnsafeDegradations(command: CapacityGuardCommand) {
        if (command.permittedDegradations.any { capability ->
                !kernel.degradationSafety.isCapacityOnly(capability)
            }
        ) {
            kernel.fail(CapacityRuntimeErrorCode.SECURITY_DEGRADATION_FORBIDDEN)
        }
    }

    private fun validateDecision(
        request: CapacityAdmissionRequest,
        expectedProviderId: ai.icen.fw.core.id.Identifier,
        decision: CapacityAdmissionDecision,
        replayed: Boolean,
        unknownOutcome: CapacityUnknownOutcomeReference,
    ) {
        try {
            require(decision.providerId == expectedProviderId)
            require(decision.request.idempotencyBindingDigest == request.idempotencyBindingDigest)
            if (!replayed) require(decision.request.bindingDigest == request.bindingDigest)
            require(decision.request.idempotencyScope.scopeDigest == request.idempotencyScope.scopeDigest)
            require(decision.request.target == request.target && decision.request.workload == request.workload)
            require(decision.degradationCapabilities.all { capability ->
                kernel.degradationSafety.isCapacityOnly(capability)
            })
        } catch (_: Exception) {
            if (replayed) kernel.fail(CapacityRuntimeErrorCode.STATE_CONFLICT)
            kernel.failUnknown(unknownOutcome)
        }
    }

    private fun validateCurrentDecision(decision: CapacityAdmissionDecision, completedAt: Long) {
        try {
            require(completedAt >= decision.decidedAt && completedAt < decision.expiresAt)
            require(decision.usage.isCurrent(completedAt))
            if (decision.outcome == CapacityAdmissionOutcome.ADMIT ||
                decision.outcome == CapacityAdmissionOutcome.DEGRADE
            ) {
                require(decision.lease?.isCurrent(completedAt) == true)
            }
        } catch (_: Exception) {
            kernel.fail(
                if (decision.lease != null) CapacityRuntimeErrorCode.LEASE_EXPIRED
                else CapacityRuntimeErrorCode.POLICY_CHANGED,
            )
        }
    }
}

class CapacityLeaseRuntimeService internal constructor(
    private val kernel: CapacityRuntimeKernel,
) {
    fun renew(
        command: CapacityLeaseRenewCommand,
        rawIdempotencyKey: CharSequence,
    ): CapacityRuntimeResult<CapacityLeaseRenewRuntimeReceipt> {
        val keyDigest = tryHash(rawIdempotencyKey)
            ?: return CapacityRuntimeResult.failure(CapacityRuntimeErrorCode.INVALID_REQUEST)
        return runCapacity {
            kernel.requireBoundary("capacity.lease.renew.begin")
            val startedAt = kernel.now()
            if (!command.lease.isCurrent(startedAt)) kernel.fail(CapacityRuntimeErrorCode.LEASE_EXPIRED)
            val context = kernel.context(CapacityPurpose.LEASE, command.lease.target, startedAt)
            validateLeaseOwner(context, command.lease.tenantId, command.lease.principalId, command.lease.principalType)
            val initialDeadline = runtimeDeadline(
                startedAt,
                command.maximumDurationMillis,
                context.authorizationExpiresAt,
            )
            val bound = kernel.provider(command.lease.providerId, command.lease.target, startedAt)
            val resolution = kernel.resolvePolicy(
                context,
                command.lease.providerId,
                command.lease.target,
                command.lease.workload,
                startedAt,
                initialDeadline,
            )
            if (command.lease.demands.any { demand -> resolution.limitFor(demand.dimension) == null }) {
                kernel.fail(CapacityRuntimeErrorCode.POLICY_UNAVAILABLE)
            }
            val renewedAt = kernel.now()
            if (!command.lease.isCurrent(renewedAt) || !resolution.isCurrent(renewedAt)) {
                kernel.fail(CapacityRuntimeErrorCode.LEASE_EXPIRED)
            }
            val fresh = kernel.refreshedContext(context, CapacityPurpose.LEASE, command.lease.target, renewedAt)
            val deadline = runtimeBoundedDeadline(
                renewedAt,
                command.maximumDurationMillis,
                fresh.authorizationExpiresAt,
                minOf(initialDeadline, resolution.expiresAt),
            )
            kernel.verifyProvider(bound, renewedAt, false)
            val request = CapacityLeaseRenewalRequest(
                kernel.nextId("renewal"),
                fresh,
                command.lease,
                CapacityWritePrecondition.renewal(
                    keyDigest,
                    command.lease.stateVersion,
                    resolution.resolutionDigest,
                ),
                command.requestedExpiresAt,
                renewedAt,
                deadline,
            )
            val unknownOutcome = unknownOutcomeReference(
                "capacity.lease.renew",
                command.lease.providerId,
                request.context,
                request.lease.target,
                request.lease.workload,
                request.bindingDigest,
                request.idempotencyScope.scopeDigest,
                request.idempotencyBindingDigest,
            )
            kernel.requireBoundary("capacity.provider.renew")
            val providerResult: CapacityProviderResult<CapacityLeaseRenewalReceipt> = try {
                bound.provider.renew(request)
            } catch (_: Exception) {
                kernel.failUnknown(unknownOutcome)
            }
            val receipt = mutationValue(providerResult, unknownOutcome, kernel)
            validateRenewal(request, receipt, providerResult.replayed, unknownOutcome)
            val completedAt = kernel.now()
            if (!receipt.renewedLease.isCurrent(completedAt)) {
                kernel.fail(CapacityRuntimeErrorCode.LEASE_EXPIRED)
            }
            if (providerResult.replayed) {
                kernel.refreshedContext(fresh, CapacityPurpose.LEASE, command.lease.target, completedAt)
            }
            kernel.verifyProvider(bound, completedAt, true, unknownOutcome)
            kernel.metric(
                command.lease.providerId,
                CapacityMetricCode.LEASE_RENEWAL,
                command.lease.target.level,
                command.lease.workload,
                receipt.receiptDigest,
                completedAt,
            )
            CapacityRuntimeResult.success(
                CapacityLeaseRenewRuntimeReceipt(receipt, bound.descriptor.descriptorDigest, completedAt),
                providerResult.replayed,
            )
        }
    }

    fun release(
        command: CapacityLeaseReleaseCommand,
        rawIdempotencyKey: CharSequence,
    ): CapacityRuntimeResult<CapacityLeaseReleaseRuntimeReceipt> {
        val keyDigest = tryHash(rawIdempotencyKey)
            ?: return CapacityRuntimeResult.failure(CapacityRuntimeErrorCode.INVALID_REQUEST)
        return runCapacity {
            kernel.requireBoundary("capacity.lease.release.begin")
            val startedAt = kernel.now()
            if (!command.lease.isCurrent(startedAt)) kernel.fail(CapacityRuntimeErrorCode.LEASE_EXPIRED)
            val context = kernel.context(CapacityPurpose.LEASE, command.lease.target, startedAt)
            validateLeaseOwner(context, command.lease.tenantId, command.lease.principalId, command.lease.principalType)
            val initialDeadline = runtimeDeadline(
                startedAt,
                command.maximumDurationMillis,
                context.authorizationExpiresAt,
            )
            val bound = kernel.provider(command.lease.providerId, command.lease.target, startedAt)
            val releasedAt = kernel.now()
            if (!command.lease.isCurrent(releasedAt)) kernel.fail(CapacityRuntimeErrorCode.LEASE_EXPIRED)
            val fresh = kernel.refreshedContext(context, CapacityPurpose.LEASE, command.lease.target, releasedAt)
            val deadline = runtimeBoundedDeadline(
                releasedAt,
                command.maximumDurationMillis,
                fresh.authorizationExpiresAt,
                initialDeadline,
            )
            kernel.verifyProvider(bound, releasedAt, false)
            val request = CapacityLeaseReleaseRequest(
                kernel.nextId("release"),
                fresh,
                command.lease,
                CapacityWritePrecondition.release(keyDigest, command.lease.stateVersion),
                command.reasonCode,
                releasedAt,
                deadline,
            )
            val unknownOutcome = unknownOutcomeReference(
                "capacity.lease.release",
                command.lease.providerId,
                request.context,
                request.lease.target,
                request.lease.workload,
                request.bindingDigest,
                request.idempotencyScope.scopeDigest,
                request.idempotencyBindingDigest,
            )
            kernel.requireBoundary("capacity.provider.release")
            val providerResult: CapacityProviderResult<CapacityLeaseReleaseReceipt> = try {
                bound.provider.release(request)
            } catch (_: Exception) {
                kernel.failUnknown(unknownOutcome)
            }
            val receipt = mutationValue(providerResult, unknownOutcome, kernel)
            validateRelease(request, receipt, providerResult.replayed, unknownOutcome)
            val completedAt = kernel.now()
            if (providerResult.replayed) {
                kernel.refreshedContext(fresh, CapacityPurpose.LEASE, command.lease.target, completedAt)
            }
            kernel.verifyProvider(bound, completedAt, true, unknownOutcome)
            kernel.metric(
                command.lease.providerId,
                CapacityMetricCode.LEASE_RELEASE,
                command.lease.target.level,
                command.lease.workload,
                receipt.receiptDigest,
                completedAt,
            )
            CapacityRuntimeResult.success(
                CapacityLeaseReleaseRuntimeReceipt(receipt, bound.descriptor.descriptorDigest, completedAt),
                providerResult.replayed,
            )
        }
    }

    private fun validateRenewal(
        request: CapacityLeaseRenewalRequest,
        receipt: CapacityLeaseRenewalReceipt,
        replayed: Boolean,
        unknownOutcome: CapacityUnknownOutcomeReference,
    ) {
        try {
            require(receipt.request.idempotencyBindingDigest == request.idempotencyBindingDigest)
            require(receipt.request.idempotencyScope.scopeDigest == request.idempotencyScope.scopeDigest)
            if (!replayed) require(receipt.request.bindingDigest == request.bindingDigest)
            require(receipt.providerId == request.lease.providerId)
            require(receipt.renewedLease.reservationId == request.lease.reservationId)
            require(receipt.renewedLease.leaseId == request.lease.leaseId)
            require(receipt.renewedLease.fencingToken > request.lease.fencingToken)
            require(receipt.renewedLease.stateVersion > request.lease.stateVersion)
        } catch (_: Exception) {
            if (replayed) kernel.fail(CapacityRuntimeErrorCode.STATE_CONFLICT)
            kernel.failUnknown(unknownOutcome)
        }
    }

    private fun validateRelease(
        request: CapacityLeaseReleaseRequest,
        receipt: CapacityLeaseReleaseReceipt,
        replayed: Boolean,
        unknownOutcome: CapacityUnknownOutcomeReference,
    ) {
        try {
            require(receipt.request.idempotencyBindingDigest == request.idempotencyBindingDigest)
            require(receipt.request.idempotencyScope.scopeDigest == request.idempotencyScope.scopeDigest)
            if (!replayed) require(receipt.request.bindingDigest == request.bindingDigest)
            require(receipt.providerId == request.lease.providerId)
            require(receipt.releasedStateVersion > request.lease.stateVersion)
        } catch (_: Exception) {
            if (replayed) kernel.fail(CapacityRuntimeErrorCode.STATE_CONFLICT)
            kernel.failUnknown(unknownOutcome)
        }
    }

    private fun validateLeaseOwner(
        context: CapacityTrustedContext,
        tenantId: ai.icen.fw.core.id.Identifier,
        principalId: ai.icen.fw.core.id.Identifier,
        principalType: String,
    ) {
        if (context.tenantId != tenantId || context.principalId != principalId ||
            context.principalType != principalType
        ) {
            kernel.fail(CapacityRuntimeErrorCode.AUTHORIZATION_REVOKED)
        }
    }
}

class CapacityObservationRuntimeService internal constructor(
    private val kernel: CapacityRuntimeKernel,
) {
    fun observe(command: CapacityObserveCommand): CapacityRuntimeResult<CapacityObservationReceipt> = runCapacity {
        kernel.requireBoundary("capacity.observe.begin")
        val startedAt = kernel.now()
        val context = kernel.context(CapacityPurpose.OBSERVE, command.target, startedAt)
        val deadline = runtimeDeadline(startedAt, command.maximumDurationMillis, context.authorizationExpiresAt)
        val bound = kernel.provider(command.providerId, command.target, startedAt)
        val snapshot = kernel.snapshot(
            bound,
            context,
            command.target,
            command.workload,
            startedAt,
            deadline,
        )
        val policyDeadline = minOf(deadline, snapshot.expiresAt, snapshot.policyResolution.expiresAt)
        if (policyDeadline <= snapshot.observedAt) kernel.fail(CapacityRuntimeErrorCode.POLICY_CHANGED)
        val resolution = kernel.resolvePolicy(
            context,
            command.providerId,
            command.target,
            command.workload,
            snapshot.observedAt,
            policyDeadline,
        )
        kernel.validateSnapshotPolicy(snapshot, resolution)
        val completedAt = kernel.now()
        kernel.refreshedContext(context, CapacityPurpose.OBSERVE, command.target, completedAt)
        kernel.verifyProvider(bound, completedAt, false)
        snapshot.measures.forEach { measure ->
            kernel.metric(
                command.providerId,
                CapacityMetricCode.PRESSURE_OBSERVATION,
                command.target.level,
                command.workload,
                snapshot.snapshotDigest,
                completedAt,
                pressure = measure.pressure,
            )
        }
        CapacityRuntimeResult.success(
            CapacityObservationReceipt(snapshot, bound.descriptor.descriptorDigest, completedAt),
        )
    }
}

class CapacityDoctorRuntimeService internal constructor(
    private val kernel: CapacityRuntimeKernel,
) {
    fun diagnose(command: CapacityDoctorCommand): CapacityRuntimeResult<CapacityDoctorRuntimeReceipt> = runCapacity {
        kernel.requireBoundary("capacity.doctor.begin")
        val startedAt = kernel.now()
        val context = kernel.context(CapacityPurpose.DOCTOR, command.target, startedAt)
        val deadline = runtimeDeadline(startedAt, command.maximumDurationMillis, context.authorizationExpiresAt)
        val bound = kernel.provider(command.providerId, command.target, startedAt)
        if (CapacityProviderCapability.DOCTOR_EVIDENCE !in bound.descriptor.capabilities) {
            kernel.fail(CapacityRuntimeErrorCode.PROVIDER_UNSUPPORTED)
        }
        val request = CapacityDoctorRequest(
            kernel.nextId("doctor"),
            context,
            command.target,
            startedAt,
            deadline,
        )
        kernel.requireBoundary("capacity.provider.doctor")
        val providerResult: CapacityProviderResult<CapacityDoctorReport> = try {
            bound.provider.doctor(request)
        } catch (_: Exception) {
            kernel.fail(CapacityRuntimeErrorCode.PROVIDER_UNAVAILABLE)
        }
        val report = providerResult.value ?: kernel.fail(mapProviderReadError(providerResult.errorCode))
        val completedAt = kernel.now()
        try {
            require(report.providerId == command.providerId)
            require(completedAt >= report.observedAt && completedAt < report.expiresAt)
        } catch (_: Exception) {
            kernel.fail(CapacityRuntimeErrorCode.PROVIDER_UNAVAILABLE)
        }
        kernel.refreshedContext(context, CapacityPurpose.DOCTOR, command.target, completedAt)
        kernel.verifyProvider(bound, completedAt, false)
        CapacityRuntimeResult.success(
            CapacityDoctorRuntimeReceipt(
                report,
                request.bindingDigest,
                bound.descriptor.descriptorDigest,
                completedAt,
            ),
            providerResult.replayed,
        )
    }
}

class CapacityOutcomeReconciliationRuntimeService internal constructor(
    private val kernel: CapacityRuntimeKernel,
) {
    /** Performs an exact read-only lookup; it never delegates to a capacity mutation method. */
    fun reconcile(
        command: CapacityOutcomeReconcileCommand,
    ): CapacityRuntimeResult<CapacityOutcomeReconciliationReceipt> = runCapacity {
        kernel.requireBoundary("capacity.reconciliation.begin")
        val reference = command.reference
        val startedAt = kernel.now()
        val context = kernel.context(CapacityRuntimePurposes.RECONCILIATION, reference.target, startedAt)
        if (!reference.isAuthorizedReconciliationTarget(context)) {
            kernel.fail(CapacityRuntimeErrorCode.AUTHORIZATION_REVOKED)
        }
        val deadline = runtimeDeadline(
            startedAt,
            command.maximumDurationMillis,
            context.authorizationExpiresAt,
        )
        val bound = kernel.provider(reference.providerId, reference.target, startedAt)
        val request = CapacityOutcomeReconciliationRequest(
            kernel.nextId("reconciliation"),
            context,
            reference,
            startedAt,
            deadline,
        )
        kernel.requireBoundary("capacity.provider.reconcile")
        val evidence = try {
            kernel.outcomeReconciliation.reconcile(request)
        } catch (_: Exception) {
            kernel.fail(CapacityRuntimeErrorCode.PROVIDER_UNAVAILABLE)
        }
        val completedAt = kernel.now()
        try {
            require(evidence.request.bindingDigest == request.bindingDigest)
            require(evidence.request.reference.referenceDigest == reference.referenceDigest)
            require(completedAt >= evidence.observedAt && completedAt < evidence.expiresAt)
        } catch (_: Exception) {
            kernel.fail(CapacityRuntimeErrorCode.PROVIDER_UNAVAILABLE)
        }
        kernel.refreshedContext(
            context,
            CapacityRuntimePurposes.RECONCILIATION,
            reference.target,
            completedAt,
        )
        kernel.verifyProvider(bound, completedAt, false)
        CapacityRuntimeResult.success(
            CapacityOutcomeReconciliationReceipt(evidence, bound.descriptor.descriptorDigest, completedAt),
        )
    }
}

private inline fun <T> runCapacity(block: () -> CapacityRuntimeResult<T>): CapacityRuntimeResult<T> = try {
    block()
} catch (failure: CapacityRuntimeFailure) {
    if (failure.code == CapacityRuntimeErrorCode.OUTCOME_UNKNOWN) {
        CapacityRuntimeResult.outcomeUnknown(requireNotNull(failure.unknownOutcomeReference))
    } else {
        CapacityRuntimeResult.failure(failure.code)
    }
} catch (_: IllegalArgumentException) {
    CapacityRuntimeResult.failure(CapacityRuntimeErrorCode.INVALID_REQUEST)
} catch (_: Exception) {
    CapacityRuntimeResult.failure(CapacityRuntimeErrorCode.INTERNAL_FAILURE)
}

private fun tryHash(rawIdempotencyKey: CharSequence): String? = try {
    hashRawIdempotencyKey(rawIdempotencyKey)
} catch (_: Exception) {
    null
}

private fun unknownOutcomeReference(
    operation: String,
    providerId: ai.icen.fw.core.id.Identifier,
    context: CapacityTrustedContext,
    target: ai.icen.fw.capacity.api.ResourceScope,
    workload: ai.icen.fw.capacity.api.WorkloadKind,
    requestBindingDigest: String,
    idempotencyScopeDigest: String,
    idempotencyBindingDigest: String,
): CapacityUnknownOutcomeReference = CapacityUnknownOutcomeReference(
    operation,
    providerId,
    target,
    workload,
    context.tenantId,
    context.principalId,
    context.principalType,
    requestBindingDigest,
    idempotencyScopeDigest,
    idempotencyBindingDigest,
)

private fun <T> mutationValue(
    providerResult: CapacityProviderResult<T>,
    unknownOutcome: CapacityUnknownOutcomeReference,
    kernel: CapacityRuntimeKernel,
): T {
    providerResult.value?.let { value -> return value }
    val mapped = mapProviderError(providerResult.errorCode)
    if (mapped == CapacityRuntimeErrorCode.OUTCOME_UNKNOWN) kernel.failUnknown(unknownOutcome)
    kernel.fail(mapped)
}
