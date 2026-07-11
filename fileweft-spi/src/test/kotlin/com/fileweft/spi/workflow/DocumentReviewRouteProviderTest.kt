package com.fileweft.spi.workflow

import com.fileweft.core.id.Identifier
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DocumentReviewRouteProviderTest {
    @Test
    fun `keeps route tasks immutable and rejects ambiguous duplicates`() {
        val task = DocumentReviewRouteTask(Identifier("reviewer-1"))
        val route = DocumentReviewRoute("DUAL_REVIEW", listOf(task))

        assertEquals(listOf(task), route.tasks)
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (route.tasks as MutableList<DocumentReviewRouteTask>).add(DocumentReviewRouteTask(Identifier("reviewer-2")))
        }
        assertFailsWith<IllegalArgumentException> {
            DocumentReviewRoute("DUAL_REVIEW", listOf(task, task))
        }
    }

    @Test
    fun `requires meaningful Java friendly route input`() {
        assertFailsWith<IllegalArgumentException> {
            DocumentReviewRouteRequest(Identifier("tenant-1"), Identifier("document-1"), "", "Document")
        }
        assertFailsWith<IllegalArgumentException> {
            DocumentReviewRoute("", listOf(DocumentReviewRouteTask()))
        }
    }
}
