package ai.icen.fw.web.runtime.v1.document;

import ai.icen.fw.application.catalog.DocumentCatalogDraftService;
import ai.icen.fw.application.catalog.DocumentCatalogMutationService;
import ai.icen.fw.application.document.DocumentDraftService;
import ai.icen.fw.application.metadata.DocumentMetadataWriteService;
import ai.icen.fw.web.api.v1.document.AddDocumentVersionCommand;
import ai.icen.fw.web.api.v1.document.CreateDocumentDraftCommand;
import ai.icen.fw.web.api.v1.document.DocumentCommandResultDto;
import ai.icen.fw.web.api.v1.document.DocumentMetadataCommand;
import ai.icen.fw.web.api.v1.document.RenameDocumentCommand;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class JavaDocumentApiWriteFacadeInteropTest {

    @Test
    void exposesJavaFriendlyMutationSignaturesWithoutTrustedContextParameters() throws Exception {
        assertNotNull(DocumentApiWriteFacade.class.getConstructor(DocumentDraftService.class));
        assertNotNull(DocumentApiWriteFacade.class.getConstructor(
            DocumentDraftService.class,
            DocumentCatalogDraftService.class
        ));
        assertNotNull(DocumentApiWriteFacade.class.getConstructor(
            DocumentDraftService.class,
            DocumentCatalogDraftService.class,
            DocumentCatalogMutationService.class
        ));
        assertNotNull(DocumentApiWriteFacade.class.getConstructor(
            DocumentDraftService.class,
            DocumentCatalogDraftService.class,
            DocumentCatalogMutationService.class,
            DocumentMetadataWriteService.class
        ));

        Method create = DocumentApiWriteFacade.class.getMethod(
            "create",
            CreateDocumentDraftCommand.class,
            InputStream.class
        );
        Method addVersion = DocumentApiWriteFacade.class.getMethod(
            "addVersion",
            String.class,
            AddDocumentVersionCommand.class,
            InputStream.class
        );
        Method createWithMetadata = DocumentApiWriteFacade.class.getMethod(
            "create",
            CreateDocumentDraftCommand.class,
            DocumentMetadataCommand.class,
            InputStream.class
        );
        Method addVersionWithMetadata = DocumentApiWriteFacade.class.getMethod(
            "addVersion",
            String.class,
            AddDocumentVersionCommand.class,
            DocumentMetadataCommand.class,
            InputStream.class
        );
        Method rename = DocumentApiWriteFacade.class.getMethod(
            "rename",
            String.class,
            RenameDocumentCommand.class
        );

        assertEquals(DocumentCommandResultDto.class, create.getReturnType());
        assertEquals(DocumentCommandResultDto.class, addVersion.getReturnType());
        assertEquals(DocumentCommandResultDto.class, rename.getReturnType());
        assertEquals(DocumentCommandResultDto.class, createWithMetadata.getReturnType());
        assertEquals(DocumentCommandResultDto.class, addVersionWithMetadata.getReturnType());
        assertEquals(
            "/fileweft/v1/documents/document-1",
            DocumentApiLocations.detailIfRoutable("document-1").toString()
        );
    }
}
