package ai.icen.fw.capacity.runtime

import ai.icen.fw.capacity.api.CapacityAdmissionDecision
import ai.icen.fw.capacity.api.CapacityAdmissionRequest
import ai.icen.fw.capacity.api.CapacityDecisionReason
import ai.icen.fw.capacity.api.CapacityDegradationCapability
import ai.icen.fw.capacity.api.CapacityDemand
import ai.icen.fw.capacity.api.CapacityDimension
import ai.icen.fw.capacity.api.CapacityDoctorReport
import ai.icen.fw.capacity.api.CapacityDoctorRequest
import ai.icen.fw.capacity.api.CapacityDoctorSignal
import ai.icen.fw.capacity.api.CapacityDoctorSignalCode
import ai.icen.fw.capacity.api.CapacityDoctorStatus
import ai.icen.fw.capacity.api.CapacityLeaseReleaseReceipt
import ai.icen.fw.capacity.api.CapacityLeaseReleaseRequest
import ai.icen.fw.capacity.api.CapacityLeaseRenewalReceipt
import ai.icen.fw.capacity.api.CapacityLeaseRenewalRequest
import ai.icen.fw.capacity.api.CapacityLimit
import ai.icen.fw.capacity.api.CapacityMeasureSnapshot
import ai.icen.fw.capacity.api.CapacityMetricEvidence
import ai.icen.fw.capacity.api.CapacityPolicy
import ai.icen.fw.capacity.api.CapacityPolicyResolution
import ai.icen.fw.capacity.api.CapacityPressureLevel
import ai.icen.fw.capacity.api.CapacityProviderCapability
import ai.icen.fw.capacity.api.CapacityProviderDescriptor
import ai.icen.fw.capacity.api.CapacityProviderErrorCode
import ai.icen.fw.capacity.api.CapacityProviderResult
import ai.icen.fw.capacity.api.CapacityProviderSpi
import ai.icen.fw.capacity.api.CapacityPurpose
import ai.icen.fw.capacity.api.CapacityReservationLease
import ai.icen.fw.capacity.api.CapacityScopeLevel
import ai.icen.fw.capacity.api.CapacitySnapshotRequest
import ai.icen.fw.capacity.api.CapacityTrustedContext
import ai.icen.fw.capacity.api.CapacityTrustedContextProvider
import ai.icen.fw.capacity.api.CapacityUsageSnapshot
import ai.icen.fw.capacity.api.CapacityWritePrecondition
import ai.icen.fw.capacity.api.ResourceScope
import ai.icen.fw.capacity.api.WorkloadKind
import ai.icen.fw.core.id.Identifier
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CapacityRuntimeTest {
    @Test
    fun `system policy lookup remains available to an explicitly system-authorized principal`() {
        val target = ResourceScope.system()
        val context = CapacityTrustedContext.authenticated(
            TENANT,
            PRINCIPAL,
            "SERVICE",
            Identifier("request-system-policy"),
            CapacityPurpose.OBSERVE,
            target,
            Identifier("authentication-system-policy"),
            Identifier("authorization-system-policy"),
            "revision-system-policy",
            DIGEST_D,
            100L,
            2_000L,
        )

        val request = CapacityPolicySourceRequest(
            context,
            PROVIDER,
            target,
            WORKLOAD,
            NOW,
            400L,
        )

        assertEquals(target, request.target)
        assertEquals(CapacityScopeLevel.SYSTEM, request.target.level)
    }

    @Test
    fun `admission hashes raw key resolves every hierarchy layer and accepts custom workload`() {
        val provider = TestProvider(DecisionMode.ADMIT)
        val metrics = mutableListOf<CapacityMetricEvidence>()
        val runtime = runtime(provider, metrics = metrics)
        val rawKey = "customer-visible-idempotency-key"

        val result = runtime.admission.admit(guard(), rawKey)

        assertTrue(result.isSuccess())
        assertEquals(1, provider.admitCalls)
        assertEquals(sha256(rawKey), provider.lastAdmission?.precondition?.idempotencyKeyDigest)
        assertEquals(WORKLOAD, result.value?.decision?.request?.workload)
        assertTrue(result.value?.isCapacityReserved() == true)
        assertEquals(1, metrics.size)
        assertFalse(result.toString().contains(rawKey))
        assertFalse(provider.lastAdmission.toString().contains(rawKey))
    }

    @Test
    fun `legal replay uses stable idempotency binding while exact request and auth revision change`() {
        val provider = TestProvider(DecisionMode.THROTTLE)
        val contexts = RotatingContexts()
        val runtime = runtime(provider, contexts)

        val first = runtime.admission.admit(guard(), "same-key")
        val firstRequest = requireNotNull(provider.lastAdmission)
        val second = runtime.admission.admit(guard(), "same-key")
        val secondRequest = requireNotNull(provider.lastAdmission)

        assertTrue(first.isSuccess())
        assertTrue(second.isSuccess())
        assertTrue(second.replayed)
        assertNotEquals(firstRequest.bindingDigest, secondRequest.bindingDigest)
        assertEquals(firstRequest.idempotencyScope.scopeDigest, secondRequest.idempotencyScope.scopeDigest)
        assertEquals(firstRequest.idempotencyBindingDigest, secondRequest.idempotencyBindingDigest)
        assertEquals(first.value?.decision?.decisionDigest, second.value?.decision?.decisionDigest)
        assertTrue(contexts.calls > 4)
    }

    @Test
    fun `same key with changed command conflicts instead of replaying previous decision`() {
        val provider = TestProvider(DecisionMode.THROTTLE)
        val runtime = runtime(provider)
        assertTrue(runtime.admission.admit(guard(amount = 1L), "same-key").isSuccess())

        val changed = runtime.admission.admit(guard(amount = 2L), "same-key")

        assertEquals(CapacityRuntimeErrorCode.STATE_CONFLICT, changed.errorCode)
        assertFalse(changed.replayed)
        assertNull(changed.unknownOutcomeReference)
    }

    @Test
    fun `post admission drift is outcome unknown with exact lookup reference and no blind retry`() {
        val provider = TestProvider(DecisionMode.ADMIT).apply { driftAfterMutation = true }
        val runtime = runtime(provider)

        val result = runtime.admission.admit(guard(), "drift-key")

        assertEquals(CapacityRuntimeErrorCode.OUTCOME_UNKNOWN, result.errorCode)
        val reference = requireNotNull(result.unknownOutcomeReference)
        assertEquals(CapacityUnknownOutcomeReference.ADMIT, reference.operation)
        assertEquals(provider.lastAdmission?.bindingDigest, reference.requestBindingDigest)
        assertEquals(1, provider.admitCalls)
        assertFalse(reference.toString().contains("drift-key"))
    }

    @Test
    fun `unknown mutation reconciles by exact read without a second admission`() {
        val provider = TestProvider(DecisionMode.ADMIT).apply { throwAfterAdmission = true }
        val reconciler = TestReconciler(provider)
        val contexts = RotatingContexts(reconciliationPrincipal = Identifier("operations-service"))
        val runtime = runtime(provider, contexts, reconciler = reconciler)

        val unknown = runtime.admission.admit(guard(), "unknown-key")
        val reference = requireNotNull(unknown.unknownOutcomeReference)
        assertEquals(1, provider.admitCalls)

        val reconciled = runtime.reconciliation.reconcile(
            CapacityOutcomeReconcileCommand(reference, 200L),
        )

        assertTrue(reconciled.isSuccess())
        assertEquals(CapacityOutcomeReconciliationStatus.APPLIED, reconciled.value?.evidence?.status)
        assertNotNull(reconciled.value?.evidence?.admissionDecision)
        assertEquals(1, provider.admitCalls)
        assertEquals(1, reconciler.calls)
        assertEquals(PRINCIPAL, reference.principalId)
        assertNotEquals(reference.principalId, contexts.lastReconciliationPrincipal)
    }

    @Test
    fun `reconciliation service principal still rejects cross tenant and wrong target`() {
        val provider = TestProvider(DecisionMode.ADMIT).apply { throwAfterAdmission = true }
        val initial = runtime(provider).admission.admit(guard(), "scope-key")
        val reference = requireNotNull(initial.unknownOutcomeReference)

        val otherTenant = runtime(
            provider,
            RotatingContexts(
                tenant = Identifier("tenant-other"),
                reconciliationPrincipal = Identifier("operations-service"),
            ),
        ).reconciliation.reconcile(CapacityOutcomeReconcileCommand(reference, 100L))
        assertEquals(CapacityRuntimeErrorCode.AUTHORIZATION_REVOKED, otherTenant.errorCode)

        val otherTarget = ResourceScope.resource(
            TENANT,
            "document",
            Identifier("resource-other"),
            PROVIDER,
        )
        val wrongTarget = runtime(
            provider,
            RotatingContexts(
                reconciliationPrincipal = Identifier("operations-service"),
                authorizedScopeOverride = otherTarget,
            ),
        ).reconciliation.reconcile(CapacityOutcomeReconcileCommand(reference, 100L))
        assertEquals(CapacityRuntimeErrorCode.AUTHORIZATION_REVOKED, wrongTarget.errorCode)
        assertEquals(1, provider.admitCalls)
    }

    @Test
    fun `post renewal and release descriptor drift are outcome unknown and invoke once`() {
        val renewalProvider = TestProvider(DecisionMode.ADMIT).apply {
            installLease(existingLease())
            driftAfterMutation = true
        }
        val renewalRuntime = runtime(renewalProvider)
        val renewal = renewalRuntime.leases.renew(
            CapacityLeaseRenewCommand(existingLease(), 900L, 200L),
            "renew-key",
        )
        assertEquals(CapacityRuntimeErrorCode.OUTCOME_UNKNOWN, renewal.errorCode)
        assertEquals(CapacityUnknownOutcomeReference.RENEW, renewal.unknownOutcomeReference?.operation)
        assertEquals(1, renewalProvider.renewCalls)

        val releaseProvider = TestProvider(DecisionMode.ADMIT).apply {
            installLease(existingLease())
            driftAfterMutation = true
        }
        val releaseRuntime = runtime(releaseProvider)
        val release = releaseRuntime.leases.release(
            CapacityLeaseReleaseCommand(existingLease(), "completed", 200L),
            "release-key",
        )
        assertEquals(CapacityRuntimeErrorCode.OUTCOME_UNKNOWN, release.errorCode)
        assertEquals(CapacityUnknownOutcomeReference.RELEASE, release.unknownOutcomeReference?.operation)
        assertEquals(1, releaseProvider.releaseCalls)
    }

    @Test
    fun `expired lease wrong fence cross tenant and active transaction fail closed`() {
        val provider = TestProvider(DecisionMode.ADMIT).apply { installLease(existingLease()) }
        val expired = runtime(provider, clock = CapacityRuntimeClock { 700L }).leases.release(
            CapacityLeaseReleaseCommand(existingLease(), "completed", 100L),
            "release-expired",
        )
        assertEquals(CapacityRuntimeErrorCode.LEASE_EXPIRED, expired.errorCode)
        assertEquals(0, provider.releaseCalls)

        provider.rejectFence = true
        val wrongFence = runtime(provider).leases.renew(
            CapacityLeaseRenewCommand(existingLease(), 900L, 200L),
            "renew-wrong-fence",
        )
        assertEquals(CapacityRuntimeErrorCode.STATE_CONFLICT, wrongFence.errorCode)

        val crossTenantContexts = RotatingContexts(tenant = Identifier("tenant-other"))
        val crossTenant = runtime(provider, crossTenantContexts).observation.observe(observe())
        assertEquals(CapacityRuntimeErrorCode.AUTHORIZATION_REVOKED, crossTenant.errorCode)

        val activeTransaction = runtime(
            provider,
            externalCalls = CapacityExternalCallBoundary { error("active transaction") },
        ).observation.observe(observe())
        assertEquals(CapacityRuntimeErrorCode.TRANSACTION_BOUNDARY_VIOLATION, activeTransaction.errorCode)
    }

    @Test
    fun `strict observation and doctor return value-free evidence`() {
        val provider = TestProvider(DecisionMode.ADMIT)
        val runtime = runtime(provider)

        val observed = runtime.observation.observe(observe())
        val doctor = runtime.doctor.diagnose(CapacityDoctorCommand(PROVIDER, TARGET, 200L))

        assertTrue(observed.isSuccess())
        assertEquals(CapacityPressureLevel.NORMAL, observed.value?.snapshot?.measures?.single()?.pressure)
        assertTrue(doctor.isSuccess())
        assertEquals(CapacityDoctorStatus.READY, doctor.value?.report?.status)
        assertFalse(doctor.value.toString().contains(TENANT.value))
    }

    @Test
    fun `missing hierarchy coverage and security degradation fail closed`() {
        val provider = TestProvider(DecisionMode.ADMIT)
        val incompletePolicies = CapacityPolicySource { request ->
            CapacityPolicySourceSnapshot(
                request,
                POLICIES,
                setOf(CapacityScopeLevel.SYSTEM, CapacityScopeLevel.TENANT),
                DIGEST_A,
                request.requestedAt,
                request.deadlineAt,
            )
        }
        val missingCoverage = runtime(provider, policySource = incompletePolicies)
            .observation.observe(observe())
        assertEquals(CapacityRuntimeErrorCode.POLICY_INVALID, missingCoverage.errorCode)

        val unsafe = CapacityGuardCommand(
            PROVIDER,
            TARGET,
            WORKLOAD,
            listOf(CapacityDemand(CapacityDimension.QUEUE_DEPTH, 1L)),
            setOf(CapacityDegradationCapability("skip.authorization")),
            200L,
        )
        val rejected = runtime(provider).admission.admit(unsafe, "unsafe-key")
        assertEquals(CapacityRuntimeErrorCode.SECURITY_DEGRADATION_FORBIDDEN, rejected.errorCode)
        assertEquals(0, provider.admitCalls)
    }

    private fun runtime(
        provider: TestProvider,
        contexts: RotatingContexts = RotatingContexts(),
        reconciler: CapacityOutcomeReconciliationPort = TestReconciler(provider),
        metrics: MutableList<CapacityMetricEvidence> = mutableListOf(),
        clock: CapacityRuntimeClock = CapacityRuntimeClock { NOW },
        externalCalls: CapacityExternalCallBoundary = CapacityExternalCallBoundary.UNMANAGED_NON_TRANSACTIONAL,
        policySource: CapacityPolicySource = FixedCapacityPolicySource(POLICIES),
    ): CapacityRuntime = CapacityRuntime(
        contexts,
        policySource,
        ImmutableCapacityProviderRegistry(mapOf(PROVIDER to provider)),
        reconciler,
        externalCalls,
        CapacityDegradationSafetyPolicy.STANDARD_CAPACITY_ONLY,
        { evidence -> metrics.add(evidence) },
        { signal -> signal.emit() },
        clock,
        SequenceIdentifiers(),
    )

    private fun guard(amount: Long = 10L): CapacityGuardCommand = CapacityGuardCommand(
        PROVIDER,
        TARGET,
        WORKLOAD,
        listOf(CapacityDemand(CapacityDimension.QUEUE_DEPTH, amount)),
        emptySet(),
        200L,
    )

    private fun observe(): CapacityObserveCommand = CapacityObserveCommand(
        PROVIDER,
        TARGET,
        WORKLOAD,
        200L,
    )

    private fun existingLease(): CapacityReservationLease {
        val resolution = resolution(NOW)
        val context = trustedContext(CapacityPurpose.ADMISSION, "lease-seed")
        val request = CapacityAdmissionRequest(
            Identifier("lease-seed-operation"),
            context,
            TARGET,
            WORKLOAD,
            listOf(CapacityDemand(CapacityDimension.QUEUE_DEPTH, 10L)),
            emptySet(),
            CapacityWritePrecondition.admission(DIGEST_A, 5L, resolution.resolutionDigest),
            NOW,
            500L,
        )
        return CapacityReservationLease.issue(
            Identifier("reservation-existing"),
            Identifier("lease-existing"),
            PROVIDER,
            request,
            10L,
            6L,
            NOW,
            600L,
        )
    }

    private class RotatingContexts(
        private val tenant: Identifier = TENANT,
        private val principal: Identifier = PRINCIPAL,
        private val reconciliationPrincipal: Identifier = principal,
        private val authorizedScopeOverride: ResourceScope? = null,
    ) : CapacityTrustedContextProvider {
        var calls: Int = 0
        var lastReconciliationPrincipal: Identifier? = null

        override fun currentContext(purpose: CapacityPurpose): CapacityTrustedContext {
            calls += 1
            val currentPrincipal = if (purpose == CapacityRuntimePurposes.RECONCILIATION) {
                reconciliationPrincipal.also { value -> lastReconciliationPrincipal = value }
            } else {
                principal
            }
            val authorized = authorizedScopeOverride
                ?: if (tenant == TENANT) TARGET else ResourceScope.tenant(tenant)
            return CapacityTrustedContext.authenticated(
                tenant,
                currentPrincipal,
                if (purpose == CapacityRuntimePurposes.RECONCILIATION) "SERVICE" else "USER",
                Identifier("request-$calls"),
                purpose,
                authorized,
                Identifier("authentication-$calls"),
                Identifier("authorization-$calls"),
                "revision-$calls",
                DIGEST_D,
                100L,
                2_000L,
            )
        }
    }

    private class SequenceIdentifiers : CapacityRuntimeIdGenerator {
        private var sequence = 0
        override fun nextId(kind: String): Identifier {
            sequence += 1
            return Identifier("$kind-$sequence")
        }
    }

    private enum class DecisionMode { ADMIT, THROTTLE }

    private class TestProvider(
        private val mode: DecisionMode,
    ) : CapacityProviderSpi {
        var admitCalls: Int = 0
        var renewCalls: Int = 0
        var releaseCalls: Int = 0
        var lastAdmission: CapacityAdmissionRequest? = null
        var driftAfterMutation: Boolean = false
        var throwAfterAdmission: Boolean = false
        var rejectFence: Boolean = false
        private var stateVersion: Long = 5L
        private var fencingToken: Long = 10L
        private var reserved: Long = 0L
        private val admissions = linkedMapOf<String, StoredAdmission>()
        val outcomes = linkedMapOf<String, Any>()

        fun installLease(lease: CapacityReservationLease) {
            stateVersion = lease.stateVersion
            fencingToken = lease.fencingToken
            reserved = lease.demands.sumOf { demand -> demand.amount }
        }

        override fun descriptor(): CapacityProviderDescriptor = CapacityProviderDescriptor(
            PROVIDER,
            "capacity.provider.v1",
            setOf(
                CapacityProviderCapability.ATOMIC_ADMISSION,
                CapacityProviderCapability.HIERARCHICAL_POLICIES,
                CapacityProviderCapability.FENCED_LEASES,
                CapacityProviderCapability.USAGE_SNAPSHOTS,
                CapacityProviderCapability.DOCTOR_EVIDENCE,
            ),
            if (driftAfterMutation && (admitCalls + renewCalls + releaseCalls) > 0) DIGEST_B else DIGEST_A,
            0L,
            2_000L,
        )

        override fun snapshot(request: CapacitySnapshotRequest): CapacityProviderResult<CapacityUsageSnapshot> =
            CapacityProviderResult.success(usage(request.requestedAt, request.deadlineAt))

        override fun admit(request: CapacityAdmissionRequest): CapacityProviderResult<CapacityAdmissionDecision> {
            admitCalls += 1
            lastAdmission = request
            val scope = request.idempotencyScope.scopeDigest
            admissions[scope]?.let { stored ->
                return if (stored.bindingDigest == request.idempotencyBindingDigest) {
                    CapacityProviderResult.success(stored.decision, replayed = true)
                } else {
                    CapacityProviderResult.failure(CapacityProviderErrorCode.STATE_CONFLICT)
                }
            }
            if (request.precondition.expectedStateVersion != stateVersion) {
                return CapacityProviderResult.failure(CapacityProviderErrorCode.STATE_CONFLICT)
            }
            val decision = when (mode) {
                DecisionMode.THROTTLE -> CapacityAdmissionDecision.throttle(
                    Identifier("decision-throttle-$admitCalls"),
                    PROVIDER,
                    request,
                    usage(request.requestedAt, request.deadlineAt),
                    50L,
                    CapacityDecisionReason.WATERMARK_PRESSURE,
                    request.requestedAt,
                    request.deadlineAt,
                )

                DecisionMode.ADMIT -> {
                    stateVersion += 1L
                    fencingToken += 1L
                    reserved += request.demands.sumOf { demand -> demand.amount }
                    val lease = CapacityReservationLease.issue(
                        Identifier("reservation-$admitCalls"),
                        Identifier("lease-$admitCalls"),
                        PROVIDER,
                        request,
                        fencingToken,
                        stateVersion,
                        request.requestedAt,
                        request.deadlineAt,
                    )
                    CapacityAdmissionDecision.admit(
                        Identifier("decision-admit-$admitCalls"),
                        PROVIDER,
                        request,
                        usage(request.requestedAt, request.deadlineAt),
                        lease,
                        request.requestedAt,
                        request.deadlineAt,
                    )
                }
            }
            admissions[scope] = StoredAdmission(request.idempotencyBindingDigest, decision)
            outcomes[mutationKey(request.idempotencyScope.scopeDigest, request.idempotencyBindingDigest)] = decision
            if (throwAfterAdmission) error("transport outcome is unknown")
            return CapacityProviderResult.success(decision)
        }

        override fun renew(request: CapacityLeaseRenewalRequest): CapacityProviderResult<CapacityLeaseRenewalReceipt> {
            renewCalls += 1
            if (rejectFence || request.lease.stateVersion != stateVersion ||
                request.lease.fencingToken != fencingToken
            ) {
                return CapacityProviderResult.failure(CapacityProviderErrorCode.STATE_CONFLICT)
            }
            stateVersion += 1L
            fencingToken += 1L
            val renewed = CapacityReservationLease.renewed(
                request,
                requireNotNull(request.precondition.expectedPolicyResolutionDigest),
                fencingToken,
                stateVersion,
                request.requestedAt,
                minOf(request.requestedExpiresAt, 800L),
            )
            val receipt = CapacityLeaseRenewalReceipt(
                Identifier("renewal-receipt-$renewCalls"),
                PROVIDER,
                request,
                renewed,
                usage(request.requestedAt, request.deadlineAt),
                request.requestedAt,
            )
            outcomes[mutationKey(request.idempotencyScope.scopeDigest, request.idempotencyBindingDigest)] = receipt
            return CapacityProviderResult.success(receipt)
        }

        override fun release(request: CapacityLeaseReleaseRequest): CapacityProviderResult<CapacityLeaseReleaseReceipt> {
            releaseCalls += 1
            if (rejectFence || request.lease.stateVersion != stateVersion ||
                request.lease.fencingToken != fencingToken
            ) {
                return CapacityProviderResult.failure(CapacityProviderErrorCode.STATE_CONFLICT)
            }
            stateVersion += 1L
            reserved = 0L
            val receipt = CapacityLeaseReleaseReceipt(
                Identifier("release-receipt-$releaseCalls"),
                PROVIDER,
                request,
                usage(request.requestedAt, request.deadlineAt),
                stateVersion,
                request.requestedAt,
            )
            outcomes[mutationKey(request.idempotencyScope.scopeDigest, request.idempotencyBindingDigest)] = receipt
            return CapacityProviderResult.success(receipt)
        }

        override fun doctor(request: CapacityDoctorRequest): CapacityProviderResult<CapacityDoctorReport> {
            val signal = CapacityDoctorSignal(
                Identifier("doctor-signal"),
                CapacityDoctorSignalCode.CAPACITY_WITHIN_LIMIT,
                CapacityDoctorStatus.READY,
                request.target.level,
                null,
                null,
                null,
                null,
                null,
                request.requestedAt,
                request.deadlineAt,
            )
            return CapacityProviderResult.success(
                CapacityDoctorReport(
                    PROVIDER,
                    CapacityDoctorStatus.READY,
                    listOf(signal),
                    request.requestedAt,
                    request.deadlineAt,
                ),
            )
        }

        private fun usage(observedAt: Long, expiresAt: Long): CapacityUsageSnapshot {
            val resolution = resolution(observedAt)
            val limit = requireNotNull(resolution.limitFor(CapacityDimension.QUEUE_DEPTH))
            return CapacityUsageSnapshot.capture(
                PROVIDER,
                resolution,
                listOf(CapacityMeasureSnapshot(limit, 10L, reserved)),
                stateVersion,
                observedAt,
                minOf(expiresAt, resolution.expiresAt),
            )
        }

        private data class StoredAdmission(
            val bindingDigest: String,
            val decision: CapacityAdmissionDecision,
        )
    }

    private class TestReconciler(
        private val provider: TestProvider,
    ) : CapacityOutcomeReconciliationPort {
        var calls: Int = 0

        override fun reconcile(
            request: CapacityOutcomeReconciliationRequest,
        ): CapacityOutcomeReconciliationEvidence {
            calls += 1
            val outcome = provider.outcomes[
                mutationKey(
                    request.reference.idempotencyScopeDigest,
                    request.reference.idempotencyBindingDigest,
                )
            ]
                ?: return CapacityOutcomeReconciliationEvidence.confirmedNotApplied(
                    request,
                    DIGEST_C,
                    request.requestedAt,
                    request.deadlineAt,
                )
            return when (outcome) {
                is CapacityAdmissionDecision -> CapacityOutcomeReconciliationEvidence.appliedAdmission(
                    request,
                    outcome,
                    DIGEST_C,
                    request.requestedAt,
                    request.deadlineAt,
                )

                is CapacityLeaseRenewalReceipt -> CapacityOutcomeReconciliationEvidence.appliedRenewal(
                    request,
                    outcome,
                    DIGEST_C,
                    request.requestedAt,
                    request.deadlineAt,
                )

                is CapacityLeaseReleaseReceipt -> CapacityOutcomeReconciliationEvidence.appliedRelease(
                    request,
                    outcome,
                    DIGEST_C,
                    request.requestedAt,
                    request.deadlineAt,
                )

                else -> error("unsupported test outcome")
            }
        }
    }

    companion object {
        private val TENANT = Identifier("tenant-1")
        private val PRINCIPAL = Identifier("principal-1")
        private val PROVIDER = Identifier("provider-1")
        private val RESOURCE = Identifier("resource-1")
        private val TARGET = ResourceScope.resource(TENANT, "document", RESOURCE, PROVIDER)
        private val WORKLOAD = WorkloadKind("custom.batch")
        private const val NOW = 200L
        private const val DIGEST_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        private const val DIGEST_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        private const val DIGEST_C = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
        private const val DIGEST_D = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
        private val POLICIES = listOf(
            policy("system", ResourceScope.system(), 100L),
            policy("tenant", ResourceScope.tenant(TENANT), 90L),
            policy("provider", ResourceScope.provider(TENANT, PROVIDER), 80L),
            policy("resource", TARGET, 70L),
        )

        private fun policy(name: String, scope: ResourceScope, limit: Long): CapacityPolicy = CapacityPolicy(
            Identifier("policy-$name"),
            CapacityPolicy.CONTRACT_VERSION,
            "revision-1",
            1L,
            scope,
            setOf(WORKLOAD),
            listOf(CapacityLimit(CapacityDimension.QUEUE_DEPTH, limit, limit / 2L, limit - 1L)),
            0L,
            1_500L,
        )

        private fun resolution(atTime: Long): CapacityPolicyResolution =
            CapacityPolicyResolution.resolve(TARGET, WORKLOAD, POLICIES, atTime)

        private fun trustedContext(purpose: CapacityPurpose, suffix: String): CapacityTrustedContext =
            CapacityTrustedContext.authenticated(
                TENANT,
                PRINCIPAL,
                "USER",
                Identifier("request-$suffix"),
                purpose,
                TARGET,
                Identifier("authentication-$suffix"),
                Identifier("authorization-$suffix"),
                "revision-$suffix",
                DIGEST_D,
                100L,
                2_000L,
            )

        private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

        private fun mutationKey(scopeDigest: String, bindingDigest: String): String =
            "$scopeDigest:$bindingDigest"
    }
}
