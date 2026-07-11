package com.fileweft.application.catalog;

import com.fileweft.application.document.AddDocumentVersionCommand;
import com.fileweft.application.document.DocumentDraftService;
import com.fileweft.core.id.Identifier;
import com.fileweft.domain.document.Document;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class JavaDocumentCatalogMutationServiceInteropTest {
    @Test
    void exposesOnlyJavaFriendlyTypesOnItsPublicConstructionAndMutationSurface() throws Exception {
        assertEquals(1, DocumentCatalogMutationService.class.getConstructors().length);
        assertArrayEquals(
            new Class<?>[] {DocumentDraftService.class, DocumentCatalogAccessService.class},
            DocumentCatalogMutationService.class.getConstructors()[0].getParameterTypes()
        );
        assertNotNull(DocumentCatalogMutationService.class.getConstructor(
            DocumentDraftService.class,
            DocumentCatalogAccessService.class
        ));
        assertEquals(
            Document.class,
            DocumentCatalogMutationService.class.getMethod(
                "addVersion",
                Identifier.class,
                AddDocumentVersionCommand.class,
                InputStream.class
            ).getReturnType()
        );
        assertEquals(
            Document.class,
            DocumentCatalogMutationService.class.getMethod(
                "rename",
                Identifier.class,
                String.class
            ).getReturnType()
        );
        assertEquals(
            Identifier.class,
            DocumentCatalogBindingChangedException.class.getMethod("getDocumentId").getReturnType()
        );
    }
}
