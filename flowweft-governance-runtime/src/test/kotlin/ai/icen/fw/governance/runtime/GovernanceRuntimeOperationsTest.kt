package ai.icen.fw.governance.runtime

import ai.icen.fw.governance.api.GovernanceCapability
import ai.icen.fw.governance.api.GovernanceCapabilityRequest
import ai.icen.fw.governance.api.GovernanceCapabilityStatus
import ai.icen.fw.governance.api.GovernanceDoctorFinding
import ai.icen.fw.governance.api.GovernanceDoctorMode
import ai.icen.fw.governance.api.GovernanceDoctorRequest
import ai.icen.fw.governance.api.GovernanceDoctorSeverity
import ai.icen.fw.governance.api.GovernanceDoctorStatus
import ai.icen.fw.governance.api.GovernanceEffectiveClock
import ai.icen.fw.governance.api.GovernancePurpose
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GovernanceRuntimeOperationsTest {
    @Test
    fun `capability discovery fails closed when one fixed deletion stage has no provider`() {
        val fixture = GovernanceRuntimeTestFixture()
        fixture.missingStage = ai.icen.fw.governance.api.GovernanceDeletionStage.PURGE_OBJECT_CONTENT
        val context = fixture.calls.create(
            fixture.invocation(GovernancePurpose.DISCOVER_CAPABILITIES, "discover-capabilities"),
            GovernancePurpose.DISCOVER_CAPABILITIES,
            "discover-capabilities",
        )
        val request = GovernanceCapabilityRequest.of(context, listOf(GovernanceCapability.SECURE_DELETION))
        val provider = ProviderNeutralGovernanceCapabilityProvider(
            fixture.clock,
            fixture.providerRegistry,
            "governance-runtime",
            "runtime-r1",
        )

        val result = provider.capabilities(request).toCompletableFuture().get()

        assertEquals(GovernanceCapabilityStatus.UNSUPPORTED, result.status)
        assertEquals(null, result.snapshot)
    }

    @Test
    fun `Doctor replaces URL shaped source values with a bounded value-free finding`() {
        val fixture = GovernanceRuntimeTestFixture()
        val context = fixture.calls.create(
            fixture.invocation(GovernancePurpose.INSPECT_DOCTOR, "inspect-doctor"),
            GovernancePurpose.INSPECT_DOCTOR,
            "inspect-doctor",
        )
        val source = GovernanceRuntimeDiagnosticSource { _, _ ->
            listOf(
                GovernanceDoctorFinding.of(
                    "https://provider.example",
                    GovernanceDoctorSeverity.ERROR,
                    1L,
                ),
            )
        }
        val doctor = ProviderNeutralGovernanceDoctor(
            fixture.clock,
            listOf(source),
            fixture.providerRegistry,
        )

        val result = doctor.inspect(
            GovernanceDoctorRequest.of(context, GovernanceDoctorMode.CONNECTIVITY),
        ).toCompletableFuture().get()

        assertEquals(GovernanceDoctorStatus.NOT_READY, result.status)
        assertTrue(result.findings.any { it.code == "diagnostic-source-invalid" })
        assertFalse(result.findings.any { it.code.contains(':') || it.code.contains('/') })
    }

    @Test
    fun `Doctor reports an unavailable clock instead of silently looking ready`() {
        val fixture = GovernanceRuntimeTestFixture()
        val context = fixture.calls.create(
            fixture.invocation(GovernancePurpose.INSPECT_DOCTOR, "inspect-clock"),
            GovernancePurpose.INSPECT_DOCTOR,
            "inspect-clock",
        )
        val unavailableClock = object : GovernanceRuntimeClockPort {
            override fun nowEpochMilli(): Long = error("clock unavailable")

            override fun observe(request: GovernanceClockObservationRequest): GovernanceEffectiveClock =
                error("clock unavailable")
        }
        val doctor = ProviderNeutralGovernanceDoctor(
            unavailableClock,
            emptyList(),
            fixture.providerRegistry,
        )

        val result = doctor.inspect(
            GovernanceDoctorRequest.of(context, GovernanceDoctorMode.CONFIGURATION),
        ).toCompletableFuture().get()

        assertEquals(GovernanceDoctorStatus.NOT_READY, result.status)
        assertTrue(result.findings.any { it.code == "runtime-clock-unavailable" })
    }

    @Test
    fun `outbox relay closes repository transactions before signalling workers`() {
        val fixture = GovernanceRuntimeTestFixture()
        val run = fixture.createRun()
        val record = fixture.repository.outbox.single()
        var transactionActive = false
        var signalCalls = 0
        var acknowledged = false
        val outbox = object : GovernanceOutboxRepository {
            override fun claimReady(request: GovernanceOutboxClaimRequest): List<GovernanceClaimedOutboxRecord> =
                transaction {
                    assertEquals(run.tenantId, request.tenantId)
                    listOf(
                        GovernanceClaimedOutboxRecord.of(
                            record,
                            request.claimId,
                            request.workerId,
                            1L,
                            request.leaseExpiresAtEpochMilli,
                        ),
                    )
                }

            override fun acknowledge(
                claim: GovernanceClaimedOutboxRecord,
                acknowledgedAtEpochMilli: Long,
            ): Boolean = transaction {
                assertEquals(record.recordDigest, claim.record.recordDigest)
                assertEquals(2_000L, acknowledgedAtEpochMilli)
                acknowledged = true
                true
            }

            private fun <T> transaction(block: () -> T): T {
                check(!transactionActive)
                transactionActive = true
                return try {
                    block()
                } finally {
                    transactionActive = false
                }
            }
        }
        val relay = GovernanceOutboxRelay(
            outbox,
            GovernanceWorkerSignalPort {
                assertFalse(transactionActive, "Worker signal ran inside an outbox transaction")
                signalCalls += 1
                java.util.concurrent.CompletableFuture.completedFuture(null)
            },
        )
        val claim = GovernanceOutboxClaimRequest.of(
            run.tenantId,
            "worker-1",
            "claim-1",
            2_000L,
            3_000L,
            10,
        )

        val result = relay.relay(claim).toCompletableFuture().get()

        assertEquals(1, result.claimed)
        assertEquals(1, result.signalled)
        assertEquals(1, result.acknowledged)
        assertEquals(1, signalCalls)
        assertTrue(acknowledged)
        assertFalse(transactionActive)
    }
}
