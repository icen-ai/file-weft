package ai.icen.fw.application.offline

import ai.icen.fw.application.audit.AuditTrail
import ai.icen.fw.application.catalog.DocumentLifecycleMutationGuard
import ai.icen.fw.application.document.DocumentNotFoundException
import ai.icen.fw.application.delivery.DocumentDeliveryRemovalPlanner
import ai.icen.fw.application.lifecycle.DocumentLifecycleMutationContext
import ai.icen.fw.application.lifecycle.DocumentLifecycleMutationTransaction
import ai.icen.fw.application.lifecycle.ValidatedDocumentLifecycleMutation
import ai.icen.fw.application.security.ApplicationAuthorization
import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.domain.document.Document
import ai.icen.fw.domain.document.DocumentMutationRepository
import ai.icen.fw.domain.document.LifecycleCommand
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.identity.UserRealmProvider
import ai.icen.fw.spi.tenant.TenantProvider

class OfflineDocumentService(
    private val tenantProvider: TenantProvider,
    userRealmProvider: UserRealmProvider,
    authorizationProvider: AuthorizationProvider,
    private val documentRepository: DocumentMutationRepository,
    private val transaction: ApplicationTransaction,
    private val auditTrail: AuditTrail? = null,
    private val removalPlanner: DocumentDeliveryRemovalPlanner? = null,
) {
    private val authorization = ApplicationAuthorization(userRealmProvider, authorizationProvider)

    fun offline(documentId: Identifier): Document = execute(documentId, null)

    @JvmSynthetic
    internal fun offline(documentId: Identifier, guard: DocumentLifecycleMutationGuard): Document =
        execute(documentId, guard)

    private fun execute(documentId: Identifier, guard: DocumentLifecycleMutationGuard?): Document {
        val context = prepareOffline(documentId, guard)
        val validated = context.revalidate()
        return transaction.execute {
            DocumentLifecycleMutationTransaction.execute { offlineInCurrentTransaction(validated) }
        }
    }

    @JvmSynthetic
    internal fun prepareOffline(
        documentId: Identifier,
        guard: DocumentLifecycleMutationGuard?,
    ): DocumentLifecycleMutationContext {
        val tenant = tenantProvider.currentTenant()
        val operator = authorization.requireDocumentAction(tenant.tenantId, documentId, OFFLINE_ACTION)
        return DocumentLifecycleMutationContext.prepare(
            tenantId = tenant.tenantId,
            operator = operator,
            documentId = documentId,
            action = OFFLINE_ACTION,
            guard = guard,
        )
    }

    @JvmSynthetic
    internal fun offlineInCurrentTransaction(validated: ValidatedDocumentLifecycleMutation): Document {
        DocumentLifecycleMutationTransaction.requireActive()
        val context = validated.contextFor(OFFLINE_ACTION)
        val document = documentRepository.findForMutation(context.tenantId, context.documentId)
            ?: throw DocumentNotFoundException(context.documentId)
        if (document.tenantId != context.tenantId || document.id != context.documentId) {
            throw DocumentNotFoundException(context.documentId)
        }
        validated.verifyLocked(document, OFFLINE_ACTION)
        document.transition(LifecycleCommand.OFFLINE)
        val removalPlan = removalPlanner?.plan(document)
        documentRepository.save(document)
        auditTrail?.record(
            tenantId = context.tenantId,
            resourceType = DOCUMENT_RESOURCE_TYPE,
            resourceId = document.id,
            action = OFFLINE_AUDIT_ACTION,
            operatorId = context.operator.id,
            operatorName = context.operator.displayName,
            details = removalPlan?.let { plan -> mapOf("downstreamRemovalCount" to plan.deliveries.size.toString()) } ?: emptyMap(),
        )
        return document
    }

    private companion object {
        const val DOCUMENT_RESOURCE_TYPE = "DOCUMENT"
        const val OFFLINE_ACTION = "document:offline"
        const val OFFLINE_AUDIT_ACTION = "document:offline"
    }
}
