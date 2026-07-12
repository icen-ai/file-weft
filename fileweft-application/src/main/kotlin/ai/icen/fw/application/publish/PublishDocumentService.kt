package ai.icen.fw.application.publish

import ai.icen.fw.application.audit.AuditTrail
import ai.icen.fw.application.catalog.DocumentLifecycleMutationGuard
import ai.icen.fw.application.delivery.DocumentDeliveryPlanner
import ai.icen.fw.application.delivery.DocumentDeliveryPreparation
import ai.icen.fw.application.document.DocumentNotFoundException
import ai.icen.fw.application.lifecycle.DocumentLifecycleMutationContext
import ai.icen.fw.application.lifecycle.DocumentLifecycleMutationTransaction
import ai.icen.fw.application.lifecycle.ValidatedDocumentLifecycleMutation
import ai.icen.fw.application.security.ApplicationAuthorization
import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.domain.document.Document
import ai.icen.fw.domain.document.DocumentRepository
import ai.icen.fw.domain.document.LifecycleCommand
import ai.icen.fw.domain.workflow.WorkflowInstanceRepository
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.identity.UserRealmProvider
import ai.icen.fw.spi.tenant.TenantProvider

class PublishDocumentService(
    private val tenantProvider: TenantProvider,
    private val userRealmProvider: UserRealmProvider,
    authorizationProvider: AuthorizationProvider,
    private val documentRepository: DocumentRepository,
    private val deliveryPlanner: DocumentDeliveryPlanner,
    private val transaction: ApplicationTransaction,
    private val auditTrail: AuditTrail? = null,
    private val workflows: WorkflowInstanceRepository,
) {
    private val authorization = ApplicationAuthorization(userRealmProvider, authorizationProvider)

    fun publish(documentId: Identifier): Document = execute(documentId, null, null)

    fun publish(documentId: Identifier, deliveryProfileId: String?): Document =
        execute(documentId, deliveryProfileId, null)

    @JvmSynthetic
    internal fun publish(
        documentId: Identifier,
        deliveryProfileId: String?,
        guard: DocumentLifecycleMutationGuard,
    ): Document = execute(documentId, deliveryProfileId, guard)

    private fun execute(
        documentId: Identifier,
        deliveryProfileId: String?,
        guard: DocumentLifecycleMutationGuard?,
    ): Document {
        val context = preparePublish(documentId, guard)
        preflightPublish(context)
        val preparation = prepareDelivery(context, deliveryProfileId)
        val validated = context.revalidate()
        return transaction.execute {
            DocumentLifecycleMutationTransaction.execute {
                publishInCurrentTransaction(validated, preparation)
            }
        }
    }

    @JvmSynthetic
    internal fun preparePublish(
        documentId: Identifier,
        guard: DocumentLifecycleMutationGuard?,
    ): DocumentLifecycleMutationContext {
        val tenant = tenantProvider.currentTenant()
        val operator = authorization.requireDocumentAction(tenant.tenantId, documentId, PUBLISH_ACTION)
        return DocumentLifecycleMutationContext.prepare(
            tenantId = tenant.tenantId,
            operator = operator,
            documentId = documentId,
            action = PUBLISH_ACTION,
            guard = guard,
        )
    }

    @JvmSynthetic
    internal fun preflightPublish(context: DocumentLifecycleMutationContext) {
        requirePublishContext(context)
        transaction.execute {
            val document = documentRepository.findById(context.tenantId, context.documentId)
                ?: throw DocumentNotFoundException(context.documentId)
            if (document.tenantId != context.tenantId || document.id != context.documentId) {
                throw DocumentNotFoundException(context.documentId)
            }
            requireNoActiveWorkflow(context.tenantId, context.documentId)
        }
    }

    /** Remote/configuration-backed delivery resolution must remain outside the final transaction. */
    @JvmSynthetic
    internal fun prepareDelivery(
        context: DocumentLifecycleMutationContext,
        deliveryProfileId: String?,
    ): DocumentDeliveryPreparation {
        requirePublishContext(context)
        return deliveryPlanner.prepare(context.tenantId, deliveryProfileId)
    }

    /** Executes only local mutation work inside an already active final transaction. */
    @JvmSynthetic
    internal fun publishInCurrentTransaction(
        validated: ValidatedDocumentLifecycleMutation,
        preparation: DocumentDeliveryPreparation,
    ): Document {
        DocumentLifecycleMutationTransaction.requireActive()
        val context = validated.contextFor(PUBLISH_ACTION)
        val document = documentRepository.findForMutation(context.tenantId, context.documentId)
            ?: throw DocumentNotFoundException(context.documentId)
        if (document.tenantId != context.tenantId || document.id != context.documentId) {
            throw DocumentNotFoundException(context.documentId)
        }
        // Document lock first, then the optional catalog asset lock.
        validated.verifyLocked(document, PUBLISH_ACTION)
        requireNoActiveWorkflow(context.tenantId, context.documentId)
        document.transition(LifecycleCommand.APPROVE)
        documentRepository.save(document)
        deliveryPlanner.plan(document, preparation)
        auditTrail?.record(
            tenantId = context.tenantId,
            resourceType = DOCUMENT_RESOURCE_TYPE,
            resourceId = document.id,
            action = PUBLISH_AUDIT_ACTION,
            operatorId = context.operator.id,
            operatorName = context.operator.displayName,
        )
        return document
    }

    private fun requirePublishContext(context: DocumentLifecycleMutationContext) {
        require(context.action == PUBLISH_ACTION) { "Lifecycle mutation context belongs to a different action." }
    }

    private fun requireNoActiveWorkflow(tenantId: Identifier, documentId: Identifier) {
        if (workflows.findActiveByDocument(tenantId, documentId) != null) {
            throw ActiveDocumentReviewWorkflowException(documentId)
        }
    }

    private companion object {
        const val DOCUMENT_RESOURCE_TYPE = "DOCUMENT"
        const val PUBLISH_ACTION = "document:publish"
        const val PUBLISH_AUDIT_ACTION = "document:publish:request"
    }
}

class ActiveDocumentReviewWorkflowException(documentId: Identifier) :
    IllegalStateException("Document ${documentId.value} has an active review workflow and cannot be published directly.")
