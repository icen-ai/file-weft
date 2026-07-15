package ai.icen.fw.workflow.consumer;

import ai.icen.fw.workflow.api.WorkflowDefinitionRef;
import ai.icen.fw.workflow.api.WorkflowPrincipalRef;
import ai.icen.fw.workflow.api.WorkflowSubjectRef;
import ai.icen.fw.workflow.api.WorkflowSubjectSnapshot;
import ai.icen.fw.workflow.domain.WorkflowExecutionIds;
import ai.icen.fw.workflow.domain.WorkflowEffectCode;
import ai.icen.fw.workflow.runtime.WorkflowClaimedEffectJob;
import ai.icen.fw.workflow.runtime.WorkflowDurableRuntime;
import ai.icen.fw.workflow.runtime.WorkflowEffectLease;
import ai.icen.fw.workflow.runtime.WorkflowEffectJobExecutionMode;
import ai.icen.fw.workflow.runtime.WorkflowEffectJobResultCheckpoint;
import ai.icen.fw.workflow.runtime.WorkflowEffectJobStoredResult;
import ai.icen.fw.workflow.runtime.WorkflowEffectObservedOutcome;
import ai.icen.fw.workflow.runtime.WorkflowEffectWorkerBatchResult;
import ai.icen.fw.workflow.runtime.WorkflowParticipantResolutionWorker;
import ai.icen.fw.workflow.runtime.WorkflowReadyEffectJobClaimRequest;
import ai.icen.fw.workflow.runtime.WorkflowReadyEffectJobPort;
import ai.icen.fw.workflow.runtime.WorkflowRuntimeAction;
import ai.icen.fw.workflow.runtime.WorkflowRuntimeCommandOptions;
import ai.icen.fw.workflow.runtime.WorkflowRuntimeResult;
import ai.icen.fw.workflow.runtime.WorkflowRuntimeHumanCollaborationRequest;
import ai.icen.fw.workflow.runtime.WorkflowRuntimeStartRequest;
import ai.icen.fw.workflow.runtime.WorkflowTrustedCallContext;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class JavaWorkflowRuntimeCompatibilityTest {
    private static final String DIGEST =
        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void javaEightConsumerCanConstructRuntimeContracts() throws Exception {
        WorkflowPrincipalRef actor = WorkflowPrincipalRef.of("user", "java-consumer");
        WorkflowTrustedCallContext context = WorkflowTrustedCallContext.of(
            "tenant-java", actor, "authentication-java", DIGEST);
        WorkflowRuntimeCommandOptions options = WorkflowRuntimeCommandOptions.of(
            "command-java", "idempotency-java", 0L, 10L, 64, ids());
        WorkflowDefinitionRef definitionRef = WorkflowDefinitionRef.of("java-flow", "v1", DIGEST);
        WorkflowSubjectSnapshot subject = WorkflowSubjectSnapshot.of(
            WorkflowSubjectRef.of("record", "record-java"), "revision-1", DIGEST);
        WorkflowRuntimeStartRequest request = WorkflowRuntimeStartRequest.of(
            context, options, "instance-java", "definition-java", definitionRef, subject);

        assertSame(WorkflowRuntimeAction.START, request.getAction());
        assertEquals("tenant-java", request.getCallContext().getTenantId());
        assertEquals(0L, request.getOptions().getExpectedInstanceVersion());
        WorkflowEffectLease lease = WorkflowEffectLease.of("lease-java", "worker-java", 1L, 10L, 20L);
        assertEquals(1L, lease.getFencingToken());
        WorkflowReadyEffectJobClaimRequest jobRequest = WorkflowReadyEffectJobClaimRequest.of(
            "tenant-java", WorkflowEffectCode.SERVICE_TASK, "worker-java", "claim-java",
            10L, 20L, 4);
        WorkflowClaimedEffectJob job = WorkflowClaimedEffectJob.of(
            "job-java", "tenant-java", "instance-java", "effect-java",
            WorkflowEffectCode.SERVICE_TASK, WorkflowEffectJobExecutionMode.EXECUTE_PROVIDER,
            1L, 0L, jobRequest.getRequestDigest(), lease, null, 10L);
        WorkflowEffectJobStoredResult stored = WorkflowEffectJobStoredResult.of(
            WorkflowEffectObservedOutcome.SUCCEEDED, "java-result-v1", DIGEST,
            new byte[] { 1 }, null, 11L);
        WorkflowEffectJobResultCheckpoint checkpoint = WorkflowEffectJobResultCheckpoint.of(
            job, 2L, stored, 12L);
        assertEquals(2L, checkpoint.getExpectedEffectVersion());
        assertEquals(jobRequest.getRequestDigest(), job.getClaimRequestDigest());

        Method start = WorkflowDurableRuntime.class.getMethod("start", WorkflowRuntimeStartRequest.class);
        assertEquals(WorkflowRuntimeResult.class, start.getReturnType());
        Method collaborate = WorkflowDurableRuntime.class.getMethod(
            "collaborateHumanTask", WorkflowRuntimeHumanCollaborationRequest.class);
        assertEquals(WorkflowRuntimeResult.class, collaborate.getReturnType());
        assertEquals(List.class, WorkflowReadyEffectJobPort.class.getMethod(
            "claimReady", WorkflowReadyEffectJobClaimRequest.class).getReturnType());
        Method poll = WorkflowParticipantResolutionWorker.class.getMethod(
            "poll", WorkflowTrustedCallContext.class, String.class, String.class,
            long.class, long.class, int.class);
        assertEquals(WorkflowEffectWorkerBatchResult.class, poll.getReturnType());
    }

    private static WorkflowExecutionIds ids() {
        return WorkflowExecutionIds.of(
            values("token", 128),
            values("execution", 128),
            values("work-item", 32),
            values("effect", 128),
            values("event", 512),
            values("scope", 32));
    }

    private static List<String> values(String prefix, int size) {
        List<String> values = new ArrayList<String>();
        for (int index = 0; index < size; index++) {
            values.add(prefix + "-" + index);
        }
        return values;
    }
}
