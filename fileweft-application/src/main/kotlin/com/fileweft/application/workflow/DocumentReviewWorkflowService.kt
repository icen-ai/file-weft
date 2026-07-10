package com.fileweft.application.workflow

import com.fileweft.application.document.DocumentNotFoundException
import com.fileweft.application.outbox.OutboxEventRepository
import com.fileweft.application.security.ApplicationAuthorization
import com.fileweft.application.security.ApplicationAuthorizationException
import com.fileweft.application.transaction.ApplicationTransaction
import com.fileweft.core.event.OutboxEvent
import com.fileweft.core.id.Identifier
import com.fileweft.core.id.IdentifierGenerator
import com.fileweft.domain.document.Document
import com.fileweft.domain.document.DocumentRepository
import com.fileweft.domain.document.LifecycleCommand
import com.fileweft.domain.workflow.WorkflowInstance
import com.fileweft.domain.workflow.WorkflowInstanceRepository
import com.fileweft.domain.workflow.WorkflowTask
import com.fileweft.spi.authorization.AuthorizationProvider
import com.fileweft.spi.identity.UserRealmProvider
import com.fileweft.spi.tenant.TenantProvider
import java.time.Clock

/** Local, persistent review workflow that gates document publication. */
class DocumentReviewWorkflowService(
    private val tenantProvider: TenantProvider,
    private val userRealmProvider: UserRealmProvider,
    authorizationProvider: AuthorizationProvider,
    private val documentRepository: DocumentRepository,
    private val workflowRepository: WorkflowInstanceRepository,
    private val outboxEventRepository: OutboxEventRepository,
    private val identifierGenerator: IdentifierGenerator,
    private val transaction: ApplicationTransaction,
    private val clock: Clock,
) {
    private val authorization = ApplicationAuthorization(userRealmProvider, authorizationProvider)

    fun submit(documentId: Identifier, reviewerId: Identifier? = null): WorkflowInstance {
        val tenant = tenantProvider.currentTenant()
        authorization.requireDocumentAction(tenant.tenantId, documentId, SUBMIT_ACTION)
        return transaction.execute {
            val document = documentRepository.findById(tenant.tenantId, documentId)
                ?: throw DocumentNotFoundException(documentId)
            require(workflowRepository.findActiveByDocument(tenant.tenantId, documentId) == null) {
                "Document already has an active review workflow."
            }
            document.transition(LifecycleCommand.SUBMIT)
            val workflowId = identifierGenerator.nextId()
            val workflow = WorkflowInstance(
                id = workflowId,
                tenantId = tenant.tenantId,
                documentId = document.id,
                workflowType = REVIEW_WORKFLOW_TYPE,
                tasks = listOf(
                    WorkflowTask(
                        id = identifierGenerator.nextId(),
                        tenantId = tenant.tenantId,
                        workflowId = workflowId,
                        assigneeId = reviewerId,
                    ),
                ),
            )
            documentRepository.save(document)
            workflowRepository.save(workflow)
            workflow
        }
    }

    fun approve(workflowId: Identifier, taskId: Identifier, comment: String? = null): Document =
        decide(workflowId, taskId, comment, approved = true)

    fun reject(workflowId: Identifier, taskId: Identifier, comment: String? = null): Document =
        decide(workflowId, taskId, comment, approved = false)

    private fun decide(workflowId: Identifier, taskId: Identifier, comment: String?, approved: Boolean): Document {
        val tenant = tenantProvider.currentTenant()
        val workflowSnapshot = transaction.execute {
            workflowRepository.findById(tenant.tenantId, workflowId) ?: throw WorkflowNotFoundException(workflowId)
        }
        val operator = userRealmProvider.currentUser()?.id
            ?: throw ApplicationAuthorizationException("A current user is required.")
        authorization.requireDocumentAction(tenant.tenantId, workflowSnapshot.documentId, AUDIT_ACTION)
        return transaction.execute {
            val workflow = workflowRepository.findById(tenant.tenantId, workflowId) ?: throw WorkflowNotFoundException(workflowId)
            val document = documentRepository.findById(tenant.tenantId, workflow.documentId)
                ?: throw DocumentNotFoundException(workflow.documentId)
            if (approved) {
                workflow.approve(taskId, operator, comment)
                document.transition(LifecycleCommand.APPROVE)
                outboxEventRepository.append(
                    OutboxEvent(
                        id = identifierGenerator.nextId(),
                        tenantId = tenant.tenantId,
                        type = PUBLISH_REQUESTED_EVENT_TYPE,
                        payload = mapOf("documentId" to document.id.value, "workflowId" to workflow.id.value),
                        timestamp = clock.millis(),
                    ),
                )
            } else {
                workflow.reject(taskId, operator, comment)
                document.transition(LifecycleCommand.REJECT)
            }
            workflowRepository.save(workflow)
            documentRepository.save(document)
            document
        }
    }

    companion object {
        const val REVIEW_WORKFLOW_TYPE = "DOCUMENT_REVIEW"
        const val SUBMIT_ACTION = "document:submit"
        const val AUDIT_ACTION = "document:audit"
        const val PUBLISH_REQUESTED_EVENT_TYPE = "document.publish.requested"
    }
}

class WorkflowNotFoundException(workflowId: Identifier) :
    NoSuchElementException("Workflow ${workflowId.value} was not found in the current tenant.")
