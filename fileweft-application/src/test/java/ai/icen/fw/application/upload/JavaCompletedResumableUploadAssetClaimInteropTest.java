package ai.icen.fw.application.upload;

import ai.icen.fw.core.id.Identifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class JavaCompletedResumableUploadAssetClaimInteropTest {
    @Test
    void constructsCommandsAndReadsStableResultFromJava() {
        Identifier uploadId = new Identifier("upload-1");
        CreateDocumentFromCompletedUploadCommand create =
            new CreateDocumentFromCompletedUploadCommand(uploadId, "DOC-1", "Contract", "claim-key");
        AddDocumentVersionFromCompletedUploadCommand version =
            new AddDocumentVersionFromCompletedUploadCommand(
                uploadId,
                new Identifier("document-1"),
                "2.0",
                "version-key"
            );
        CompletedResumableUploadAssetClaimResult result = new CompletedResumableUploadAssetClaimResult(
            uploadId,
            new Identifier("file-1"),
            new Identifier("asset-1"),
            new Identifier("document-1"),
            new Identifier("version-1"),
            false
        );

        assertEquals("DOC-1", create.getDocumentNumber());
        assertEquals("2.0", version.getVersionNumber());
        assertEquals("asset-1", result.getFileAssetId().getValue());
        assertFalse(result.getReplayed());
    }
}
