package ai.icen.fw.application.catalog;

import ai.icen.fw.application.document.AddDocumentVersionCommand;
import ai.icen.fw.application.document.DocumentDraftService;
import ai.icen.fw.core.id.Identifier;
import ai.icen.fw.domain.document.Document;
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
