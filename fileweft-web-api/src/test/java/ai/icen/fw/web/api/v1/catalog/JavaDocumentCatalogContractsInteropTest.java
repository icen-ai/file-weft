package ai.icen.fw.web.api.v1.catalog;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class JavaDocumentCatalogContractsInteropTest {
    @Test
    void exposesJavaFriendlyFolderPageAndMoveContracts() {
        DocumentCatalogFolderDto folder = new DocumentCatalogFolderDto("child", "Child", "root");
        DocumentCatalogPageQuery query = new DocumentCatalogPageQuery(null, 25);
        MoveDocumentToFolderRequest move = new MoveDocumentToFolderRequest();
        move.setFolderId("child");
        MoveDocumentToFolderCommand command = new MoveDocumentToFolderCommand(move.getFolderId());

        assertEquals("child", folder.getId());
        assertEquals("root", folder.getParentFolderId());
        assertEquals(25, query.getLimit());
        assertEquals("child", move.getFolderId());
        assertEquals("child", command.getFolderId());
    }
}
