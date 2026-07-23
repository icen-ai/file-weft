package ai.icen.fw.domain.document

import org.junit.jupiter.api.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DocumentRepositoryTest {
    @Test
    fun `mutation capability is an additive subinterface of the read repository`() {
        assertTrue(
            DocumentRepository::class.java.isAssignableFrom(DocumentMutationRepository::class.java),
            "DocumentMutationRepository must remain a DocumentRepository.",
        )
    }

    @Test
    fun `read repository no longer carries fail-open mutation defaults`() {
        val declaredMethods = DocumentRepository::class.java.methods.map { it.name }

        assertFalse(
            "findForMutation" in declaredMethods,
            "DocumentRepository must not declare findForMutation; write paths require DocumentMutationRepository.",
        )
        assertFalse(
            "findByDocumentNumber" in declaredMethods,
            "DocumentRepository must not declare findByDocumentNumber; write paths require DocumentMutationRepository.",
        )
    }
}
