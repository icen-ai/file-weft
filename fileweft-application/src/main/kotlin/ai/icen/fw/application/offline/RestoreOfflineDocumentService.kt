package ai.icen.fw.application.offline

import ai.icen.fw.application.audit.AuditTrail
import ai.icen.fw.application.catalog.DocumentLifecycleMutationGuard
import ai.icen.fw.application.delivery.DocumentDeliveryRemovalStatus
import ai.icen.fw.application.delivery.DocumentDeliveryStatus
import ai.icen.fw.application.delivery.DocumentDeliveryTargetRepository
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
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.identity.UserRealmProvider
import ai.icen.fw.spi.tenant.TenantProvider

/**
 * Starts a controlled replacement only after the current publication generation
 * has settled: successfully delivered targets must be withdrawn and no target
 * may still be waiting for a downstream synchronization result.
 */
class RestoreOfflineDocumentService(
    private val tenantProvider: TenantProvider,
    userRealmProvider: UserRealmProvider,
    authorizationProvider: AuthorizationProvider,
    private val documentRepository: DocumentRepository,
    private val deliveries: DocumentDeliveryTargetRepository,
    private val transaction: ApplicationTransaction,
    private val auditTrail: AuditTrail? = null,
) {
    private val authorization = ApplicationAuthorization(userRealmProvider, authorizationProvider)

    fun restore(documentId: Identifier): Document = execute(documentId, null)

    @JvmSynthetic
    internal fun restore(documentId: Identifier, guard: DocumentLifecycleMutationGuard): Document =
        execute(documentId, guard)

    private fun execute(documentId: Identifier, guard: DocumentLifecycleMutationGuard?): Document {
        val context = prepareRestore(documentId, guard)
        val validated = context.revalidate()
        return transaction.execute {
            DocumentLifecycleMutationTransaction.execute { restoreInCurrentTransaction(validated) }
        }
    }

    @JvmSynthetic
    internal fun prepareRestore(
        documentId: Identifier,
        guard: DocumentLifecycleMutationGuard?,
    ): DocumentLifecycleMutationContext {
        val tenant = tenantProvider.currentTenant()
        val operator = authorization.requireDocumentAction(tenant.tenantId, documentId, RESTORE_ACTION)
        return DocumentLifecycleMutationContext.prepare(
            tenantId = tenant.tenantId,
            operator = operator,
            documentId = documentId,
            action = RESTORE_ACTION,
            guard = guard,
        )
    }

    @JvmSynthetic
    internal fun restoreInCurrentTransaction(validated: ValidatedDocumentLifecycleMutation): Document {
        DocumentLifecycleMutationTransaction.requireActive()
        val context = validated.contextFor(RESTORE_ACTION)
        val document = documentRepository.findForMutation(context.tenantId, context.documentId)
            ?: throw DocumentNotFoundException(context.documentId)
        if (document.tenantId != context.tenantId || document.id != context.documentId) {
            throw DocumentNotFoundException(context.documentId)
        }
        validated.verifyLocked(document, RESTORE_ACTION)
        val currentDeliveries = deliveries.findByDocumentGeneration(
            context.tenantId,
            document.id,
            document.deliveryGeneration,
        )
        if (currentDeliveries.any { it.status in ACTIVE_DELIVERY_STATES }) {
            throw DocumentRestoreConflictException(DocumentRestoreConflictReason.DELIVERY_IN_PROGRESS)
        }
        if (currentDeliveries.any {
                it.status == DocumentDeliveryStatus.SUCCEEDED &&
                    it.removalStatus != DocumentDeliveryRemovalStatus.SUCCEEDED
            }
        ) {
            throw DocumentRestoreConflictException(DocumentRestoreConflictReason.WITHDRAWAL_INCOMPLETE)
        }
        document.transition(LifecycleCommand.RESTORE_DRAFT)
        documentRepository.save(document)
        auditTrail?.record(
            tenantId = context.tenantId,
            resourceType = DOCUMENT_RESOURCE_TYPE,
            resourceId = document.id,
            action = RESTORE_ACTION,
            operatorId = context.operator.id,
            operatorName = context.operator.displayName,
            details = mapOf("completedDeliveryGeneration" to document.deliveryGeneration.toString()),
        )
        return document
    }

    private companion object {
        const val RESTORE_ACTION = "document:restore"
        const val DOCUMENT_RESOURCE_TYPE = "DOCUMENT"
        val ACTIVE_DELIVERY_STATES = setOf(DocumentDeliveryStatus.PENDING, DocumentDeliveryStatus.RETRYING)
    }
}

enum class DocumentRestoreConflictReason {
    DELIVERY_IN_PROGRESS,
    WITHDRAWAL_INCOMPLETE,
}

class DocumentRestoreConflictException(
    val reason: DocumentRestoreConflictReason,
) : IllegalStateException(
    when (reason) {
        DocumentRestoreConflictReason.DELIVERY_IN_PROGRESS ->
            "Document delivery is still in progress."
        DocumentRestoreConflictReason.WITHDRAWAL_INCOMPLETE ->
            "Document delivery withdrawal is incomplete."
    },
)
