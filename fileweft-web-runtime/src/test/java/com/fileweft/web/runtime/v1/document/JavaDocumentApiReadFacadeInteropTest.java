package com.fileweft.web.runtime.v1.document;

import com.fileweft.application.document.DocumentQueryService;
import com.fileweft.web.api.ApiPage;
import com.fileweft.web.api.v1.document.DocumentDetailDto;
import com.fileweft.web.api.v1.document.DocumentDto;
import com.fileweft.web.api.v1.document.DocumentPageQuery;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class JavaDocumentApiReadFacadeInteropTest {

    @Test
    void readsTheStableV1ContractsFromJava() throws NoSuchMethodException {
        DocumentApiReadFacade facade = JavaRuntimeInteropFixtures.facade();

        DocumentDetailDto detail = facade.detail("document-java");
        ApiPage<DocumentDto> page = facade.page(new DocumentPageQuery());

        assertEquals("document-java", detail.getDocument().getId());
        assertEquals("version-java", detail.getDocument().getCurrentVersionId());
        assertEquals("version-java", detail.getVersions().get(0).getId());
        assertEquals("document-java", page.getItems().get(0).getId());
        assertNotNull(page.getNextCursor());
        assertNotNull(DocumentApiReadFacade.class.getConstructor(DocumentQueryService.class));
        assertEquals(DocumentDetailDto.class, DocumentApiReadFacade.class.getMethod("detail", String.class).getReturnType());
        assertEquals(ApiPage.class, DocumentApiReadFacade.class.getMethod("page", DocumentPageQuery.class).getReturnType());
    }
}
