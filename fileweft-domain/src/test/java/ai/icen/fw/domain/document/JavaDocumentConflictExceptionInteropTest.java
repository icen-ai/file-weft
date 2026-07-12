package ai.icen.fw.domain.document;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

class JavaDocumentConflictExceptionInteropTest {

    @Test
    void exposesDocumentConflictHierarchyAndPropertiesToJava() {
        DocumentConflictException duplicateNumber = new DocumentNumberAlreadyExistsException("DOC-001");
        DocumentConflictException invalidTransition = new InvalidLifecycleTransitionException(
            LifecycleState.PUBLISHED,
            LifecycleCommand.SUBMIT
        );
        DocumentConflictException notEditable = new DocumentNotEditableException(LifecycleState.PENDING_REVIEW);
        DocumentConflictException duplicateVersion = new DocumentVersionAlreadyExistsException("1.0");
        DocumentConflictException missingVersion = new DocumentVersionRequiredException();

        assertInstanceOf(IllegalStateException.class, duplicateNumber);
        assertEquals("DOC-001", ((DocumentNumberAlreadyExistsException) duplicateNumber).getDocumentNumber());
        assertEquals(LifecycleCommand.SUBMIT, ((InvalidLifecycleTransitionException) invalidTransition).getCommand());
        assertEquals(LifecycleState.PENDING_REVIEW, ((DocumentNotEditableException) notEditable).getCurrentState());
        assertEquals("1.0", ((DocumentVersionAlreadyExistsException) duplicateVersion).getVersionNumber());
        assertEquals("A document version is required before submission.", missingVersion.getMessage());
    }

    @Test
    void keepsTheCompatibilityBaseConstructorsAvailableToJava() {
        RuntimeException cause = new RuntimeException("root cause");
        DocumentConflictException failure = new DocumentConflictException("conflict", cause);

        assertEquals("conflict", failure.getMessage());
        assertSame(cause, failure.getCause());
    }
}
