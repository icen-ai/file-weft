package ai.icen.fw.web.api;

import ai.icen.fw.web.api.v1.document.DocumentLifecycleCommandResultDto;
import ai.icen.fw.web.api.v1.document.PublishDocumentCommand;
import ai.icen.fw.web.api.v1.document.PublishDocumentRequest;
import ai.icen.fw.web.api.v1.workflow.ApproveWorkflowTaskCommand;
import ai.icen.fw.web.api.v1.workflow.ApproveWorkflowTaskRequest;
import ai.icen.fw.web.api.v1.workflow.RejectWorkflowTaskCommand;
import ai.icen.fw.web.api.v1.workflow.RejectWorkflowTaskRequest;
import ai.icen.fw.web.api.v1.workflow.SubmitDocumentReviewCommand;
import ai.icen.fw.web.api.v1.workflow.SubmitDocumentReviewRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class JavaLifecycleCommandContractsInteropTest {
    @Test
    void exposesJava8FriendlyReceiptsCommandsAndMutableRequestBeans() {
        DocumentLifecycleCommandResultDto minimal = new DocumentLifecycleCommandResultDto("document-1");
        DocumentLifecycleCommandResultDto full = new DocumentLifecycleCommandResultDto(
            "document-1", "workflow-1", "task-1"
        );
        assertNull(minimal.getWorkflowId());
        assertNull(minimal.getTaskId());
        assertEquals("workflow-1", full.getWorkflowId());
        assertEquals("task-1", full.getTaskId());

        PublishDocumentRequest publish = new PublishDocumentRequest();
        publish.setDeliveryProfileId("regulated");
        SubmitDocumentReviewRequest submit = new SubmitDocumentReviewRequest();
        submit.setReviewRouteId("dual-control");
        ApproveWorkflowTaskRequest approve = new ApproveWorkflowTaskRequest();
        approve.setComment("Approved");
        approve.setDeliveryProfileId("regulated");
        RejectWorkflowTaskRequest reject = new RejectWorkflowTaskRequest();
        reject.setComment("Rejected");

        assertEquals("regulated", new PublishDocumentCommand(publish.getDeliveryProfileId()).getDeliveryProfileId());
        assertEquals("dual-control", new SubmitDocumentReviewCommand(submit.getReviewRouteId()).getReviewRouteId());
        assertEquals(
            "Approved",
            new ApproveWorkflowTaskCommand(approve.getComment(), approve.getDeliveryProfileId()).getComment()
        );
        assertEquals("Rejected", new RejectWorkflowTaskCommand(reject.getComment()).getComment());
        assertNull(new PublishDocumentCommand().getDeliveryProfileId());
    }
}
