package ai.icen.fw.web.runtime.v1.document;

import ai.icen.fw.application.upload.CompletedResumableUploadAssetClaimService;
import ai.icen.fw.web.api.v1.document.AddDocumentVersionFromCompletedUploadRequest;
import ai.icen.fw.web.api.v1.document.CreateDocumentFromCompletedUploadRequest;
import ai.icen.fw.web.api.v1.document.DocumentCommandResultDto;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class JavaCompletedUploadDocumentApiFacadeSurfaceTest {
    @Test
    void exposesOnlyTheAtomicClaimServiceConstructor() throws Exception {
        assertNotNull(CompletedUploadDocumentApiFacade.class.getConstructor(
            CompletedResumableUploadAssetClaimService.class
        ));
        assertEquals(
            1,
            java.util.Arrays.stream(CompletedUploadDocumentApiFacade.class.getConstructors())
                .filter(constructor -> !constructor.isSynthetic())
                .count()
        );
    }

    @Test
    void exposesJavaFriendlyCompletedUploadDocumentOperations() throws Exception {
        assertEquals(
            DocumentCommandResultDto.class,
            CompletedUploadDocumentApiFacade.class.getMethod(
                "createDocument",
                String.class,
                List.class,
                CreateDocumentFromCompletedUploadRequest.class
            ).getReturnType()
        );
        assertEquals(
            DocumentCommandResultDto.class,
            CompletedUploadDocumentApiFacade.class.getMethod(
                "addDocumentVersion",
                String.class,
                String.class,
                List.class,
                AddDocumentVersionFromCompletedUploadRequest.class
            ).getReturnType()
        );
    }
}
