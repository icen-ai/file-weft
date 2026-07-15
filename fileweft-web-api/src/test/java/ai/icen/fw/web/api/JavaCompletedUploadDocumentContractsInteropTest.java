package ai.icen.fw.web.api;

import ai.icen.fw.web.api.v1.document.AddDocumentVersionFromCompletedUploadRequest;
import ai.icen.fw.web.api.v1.document.CreateDocumentFromCompletedUploadRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JavaCompletedUploadDocumentContractsInteropTest {
    @Test
    void mutableJsonRequestsRemainUsableWithoutKotlinRuntimeConventions() {
        CreateDocumentFromCompletedUploadRequest create =
            new CreateDocumentFromCompletedUploadRequest();
        create.setDocumentNumber("DOC-100");
        create.setTitle("Contract");

        AddDocumentVersionFromCompletedUploadRequest version =
            new AddDocumentVersionFromCompletedUploadRequest();
        version.setVersionNumber("2.0");

        assertEquals("DOC-100", create.getDocumentNumber());
        assertEquals("Contract", create.getTitle());
        assertEquals("2.0", version.getVersionNumber());
    }
}
