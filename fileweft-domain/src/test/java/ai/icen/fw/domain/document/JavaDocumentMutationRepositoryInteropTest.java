package ai.icen.fw.domain.document;

import ai.icen.fw.core.id.Identifier;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JavaDocumentMutationRepositoryInteropTest {
    @Test
    void exposesMutationReadsAsAnAdditiveJavaFriendlyCapability() throws Exception {
        assertTrue(DocumentRepository.class.isAssignableFrom(DocumentMutationRepository.class));
        assertEquals(
            Document.class,
            DocumentMutationRepository.class.getMethod(
                "findForMutation",
                Identifier.class,
                Identifier.class
            ).getReturnType()
        );
        assertEquals(
            Document.class,
            DocumentMutationRepository.class.getMethod(
                "findByDocumentNumber",
                Identifier.class,
                String.class
            ).getReturnType()
        );
    }
}
