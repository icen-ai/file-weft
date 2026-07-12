package com.fileweft.web.runtime.v1.document

import com.fileweft.application.lifecycle.DocumentLifecycleReceipt
import com.fileweft.application.lifecycle.IdempotentDocumentCatalogLifecycleService
import com.fileweft.application.lifecycle.IdempotentDocumentLifecycleService
import com.fileweft.application.workflow.IdempotentDocumentCatalogReviewWorkflowService
import com.fileweft.application.workflow.IdempotentDocumentReviewWorkflowService
import com.fileweft.core.id.Identifier
import com.fileweft.web.api.ApiErrorCodes
import com.fileweft.web.api.v1.document.PublishDocumentCommand
import com.fileweft.web.api.v1.workflow.ApproveWorkflowTaskCommand
import com.fileweft.web.api.v1.workflow.RejectWorkflowTaskCommand
import com.fileweft.web.api.v1.workflow.SubmitDocumentReviewCommand
import com.fileweft.web.runtime.v1.ApiHttpStatus
import com.fileweft.web.runtime.v1.V1ApiResponseFactory
import com.fileweft.web.runtime.v1.V1FeatureUnavailableException
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DocumentLifecycleApiFacadeTest {
    @Test
    fun `forwards all lifecycle and review commands and maps stable receipt ids`() {
        val lifecycles = RecordingLifecycles()
        val reviews = RecordingReviews()
        val facade = DocumentLifecycleApiFacade(lifecycles, reviews)

        val revise = facade.revise("document-revise", "revise-key")
        val publish = facade.publish(
            "document-publish",
            PublishDocumentCommand("regulated"),
            "publish-key",
        )
        val offline = facade.offline("document-offline", "offline-key")
        val restore = facade.restore("document-restore", "restore-key")
        val archive = facade.archive("document-archive", "archive-key")
        val submit = facade.submitForReview(
            "document-submit",
            SubmitDocumentReviewCommand("dual-control"),
            "submit-key",
        )
        val approve = facade.approve(
            "workflow-approve",
            "task-approve",
            ApproveWorkflowTaskCommand("approved", "regulated"),
            "approve-key",
        )
        val reject = facade.reject(
            "workflow-reject",
            "task-reject",
            RejectWorkflowTaskCommand("revise"),
            "reject-key",
        )

        assertEquals(
            listOf(
                "revise:document-revise:revise-key",
                "publish:document-publish:regulated:publish-key",
                "offline:document-offline:offline-key",
                "restore:document-restore:restore-key",
                "archive:document-archive:archive-key",
            ),
            lifecycles.calls,
        )
        assertEquals(
            listOf(
                "submit:document-submit:dual-control:submit-key",
                "approve:workflow-approve:task-approve:approved:regulated:approve-key",
                "reject:workflow-reject:task-reject:revise:reject-key",
            ),
            reviews.calls,
        )
        listOf(revise, publish, offline, restore, archive).forEach { result ->
            assertTrue(result.documentId.startsWith("result-"))
            assertNull(result.workflowId)
            assertNull(result.taskId)
        }
        assertEquals("result-submit-document", submit.documentId)
        assertEquals("result-submit-workflow", submit.workflowId)
        assertNull(submit.taskId)
        assertEquals("result-approve-document", approve.documentId)
        assertEquals("result-approve-workflow", approve.workflowId)
        assertEquals("result-approve-task", approve.taskId)
        assertEquals("result-reject-document", reject.documentId)
        assertEquals("result-reject-workflow", reject.workflowId)
        assertEquals("result-reject-task", reject.taskId)
    }

    @Test
    fun `missing lifecycle and review capabilities fail independently as feature unavailable`() {
        val responseFactory = V1ApiResponseFactory()
        val lifecycleMissing = DocumentLifecycleApiFacade(null, RecordingReviews())
        val reviewMissing = DocumentLifecycleApiFacade(RecordingLifecycles(), null)

        val lifecycleFailure = assertFailsWith<V1FeatureUnavailableException> {
            lifecycleMissing.revise("document-1", "valid-key")
        }
        val reviewFailure = assertFailsWith<V1FeatureUnavailableException> {
            reviewMissing.submitForReview("document-1", SubmitDocumentReviewCommand(), "valid-key")
        }

        listOf(lifecycleFailure, reviewFailure).forEach { failure ->
            val mapped = responseFactory.failure(failure)
            assertEquals(ApiHttpStatus.SERVICE_UNAVAILABLE, mapped.status)
            assertEquals(ApiErrorCodes.FEATURE_UNAVAILABLE, mapped.response.error?.code)
        }
        assertEquals("result-submit-document", lifecycleMissing.submitForReview(
            "document-1",
            SubmitDocumentReviewCommand(),
            "review-key",
        ).documentId)
        assertEquals("result-revise", reviewMissing.revise("document-1", "lifecycle-key").documentId)
    }

    @Test
    fun `invalid identifiers and keys map to bad request when capability exists`() {
        val lifecycles = RecordingLifecycles()
        val reviews = RecordingReviews()
        val facade = DocumentLifecycleApiFacade(lifecycles, reviews)
        val responseFactory = V1ApiResponseFactory()
        val failures = listOf(
            assertFailsWith<IllegalArgumentException> { facade.revise(" ", "valid-key") },
            assertFailsWith<IllegalArgumentException> { facade.revise("document-1", "invalid key") },
            assertFailsWith<IllegalArgumentException> {
                facade.approve("\u0000workflow", "task-1", ApproveWorkflowTaskCommand(), "valid-key")
            },
            assertFailsWith<IllegalArgumentException> {
                facade.reject("workflow-1", "", RejectWorkflowTaskCommand(), "valid-key")
            },
        )

        failures.forEach { failure ->
            assertEquals(ApiHttpStatus.BAD_REQUEST, responseFactory.failure(failure).status)
        }
        assertEquals(emptyList(), lifecycles.calls)
        assertEquals(emptyList(), reviews.calls)
    }

    @Test
    fun `public constructor selects only the boundary matching catalog access`() {
        val flatLifecycle = allocate(IdempotentDocumentLifecycleService::class.java)
        val catalogLifecycle = allocate(IdempotentDocumentCatalogLifecycleService::class.java)
        val flatReview = allocate(IdempotentDocumentReviewWorkflowService::class.java)
        val catalogReview = allocate(IdempotentDocumentCatalogReviewWorkflowService::class.java)

        val flat = DocumentLifecycleApiFacade(
            0,
            listOf(flatLifecycle),
            emptyList(),
            listOf(flatReview),
            emptyList(),
        )
        val guarded = DocumentLifecycleApiFacade(
            1,
            emptyList(),
            listOf(catalogLifecycle),
            emptyList(),
            listOf(catalogReview),
        )

        assertSelected(flat, "lifecycles", "FlatLifecycleCommands")
        assertSelected(flat, "reviews", "FlatReviewCommands")
        assertSelected(guarded, "lifecycles", "CatalogLifecycleCommands")
        assertSelected(guarded, "reviews", "CatalogReviewCommands")
    }

    @Test
    fun `catalog and flat mixed candidates never fall back to an unguarded boundary`() {
        val mixed = DocumentLifecycleApiFacade(
            1,
            listOf(allocate(IdempotentDocumentLifecycleService::class.java)),
            listOf(allocate(IdempotentDocumentCatalogLifecycleService::class.java)),
            listOf(allocate(IdempotentDocumentReviewWorkflowService::class.java)),
            listOf(allocate(IdempotentDocumentCatalogReviewWorkflowService::class.java)),
        )

        assertFailsWith<V1FeatureUnavailableException> { mixed.revise("document-1", "valid-key") }
        assertFailsWith<V1FeatureUnavailableException> {
            mixed.submitForReview("document-1", SubmitDocumentReviewCommand(), "valid-key")
        }
        assertNull(selected(mixed, "lifecycles"))
        assertNull(selected(mixed, "reviews"))
    }

    @Test
    fun `public constructor rejects multiple access or command candidates at startup`() {
        val flatLifecycle = allocate(IdempotentDocumentLifecycleService::class.java)
        val catalogLifecycle = allocate(IdempotentDocumentCatalogLifecycleService::class.java)
        val flatReview = allocate(IdempotentDocumentReviewWorkflowService::class.java)
        val catalogReview = allocate(IdempotentDocumentCatalogReviewWorkflowService::class.java)

        assertFailsWith<IllegalArgumentException> {
            DocumentLifecycleApiFacade(2, emptyList(), emptyList(), emptyList(), emptyList())
        }
        assertFailsWith<IllegalArgumentException> {
            DocumentLifecycleApiFacade(
                0,
                listOf(flatLifecycle, flatLifecycle),
                emptyList(),
                emptyList(),
                emptyList(),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            DocumentLifecycleApiFacade(
                1,
                emptyList(),
                listOf(catalogLifecycle, catalogLifecycle),
                emptyList(),
                emptyList(),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            DocumentLifecycleApiFacade(
                0,
                listOf(flatLifecycle),
                emptyList(),
                listOf(flatReview, flatReview),
                emptyList(),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            DocumentLifecycleApiFacade(
                1,
                emptyList(),
                listOf(catalogLifecycle),
                emptyList(),
                listOf(catalogReview, catalogReview),
            )
        }
    }

    private fun assertSelected(facade: DocumentLifecycleApiFacade, fieldName: String, expected: String) {
        assertEquals(expected, checkNotNull(selected(facade, fieldName)).javaClass.simpleName)
    }

    private fun selected(facade: DocumentLifecycleApiFacade, fieldName: String): Any? {
        val field = DocumentLifecycleApiFacade::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        return field.get(facade)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> allocate(type: Class<T>): T {
        val unsafeType = Class.forName("sun.misc.Unsafe")
        val field = unsafeType.getDeclaredField("theUnsafe")
        field.isAccessible = true
        val unsafe = field.get(null)
        return unsafeType.getMethod("allocateInstance", Class::class.java).invoke(unsafe, type) as T
    }

    private class RecordingLifecycles : DocumentLifecycleApiFacade.LifecycleCommands {
        val calls = mutableListOf<String>()

        override fun revise(documentId: Identifier, key: String): DocumentLifecycleReceipt {
            calls += "revise:${documentId.value}:$key"
            return DocumentLifecycleReceipt(Identifier("result-revise"))
        }

        override fun publish(documentId: Identifier, profileId: String?, key: String): DocumentLifecycleReceipt {
            calls += "publish:${documentId.value}:$profileId:$key"
            return DocumentLifecycleReceipt(Identifier("result-publish"))
        }

        override fun offline(documentId: Identifier, key: String): DocumentLifecycleReceipt {
            calls += "offline:${documentId.value}:$key"
            return DocumentLifecycleReceipt(Identifier("result-offline"))
        }

        override fun restore(documentId: Identifier, key: String): DocumentLifecycleReceipt {
            calls += "restore:${documentId.value}:$key"
            return DocumentLifecycleReceipt(Identifier("result-restore"))
        }

        override fun archive(documentId: Identifier, key: String): DocumentLifecycleReceipt {
            calls += "archive:${documentId.value}:$key"
            return DocumentLifecycleReceipt(Identifier("result-archive"))
        }
    }

    private class RecordingReviews : DocumentLifecycleApiFacade.ReviewCommands {
        val calls = mutableListOf<String>()

        override fun submitForReview(
            documentId: Identifier,
            routeId: String?,
            key: String,
        ): DocumentLifecycleReceipt {
            calls += "submit:${documentId.value}:$routeId:$key"
            return DocumentLifecycleReceipt(
                Identifier("result-submit-document"),
                Identifier("result-submit-workflow"),
            )
        }

        override fun approve(
            workflowId: Identifier,
            taskId: Identifier,
            comment: String?,
            profileId: String?,
            key: String,
        ): DocumentLifecycleReceipt {
            calls += "approve:${workflowId.value}:${taskId.value}:$comment:$profileId:$key"
            return DocumentLifecycleReceipt(
                Identifier("result-approve-document"),
                Identifier("result-approve-workflow"),
                Identifier("result-approve-task"),
            )
        }

        override fun reject(
            workflowId: Identifier,
            taskId: Identifier,
            comment: String?,
            key: String,
        ): DocumentLifecycleReceipt {
            calls += "reject:${workflowId.value}:${taskId.value}:$comment:$key"
            return DocumentLifecycleReceipt(
                Identifier("result-reject-document"),
                Identifier("result-reject-workflow"),
                Identifier("result-reject-task"),
            )
        }
    }
}
