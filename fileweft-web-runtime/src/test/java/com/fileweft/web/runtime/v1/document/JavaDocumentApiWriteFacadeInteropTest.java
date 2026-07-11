package com.fileweft.web.runtime.v1.document;

import com.fileweft.application.catalog.DocumentCatalogDraftService;
import com.fileweft.application.catalog.DocumentCatalogMutationService;
import com.fileweft.application.document.DocumentDraftService;
import com.fileweft.web.api.v1.document.AddDocumentVersionCommand;
import com.fileweft.web.api.v1.document.CreateDocumentDraftCommand;
import com.fileweft.web.api.v1.document.DocumentCommandResultDto;
import com.fileweft.web.api.v1.document.RenameDocumentCommand;
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
        Method rename = DocumentApiWriteFacade.class.getMethod(
            "rename",
            String.class,
            RenameDocumentCommand.class
        );

        assertEquals(DocumentCommandResultDto.class, create.getReturnType());
        assertEquals(DocumentCommandResultDto.class, addVersion.getReturnType());
        assertEquals(DocumentCommandResultDto.class, rename.getReturnType());
        assertEquals(
            "/fileweft/v1/documents/document-1",
            DocumentApiLocations.detailIfRoutable("document-1").toString()
        );
    }
}
