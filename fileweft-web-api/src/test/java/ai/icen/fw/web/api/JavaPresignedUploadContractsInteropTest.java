package ai.icen.fw.web.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import ai.icen.fw.web.api.v1.upload.PresignedUploadGrantDto;
import ai.icen.fw.web.api.v1.upload.PresignedUploadFinalizationDto;
import ai.icen.fw.web.api.v1.upload.StartPresignedUploadCommand;
import java.net.URI;
import java.util.Collections;
import org.junit.jupiter.api.Test;

class JavaPresignedUploadContractsInteropTest {
    @Test
    void constructsTheAdditiveFlowWeftV1ContractsFromJava() {
        StartPresignedUploadCommand command = new StartPresignedUploadCommand(
                "contract.pdf",
                12L,
                "application/pdf",
                "sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "md5",
                "CY9rzUYh03PK3k6DJie09g==");
        PresignedUploadGrantDto grant = new PresignedUploadGrantDto(
                "upload-1",
                URI.create("https://uploads.example/object?signature=redacted"),
                Collections.singletonMap("Content-Type", "application/pdf"),
                2_000L,
                true);
        PresignedUploadFinalizationDto finalized = new PresignedUploadFinalizationDto(
                "upload-1", "file-object-1", "file-asset-1", false);

        assertEquals("contract.pdf", command.getFileName());
        assertEquals("PUT", grant.getMethod());
        assertEquals("upload-1", grant.getUploadId());
        assertEquals("file-object-1", finalized.getFileObjectId());
        assertEquals("file-asset-1", finalized.getFileAssetId());
        assertEquals("COMPLETED", finalized.getStatus());
    }

    @Test
    void rejectsCredentialHeadersAtThePublicBoundary() {
        assertThrows(IllegalArgumentException.class, () -> new PresignedUploadGrantDto(
                "upload-1",
                URI.create("https://uploads.example/object?signature=redacted"),
                Collections.singletonMap("Authorization", "Bearer secret"),
                2_000L,
                true));
    }
}
