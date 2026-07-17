package ai.icen.fw.application.catalog;

import ai.icen.fw.core.id.Identifier;
import ai.icen.fw.domain.document.Document;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class JavaDocumentCatalogBindingCommandInteropTest {
    @Test
    void canImplementTheCatalogBindingBoundaryFromJava() throws NoSuchMethodException {
        DocumentCatalogBindingCommand command = new DocumentCatalogBindingCommand() {
            @Override
            public Document move(Identifier documentId, String folderId) {
                throw new UnsupportedOperationException("compile-only fixture");
            }
        };

        assertNotNull(command);
        assertNotNull(DocumentCatalogBindingCommand.class.getMethod("move", Identifier.class, String.class));
    }
}
