package ai.icen.fw.workflow.spi

import ai.icen.fw.workflow.api.WorkflowDefinitionRef
import ai.icen.fw.workflow.api.WorkflowInstanceRef
import ai.icen.fw.workflow.api.WorkflowPrincipalRef
import ai.icen.fw.workflow.api.WorkflowSubjectRef
import ai.icen.fw.workflow.api.WorkflowSubjectSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class WorkflowAttestationLifecycleSpiTest {
    @Test
    fun `accepted signature is exact-bound and cannot be blindly resubmitted`() {
        val request = signatureRequest()
        val accepted = WorkflowAttestationLifecycleResult.acceptedElectronicSignature(
            request,
            "remote-operation-1",
            1_050L,
            20_000L,
            1_060L,
            1_100L,
        )

        assertEquals(WorkflowAttestationLifecycleStatus.ACCEPTED, accepted.status)
        assertTrue(accepted.requiresReconciliation)
        assertFalse(accepted.originalRequestResubmissionAllowed)
        assertNull(accepted.evidence)
        assertEquals(request.requestDigest, accepted.operation?.originalRequestDigest)
        assertEquals(request.statement.actor, accepted.operation?.actor)
        assertEquals(request.profile, accepted.operation?.profile)
        assertEquals(request.context.tenantId, accepted.operation?.tenantId)
        assertEquals(request.context.providerRevision, accepted.operation?.providerRevision)

        val otherActorRequest = signatureRequest(actorId = "approver-2")
        val otherOperation = WorkflowAttestationOperationRef.forElectronicSignature(
            otherActorRequest,
            "remote-operation-2",
            1_050L,
            20_000L,
        )
        assertFailsWith<IllegalArgumentException> {
            WorkflowAttestationReconciliationRequest.forElectronicSignature(
                lifecycleContext("reconcile-1", 2_000L, 2_200L),
                request,
                otherOperation,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            WorkflowAttestationReconciliationRequest.forElectronicSignature(
                lifecycleContext("reconcile-2", 2_000L, 2_200L, tenantId = "tenant-b"),
                request,
                accepted.operation,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            WorkflowAttestationReconciliationRequest.forElectronicSignature(
                request.context,
                request,
                accepted.operation,
            )
        }
    }

    @Test
    fun `timeout and exception outcomes are non-retryable reconciliation states`() {
        val request = signatureRequest()
        val unknown = WorkflowAttestationLifecycleResult.outcomeUnknownElectronicSignature(
            request,
            request.context.deadlineEpochMilli,
            request.context.deadlineEpochMilli,
        )

        assertEquals(WorkflowAttestationLifecycleStatus.OUTCOME_UNKNOWN, unknown.status)
        assertTrue(unknown.requiresReconciliation)
        assertFalse(unknown.originalRequestResubmissionAllowed)
        assertEquals("outcome-unknown", unknown.receipt.failure?.code)
        assertFalse(requireNotNull(unknown.receipt.failure).retryable)

        val byDigest = WorkflowAttestationReconciliationRequest.forElectronicSignature(
            lifecycleContext("digest-reconcile", 2_000L, 2_200L),
            request,
        )
        assertEquals(WorkflowAttestationReconciliationMode.ORIGINAL_REQUEST_DIGEST, byDigest.mode)
        assertEquals(request.requestDigest, byDigest.originalRequestDigest)

        val reconcileUnknown = WorkflowAttestationLifecycleResult.reconciliationOutcomeUnknown(
            byDigest,
            2_200L,
            2_200L,
        )
        assertTrue(reconcileUnknown.requiresReconciliation)
        assertFalse(requireNotNull(reconcileUnknown.receipt.failure).retryable)
    }

    @Test
    fun `reconciliation reuses exact terminal result and signature evidence`() {
        val request = signatureRequest()
        val accepted = WorkflowAttestationLifecycleResult.acceptedElectronicSignature(
            request, "remote-operation-1", 1_050L, 20_000L, 1_060L, 1_100L,
        )
        val reconcileRequest = WorkflowAttestationReconciliationRequest.forElectronicSignature(
            lifecycleContext("reconcile-1", 2_000L, 2_200L),
            request,
            accepted.operation,
        )
        val evidence = evidence(request.statement.actor)
        val terminal = WorkflowElectronicSignatureResult.success(request, evidence, 1_080L, 1_100L)
        val reconciled = WorkflowAttestationLifecycleResult.reconciledElectronicSignature(
            reconcileRequest,
            terminal,
            2_050L,
            2_100L,
        )

        assertEquals(WorkflowAttestationLifecycleStatus.COMPLETED, reconciled.status)
        assertFalse(reconciled.requiresReconciliation)
        assertSame(evidence, reconciled.evidence)
        assertSame(terminal.receipt, reconciled.terminalReceipt)
        assertEquals(reconcileRequest.requestDigest, reconciled.receipt.requestDigest)
        assertEquals(request.requestDigest, reconciled.terminalReceipt?.requestDigest)

        val wrongRequest = signatureRequest(actorId = "approver-2")
        val wrongTerminal = WorkflowElectronicSignatureResult.success(
            wrongRequest, evidence(wrongRequest.statement.actor), 1_080L, 1_100L,
        )
        assertFailsWith<IllegalArgumentException> {
            WorkflowAttestationLifecycleResult.reconciledElectronicSignature(
                reconcileRequest, wrongTerminal, 2_050L, 2_100L,
            )
        }
    }

    @Test
    fun `witness lifecycle remains a separate original request and evidence boundary`() {
        val request = witnessRequest()
        val accepted = WorkflowAttestationLifecycleResult.acceptedWitness(
            request, "witness-operation-1", 1_050L, 20_000L, 1_060L, 1_100L,
        )
        val reconcileRequest = WorkflowAttestationReconciliationRequest.forWitness(
            lifecycleContext("witness-reconcile", 2_000L, 2_200L), request, accepted.operation,
        )
        val witnessEvidence = evidence(WorkflowPrincipalRef.of("service", "witness-provider"))
        val terminal = WorkflowWitnessResult.success(request, witnessEvidence, 1_080L, 1_100L)
        val reconciled = WorkflowAttestationLifecycleResult.reconciledWitness(
            reconcileRequest, terminal, 2_050L, 2_100L,
        )

        assertEquals(WorkflowAttestationKind.WITNESS, reconciled.kind)
        assertSame(witnessEvidence, reconciled.evidence)
        assertFailsWith<IllegalArgumentException> {
            WorkflowAttestationReconciliationRequest.forElectronicSignature(
                lifecycleContext("wrong-kind", 2_000L, 2_200L), signatureRequest(), accepted.operation,
            )
        }
    }

    @Test
    fun `capabilities fail closed and asynchronous mode requires both reconciliation paths`() {
        val profile = profile()
        val context = lifecycleContext("capability-1", 3_000L, 3_200L)
        val required = listOf(
            WorkflowAttestationCapabilityCode.ELECTRONIC_SIGNATURE,
            WorkflowAttestationCapabilityCode.ASYNCHRONOUS_COMPLETION,
            WorkflowAttestationCapabilityCode.RECONCILIATION_BY_REQUEST_DIGEST,
        )
        val request = WorkflowAttestationCapabilityRequest.of(context, profile, required)
        val supported = listOf(
            WorkflowAttestationCapabilityCode.ELECTRONIC_SIGNATURE,
            WorkflowAttestationCapabilityCode.ASYNCHRONOUS_COMPLETION,
            WorkflowAttestationCapabilityCode.RECONCILIATION_BY_OPERATION,
            WorkflowAttestationCapabilityCode.RECONCILIATION_BY_REQUEST_DIGEST,
            WorkflowAttestationCapabilityCode.CANCELLATION,
            WorkflowAttestationCapabilityCode.DIAGNOSTICS,
        )
        val snapshot = WorkflowAttestationCapabilitySnapshot.of(
            profile, "r1", supported, 32, 3_010L, 20_000L,
        )
        val available = WorkflowAttestationCapabilityResult.available(request, snapshot, 3_050L, 3_100L)

        assertEquals(WorkflowAttestationCapabilityStatus.AVAILABLE, available.status)
        assertSame(snapshot, available.snapshot)

        val staleSnapshot = WorkflowAttestationCapabilitySnapshot.of(
            profile, "r1", supported, 32, 3_010L, 3_040L,
        )
        assertFailsWith<IllegalArgumentException> {
            WorkflowAttestationCapabilityResult.available(request, staleSnapshot, 3_050L, 3_100L)
        }

        assertFailsWith<IllegalArgumentException> {
            WorkflowAttestationCapabilitySnapshot.of(
                profile,
                "r1",
                listOf(
                    WorkflowAttestationCapabilityCode.ELECTRONIC_SIGNATURE,
                    WorkflowAttestationCapabilityCode.ASYNCHRONOUS_COMPLETION,
                ),
                32,
                3_010L,
                20_000L,
            )
        }

        val partial = WorkflowAttestationCapabilitySnapshot.of(
            profile,
            "r1",
            listOf(WorkflowAttestationCapabilityCode.ELECTRONIC_SIGNATURE),
            0,
            3_010L,
            20_000L,
        )
        val unsupported = WorkflowAttestationCapabilityResult.unsupported(request, partial, 3_050L, 3_100L)
        assertEquals(WorkflowProviderOutcome.UNSUPPORTED, unsupported.receipt.outcome)
        assertFalse(requireNotNull(unsupported.receipt.failure).retryable)
    }

    @Test
    fun `cancellation uncertainty never claims that the provider operation stopped`() {
        val request = signatureRequest()
        val operation = WorkflowAttestationOperationRef.forElectronicSignature(
            request, "remote-operation-1", 1_050L, 20_000L,
        )
        val cancellation = WorkflowAttestationCancellationRequest.forElectronicSignature(
            lifecycleContext("cancel-1", 3_000L, 3_200L),
            request,
            operation,
            "user-withdrew",
        )
        val unknown = WorkflowAttestationCancellationResult.outcomeUnknown(cancellation, 3_200L, 3_200L)

        assertFalse(unknown.cancellationConfirmed)
        assertTrue(unknown.requiresReconciliation)
        assertEquals("outcome-unknown", unknown.receipt.failure?.code)

        val cancelled = WorkflowAttestationCancellationResult.success(
            cancellation,
            WorkflowAttestationCancellationStatus.CANCELLED,
            3_050L,
            3_100L,
        )
        assertTrue(cancelled.cancellationConfirmed)
        assertFalse(cancelled.requiresReconciliation)

        assertFailsWith<IllegalArgumentException> {
            WorkflowAttestationCancellationRequest.forElectronicSignature(
                request.context,
                request,
                operation,
                "user-withdrew",
            )
        }
    }

    @Test
    fun `Doctor is bounded to value-free machine findings`() {
        val request = WorkflowAttestationDoctorRequest.forElectronicSignature(
            lifecycleContext("doctor-1", 4_000L, 4_200L), profile(),
        )
        val finding = WorkflowAttestationDoctorFinding.of(
            "provider-latency-high",
            WorkflowAttestationDoctorSeverity.WARNING,
            2,
        )
        val result = WorkflowAttestationDoctorResult.observed(
            request,
            WorkflowAttestationDoctorStatus.DEGRADED,
            listOf(finding),
            4_050L,
            4_100L,
        )

        assertEquals(WorkflowAttestationDoctorStatus.DEGRADED, result.status)
        assertEquals(listOf("provider-latency-high"), result.findings.map { it.code })
        assertFailsWith<IllegalArgumentException> {
            WorkflowAttestationDoctorFinding.of(
                "https://provider.example/secret?token=value",
                WorkflowAttestationDoctorSeverity.ERROR,
                1,
            )
        }
    }

    private fun signatureRequest(actorId: String = "approver-1"): WorkflowElectronicSignatureRequest =
        WorkflowElectronicSignatureRequest.of(originalContext(), profile(), statement(actorId))

    private fun witnessRequest(): WorkflowWitnessRequest =
        WorkflowWitnessRequest.of(originalContext(), profile(), statement("approver-1"))

    private fun originalContext(): WorkflowProviderCallContext = WorkflowProviderCallContext.of(
        "dispatch-1", "tenant-a", "provider-a", "r1", "attestation-dispatch",
        1_000L, 1_200L, 4_096, 4_096, 16,
    )

    private fun lifecycleContext(
        requestId: String,
        requestedAt: Long,
        deadline: Long,
        tenantId: String = "tenant-a",
    ): WorkflowProviderCallContext = WorkflowProviderCallContext.of(
        requestId, tenantId, "provider-a", "r1", "attestation-lifecycle",
        requestedAt, deadline, 4_096, 4_096, 16,
    )

    private fun profile(): WorkflowAttestationProfileRef = WorkflowAttestationProfileRef.of(
        "provider-a", "qualified-signature", "1", digest('a'),
    )

    private fun statement(actorId: String): WorkflowAttestationStatement = WorkflowAttestationStatement.of(
        WorkflowDefinitionRef.of("legal-review", "2", digest('7')),
        WorkflowInstanceRef.of("instance-7", 3L),
        null,
        WorkflowSubjectSnapshot.of(
            WorkflowSubjectRef.of("legal-file", "file-7"), "revision-3", digest('3'),
        ),
        WorkflowPrincipalRef.of("user", actorId),
        digest('8'),
        "attestation-idem-$actorId",
        digest('9'),
    )

    private fun evidence(attestor: WorkflowPrincipalRef): WorkflowAttestationEvidence = WorkflowAttestationEvidence.of(
        WorkflowAttestationArtifactRef.of("artifact-1", "application/pdf", digest('b'), 12L),
        attestor,
        "provider-evidence-1",
        1_075L,
    )

    private fun digest(character: Char): String = character.toString().repeat(64)
}
