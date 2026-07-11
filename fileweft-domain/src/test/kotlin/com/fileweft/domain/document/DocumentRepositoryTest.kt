package com.fileweft.domain.document

import com.fileweft.core.id.Identifier
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DocumentRepositoryTest {
    @Test
    fun `requires repository implementations to explicitly provide mutation serialization`() {
        val repository = object : DocumentRepository {
            override fun findById(tenantId: Identifier, documentId: Identifier): Document? = null

            override fun save(document: Document) = Unit
        }

        val failure = assertFailsWith<UnsupportedOperationException> {
            repository.findForMutation(Identifier("tenant-1"), Identifier("document-1"))
        }

        assertEquals(
            "DocumentRepository must implement findForMutation with concurrency-safe mutation semantics.",
            failure.message,
        )
    }
}
