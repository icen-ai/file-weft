package ai.icen.fw.capacity.persistence.jdbc

import ai.icen.fw.capacity.api.CapacityAdmissionDecision
import ai.icen.fw.capacity.api.CapacityAdmissionRequest
import ai.icen.fw.capacity.api.CapacityDegradationCapability
import ai.icen.fw.capacity.api.CapacityDemand
import ai.icen.fw.capacity.api.CapacityDimension
import ai.icen.fw.capacity.api.CapacityLeaseReleaseReceipt
import ai.icen.fw.capacity.api.CapacityLeaseReleaseRequest
import ai.icen.fw.capacity.api.CapacityLeaseRenewalReceipt
import ai.icen.fw.capacity.api.CapacityLeaseRenewalRequest
import ai.icen.fw.capacity.api.CapacityLimit
import ai.icen.fw.capacity.api.CapacityMeasureSnapshot
import ai.icen.fw.capacity.api.CapacityPolicy
import ai.icen.fw.capacity.api.CapacityPolicyResolution
import ai.icen.fw.capacity.api.CapacityPurpose
import ai.icen.fw.capacity.api.CapacityReservationLease
import ai.icen.fw.capacity.api.CapacityTrustedContext
import ai.icen.fw.capacity.api.CapacityUsageSnapshot
import ai.icen.fw.capacity.api.CapacityWritePrecondition
import ai.icen.fw.capacity.api.ResourceScope
import ai.icen.fw.capacity.api.WorkloadKind
import ai.icen.fw.core.id.Identifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CapacityJdbcCanonicalCodecTest {
    @Test
    fun `round trips resolution admission renewed chain and release canonically`() {
        val resolution = resolution()
        assertEquals(
            resolution.resolutionDigest,
            CapacityJdbcCanonicalCodec.decodeResolution(
                CapacityJdbcCanonicalCodec.encodeResolution(resolution),
            ).resolutionDigest,
        )

        val admissionRequest = admissionRequest(resolution)
        val initialChain = CapacityJdbcLeaseChain(
            admissionRequest,
            id("reservation-1"),
            id("lease-1"),
            PROVIDER,
            1L,
            6L,
            250L,
            600L,
        )
        val initialLease = initialChain.lease()
        val admission = CapacityAdmissionDecision.admit(
            id("decision-1"),
            PROVIDER,
            admissionRequest,
            usage(resolution, 6L, 250L, 0L, 10L),
            initialLease,
            250L,
            600L,
        )
        assertEquals(
            admission.decisionDigest,
            CapacityJdbcCanonicalCodec.decodeAdmission(
                CapacityJdbcCanonicalCodec.encodeAdmission(admission, initialChain),
            ).decisionDigest,
        )

        val renewalRequest = CapacityLeaseRenewalRequest(
            id("renew-operation-1"),
            context(CapacityPurpose.LEASE, "renew-request"),
            initialLease,
            CapacityWritePrecondition.renewal(DIGEST_B, 6L, resolution.resolutionDigest),
            800L,
            300L,
            500L,
        )
        val step = CapacityJdbcRenewalStep(
            renewalRequest.operationId,
            renewalRequest.context,
            renewalRequest.precondition,
            renewalRequest.requestedExpiresAt,
            renewalRequest.requestedAt,
            renewalRequest.deadlineAt,
            resolution.resolutionDigest,
            2L,
            7L,
            300L,
            800L,
        )
        val renewedChain = initialChain.copy(renewals = listOf(step))
        val renewedLease = renewedChain.lease()
        val renewal = CapacityLeaseRenewalReceipt(
            id("renew-receipt-1"),
            PROVIDER,
            renewalRequest,
            renewedLease,
            usage(resolution, 7L, 300L, 0L, 10L),
            300L,
        )
        assertEquals(
            renewal.receiptDigest,
            CapacityJdbcCanonicalCodec.decodeRenewal(
                CapacityJdbcCanonicalCodec.encodeRenewal(renewal, renewedChain),
            ).receiptDigest,
        )
        assertEquals(
            renewedLease.leaseDigest,
            CapacityJdbcCanonicalCodec.decodeLeaseChain(
                CapacityJdbcCanonicalCodec.encodeLeaseChain(renewedChain),
            ).lease().leaseDigest,
        )

        val releaseRequest = CapacityLeaseReleaseRequest(
            id("release-operation-1"),
            context(CapacityPurpose.LEASE, "release-request"),
            renewedLease,
            CapacityWritePrecondition.release(DIGEST_C, 7L),
            "completed",
            350L,
            500L,
        )
        val release = CapacityLeaseReleaseReceipt(
            id("release-receipt-1"),
            PROVIDER,
            releaseRequest,
            usage(resolution, 8L, 350L, 0L, 0L),
            8L,
            350L,
        )
        assertEquals(
            release.receiptDigest,
            CapacityJdbcCanonicalCodec.decodeRelease(
                CapacityJdbcCanonicalCodec.encodeRelease(release, renewedChain),
            ).receiptDigest,
        )
    }

    @Test
    fun `rejects truncation instead of partially reconstructing an outcome`() {
        val bytes = CapacityJdbcCanonicalCodec.encodeResolution(resolution())
        assertFailsWith<IllegalArgumentException> {
            CapacityJdbcCanonicalCodec.decodeResolution(bytes.copyOf(bytes.size - 1))
        }
    }

    private fun admissionRequest(resolution: CapacityPolicyResolution): CapacityAdmissionRequest =
        CapacityAdmissionRequest(
            id("admission-operation-1"),
            context(CapacityPurpose.ADMISSION, "admission-request"),
            target(),
            WorkloadKind.UPLOAD,
            listOf(CapacityDemand(CapacityDimension.QUEUE_DEPTH, 10L)),
            setOf(CapacityDegradationCapability.DEFER_SECONDARY_INDEXING),
            CapacityWritePrecondition.admission(DIGEST_A, 5L, resolution.resolutionDigest),
            250L,
            700L,
        )

    private fun resolution(): CapacityPolicyResolution = CapacityPolicyResolution.resolve(
        target(),
        WorkloadKind.UPLOAD,
        listOf(CapacityPolicy(
            id("policy-1"),
            CapacityPolicy.CONTRACT_VERSION,
            "revision-1",
            1L,
            ResourceScope.tenant(TENANT),
            setOf(WorkloadKind.UPLOAD),
            listOf(CapacityLimit(CapacityDimension.QUEUE_DEPTH, 100L, 70L, 90L)),
            0L,
            1_000L,
            setOf(CapacityDegradationCapability.DEFER_SECONDARY_INDEXING),
            true,
        )),
        200L,
    )

    private fun usage(
        resolution: CapacityPolicyResolution,
        stateVersion: Long,
        observedAt: Long,
        used: Long,
        reserved: Long,
    ): CapacityUsageSnapshot = CapacityUsageSnapshot.capture(
        PROVIDER,
        resolution,
        listOf(CapacityMeasureSnapshot(resolution.effectiveLimits.single(), used, reserved)),
        stateVersion,
        observedAt,
        750L,
    )

    private fun context(purpose: CapacityPurpose, requestId: String): CapacityTrustedContext =
        CapacityTrustedContext.authenticated(
            TENANT,
            id("principal-1"),
            "USER",
            id(requestId),
            purpose,
            target(),
            id("authentication-1"),
            id("authorization-1"),
            "auth-revision-1",
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
        private const val DIGEST_A = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        private const val DIGEST_B = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        private const val DIGEST_C = "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"
        private const val DIGEST_D = "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd"
    }
}
