package com.fileweft.application.delivery;

import com.fileweft.application.document.DocumentFolderReadScope;
import com.fileweft.application.transaction.ApplicationTransaction;
import com.fileweft.core.context.TenantContext;
import com.fileweft.core.id.Identifier;
import com.fileweft.spi.authorization.AuthorizationDecision;
import com.fileweft.spi.authorization.AuthorizationProvider;
import com.fileweft.spi.delivery.DeliveryRequirement;
import com.fileweft.spi.identity.UserIdentity;
import com.fileweft.spi.identity.UserRealmProvider;
import com.fileweft.spi.tenant.TenantProvider;
import kotlin.jvm.functions.Function0;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaDocumentSyncStatusQueryInteropTest {

    @Test
    void constructsAndExecutesSafeSynchronizationQueryContractsFromJava() {
        Identifier tenantId = new Identifier("tenant-java");
        Identifier documentId = new Identifier("document-java");
        DocumentDeliveryStatusView target = new DocumentDeliveryStatusView(
            new Identifier("delivery-java"),
            "archive",
            "Archive",
            DeliveryRequirement.REQUIRED,
            DocumentDeliveryStatus.FAILED,
            3,
            DocumentDeliveryRemovalStatus.NOT_REQUESTED,
            0,
            true,
            false,
            100L
        );
        DocumentSyncStatusView expected = new DocumentSyncStatusView(
            documentId,
            Collections.singletonList(target)
        );
        DocumentSyncStatusQueryRepository repository = new DocumentSyncStatusQueryRepository() {
            @Override
            public DocumentSyncStatusView findByDocument(
                Identifier queryTenantId,
                Identifier queryDocumentId,
                DocumentFolderReadScope folderReadScope
            ) {
                assertEquals(tenantId, queryTenantId);
                assertEquals(documentId, queryDocumentId);
                assertNull(folderReadScope);
                return expected;
            }
        };
        TenantProvider tenants = () -> new TenantContext(tenantId);
        UserRealmProvider users = new UserRealmProvider() {
            @Override
            public UserIdentity currentUser() {
                return new UserIdentity(new Identifier("reader-java"), "Java reader", Collections.emptyMap());
            }

            @Override
            public UserIdentity findUser(Identifier userId) {
                return null;
            }
        };
        AuthorizationProvider authorization = request -> new AuthorizationDecision(true, null);
        ApplicationTransaction transaction = new ApplicationTransaction() {
            @Override
            public <T> T execute(Function0<? extends T> action) {
                return action.invoke();
            }
        };
        DocumentSyncStatusQueryService service = new DocumentSyncStatusQueryService(
            tenants,
            users,
            authorization,
            repository,
            transaction
        );

        DocumentSyncStatusView loaded = service.status(documentId);

        assertEquals("document-java", loaded.getDocumentId().getValue());
        assertEquals("delivery-java", loaded.getDeliveryTargets().get(0).getDeliveryId().getValue());
        assertEquals("archive", target.getTargetId());
        assertEquals("Archive", target.getDisplayName());
        assertEquals(DeliveryRequirement.REQUIRED, target.getRequirement());
        assertEquals(DocumentDeliveryStatus.FAILED, target.getDeliveryStatus());
        assertEquals(3, target.getDeliveryRetryCount());
        assertTrue(target.getDeliveryRetryable());
        assertFalse(target.getRemovalRetryable());
        assertTrue(new DocumentSyncStatusView(documentId).getDeliveryTargets().isEmpty());
    }
}
