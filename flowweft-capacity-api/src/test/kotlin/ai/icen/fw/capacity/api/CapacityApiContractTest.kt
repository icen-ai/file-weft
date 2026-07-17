package ai.icen.fw.capacity.api

import ai.icen.fw.core.id.Identifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CapacityApiContractTest {
    @Test
    fun `hierarchy chooses every strictest threshold and intersects degradations`() {
        val target = target()
        val policies = listOf(
            policy("system", ResourceScope.system(), 100L, 80L, 90L, setOf(DEFER)),
            policy("tenant", ResourceScope.tenant(TENANT), 80L, 70L, 75L, setOf(DEFER, ASYNC)),
            policy("provider", ResourceScope.provider(TENANT, PROVIDER), 60L, 50L, 55L, setOf(DEFER)),
            policy("resource", target, 70L, 40L, 65L, setOf(DEFER, ASYNC)),
        )

        val resolution = CapacityPolicyResolution.resolve(target, WorkloadKind.UPLOAD, policies, 200L)
        val limit = requireNotNull(resolution.limitFor(CapacityDimension.QUEUE_DEPTH))

        assertEquals(60L, limit.limit)
        assertEquals(40L, limit.warningWatermark)
        assertEquals(55L, limit.criticalWatermark)
        assertEquals(setOf(DEFER), resolution.allowedDegradations)
        assertTrue(ResourceScope.system().appliesTo(target))
        assertTrue(ResourceScope.tenant(TENANT).appliesTo(target))
        assertFalse(ResourceScope.provider(TENANT, id("other-provider")).appliesTo(target))
        assertEquals("custom.pipeline", WorkloadKind("custom.pipeline").value)
        assertEquals("flowweft.capacity.policy.v1", CapacityPolicy.CONTRACT_VERSION)
        assertEquals("flowweft.capacity.resource-scope.v1", ResourceScope.CONTRACT_VERSION)
        assertEquals("flowweft.capacity.workload-kind.v1", WorkloadKind.CONTRACT_VERSION)

        val wrongUnit = policy(
            "wrong-unit",
            target,
            10L,
            5L,
            8L,
            setOf(DEFER),
            CapacityDimension("queue_depth", CapacityUnit.BYTES),
        )
        assertFailsWith<IllegalArgumentException> {
            CapacityPolicyResolution.resolve(target, WorkloadKind.UPLOAD, policies + wrongUnit, 200L)
        }
    }

    @Test
    fun `atomic admission outcomes cannot hide retry reservation or security degradation`() {
        val resolution = resolution()
        val request = admissionRequest(resolution)
        val lease = CapacityReservationLease.issue(
            id("reservation-1"),
            id("lease-1"),
            PROVIDER,
            request,
            1L,
            6L,
            300L,
            600L,
        )
        val admittedUsage = usage(resolution, 6L, 300L, used = 20L, reserved = 10L)
        val admitted = CapacityAdmissionDecision.admit(
            id("decision-admit"),
            PROVIDER,
            request,
            admittedUsage,
            lease,
            300L,
            550L,
        )
        assertEquals(CapacityAdmissionOutcome.ADMIT, admitted.outcome)
        assertEquals(lease.leaseDigest, admitted.lease?.leaseDigest)

        val degraded = CapacityAdmissionDecision.degrade(
            id("decision-degrade"),
            PROVIDER,
            request,
            admittedUsage,
            lease,
            setOf(DEFER),
            CapacityDecisionReason.WATERMARK_PRESSURE,
            300L,
            550L,
        )
        assertEquals(setOf(DEFER), degraded.degradationCapabilities)
        assertFailsWith<IllegalArgumentException> {
            CapacityAdmissionDecision.degrade(
                id("decision-unsafe"),
                PROVIDER,
                request,
                admittedUsage,
                lease,
                setOf(ASYNC),
                CapacityDecisionReason.WATERMARK_PRESSURE,
                300L,
                550L,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            CapacityAdmissionDecision.admit(
                id("decision-outlives-lease"),
                PROVIDER,
                request,
                admittedUsage,
                lease,
                300L,
                650L,
            )
        }

        val unchangedUsage = usage(resolution, 5L, 300L, used = 50L, reserved = 0L)
        val throttled = CapacityAdmissionDecision.throttle(
            id("decision-throttle"),
            PROVIDER,
            request,
            unchangedUsage,
            250L,
            CapacityDecisionReason.WATERMARK_PRESSURE,
            300L,
            650L,
        )
        assertEquals(250L, throttled.retryAfterMillis)
        assertEquals(null, throttled.lease)
        val rejected = CapacityAdmissionDecision.reject(
            id("decision-reject"),
            PROVIDER,
            request,
            unchangedUsage,
            CapacityDecisionReason.LIMIT_EXCEEDED,
            300L,
            650L,
        )
        assertEquals(null, rejected.retryAfterMillis)

        val otherRequest = admissionRequest(
            resolution,
            context = context(CapacityPurpose.ADMISSION, requestId = "request-retry", revision = "auth-r2"),
        )
        assertEquals(request.idempotencyScope.scopeDigest, otherRequest.idempotencyScope.scopeDigest)
        assertEquals(request.idempotencyBindingDigest, otherRequest.idempotencyBindingDigest)
        assertNotEquals(request.bindingDigest, otherRequest.bindingDigest)
        assertFailsWith<IllegalArgumentException> {
            admissionRequest(
                resolution,
                context = context(
                    CapacityPurpose.ADMISSION,
                    authorizedScope = ResourceScope.provider(TENANT, id("other-provider")),
                ),
            )
        }
    }

    @Test
    fun `reservation renewal and release are principal scoped fenced and CAS bound`() {
        val resolution = resolution()
        val admission = admissionRequest(resolution)
        val lease = CapacityReservationLease.issue(
            id("reservation-lease"),
            id("lease-current"),
            PROVIDER,
            admission,
            10L,
            6L,
            300L,
            600L,
        )
        val leaseContext = context(CapacityPurpose.LEASE, requestId = "lease-request")
        val renewalRequest = CapacityLeaseRenewalRequest(
            id("renew-operation"),
            leaseContext,
            lease,
            CapacityWritePrecondition.renewal(DIGEST_B, 6L, resolution.resolutionDigest),
            900L,
            350L,
            500L,
        )
        val renewed = CapacityReservationLease.renewed(
            renewalRequest,
            resolution.resolutionDigest,
            11L,
            7L,
            400L,
            800L,
        )
        val renewedUsage = usage(resolution, 7L, 400L, used = 20L, reserved = 10L)
        val renewalReceipt = CapacityLeaseRenewalReceipt(
            id("renew-receipt"),
            PROVIDER,
            renewalRequest,
            renewed,
            renewedUsage,
            400L,
        )
        assertEquals(11L, renewalReceipt.renewedLease.fencingToken)

        val releaseRequest = CapacityLeaseReleaseRequest(
            id("release-operation"),
            leaseContext,
            renewed,
            CapacityWritePrecondition.release(DIGEST_C, 7L),
            "completed",
            420L,
            500L,
        )
        val releasedUsage = usage(resolution, 8L, 430L, used = 20L, reserved = 0L)
        val releaseReceipt = CapacityLeaseReleaseReceipt(
            id("release-receipt"),
            PROVIDER,
            releaseRequest,
            releasedUsage,
            8L,
            430L,
        )
        assertEquals(8L, releaseReceipt.releasedStateVersion)
        assertFailsWith<IllegalArgumentException> {
            CapacityLeaseRenewalRequest(
                id("cross-principal-renew"),
                context(CapacityPurpose.LEASE, principalId = "principal-2"),
                renewed,
                CapacityWritePrecondition.renewal(DIGEST_D, 7L, resolution.resolutionDigest),
                950L,
                450L,
                550L,
            )
        }
    }

    @Test
    fun `provider Doctor and metric contracts expose only codes bands and evidence digests`() {
        val resolution = resolution()
        val snapshot = usage(resolution, 5L, 300L, used = 10L, reserved = 0L)
        val signal = CapacityDoctorSignal(
            id("doctor-signal"),
            CapacityDoctorSignalCode.CAPACITY_WITHIN_LIMIT,
            CapacityDoctorStatus.READY,
            CapacityScopeLevel.RESOURCE,
            WorkloadKind.UPLOAD,
            CapacityDimension.QUEUE_DEPTH,
            CapacityPressureLevel.NORMAL,
            resolution.resolutionDigest,
            snapshot.snapshotDigest,
            300L,
            700L,
        )
        val report = CapacityDoctorReport(PROVIDER, CapacityDoctorStatus.READY, listOf(signal), 310L, 650L)
        assertEquals(64, report.reportDigest.length)
        assertFalse(report.toString().contains(TENANT.value))

        val metric = CapacityMetricEvidence(
            id("metric-1"),
            PROVIDER,
            CapacityMetricCode.ADMISSION_DECISION,
            CapacityScopeLevel.RESOURCE,
            WorkloadKind.UPLOAD,
            CapacityAdmissionOutcome.ADMIT,
            null,
            snapshot.snapshotDigest,
            310L,
        )
        assertEquals(64, metric.evidenceDigest.length)
        assertEquals(
            true,
            CapacityProviderResult.success(report, replayed = true).replayed,
        )
        assertEquals(
            CapacityProviderErrorCode.STATE_CONFLICT,
            CapacityProviderResult.failure<CapacityDoctorReport>(CapacityProviderErrorCode.STATE_CONFLICT).errorCode,
        )

        val descriptor = CapacityProviderDescriptor(
            PROVIDER,
            "capacity.provider.v1",
            setOf(
                CapacityProviderCapability.ATOMIC_ADMISSION,
                CapacityProviderCapability.HIERARCHICAL_POLICIES,
                CapacityProviderCapability.FENCED_LEASES,
                CapacityProviderCapability.USAGE_SNAPSHOTS,
                CapacityProviderCapability.DOCTOR_EVIDENCE,
            ),
            DIGEST_A,
            100L,
            900L,
        )
        assertTrue(descriptor.isCurrent(300L))
    }

    private fun admissionRequest(
        resolution: CapacityPolicyResolution,
        context: CapacityTrustedContext = context(CapacityPurpose.ADMISSION),
    ): CapacityAdmissionRequest = CapacityAdmissionRequest(
        id("admission-operation"),
        context,
        target(),
        WorkloadKind.UPLOAD,
        listOf(CapacityDemand(CapacityDimension.QUEUE_DEPTH, 10L)),
        setOf(DEFER),
        CapacityWritePrecondition.admission(DIGEST_A, 5L, resolution.resolutionDigest),
        250L,
        700L,
    )

    private fun resolution(): CapacityPolicyResolution = CapacityPolicyResolution.resolve(
        target(),
        WorkloadKind.UPLOAD,
        listOf(
            policy("system", ResourceScope.system(), 100L, 40L, 80L, setOf(DEFER)),
            policy("tenant", ResourceScope.tenant(TENANT), 80L, 30L, 70L, setOf(DEFER)),
            policy("provider", ResourceScope.provider(TENANT, PROVIDER), 60L, 25L, 55L, setOf(DEFER)),
            policy("resource", target(), 70L, 20L, 50L, setOf(DEFER)),
        ),
        200L,
    )

    private fun usage(
        resolution: CapacityPolicyResolution,
        stateVersion: Long,
        observedAt: Long,
        used: Long,
        reserved: Long,
    ): CapacityUsageSnapshot {
        val limit = requireNotNull(resolution.limitFor(CapacityDimension.QUEUE_DEPTH))
        return CapacityUsageSnapshot.capture(
            PROVIDER,
            resolution,
            listOf(CapacityMeasureSnapshot(limit, used, reserved)),
            stateVersion,
            observedAt,
            750L,
        )
    }

    private fun policy(
        name: String,
        scope: ResourceScope,
        limit: Long,
        warning: Long,
        critical: Long,
        degradations: Set<CapacityDegradationCapability>,
        dimension: CapacityDimension = CapacityDimension.QUEUE_DEPTH,
    ): CapacityPolicy = CapacityPolicy(
        id("policy-$name"),
        CapacityPolicy.CONTRACT_VERSION,
        "revision-1",
        1L,
        scope,
        setOf(WorkloadKind.UPLOAD),
        listOf(CapacityLimit(dimension, limit, warning, critical)),
        0L,
        1_000L,
        degradations,
        true,
    )

    private fun context(
        purpose: CapacityPurpose,
        authorizedScope: ResourceScope = target(),
        principalId: String = "principal-1",
        requestId: String = "request-1",
        revision: String = "auth-r1",
    ): CapacityTrustedContext = CapacityTrustedContext.authenticated(
        TENANT,
        id(principalId),
        "USER",
        id(requestId),
        purpose,
        authorizedScope,
        id("authentication-1"),
        id("authorization-decision-1"),
        revision,
        DIGEST_D,
        100L,
        1_000L,
    )

    private fun target(): ResourceScope = ResourceScope.resource(TENANT, "document", RESOURCE, PROVIDER)

    private fun id(value: String): Identifier = Identifier(value)

    companion object {
        private val TENANT = Identifier("tenant-1")
        private val PROVIDER = Identifier("provider-1")
        private val RESOURCE = Identifier("resource-1")
        private val DEFER = CapacityDegradationCapability.DEFER_SECONDARY_INDEXING
        private val ASYNC = CapacityDegradationCapability.ASYNC_CONNECTOR_DELIVERY
        private const val DIGEST_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        private const val DIGEST_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        private const val DIGEST_C = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
        private const val DIGEST_D = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
    }
}
