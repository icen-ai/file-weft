package ai.icen.fw.workflow.consumer;

import ai.icen.fw.workflow.api.WorkflowDefinitionRef;
import ai.icen.fw.workflow.api.WorkflowInstanceRef;
import ai.icen.fw.workflow.api.WorkflowPrincipalRef;
import ai.icen.fw.workflow.api.WorkflowSubjectRef;
import ai.icen.fw.workflow.api.WorkflowSubjectSnapshot;
import ai.icen.fw.workflow.spi.WorkflowAttestationCapabilityCode;
import ai.icen.fw.workflow.spi.WorkflowAttestationCapabilityProvider;
import ai.icen.fw.workflow.spi.WorkflowAttestationCapabilityRequest;
import ai.icen.fw.workflow.spi.WorkflowAttestationCapabilityResult;
import ai.icen.fw.workflow.spi.WorkflowAttestationCapabilitySnapshot;
import ai.icen.fw.workflow.spi.WorkflowAttestationCancellationProvider;
import ai.icen.fw.workflow.spi.WorkflowAttestationCancellationRequest;
import ai.icen.fw.workflow.spi.WorkflowAttestationCancellationResult;
import ai.icen.fw.workflow.spi.WorkflowAttestationCancellationStatus;
import ai.icen.fw.workflow.spi.WorkflowAttestationDoctor;
import ai.icen.fw.workflow.spi.WorkflowAttestationDoctorFinding;
import ai.icen.fw.workflow.spi.WorkflowAttestationDoctorRequest;
import ai.icen.fw.workflow.spi.WorkflowAttestationDoctorResult;
import ai.icen.fw.workflow.spi.WorkflowAttestationDoctorSeverity;
import ai.icen.fw.workflow.spi.WorkflowAttestationDoctorStatus;
import ai.icen.fw.workflow.spi.WorkflowAttestationLifecycleResult;
import ai.icen.fw.workflow.spi.WorkflowAttestationLifecycleStatus;
import ai.icen.fw.workflow.spi.WorkflowAttestationOperationRef;
import ai.icen.fw.workflow.spi.WorkflowAttestationProfileRef;
import ai.icen.fw.workflow.spi.WorkflowAttestationReconciliationProvider;
import ai.icen.fw.workflow.spi.WorkflowAttestationReconciliationRequest;
import ai.icen.fw.workflow.spi.WorkflowAttestationStatement;
import ai.icen.fw.workflow.spi.WorkflowElectronicSignatureLifecycleProvider;
import ai.icen.fw.workflow.spi.WorkflowElectronicSignatureRequest;
import ai.icen.fw.workflow.spi.WorkflowProviderCallContext;
import ai.icen.fw.workflow.spi.WorkflowWitnessLifecycleProvider;
import ai.icen.fw.workflow.spi.WorkflowWitnessRequest;
import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaWorkflowAttestationLifecycleCompatibilityTest {
    @Test
    void additiveLifecycleFactoriesAndPortsAreUsableFromPureJava() throws Exception {
        WorkflowAttestationProfileRef profile = WorkflowAttestationProfileRef.of(
            "provider-a", "qualified-signature", "1", digest('a')
        );
        WorkflowAttestationStatement statement = WorkflowAttestationStatement.of(
            WorkflowDefinitionRef.of("legal-review", "2", digest('7')),
            WorkflowInstanceRef.of("instance-7", 3L),
            null,
            WorkflowSubjectSnapshot.of(
                WorkflowSubjectRef.of("legal-file", "file-7"), "revision-3", digest('3')
            ),
            WorkflowPrincipalRef.of("user", "approver-1"),
            digest('8'),
            "attestation-idem-1",
            digest('9')
        );
        WorkflowElectronicSignatureRequest signatureRequest = WorkflowElectronicSignatureRequest.of(
            context("dispatch-1", 1_000L, 1_200L), profile, statement
        );
        WorkflowWitnessRequest witnessRequest = WorkflowWitnessRequest.of(
            context("dispatch-2", 1_000L, 1_200L), profile, statement
        );
        WorkflowAttestationLifecycleResult accepted =
            WorkflowAttestationLifecycleResult.acceptedElectronicSignature(
                signatureRequest, "remote-operation-1", 1_050L, 20_000L, 1_060L, 1_100L
            );
        WorkflowAttestationOperationRef operation = accepted.getOperation();

        assertNotNull(operation);
        assertTrue(accepted.getRequiresReconciliation());
        assertFalse(accepted.getOriginalRequestResubmissionAllowed());

        WorkflowElectronicSignatureLifecycleProvider signatureProvider = request ->
            CompletableFuture.completedFuture(accepted);
        WorkflowWitnessLifecycleProvider witnessProvider = request ->
            CompletableFuture.completedFuture(
                WorkflowAttestationLifecycleResult.acceptedWitness(
                    request, "remote-witness-1", 1_050L, 20_000L, 1_060L, 1_100L
                )
            );
        CompletionStage<WorkflowAttestationLifecycleResult> signatureStage =
            signatureProvider.dispatch(signatureRequest);
        assertEquals(WorkflowAttestationLifecycleStatus.ACCEPTED, signatureStage.toCompletableFuture().get().getStatus());
        assertNotNull(witnessProvider.dispatch(witnessRequest).toCompletableFuture().get().getOperation());

        WorkflowAttestationReconciliationRequest reconcileRequest =
            WorkflowAttestationReconciliationRequest.forElectronicSignature(
                context("reconcile-1", 2_000L, 2_200L), signatureRequest, operation
            );
        WorkflowAttestationLifecycleResult pending = WorkflowAttestationLifecycleResult.pending(
            reconcileRequest, operation, 2_050L, 2_100L
        );
        WorkflowAttestationReconciliationProvider reconciliationProvider = request ->
            CompletableFuture.completedFuture(pending);
        assertTrue(
            reconciliationProvider.reconcile(reconcileRequest).toCompletableFuture().get().getRequiresReconciliation()
        );

        WorkflowAttestationCancellationRequest cancellationRequest =
            WorkflowAttestationCancellationRequest.forElectronicSignature(
                context("cancel-1", 3_000L, 3_200L),
                signatureRequest,
                operation,
                "user-withdrew"
            );
        WorkflowAttestationCancellationProvider cancellationProvider = request ->
            CompletableFuture.completedFuture(
                WorkflowAttestationCancellationResult.success(
                    request, WorkflowAttestationCancellationStatus.CANCELLED, 3_050L, 3_100L
                )
            );
        assertTrue(
            cancellationProvider.cancel(cancellationRequest).toCompletableFuture().get().getCancellationConfirmed()
        );

        WorkflowAttestationCapabilityRequest capabilityRequest = WorkflowAttestationCapabilityRequest.of(
            context("capabilities-1", 4_000L, 4_200L),
            profile,
            Arrays.asList(
                WorkflowAttestationCapabilityCode.ELECTRONIC_SIGNATURE,
                WorkflowAttestationCapabilityCode.ASYNCHRONOUS_COMPLETION
            )
        );
        WorkflowAttestationCapabilitySnapshot snapshot = WorkflowAttestationCapabilitySnapshot.of(
            profile,
            "r1",
            Arrays.asList(
                WorkflowAttestationCapabilityCode.ELECTRONIC_SIGNATURE,
                WorkflowAttestationCapabilityCode.ASYNCHRONOUS_COMPLETION,
                WorkflowAttestationCapabilityCode.RECONCILIATION_BY_OPERATION,
                WorkflowAttestationCapabilityCode.RECONCILIATION_BY_REQUEST_DIGEST
            ),
            16,
            4_010L,
            20_000L
        );
        WorkflowAttestationCapabilityProvider capabilityProvider = request ->
            CompletableFuture.completedFuture(
                WorkflowAttestationCapabilityResult.available(request, snapshot, 4_050L, 4_100L)
            );
        assertNotNull(capabilityProvider.capabilities(capabilityRequest).toCompletableFuture().get().getSnapshot());

        WorkflowAttestationDoctorRequest doctorRequest = WorkflowAttestationDoctorRequest.forElectronicSignature(
            context("doctor-1", 5_000L, 5_200L), profile
        );
        WorkflowAttestationDoctorFinding finding = WorkflowAttestationDoctorFinding.of(
            "provider-latency-high", WorkflowAttestationDoctorSeverity.WARNING, 1
        );
        WorkflowAttestationDoctor doctor = request -> CompletableFuture.completedFuture(
            WorkflowAttestationDoctorResult.observed(
                request,
                WorkflowAttestationDoctorStatus.DEGRADED,
                Collections.singletonList(finding),
                5_050L,
                5_100L
            )
        );
        assertEquals(
            "provider-latency-high",
            doctor.diagnose(doctorRequest).toCompletableFuture().get().getFindings().get(0).getCode()
        );
    }

    private static WorkflowProviderCallContext context(String requestId, long requestedAt, long deadline) {
        return WorkflowProviderCallContext.of(
            requestId,
            "tenant-a",
            "provider-a",
            "r1",
            "java-attestation-lifecycle",
            requestedAt,
            deadline,
            4_096,
            4_096,
            16
        );
    }

    private static String digest(char character) {
        StringBuilder builder = new StringBuilder(64);
        for (int index = 0; index < 64; index++) {
            builder.append(character);
        }
        return builder.toString();
    }
}
