package ai.icen.fw.workflow.consumer;

import ai.icen.fw.workflow.api.WorkflowPrincipalRef;
import ai.icen.fw.workflow.api.WorkflowSubjectRef;
import ai.icen.fw.workflow.api.WorkflowSubjectSnapshot;
import ai.icen.fw.workflow.runtime.WorkflowBusinessCalendarCommand;
import ai.icen.fw.workflow.runtime.WorkflowBusinessCalendarProfile;
import ai.icen.fw.workflow.runtime.WorkflowBusinessCalendarResultCode;
import ai.icen.fw.workflow.runtime.WorkflowRuntimeAction;
import ai.icen.fw.workflow.runtime.WorkflowTrustedCallContext;
import ai.icen.fw.workflow.spi.WorkflowBusinessCalendarRef;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class JavaWorkflowBusinessCalendarRuntimeCompatibilityTest {
    @Test
    void exposesJavaFriendlyCalendarContracts() {
        WorkflowTrustedCallContext context = WorkflowTrustedCallContext.of(
                "tenant-1",
                WorkflowPrincipalRef.of("user", "alice"),
                "authentication-1",
                repeat('a'));
        WorkflowSubjectSnapshot subject = WorkflowSubjectSnapshot.of(
                WorkflowSubjectRef.of("case", "case-1"),
                "subject-r1",
                repeat('b'));
        WorkflowBusinessCalendarRef calendar = WorkflowBusinessCalendarRef.of(
                "calendar-provider",
                "cn-workdays",
                "calendar-v1",
                repeat('c'));

        WorkflowBusinessCalendarCommand command = WorkflowBusinessCalendarCommand.addWorkingDuration(
                context,
                "request-1",
                "instance-1",
                subject,
                calendar,
                100L,
                10L);
        WorkflowBusinessCalendarProfile profile = WorkflowBusinessCalendarProfile.of(
                "calendar-provider",
                "provider-r1",
                1_000L,
                1_024,
                1_024);

        assertNotNull(command.getRequestDigest());
        assertEquals("calendar-provider", profile.getProviderId());
        assertEquals("evaluate-business-time", WorkflowRuntimeAction.EVALUATE_BUSINESS_TIME.getCode());
        assertEquals("succeeded", WorkflowBusinessCalendarResultCode.SUCCEEDED.getCode());
    }

    private static String repeat(char value) {
        char[] chars = new char[64];
        java.util.Arrays.fill(chars, value);
        return new String(chars);
    }
}
