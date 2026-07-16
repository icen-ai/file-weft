package ai.icen.fw.workflow.consumer;

import ai.icen.fw.workflow.api.WorkflowDefinition;
import ai.icen.fw.workflow.api.WorkflowDefinitionStatus;
import ai.icen.fw.workflow.api.WorkflowNodeDefinition;
import ai.icen.fw.workflow.api.WorkflowNodeKind;
import ai.icen.fw.workflow.api.WorkflowPrincipalRef;
import ai.icen.fw.workflow.api.WorkflowSubjectRef;
import ai.icen.fw.workflow.api.WorkflowSubjectSnapshot;
import ai.icen.fw.workflow.api.WorkflowTransitionDefinition;
import ai.icen.fw.workflow.domain.WorkflowCommandContext;
import ai.icen.fw.workflow.domain.WorkflowDefinitionExecutionReceipt;
import ai.icen.fw.workflow.domain.WorkflowDefinitionIndex;
import ai.icen.fw.workflow.domain.WorkflowDomainEngine;
import ai.icen.fw.workflow.domain.WorkflowDomainResult;
import ai.icen.fw.workflow.domain.WorkflowExecutionIds;
import ai.icen.fw.workflow.domain.WorkflowIdempotencyReceipt;
import ai.icen.fw.workflow.domain.WorkflowInstanceStatus;
import ai.icen.fw.workflow.domain.WorkflowParticipantActivationReceipt;
import ai.icen.fw.workflow.domain.WorkflowResultCode;
import ai.icen.fw.workflow.domain.WorkflowStartCommand;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaWorkflowDomainCompatibilityTest {
    private static final String DIGEST =
        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void deterministicStartToEndIsCallableFromExternalJava8() {
        WorkflowNodeDefinition start = WorkflowNodeDefinition.of(
            "start", WorkflowNodeKind.START, "开始", null
        );
        WorkflowNodeDefinition end = WorkflowNodeDefinition.of(
            "end", WorkflowNodeKind.END, "结束", null
        );
        WorkflowDefinition definition = WorkflowDefinition.of(
            "tenant-java",
            "definition-java",
            "java-domain",
            "v1",
            1,
            WorkflowDefinitionStatus.PUBLISHED,
            "Java 通用工作流",
            null,
            Arrays.asList(start, end),
            Collections.singletonList(
                WorkflowTransitionDefinition.unconditional("start-end", "start", "end")
            )
        );
        WorkflowDefinitionIndex index = WorkflowDefinitionIndex.compile(definition);
        WorkflowSubjectSnapshot subject = WorkflowSubjectSnapshot.of(
            WorkflowSubjectRef.of("record", "record-java"),
            "revision-1",
            DIGEST
        );
        WorkflowExecutionIds ids = WorkflowExecutionIds.of(
            Collections.singletonList("java-token-1"),
            Arrays.asList("java-execution-1", "java-execution-2"),
            Collections.<String>emptyList(),
            Collections.<String>emptyList(),
            Arrays.asList(
                "java-event-1", "java-event-2", "java-event-3", "java-event-4",
                "java-event-5", "java-event-6", "java-event-7", "java-event-8"
            ),
            Collections.<String>emptyList()
        );
        WorkflowCommandContext context = WorkflowCommandContext.of(
            "java-command-1",
            "java-idempotency-1",
            0L,
            100L,
            16,
            ids,
            WorkflowIdempotencyReceipt.fresh(
                "tenant-java", "instance-java", "java-idempotency-1", 100L
            )
        );
        WorkflowDefinitionExecutionReceipt executionReceipt = WorkflowDefinitionExecutionReceipt.of(
            "java-deployment-1",
            "tenant-java",
            "definition-java",
            definition.getRef(),
            1,
            DIGEST,
            100L,
            200L
        );
        WorkflowStartCommand command = WorkflowStartCommand.of(
            context,
            "tenant-java",
            "instance-java",
            "definition-java",
            definition.getRef(),
            subject,
            WorkflowPrincipalRef.of("user", "java-initiator"),
            executionReceipt
        );

        WorkflowDomainResult result = WorkflowDomainEngine.start(index, command);

        assertEquals(WorkflowResultCode.APPLIED, result.getCode());
        assertNotNull(result.getState());
        assertEquals(WorkflowInstanceStatus.COMPLETED, result.getState().getStatus());
        assertEquals(2, result.getState().getNodeExecutions().size());
        assertEquals(7, result.getEvents().size());
        assertTrue(result.getEffects().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> result.getEvents().clear());
        assertEquals("WorkflowDomainResult(<redacted>)", result.toString());

        WorkflowParticipantActivationReceipt legacyReceipt = WorkflowParticipantActivationReceipt.of(
            "receipt-java", "effect-java", "tenant-java", "instance-java", "definition-java",
            definition.getRef(), subject, "token-java", "execution-java", "work-java", "review",
            0, DIGEST, DIGEST, Collections.singletonList(WorkflowPrincipalRef.of("user", "reviewer")),
            DIGEST, 100L, 200L
        );
        WorkflowParticipantActivationReceipt currentReceipt = WorkflowParticipantActivationReceipt.organizationBound(
            "receipt-java-v2", "effect-java", "tenant-java", "instance-java", "definition-java",
            definition.getRef(), subject, "token-java", "execution-java", "work-java", "review",
            0, DIGEST, DIGEST, Collections.singletonList(WorkflowPrincipalRef.of("user", "reviewer")),
            DIGEST, "java-directory", "revision-7", DIGEST, DIGEST, 100L, 200L
        );
        assertEquals(1, legacyReceipt.getEvidenceVersion());
        assertEquals(2, currentReceipt.getEvidenceVersion());
        assertEquals("java-directory", currentReceipt.getOrganizationAuthority());
    }
}
