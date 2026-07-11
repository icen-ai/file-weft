package com.fileweft.dev.api.service

import com.fileweft.application.catalog.DocumentCatalogLifecycleService
import com.fileweft.core.context.TenantContext
import com.fileweft.core.id.Identifier
import com.fileweft.dev.api.config.DevRole
import com.fileweft.dev.api.security.DevPrincipal
import com.fileweft.dev.api.security.DevUserDirectory
import com.fileweft.domain.workflow.WorkflowInstance
import com.fileweft.domain.workflow.WorkflowTask
import com.fileweft.spi.tenant.TenantProvider
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame

class DevReviewServiceTest {
    @Test
    fun `denied document submit authorization does not inspect reviewer or workflow`() {
        val documentId = Identifier("document-1")
        val access = Mockito.mock(DevAccessService::class.java)
        val users = Mockito.mock(DevUserDirectory::class.java)
        val tenants = Mockito.mock(TenantProvider::class.java)
        val lifecycle = Mockito.mock(DocumentCatalogLifecycleService::class.java)
        Mockito.doThrow(SecurityException("Denied before reviewer lookup."))
            .`when`(access)
            .requireDocumentAction(documentId, "document:submit")
        val service = DevReviewService(access, users, tenants, lifecycle)

        val failure = assertFailsWith<SecurityException> {
            service.submit(documentId, "alpha-reviewer", "dual-control")
        }

        assertEquals("Denied before reviewer lookup.", failure.message)
        Mockito.verify(access).requireDocumentAction(documentId, "document:submit")
        Mockito.verifyNoInteractions(users, tenants, lifecycle)
    }

    @Test
    fun `allowed submit authorizes before reviewer lookup and catalog lifecycle submission`() {
        val events = mutableListOf<String>()
        val documentId = Identifier("document-1")
        val tenantId = Identifier("alpha")
        val reviewer = DevPrincipal(
            id = Identifier("alpha-reviewer"),
            username = "alpha-reviewer",
            displayName = "Alpha Reviewer",
            tenantId = tenantId,
            role = DevRole.REVIEWER,
        )
        val workflow = workflow(documentId, tenantId, reviewer.id)
        val access = Mockito.mock(DevAccessService::class.java)
        val users = Mockito.mock(DevUserDirectory::class.java)
        val tenants = Mockito.mock(TenantProvider::class.java)
        val lifecycle = Mockito.mock(DocumentCatalogLifecycleService::class.java)
        Mockito.doAnswer {
            events += "access"
            null
        }.`when`(access).requireDocumentAction(documentId, "document:submit")
        Mockito.`when`(users.findById(reviewer.id)).thenAnswer {
            events += "reviewer lookup"
            reviewer
        }
        Mockito.`when`(tenants.currentTenant()).thenReturn(TenantContext(tenantId))
        Mockito.`when`(lifecycle.submitForReview(documentId, reviewer.id, "dual-control")).thenAnswer {
            events += "catalog lifecycle submit"
            workflow
        }
        val service = DevReviewService(access, users, tenants, lifecycle)

        val result = service.submit(documentId, reviewer.id.value, "dual-control")

        assertSame(workflow, result)
        assertEquals(listOf("access", "reviewer lookup", "catalog lifecycle submit"), events)
        val ordered = Mockito.inOrder(access, users, lifecycle)
        ordered.verify(access).requireDocumentAction(documentId, "document:submit")
        ordered.verify(users).findById(reviewer.id)
        ordered.verify(lifecycle).submitForReview(documentId, reviewer.id, "dual-control")
        ordered.verifyNoMoreInteractions()
    }

    private fun workflow(
        documentId: Identifier,
        tenantId: Identifier,
        reviewerId: Identifier,
    ): WorkflowInstance {
        val workflowId = Identifier("workflow-1")
        return WorkflowInstance(
            id = workflowId,
            tenantId = tenantId,
            documentId = documentId,
            workflowType = "DOCUMENT_REVIEW",
            tasks = listOf(WorkflowTask(Identifier("task-1"), tenantId, workflowId, reviewerId)),
        )
    }
}
