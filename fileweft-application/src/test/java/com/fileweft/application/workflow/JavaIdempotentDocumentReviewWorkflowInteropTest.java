package com.fileweft.application.workflow;

import com.fileweft.application.catalog.DocumentCatalogLifecycleService;
import com.fileweft.application.idempotency.RequestIdempotencyService;
import com.fileweft.application.lifecycle.DocumentLifecycleReceipt;
import com.fileweft.core.id.Identifier;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class JavaIdempotentDocumentReviewWorkflowInteropTest {
    @Test
    void exposesJavaFriendlyConstructorsReceiptsAndOverloads() throws Exception {
        assertNotNull(IdempotentDocumentReviewWorkflowService.class.getConstructor(
            DocumentReviewWorkflowService.class,
            RequestIdempotencyService.class
        ));
        assertNotNull(IdempotentDocumentCatalogReviewWorkflowService.class.getConstructor(
            DocumentCatalogLifecycleService.class,
            RequestIdempotencyService.class
        ));
        assertNotNull(DocumentLifecycleReceipt.class.getConstructor(Identifier.class));
        assertNotNull(DocumentLifecycleReceipt.class.getConstructor(Identifier.class, Identifier.class));
        assertNotNull(DocumentLifecycleReceipt.class.getConstructor(
            Identifier.class,
            Identifier.class,
            Identifier.class
        ));

        for (Class<?> boundary : new Class<?>[] {
            IdempotentDocumentReviewWorkflowService.class,
            IdempotentDocumentCatalogReviewWorkflowService.class
        }) {
            assertReceipt(boundary.getMethod("submitForReview", Identifier.class, String.class));
            assertReceipt(boundary.getMethod(
                "submitForReview",
                Identifier.class,
                String.class,
                String.class
            ));
            assertReceipt(boundary.getMethod(
                "submitForReview",
                Identifier.class,
                Identifier.class,
                String.class,
                String.class
            ));
            assertReceipt(boundary.getMethod("approve", Identifier.class, Identifier.class, String.class));
            assertReceipt(boundary.getMethod(
                "approve",
                Identifier.class,
                Identifier.class,
                String.class,
                String.class
            ));
            assertReceipt(boundary.getMethod(
                "approve",
                Identifier.class,
                Identifier.class,
                String.class,
                String.class,
                String.class
            ));
            assertReceipt(boundary.getMethod("reject", Identifier.class, Identifier.class, String.class));
            assertReceipt(boundary.getMethod(
                "reject",
                Identifier.class,
                Identifier.class,
                String.class,
                String.class
            ));
        }
    }

    @Test
    void doesNotExposeTheUnguardedDelegateCommandsToJavaHosts() throws Exception {
        Class<?> delegate = Class.forName(
            "com.fileweft.application.workflow.IdempotentDocumentReviewWorkflowDelegate"
        );
        assertFalse(Arrays.stream(delegate.getDeclaredMethods()).anyMatch(method ->
            Modifier.isPublic(method.getModifiers()) && !method.isSynthetic()
        ));
    }

    private static void assertReceipt(Method method) {
        assertEquals(DocumentLifecycleReceipt.class, method.getReturnType());
    }
}
