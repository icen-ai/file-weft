package ai.icen.fw.application.workflow;

import ai.icen.fw.application.catalog.DocumentCatalogLifecycleService;
import ai.icen.fw.application.idempotency.RequestIdempotencyService;
import ai.icen.fw.application.lifecycle.DocumentLifecycleReceipt;
import ai.icen.fw.core.id.Identifier;
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
            "ai.icen.fw.application.workflow.IdempotentDocumentReviewWorkflowDelegate"
        );
        assertFalse(Arrays.stream(delegate.getDeclaredMethods()).anyMatch(method ->
            Modifier.isPublic(method.getModifiers()) && !method.isSynthetic()
        ));
    }

    private static void assertReceipt(Method method) {
        assertEquals(DocumentLifecycleReceipt.class, method.getReturnType());
    }
}
