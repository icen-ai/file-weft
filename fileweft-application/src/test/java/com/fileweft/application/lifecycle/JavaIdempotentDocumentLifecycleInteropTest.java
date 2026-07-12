package com.fileweft.application.lifecycle;

import com.fileweft.application.archive.ArchiveDocumentService;
import com.fileweft.application.catalog.DocumentCatalogLifecycleService;
import com.fileweft.application.document.DocumentCommandService;
import com.fileweft.application.idempotency.RequestIdempotencyService;
import com.fileweft.application.offline.OfflineDocumentService;
import com.fileweft.application.offline.RestoreOfflineDocumentService;
import com.fileweft.application.publish.PublishDocumentService;
import com.fileweft.core.id.Identifier;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Modifier;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class JavaIdempotentDocumentLifecycleInteropTest {
    @Test
    void exposesStableReceiptsAndJavaFriendlyLifecycleMethods() throws Exception {
        assertNotNull(DocumentLifecycleReceipt.class.getConstructor(Identifier.class));
        assertNotNull(DocumentLifecycleReceipt.class.getConstructor(Identifier.class, Identifier.class));
        assertNotNull(IdempotentDocumentLifecycleService.class.getConstructor(
            DocumentCommandService.class,
            PublishDocumentService.class,
            OfflineDocumentService.class,
            RestoreOfflineDocumentService.class,
            ArchiveDocumentService.class,
            RequestIdempotencyService.class
        ));
        assertNotNull(IdempotentDocumentCatalogLifecycleService.class.getConstructor(
            DocumentCatalogLifecycleService.class,
            RequestIdempotencyService.class
        ));

        for (String method : new String[] {"revise", "publish", "offline", "restore", "archive"}) {
            assertEquals(
                DocumentLifecycleReceipt.class,
                IdempotentDocumentLifecycleService.class
                    .getMethod(method, Identifier.class, String.class)
                    .getReturnType()
            );
            assertEquals(
                DocumentLifecycleReceipt.class,
                IdempotentDocumentCatalogLifecycleService.class
                    .getMethod(method, Identifier.class, String.class)
                .getReturnType()
            );
        }

        assertEquals(
            DocumentLifecycleReceipt.class,
            IdempotentDocumentLifecycleService.class
                .getMethod("publish", Identifier.class, String.class, String.class)
                .getReturnType()
        );
        assertEquals(
            DocumentLifecycleReceipt.class,
            IdempotentDocumentCatalogLifecycleService.class
                .getMethod("publish", Identifier.class, String.class, String.class)
                .getReturnType()
        );
    }

    @Test
    void doesNotExposeTheUnguardedDelegateCommandsToJavaHosts() throws Exception {
        Class<?> delegate = Class.forName(
            "com.fileweft.application.lifecycle.IdempotentDocumentLifecycleDelegate"
        );
        assertFalse(Arrays.stream(delegate.getDeclaredMethods()).anyMatch(method ->
            Modifier.isPublic(method.getModifiers()) && !method.isSynthetic()
        ));
    }
}
