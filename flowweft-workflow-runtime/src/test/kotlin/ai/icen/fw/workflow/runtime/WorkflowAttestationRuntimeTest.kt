package ai.icen.fw.workflow.runtime

import ai.icen.fw.workflow.api.WorkflowDefinitionRef
import ai.icen.fw.workflow.api.WorkflowInstanceRef
import ai.icen.fw.workflow.api.WorkflowPrincipalRef
import ai.icen.fw.workflow.api.WorkflowSubjectRef
import ai.icen.fw.workflow.api.WorkflowSubjectSnapshot
import ai.icen.fw.workflow.api.WorkflowWorkItemRef
import ai.icen.fw.workflow.spi.WorkflowAttestationArtifactRef
import ai.icen.fw.workflow.spi.WorkflowAttestationEvidence
import ai.icen.fw.workflow.spi.WorkflowAttestationProfileRef
import ai.icen.fw.workflow.spi.WorkflowAttestationStatement
import ai.icen.fw.workflow.spi.WorkflowElectronicSignatureProvider
import ai.icen.fw.workflow.spi.WorkflowElectronicSignatureResult
import ai.icen.fw.workflow.spi.WorkflowWitnessProvider
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class WorkflowAttestationRuntimeTest {
    @Test
    fun `signature is authorized twice and returns only immutable evidence reference`() {
        val fixture = Fixture()
        val result = fixture.runtime.attest(fixture.command())

        assertEquals(WorkflowAttestationResultCode.SUCCEEDED, result.code)
        assertNotNull(result.evaluation)
        assertEquals(2, fixture.authorization.calls)
        assertFalse(result.evaluation.toString().contains("artifact-sensitive"))
        assertFalse(result.evaluation.toString().contains("provider-evidence-sensitive"))
    }

    @Test
    fun `provider exception is outcome unknown and is never marked retryable`() {
        val fixture = Fixture(providerFailure = true)
        val result = fixture.runtime.attest(fixture.command())

        assertEquals(WorkflowAttestationResultCode.OUTCOME_UNKNOWN, result.code)
        assertNull(result.evaluation)
        assertEquals(false, result.diagnostic?.retryable)
    }

    @Test
    fun `mid-call revocation discards evidence and receipt drift fails closed`() {
        val revoked = Fixture(revokeOnSecondAuthorization = true)
        val revokedResult = revoked.runtime.attest(revoked.command())
        assertEquals(WorkflowAttestationResultCode.AUTHORIZATION_DENIED, revokedResult.code)

        val drifted = Fixture(foreignReceipt = true)
        val driftedResult = drifted.runtime.attest(drifted.command())
        assertEquals(WorkflowAttestationResultCode.RECEIPT_INVALID, driftedResult.code)
    }

    private class Fixture(
        revokeOnSecondAuthorization: Boolean = false,
        private val providerFailure: Boolean = false,
        private val foreignReceipt: Boolean = false,
    ) {
        val clock = MutableClock(100L)
        val authorization = TestAuthorization(revokeOnSecondAuthorization)
        private val statement = statement()
        private val profile = WorkflowAttestationProfileRef.of(
            "signature-provider",
            "enterprise-signature",
            "profile-v1",
            sha('c'),
        )
        private val signature = WorkflowElectronicSignatureProvider { request ->
            if (providerFailure) {
                CompletableFuture<WorkflowElectronicSignatureResult>().also { future ->
                    future.completeExceptionally(IllegalStateException("secret provider failure"))
                }
            } else {
                clock.now = 120L
                val context = if (foreignReceipt) {
                    ai.icen.fw.workflow.spi.WorkflowProviderCallContext.of(
                        "foreign-request",
                        request.context.tenantId,
                        request.context.providerId,
                        request.context.providerRevision,
                        request.context.purpose,
                        request.context.requestedAtEpochMilli,
                        request.context.deadlineEpochMilli,
                        request.context.maximumInputBytes,
                        request.context.maximumOutputBytes,
                        request.context.maximumItems,
                    )
                } else {
                    request.context
                }
                val rebound = ai.icen.fw.workflow.spi.WorkflowElectronicSignatureRequest.of(
                    context,
                    request.profile,
                    request.statement,
                )
                CompletableFuture.completedFuture(
                    WorkflowElectronicSignatureResult.success(
                        rebound,
                        evidence(request.statement.actor),
                        120L,
                        200L,
                    ),
                )
            }
        }
        val runtime = WorkflowAttestationRuntime(
            authorization,
            signature,
            WorkflowWitnessProvider { throw UnsupportedOperationException("Not used") },
            WorkflowAttestationProviderProfile.of("signature-provider", "provider-r1", 200L, 4096, 4096),
            clock,
        )

        fun command(): WorkflowAttestationCommand = WorkflowAttestationCommand.electronicSignature(
            trustedContext(),
            "attestation-request-1",
            profile,
            statement,
        )
    }

    private class TestAuthorization(
        private val revokeOnSecondAuthorization: Boolean,
    ) : WorkflowRuntimeAuthorizationPort {
        var calls: Int = 0

        override fun authorize(request: WorkflowRuntimeAuthorizationRequest): WorkflowRuntimeAuthorizationDecision {
            calls += 1
            val denied = revokeOnSecondAuthorization && calls > 1
            return WorkflowRuntimeAuthorizationDecision.of(
                "authorization-$calls",
                request.callContext.tenantId,
                request.callContext.actor,
                request.action,
                request.instanceId,
                request.requestDigest,
                if (denied) WorkflowRuntimeAuthorizationStatus.DENIED else WorkflowRuntimeAuthorizationStatus.AUTHORIZED,
                "authority-r$calls",
                sha(if (denied) 'd' else 'a'),
                request.evaluatedAt,
                1_000L,
            )
        }

        override fun issueHumanDecisionReceipt(
            request: WorkflowRuntimeHumanDecisionReceiptRequest,
        ): ai.icen.fw.workflow.domain.WorkflowHumanDecisionAuthorizationReceipt =
            throw UnsupportedOperationException("Not used by attestation.")
    }

    private class MutableClock(var now: Long) : WorkflowWorkerClock {
        override fun currentTimeMillis(): Long = now
    }

    companion object {
        private fun statement(): WorkflowAttestationStatement = WorkflowAttestationStatement.of(
            WorkflowDefinitionRef.of("legal-review", "1", sha('1')),
            WorkflowInstanceRef.of("instance-1", 4L),
            WorkflowWorkItemRef.of("work-item-1", 2L),
            subject(),
            actor(),
            sha('2'),
            "signature-idempotency-1",
            sha('3'),
        )

        private fun evidence(attestor: WorkflowPrincipalRef): WorkflowAttestationEvidence =
            WorkflowAttestationEvidence.of(
                WorkflowAttestationArtifactRef.of(
                    "artifact-sensitive",
                    "signature-evidence",
                    sha('4'),
                    512L,
                ),
                attestor,
                "provider-evidence-sensitive",
                120L,
            )

        private fun trustedContext(): WorkflowTrustedCallContext = WorkflowTrustedCallContext.of(
            "tenant-1",
            actor(),
            "authentication-1",
            sha('5'),
        )

        private fun actor(): WorkflowPrincipalRef = WorkflowPrincipalRef.of("user", "alice")

        private fun subject(): WorkflowSubjectSnapshot = WorkflowSubjectSnapshot.of(
            WorkflowSubjectRef.of("legal-file", "file-1"),
            "subject-r1",
            sha('6'),
        )

        private fun sha(character: Char): String = character.toString().repeat(64)
    }
}
