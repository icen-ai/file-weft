package ai.icen.fw.web.api;

import ai.icen.fw.web.api.v1.upload.ResumableUploadCompletionDto;
import ai.icen.fw.web.api.v1.upload.ResumableUploadDto;
import ai.icen.fw.web.api.v1.upload.ResumableUploadPartDto;
import ai.icen.fw.web.api.v1.upload.ResumableUploadStatuses;
import ai.icen.fw.web.api.v1.upload.StartResumableUploadCommand;
import ai.icen.fw.web.api.v1.upload.StartResumableUploadRequest;
import java.util.Collections;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class JavaResumableUploadContractsInteropTest {
    @Test
    void contractsExposeOrdinaryJavaConstructorsAndAccessors() {
        StartResumableUploadRequest request = new StartResumableUploadRequest();
        request.setFileName("contract.pdf");
        request.setContentLength(4L);
        request.setContentType("application/pdf");
        request.setContentHash(null);

        StartResumableUploadCommand command = new StartResumableUploadCommand(
            request.getFileName(),
            request.getContentLength(),
            request.getContentType()
        );
        ResumableUploadPartDto part = new ResumableUploadPartDto("upload-1", 1, 4L, 20L);
        ResumableUploadCompletionDto completion = new ResumableUploadCompletionDto(
            "upload-1",
            "object-1",
            "asset-1",
            30L
        );
        ResumableUploadDto upload = new ResumableUploadDto(
            "upload-1",
            command.getFileName(),
            command.getContentLength(),
            ResumableUploadStatuses.COMPLETED,
            100L,
            10L,
            30L,
            Collections.singletonList(part),
            command.getContentType(),
            command.getContentHash(),
            completion
        );

        assertEquals("upload-1", upload.getUploadId());
        assertEquals(ResumableUploadStatuses.COMPLETED, upload.getStatus());
        assertEquals("object-1", upload.getCompletion().getFileObjectId());
        assertEquals(10_000, ResumableUploadPartDto.MAX_PART_NUMBER);
        assertNull(command.getContentHash());
    }
}
