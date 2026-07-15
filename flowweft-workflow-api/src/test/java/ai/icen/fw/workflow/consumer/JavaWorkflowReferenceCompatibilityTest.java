package ai.icen.fw.workflow.consumer;

import ai.icen.fw.workflow.api.WorkflowDefinitionRef;
import ai.icen.fw.workflow.api.WorkflowInstanceRef;
import ai.icen.fw.workflow.api.WorkflowPrincipalRef;
import ai.icen.fw.workflow.api.WorkflowSubjectRef;
import ai.icen.fw.workflow.api.WorkflowSubjectSnapshot;
import ai.icen.fw.workflow.api.WorkflowWorkItemRef;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaWorkflowReferenceCompatibilityTest {
    private static final String DIGEST =
        "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void staticFactoriesAndGettersArePlainExternalJava8Contracts() throws Exception {
        WorkflowPrincipalRef principal = WorkflowPrincipalRef.of("USER", "用户-甲");
        WorkflowSubjectRef subject = WorkflowSubjectRef.of("DOCUMENT", "文档-乙");
        WorkflowSubjectSnapshot snapshot = WorkflowSubjectSnapshot.of(subject, "R7", DIGEST);
        WorkflowDefinitionRef definition = WorkflowDefinitionRef.of("leave.approval", "V3", DIGEST);
        WorkflowInstanceRef instance = WorkflowInstanceRef.of("instance-1", 11L);
        WorkflowWorkItemRef workItem = WorkflowWorkItemRef.of("work-item-1", 12L);

        assertEquals("USER", principal.getType());
        assertEquals("用户-甲", principal.getId());
        assertEquals("DOCUMENT", subject.getType());
        assertEquals("文档-乙", subject.getId());
        assertEquals(subject, snapshot.getRef());
        assertEquals("R7", snapshot.getRevision());
        assertEquals(DIGEST, snapshot.getDigest());
        assertEquals("leave.approval", definition.getKey());
        assertEquals("V3", definition.getVersion());
        assertEquals(DIGEST, definition.getDigest());
        assertEquals("instance-1", instance.getId());
        assertEquals(11L, instance.getExpectedVersion());
        assertEquals("work-item-1", workItem.getId());
        assertEquals(12L, workItem.getExpectedVersion());

        Method instanceFactory = WorkflowInstanceRef.class.getMethod("of", String.class, long.class);
        Method workItemFactory = WorkflowWorkItemRef.class.getMethod("of", String.class, long.class);
        assertTrue(Modifier.isPublic(instanceFactory.getModifiers()));
        assertTrue(Modifier.isStatic(instanceFactory.getModifiers()));
        assertTrue(Modifier.isPublic(workItemFactory.getModifiers()));
        assertTrue(Modifier.isStatic(workItemFactory.getModifiers()));
    }

    @Test
    void valuesAreImmutableFinalAndHaveNoKotlinDataClassSurface() {
        assertImmutableValue(WorkflowPrincipalRef.class);
        assertImmutableValue(WorkflowSubjectRef.class);
        assertImmutableValue(WorkflowSubjectSnapshot.class);
        assertImmutableValue(WorkflowDefinitionRef.class);
        assertImmutableValue(WorkflowInstanceRef.class);
        assertImmutableValue(WorkflowWorkItemRef.class);

        WorkflowSubjectRef first = WorkflowSubjectRef.of("DOCUMENT", "Case-A");
        WorkflowSubjectRef same = WorkflowSubjectRef.of("DOCUMENT", "Case-A");
        assertEquals(first, same);
        assertEquals(first.hashCode(), same.hashCode());
        assertNotEquals(first, WorkflowSubjectRef.of("document", "Case-A"));
        assertNotEquals(first, WorkflowSubjectRef.of("DOCUMENT", "case-a"));
    }

    @Test
    void JavaCallersReceiveFailClosedValidationAndExactRedactedStrings() throws Exception {
        assertThrows(NullPointerException.class, () -> WorkflowPrincipalRef.of(null, "principal"));
        assertThrows(IllegalArgumentException.class, () -> WorkflowPrincipalRef.of("USER\n", "principal"));
        assertThrows(
            IllegalArgumentException.class,
            () -> WorkflowDefinitionRef.of("leave", "V1", DIGEST.toUpperCase(Locale.ROOT))
        );
        assertThrows(IllegalArgumentException.class, () -> WorkflowInstanceRef.of("instance", -1L));
        assertThrows(IllegalArgumentException.class, () -> WorkflowWorkItemRef.of("item", -1L));

        assertEquals("WorkflowPrincipalRef(<redacted>)", WorkflowPrincipalRef.of("USER", "secret").toString());
        assertEquals("WorkflowSubjectRef(<redacted>)", WorkflowSubjectRef.of("DOCUMENT", "secret").toString());
        assertEquals(
            "WorkflowSubjectSnapshot(<redacted>)",
            WorkflowSubjectSnapshot.of(WorkflowSubjectRef.of("DOCUMENT", "secret"), "secret", DIGEST).toString()
        );
        assertEquals(
            "WorkflowDefinitionRef(<redacted>)",
            WorkflowDefinitionRef.of("secret", "secret", DIGEST).toString()
        );
        assertEquals("WorkflowInstanceRef(<redacted>)", WorkflowInstanceRef.of("secret", 99L).toString());
        assertEquals("WorkflowWorkItemRef(<redacted>)", WorkflowWorkItemRef.of("secret", 99L).toString());

        Class<?> support = Class.forName("ai.icen.fw.workflow.api.WorkflowContractSupport");
        assertFalse(Modifier.isPublic(support.getModifiers()));
    }

    private static void assertImmutableValue(Class<?> type) {
        assertTrue(Modifier.isFinal(type.getModifiers()), type.getName() + " must be final");
        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            if (!constructor.isSynthetic()) {
                assertTrue(Modifier.isPrivate(constructor.getModifiers()), constructor + " must be private");
            }
        }
        for (Field field : type.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) {
                assertTrue(Modifier.isPrivate(field.getModifiers()), field + " must be private");
                assertTrue(Modifier.isFinal(field.getModifiers()), field + " must be final");
            }
        }
        for (Method method : type.getDeclaredMethods()) {
            assertFalse(method.getName().startsWith("set"), method + " must not mutate state");
            assertFalse(method.getName().startsWith("component"), method + " must not expose data-class components");
            assertFalse(method.getName().equals("copy"), method + " must not expose a data-class copy method");
        }
    }
}
