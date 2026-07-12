package ai.icen.fw.application.document

import ai.icen.fw.application.audit.AuditTrail
import ai.icen.fw.application.catalog.DocumentLifecycleMutationGuard
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

class DocumentCommandService(
    private val tenantProvider: TenantProvider,
    userRealmProvider: UserRealmProvider,
    authorizationProvider: AuthorizationProvider,
    private val documentRepository: DocumentRepository,
    private val transaction: ApplicationTransaction,
    private val auditTrail: AuditTrail? = null,
) {
    private val authorization = ApplicationAuthorization(userRealmProvider, authorizationProvider)

    fun submit(documentId: Identifier): Document = execute(documentId, SUBMIT_ACTION, LifecycleCommand.SUBMIT, null)

    @JvmSynthetic
    internal fun submit(documentId: Identifier, guard: DocumentLifecycleMutationGuard): Document =
        execute(documentId, SUBMIT_ACTION, LifecycleCommand.SUBMIT, guard)

    fun reject(documentId: Identifier): Document = execute(documentId, REJECT_ACTION, LifecycleCommand.REJECT, null)

    @JvmSynthetic
    internal fun reject(documentId: Identifier, guard: DocumentLifecycleMutationGuard): Document =
        execute(documentId, REJECT_ACTION, LifecycleCommand.REJECT, guard)

    fun revise(documentId: Identifier): Document = execute(documentId, REVISE_ACTION, LifecycleCommand.REVISE, null)

    @JvmSynthetic
    internal fun revise(documentId: Identifier, guard: DocumentLifecycleMutationGuard): Document =
        execute(documentId, REVISE_ACTION, LifecycleCommand.REVISE, guard)

    @JvmSynthetic
    internal fun prepareRevise(
        documentId: Identifier,
        guard: DocumentLifecycleMutationGuard?,
    ): DocumentLifecycleMutationContext = prepare(documentId, REVISE_ACTION, guard)

    /** Runs only inside the caller's already-open final local transaction. */
    @JvmSynthetic
    internal fun reviseInCurrentTransaction(validated: ValidatedDocumentLifecycleMutation): Document =
        mutateInCurrentTransaction(validated, REVISE_ACTION, LifecycleCommand.REVISE)

    private fun execute(
        documentId: Identifier,
        action: String,
        command: LifecycleCommand,
        guard: DocumentLifecycleMutationGuard?,
    ): Document {
        val context = prepare(documentId, action, guard)
        val validated = context.revalidate()
        return transaction.execute {
            DocumentLifecycleMutationTransaction.execute {
                mutateInCurrentTransaction(validated, action, command)
            }
        }
    }

    private fun prepare(
        documentId: Identifier,
        action: String,
        guard: DocumentLifecycleMutationGuard?,
    ): DocumentLifecycleMutationContext {
        val tenant = tenantProvider.currentTenant()
        // Base authorization must precede every repository and catalog access.
        val operator = authorization.requireDocumentAction(tenant.tenantId, documentId, action)
        return DocumentLifecycleMutationContext.prepare(
            tenantId = tenant.tenantId,
            operator = operator,
            documentId = documentId,
            action = action,
            guard = guard,
        )
    }

    private fun mutateInCurrentTransaction(
        validated: ValidatedDocumentLifecycleMutation,
        expectedAction: String,
        command: LifecycleCommand,
    ): Document {
        DocumentLifecycleMutationTransaction.requireActive()
        val context = validated.contextFor(expectedAction)
        val document = documentRepository.findForMutation(context.tenantId, context.documentId)
            ?: throw DocumentNotFoundException(context.documentId)
        if (document.tenantId != context.tenantId || document.id != context.documentId) {
            throw DocumentNotFoundException(context.documentId)
        }
        validated.verifyLocked(document, expectedAction)
        document.transition(command)
        documentRepository.save(document)
        auditTrail?.record(
            tenantId = context.tenantId,
            resourceType = DOCUMENT_RESOURCE_TYPE,
            resourceId = document.id,
            action = expectedAction,
            operatorId = context.operator.id,
            operatorName = context.operator.displayName,
        )
        return document
    }

    private companion object {
        const val DOCUMENT_RESOURCE_TYPE = "DOCUMENT"
        const val SUBMIT_ACTION = "document:submit"
        const val REJECT_ACTION = "document:reject"
        const val REVISE_ACTION = "document:revise"
    }
}

class DocumentNotFoundException(documentId: Identifier) :
    NoSuchElementException("Document ${documentId.value} was not found in the current tenant.")
