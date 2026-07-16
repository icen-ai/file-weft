package ai.icen.fw.workflow.sla;

import ai.icen.fw.workflow.api.WorkflowDefinitionRef;
import ai.icen.fw.workflow.api.WorkflowPrincipalRef;
import ai.icen.fw.workflow.api.WorkflowSubjectRef;
import ai.icen.fw.workflow.api.WorkflowSubjectSnapshot;
import ai.icen.fw.workflow.runtime.WorkflowBusinessCalendarProfile;
import ai.icen.fw.workflow.runtime.WorkflowTrustedCallContext;
import ai.icen.fw.workflow.spi.WorkflowBusinessCalendarRef;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class JavaWorkflowSlaCompatibilityTest {
    @Test
    void javaEightConsumerCanBuildExactPolicyTaskAndCommand() {
        WorkflowDefinitionRef definition = WorkflowDefinitionRef.of("approval", "1", sha('1'));
        WorkflowBusinessCalendarRef calendar = WorkflowBusinessCalendarRef.of(
            "calendar-provider",
            "calendar-cn",
            "calendar-r1",
            sha('2')
        );
        WorkflowBusinessCalendarProfile provider = WorkflowBusinessCalendarProfile.of(
            "calendar-provider",
            "provider-r1",
            10_000L,
            1024,
            1024
        );
        WorkflowSlaCalendarBinding calendarBinding = WorkflowSlaCalendarBinding.of(
            "calendar-profile",
            "1",
            sha('3'),
            sha('4'),
            calendar,
            provider
        );
        WorkflowSlaActionProfile actionProfile = WorkflowSlaActionProfile.of(
            "sla-actions",
            "1",
            sha('5'),
            "action-provider",
            "provider-r1",
            10_000L,
            1024,
            1024,
            3,
            1_000L
        );
        WorkflowSlaPolicy policy = WorkflowSlaPolicy.standard(
            "policy-1",
            "1",
            sha('6'),
            definition,
            "approve",
            calendarBinding,
            actionProfile,
            1_000L,
            2_000L,
            3_000L
        );
        WorkflowSubjectSnapshot subject = WorkflowSubjectSnapshot.of(
            WorkflowSubjectRef.of("case", "case-1"),
            "subject-r1",
            sha('7')
        );
        WorkflowSlaTaskSnapshot task = WorkflowSlaTaskSnapshot.of(
            "tenant-1",
            "instance-1",
            "task-1",
            "definition-1",
            definition,
            "approve",
            subject,
            WorkflowSlaTaskStatus.ACTIVE,
            1L,
            sha('8'),
            100L,
            200L
        );
        WorkflowTrustedCallContext context = WorkflowTrustedCallContext.of(
            "tenant-1",
            WorkflowPrincipalRef.of("user", "alice"),
            "authentication-1",
            sha('9')
        );
        WorkflowSlaCreateCommand command = WorkflowSlaCreateCommand.of(
            context,
            "schedule-1",
            "idempotency-1",
            task.getInstanceId(),
            task.getWorkItemId(),
            policy
        );

        assertEquals(definition, command.getPolicy().getDefinitionRef());
        assertEquals("approve", policy.getNodeId());
        assertEquals(3, policy.getMilestones().size());
        assertNotNull(command.getRequestDigest());
        assertEquals("WorkflowSlaTaskSnapshot(<redacted>)", task.toString());
    }

    private static String sha(char value) {
        StringBuilder result = new StringBuilder(64);
        for (int index = 0; index < 64; index++) result.append(value);
        return result.toString();
    }
}
