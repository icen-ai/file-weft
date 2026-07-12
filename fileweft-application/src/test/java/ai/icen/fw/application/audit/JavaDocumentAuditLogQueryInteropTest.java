package ai.icen.fw.application.audit;

import ai.icen.fw.core.id.Identifier;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class JavaDocumentAuditLogQueryInteropTest {
    @Test
    void constructsAndReadsThePublicQueryModelsWithoutKotlinHelpers() {
        Identifier auditId = new Identifier("audit-1");
        DocumentAuditLogPageCursor cursor = new DocumentAuditLogPageCursor(10L, auditId);
        DocumentAuditLogPageRequest defaultRequest = new DocumentAuditLogPageRequest();
        DocumentAuditLogPageRequest pagedRequest = new DocumentAuditLogPageRequest(cursor, 50);
        DocumentAuditLogView minimal = new DocumentAuditLogView(auditId, "document:create", 10L);
        DocumentAuditLogView complete = new DocumentAuditLogView(
            auditId,
            "document:create",
            10L,
            new Identifier("operator-1"),
            "Operator One",
            new Identifier("trace-1")
        );
        DocumentAuditLogPageResult result = new DocumentAuditLogPageResult(
            new Identifier("document-1"),
            Collections.singletonList(complete)
        );

        assertEquals(DocumentAuditLogPageRequest.DEFAULT_LIMIT, defaultRequest.getLimit());
        assertEquals(50, pagedRequest.getLimit());
        assertEquals(auditId, pagedRequest.getCursor().getId());
        assertNull(minimal.getOperatorId());
        assertNull(minimal.getOperatorName());
        assertNull(minimal.getTraceId());
        assertEquals("Operator One", result.getItems().get(0).getOperatorName());
        assertNull(result.getNextCursor());
    }
}
