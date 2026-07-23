package ai.icen.fw.application.archive

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

/** Archives a published document through its domain lifecycle. */
class ArchiveDocumentService(
    private val tenantProvider: TenantProvider,
    userRealmProvider: UserRealmProvider,
    authorizationProvider: AuthorizationProvider,
    private val documentRepository: DocumentMutationRepository,
    private val transaction: ApplicationTransaction,
    private val auditTrail: AuditTrail? = null,
    private val removalPlanner: DocumentDeliveryRemovalPlanner? = null,
) {
    private val authorization = ApplicationAuthorization(userRealmProvider, authorizationProvider)

    fun archive(documentId: Identifier): Document = execute(documentId, null)

    @JvmSynthetic
    internal fun archive(documentId: Identifier, guard: DocumentLifecycleMutationGuard): Document =
        execute(documentId, guard)

    private fun execute(documentId: Identifier, guard: DocumentLifecycleMutationGuard?): Document {
        val context = prepareArchive(documentId, guard)
        val validated = context.revalidate()
        return transaction.execute {
            DocumentLifecycleMutationTransaction.execute { archiveInCurrentTransaction(validated) }
        }
    }

    @JvmSynthetic
    internal fun prepareArchive(
        documentId: Identifier,
        guard: DocumentLifecycleMutationGuard?,
    ): DocumentLifecycleMutationContext {
        val tenant = tenantProvider.currentTenant()
        val operator = authorization.requireDocumentAction(tenant.tenantId, documentId, ARCHIVE_ACTION)
        return DocumentLifecycleMutationContext.prepare(
            tenantId = tenant.tenantId,
            operator = operator,
            documentId = documentId,
            action = ARCHIVE_ACTION,
            guard = guard,
        )
    }

    @JvmSynthetic
    internal fun archiveInCurrentTransaction(validated: ValidatedDocumentLifecycleMutation): Document {
        DocumentLifecycleMutationTransaction.requireActive()
        val context = validated.contextFor(ARCHIVE_ACTION)
        val document = documentRepository.findForMutation(context.tenantId, context.documentId)
            ?: throw DocumentNotFoundException(context.documentId)
        if (document.tenantId != context.tenantId || document.id != context.documentId) {
            throw DocumentNotFoundException(context.documentId)
        }
        validated.verifyLocked(document, ARCHIVE_ACTION)
        document.transition(LifecycleCommand.ARCHIVE)
        val removalPlan = removalPlanner?.plan(document)
        documentRepository.save(document)
        auditTrail?.record(
            tenantId = context.tenantId,
            resourceType = DOCUMENT_RESOURCE_TYPE,
            resourceId = document.id,
            action = ARCHIVE_AUDIT_ACTION,
            operatorId = context.operator.id,
            operatorName = context.operator.displayName,
            details = removalPlan?.let { plan -> mapOf("downstreamRemovalCount" to plan.deliveries.size.toString()) } ?: emptyMap(),
        )
        return document
    }

    private companion object {
        const val DOCUMENT_RESOURCE_TYPE = "DOCUMENT"
        const val ARCHIVE_ACTION = "document:archive"
        const val ARCHIVE_AUDIT_ACTION = "document:archive"
    }
}
