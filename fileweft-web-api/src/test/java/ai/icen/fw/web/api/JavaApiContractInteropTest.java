package ai.icen.fw.web.api;

import ai.icen.fw.web.api.v1.doctor.DoctorCheckDto;
import ai.icen.fw.web.api.v1.doctor.DoctorReportDto;
import ai.icen.fw.web.api.v1.doctor.ScheduleDocumentDoctorCommand;
import ai.icen.fw.web.api.v1.document.CreateDocumentDraftCommand;
import ai.icen.fw.web.api.v1.document.DocumentDetailDto;
import ai.icen.fw.web.api.v1.document.DocumentCommandResultDto;
import ai.icen.fw.web.api.v1.document.DocumentDto;
import ai.icen.fw.web.api.v1.document.DocumentPageQuery;
import ai.icen.fw.web.api.v1.document.DocumentVersionDto;
import ai.icen.fw.web.api.v1.document.RenameDocumentRequest;
import ai.icen.fw.web.api.v1.workflow.ApproveWorkflowTaskCommand;
import ai.icen.fw.web.api.v1.workflow.WorkflowTaskDto;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaApiContractInteropTest {

    @Test
    void constructsAndReadsThePublicContractsFromJava() {
        CreateDocumentDraftCommand create = new CreateDocumentDraftCommand("DOC-1", "Tax certificate", "proof.pdf", 8L);
        DocumentDto document = new DocumentDto("document-1", "DOC-1", "Tax certificate", "DRAFT", 100L, 100L);
        DocumentVersionDto version = new DocumentVersionDto("version-1", "1.0", "proof.pdf", 8L, 100L, 100L);
        DocumentDetailDto detail = new DocumentDetailDto(document, Collections.singletonList(version));
        DocumentCommandResultDto commandResult = new DocumentCommandResultDto("document-1", "version-1");
        DocumentCommandResultDto renameResult = new DocumentCommandResultDto("document-1");
        RenameDocumentRequest renameRequest = new RenameDocumentRequest();
        renameRequest.setTitle("Renamed certificate");
        WorkflowTaskDto task = new WorkflowTaskDto("task-1", "workflow-1", "PENDING", 100L, 100L);
        DoctorCheckDto check = new DoctorCheckDto("storage", "HEALTHY", "Object exists.");
        DoctorReportDto report = new DoctorReportDto("document-1", "HEALTHY", Collections.singletonList(check), 100L);
        ApiPage<DocumentDto> page = new ApiPage<>(Collections.singletonList(document), "cursor-2", 1L);
        ApiResponse<ApiPage<DocumentDto>> success = ApiResponse.success(page, "Document loaded", "trace-1");
        ApiResponse<DocumentDto> failure = ApiResponse.failure(new ApiError(ApiErrorCodes.NOT_FOUND, "Document was not found."), "trace-2");

        assertEquals("DOC-1", create.getDocumentNumber());
        assertEquals("document-1", document.getId());
        assertEquals("version-1", version.getId());
        assertEquals("document-1", detail.getDocument().getId());
        assertEquals("version-1", commandResult.getVersionId());
        assertNull(renameResult.getVersionId());
        assertEquals("Renamed certificate", renameRequest.getTitle());
        assertEquals(1, detail.getVersions().size());
        assertEquals("workflow-1", task.getWorkflowId());
        assertFalse(task.getAssignedToCurrentUser());
        assertEquals("storage", report.getChecks().get(0).getCheckerName());
        assertEquals(1L, page.getTotal());
        assertEquals("cursor-2", page.getNextCursor());
        assertEquals("OK", ApiErrorCodes.OK);
        assertEquals("INVALID_REQUEST", ApiErrorCodes.INVALID_REQUEST);
        assertEquals("UNAUTHENTICATED", ApiErrorCodes.UNAUTHENTICATED);
        assertEquals("FORBIDDEN", ApiErrorCodes.FORBIDDEN);
        assertEquals("NOT_FOUND", ApiErrorCodes.NOT_FOUND);
        assertEquals("METHOD_NOT_ALLOWED", ApiErrorCodes.METHOD_NOT_ALLOWED);
        assertEquals("RANGE_NOT_SUPPORTED", ApiErrorCodes.RANGE_NOT_SUPPORTED);
        assertEquals("CONFLICT", ApiErrorCodes.CONFLICT);
        assertEquals("FEATURE_UNAVAILABLE", ApiErrorCodes.FEATURE_UNAVAILABLE);
        assertEquals("CONTENT_UNAVAILABLE", ApiErrorCodes.CONTENT_UNAVAILABLE);
        assertEquals("INTERNAL_ERROR", ApiErrorCodes.INTERNAL_ERROR);
        assertTrue(success.isSuccess());
        assertFalse(success.isFailure());
        assertEquals("trace-1", success.getTraceId());
        assertTrue(failure.isFailure());
        assertNull(failure.getData());
        assertEquals("NOT_FOUND", failure.getError().getCode());
        assertNotNull(new ScheduleDocumentDoctorCommand());
        assertEquals(20, new DocumentPageQuery().getLimit());
        assertEquals("approved", new ApproveWorkflowTaskCommand("approved").getComment());
    }
}
