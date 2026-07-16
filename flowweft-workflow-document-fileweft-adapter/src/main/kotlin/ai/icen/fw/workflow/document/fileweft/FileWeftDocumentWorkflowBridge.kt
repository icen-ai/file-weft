package ai.icen.fw.workflow.document.fileweft

import ai.icen.fw.application.document.DocumentCommandService
import ai.icen.fw.application.document.DocumentDetailView
import ai.icen.fw.application.document.DocumentQueryService
import ai.icen.fw.application.security.ApplicationAuthorizationException
import ai.icen.fw.application.transaction.ApplicationTransactionOutcomeUnknownException
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.domain.document.InvalidLifecycleTransitionException
import ai.icen.fw.domain.document.LifecycleState
import ai.icen.fw.spi.authorization.AuthorizationAction
import ai.icen.fw.spi.authorization.AuthorizationEnvironment
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.authorization.AuthorizationRequest
import ai.icen.fw.spi.authorization.AuthorizationResource
import ai.icen.fw.spi.authorization.AuthorizationSubject
import ai.icen.fw.spi.identity.UserIdentity
import ai.icen.fw.spi.identity.UserRealmProvider
import ai.icen.fw.spi.tenant.TenantProvider
import ai.icen.fw.workflow.api.WorkflowSubjectRef
import ai.icen.fw.workflow.api.WorkflowSubjectSnapshot
import ai.icen.fw.workflow.document.DocumentWorkflowAction
import ai.icen.fw.workflow.document.DocumentWorkflowAuthorizationApplicationPort
import ai.icen.fw.workflow.document.DocumentWorkflowAuthorizationDecision
import ai.icen.fw.workflow.document.DocumentWorkflowAuthorizationRequest
import ai.icen.fw.workflow.document.DocumentWorkflowAuthorizationStatus
import ai.icen.fw.workflow.document.DocumentWorkflowDocumentApplicationPort
import ai.icen.fw.workflow.document.DocumentWorkflowDocumentMutationAction
import ai.icen.fw.workflow.document.DocumentWorkflowDocumentMutationRequest
import ai.icen.fw.workflow.document.DocumentWorkflowDocumentMutationResult
import ai.icen.fw.workflow.document.DocumentWorkflowLifecycle
import ai.icen.fw.workflow.document.DocumentWorkflowPortOutcome
import ai.icen.fw.workflow.document.DocumentWorkflowSubjectApplicationPort
import ai.icen.fw.workflow.document.DocumentWorkflowSubjectRecord
import ai.icen.fw.workflow.document.DocumentWorkflowSubjectResolveRequest
import ai.icen.fw.workflow.runtime.WorkflowTrustedCallContext
import java.util.Collections
import java.util.LinkedHashMap
import java.util.Locale

/**
 * Optional host bridge between FileWeft's existing application boundary and the generic Workflow
 * document adapter. It never receives a repository, storage adapter, legacy review service, raw
 * tenant parameter, or domain mutation callback.
 *
 * Subject evidence is a SHA-256 digest of FileWeft's immutable public version projection. It is not
 * falsely described as a byte-content hash: the digest domain explicitly includes `projection-v1`.
 * Hosts that need a storage-attested content hash should expose that value through a future
 * authorized FileWeft application projection, without giving this bridge object-storage access.
 *
 * Existing [DocumentCommandService.submit] is used for both initial and replacement-version
 * submission. Its locked lifecycle transition prevents duplicate effects. Because that old public
 * method has no durable idempotency receipt, an already-pending document is reported as outcome
 * unknown instead of being falsely claimed as a replay. Opening a revision draft is delegated only
 * to [FileWeftDocumentRevisionCycleApplicationFacade] and is unsupported by default.
 */
class FileWeftDocumentWorkflowBridge @JvmOverloads constructor(
    private val tenantProvider: TenantProvider,
    private val userRealmProvider: UserRealmProvider,
    private val authorizationProvider: AuthorizationProvider,
    private val documentQueries: DocumentQueryService,
    private val documentCommands: DocumentCommandService,
    private val revisionCycles: FileWeftDocumentRevisionCycleApplicationFacade =
        FileWeftDocumentRevisionCycleApplicationFacade.unsupported(),
) : DocumentWorkflowSubjectApplicationPort,
    DocumentWorkflowAuthorizationApplicationPort,
    DocumentWorkflowDocumentApplicationPort {

    override fun resolve(request: DocumentWorkflowSubjectResolveRequest): DocumentWorkflowSubjectRecord? {
        if (request.subject.type != DOCUMENT_SUBJECT_TYPE) return null
        val security = currentSecurity(request.callContext) ?: return null
        val detail = queryVisibleDocument(request.callContext, request.subject) ?: return null
        currentSecurity(request.callContext)?.takeIf { it.samePrincipal(security) } ?: return null
        val snapshot = snapshot(detail) ?: return null
        return DocumentWorkflowSubjectRecord.of(
            request.callContext.tenantId,
            request.callContext.actor,
            snapshot,
            lifecycle(detail.document.lifecycleState),
            authorityRevision(request.callContext),
            request.evaluatedAtEpochMilli,
        )
    }

    override fun authorize(request: DocumentWorkflowAuthorizationRequest): DocumentWorkflowAuthorizationDecision {
        val security = currentSecurity(request.callContext)
        val action = fileWeftAction(request.action)
        var allowed = false
        if (security != null && action != null && request.subject.ref.type == DOCUMENT_SUBJECT_TYPE) {
            val detail = queryVisibleDocument(request.callContext, request.subject.ref)
            val current = detail?.let(::snapshot)
            val refreshed = currentSecurity(request.callContext)
            if (current == request.subject && refreshed?.samePrincipal(security) == true) {
                allowed = authorizeCurrent(
                    refreshed,
                    request.subject.ref,
                    action,
                    request.callContext,
                    request.requestDigest,
                    request.phase.code,
                    request.selection.selectionDigest,
                )
            }
        }
        return authorizationDecision(request, allowed)
    }

    override fun mutate(request: DocumentWorkflowDocumentMutationRequest): DocumentWorkflowDocumentMutationResult {
        val semanticAction = semanticAction(request.action)
            ?: return rejected(UNSUPPORTED_ACTION)
        val security = currentSecurity(request.callContext)
            ?: return rejected(TRUSTED_CONTEXT_MISMATCH)
        val initial = queryVisibleDocument(request.callContext, request.subject.ref)
            ?: return rejected(SUBJECT_NOT_FOUND)
        val initialSnapshot = snapshot(initial) ?: return rejected(SUBJECT_EVIDENCE_UNAVAILABLE)
        if (initialSnapshot != request.subject) return rejected(SUBJECT_DRIFT)
        val refreshed = currentSecurity(request.callContext)
            ?.takeIf { it.samePrincipal(security) }
            ?: return rejected(TRUSTED_CONTEXT_MISMATCH)
        val fileWeftAction = fileWeftAction(semanticAction)
            ?: return rejected(UNSUPPORTED_ACTION)
        if (!authorizeCurrent(
                refreshed,
                request.subject.ref,
                fileWeftAction,
                request.callContext,
                request.requestDigest,
                MUTATION_PHASE,
                request.selection.selectionDigest,
            )
        ) return rejected(AUTHORIZATION_DENIED)

        return when (request.action) {
            DocumentWorkflowDocumentMutationAction.SUBMIT_FOR_REVIEW,
            DocumentWorkflowDocumentMutationAction.SUBMIT_REVISION_FOR_REVIEW ->
                submitForReview(request, initial)
            DocumentWorkflowDocumentMutationAction.OPEN_REVISION_DRAFT ->
                openRevisionDraft(request, refreshed, initial)
            else -> rejected(UNSUPPORTED_ACTION)
        }
    }

    private fun submitForReview(
        request: DocumentWorkflowDocumentMutationRequest,
        initial: DocumentDetailView,
    ): DocumentWorkflowDocumentMutationResult {
        if (initial.document.lifecycleState == LifecycleState.PENDING_REVIEW) {
            return unknown(IDEMPOTENCY_EVIDENCE_UNAVAILABLE)
        }
        if (initial.document.lifecycleState != LifecycleState.DRAFT) return rejected(LIFECYCLE_DRIFT)
        val documentId = initial.document.id
        try {
            documentCommands.submit(documentId)
        } catch (_: ApplicationTransactionOutcomeUnknownException) {
            return unknown(MUTATION_OUTCOME_UNKNOWN)
        } catch (_: ApplicationAuthorizationException) {
            return rejected(AUTHORIZATION_DENIED)
        } catch (_: InvalidLifecycleTransitionException) {
            return rejected(LIFECYCLE_DRIFT)
        } catch (_: NoSuchElementException) {
            return rejected(SUBJECT_NOT_FOUND)
        } catch (_: IllegalArgumentException) {
            return rejected(MUTATION_REJECTED)
        } catch (_: RuntimeException) {
            return unknown(MUTATION_OUTCOME_UNKNOWN)
        }

        val current = try {
            queryVisibleDocument(request.callContext, request.subject.ref)
        } catch (_: RuntimeException) {
            null
        } ?: return unknown(MUTATION_RECEIPT_UNAVAILABLE)
        val currentSnapshot = snapshot(current) ?: return unknown(MUTATION_RECEIPT_UNAVAILABLE)
        if (current.document.lifecycleState != LifecycleState.PENDING_REVIEW ||
            currentSnapshot != request.subject || current.document.id != documentId
        ) return unknown(MUTATION_RECEIPT_DRIFT)
        if (currentSecurity(request.callContext) == null) return unknown(TRUSTED_CONTEXT_DRIFT)

        return DocumentWorkflowDocumentMutationResult.success(
            DocumentWorkflowPortOutcome.APPLIED,
            currentSnapshot,
            receiptDigest(request, currentSnapshot, null),
        )
    }

    private fun openRevisionDraft(
        request: DocumentWorkflowDocumentMutationRequest,
        security: CurrentSecurity,
        initial: DocumentDetailView,
    ): DocumentWorkflowDocumentMutationResult {
        if (initial.document.lifecycleState != LifecycleState.PENDING_REVIEW) {
            return rejected(LIFECYCLE_DRIFT)
        }
        val hostResult = try {
            revisionCycles.openRevisionDraft(
                FileWeftOpenDocumentRevisionDraftCommand.of(
                    security.tenantId,
                    security.user.id,
                    initial.document.id,
                    request.subject,
                    request.instanceId,
                    request.cycleNumber,
                    request.idempotencyKey,
                    request.logicalRequestDigest,
                    request.authorizationDecisionDigest,
                    request.reasonDigest,
                    request.selection.selectionDigest,
                    request.executedAtEpochMilli,
                ),
            )
        } catch (_: RuntimeException) {
            return unknown(MUTATION_OUTCOME_UNKNOWN)
        }
        when (hostResult.outcome) {
            DocumentWorkflowPortOutcome.REJECTED ->
                return rejected(hostResult.failureCode ?: MUTATION_REJECTED)
            DocumentWorkflowPortOutcome.OUTCOME_UNKNOWN ->
                return unknown(hostResult.failureCode ?: MUTATION_OUTCOME_UNKNOWN)
        }
        if (hostResult.retainedSubject != request.subject || hostResult.receiptDigest == null) {
            return unknown(MUTATION_RECEIPT_DRIFT)
        }
        val current = try {
            queryVisibleDocument(request.callContext, request.subject.ref)
        } catch (_: RuntimeException) {
            null
        } ?: return unknown(MUTATION_RECEIPT_UNAVAILABLE)
        val currentSnapshot = snapshot(current) ?: return unknown(MUTATION_RECEIPT_UNAVAILABLE)
        if (current.document.lifecycleState != LifecycleState.DRAFT || currentSnapshot != request.subject) {
            return unknown(MUTATION_RECEIPT_DRIFT)
        }
        if (currentSecurity(request.callContext)?.samePrincipal(security) != true) {
            return unknown(TRUSTED_CONTEXT_DRIFT)
        }
        return DocumentWorkflowDocumentMutationResult.success(
            hostResult.outcome,
            currentSnapshot,
            receiptDigest(request, currentSnapshot, hostResult.receiptDigest),
        )
    }

    private fun queryVisibleDocument(
        context: WorkflowTrustedCallContext,
        subject: WorkflowSubjectRef,
    ): DocumentDetailView? {
        if (subject.type != DOCUMENT_SUBJECT_TYPE || currentSecurity(context) == null) return null
        return try {
            documentQueries.detail(Identifier(subject.id))
        } catch (_: ApplicationAuthorizationException) {
            null
        } catch (_: NoSuchElementException) {
            null
        }
    }

    private fun snapshot(detail: DocumentDetailView): WorkflowSubjectSnapshot? {
        val currentVersionId = detail.document.currentVersionId ?: return null
        val currentVersion = detail.versions.singleOrNull { it.id == currentVersionId } ?: return null
        val digest = FileWeftDocumentWorkflowSupport.sha256(
            SUBJECT_EVIDENCE_DOMAIN,
            detail.document.id.value,
            currentVersion.id.value,
            currentVersion.versionNumber,
            currentVersion.fileName,
            currentVersion.contentLength.toString(),
            currentVersion.contentType,
            currentVersion.createdTime.toString(),
            currentVersion.updatedTime.toString(),
        )
        return WorkflowSubjectSnapshot.of(
            WorkflowSubjectRef.of(DOCUMENT_SUBJECT_TYPE, detail.document.id.value),
            currentVersion.id.value,
            digest,
        )
    }

    private fun currentSecurity(context: WorkflowTrustedCallContext): CurrentSecurity? {
        val tenant = try {
            tenantProvider.currentTenant().tenantId
        } catch (_: RuntimeException) {
            return null
        }
        val user = try {
            userRealmProvider.currentUser()
        } catch (_: RuntimeException) {
            return null
        } ?: return null
        if (context.actor.type != USER_PRINCIPAL_TYPE ||
            context.tenantId != tenant.value || context.actor.id != user.id.value
        ) return null
        return CurrentSecurity(tenant, snapshotUser(user))
    }

    private fun authorizeCurrent(
        security: CurrentSecurity,
        subject: WorkflowSubjectRef,
        action: String,
        context: WorkflowTrustedCallContext,
        exactRequestDigest: String,
        phase: String,
        selectionDigest: String,
    ): Boolean {
        val decision = authorizationProvider.authorize(
            AuthorizationRequest(
                AuthorizationSubject(
                    security.user.id,
                    FILEWEFT_USER_TYPE,
                    security.user.attributes,
                ),
                AuthorizationResource(
                    Identifier(subject.id),
                    FILEWEFT_DOCUMENT_TYPE,
                    security.tenantId,
                    mapOf(
                        "flowweft.workflow.selectionDigest" to selectionDigest,
                    ),
                ),
                AuthorizationAction(action),
                AuthorizationEnvironment(
                    mapOf(
                        "flowweft.workflow.phase" to phase,
                        "flowweft.workflow.requestDigest" to exactRequestDigest,
                        "flowweft.workflow.authorityContextDigest" to context.authorityContextDigest,
                    ),
                ),
            ),
        )
        return decision.allowed && currentSecurity(context)?.samePrincipal(security) == true
    }

    private fun authorizationDecision(
        request: DocumentWorkflowAuthorizationRequest,
        allowed: Boolean,
    ): DocumentWorkflowAuthorizationDecision = DocumentWorkflowAuthorizationDecision.of(
        "fileweft-${request.phase.code}-${request.requestDigest.substring(0, 32)}",
        request.callContext.tenantId,
        request.callContext.actor,
        request.requestDigest,
        if (allowed) {
            DocumentWorkflowAuthorizationStatus.AUTHORIZED
        } else {
            DocumentWorkflowAuthorizationStatus.DENIED
        },
        authorityRevision(request.callContext),
        FileWeftDocumentWorkflowSupport.sha256(
            AUTHORITY_EVIDENCE_DOMAIN,
            request.callContext.contextDigest,
            request.requestDigest,
            request.phase.code,
            request.action.code,
            allowed.toString(),
        ),
        request.evaluatedAtEpochMilli,
        request.evaluatedAtEpochMilli,
    )

    private fun authorityRevision(context: WorkflowTrustedCallContext): String =
        "fileweft-context-${context.authorityContextDigest}"

    private fun lifecycle(state: LifecycleState): DocumentWorkflowLifecycle = when (state) {
        LifecycleState.DRAFT -> DocumentWorkflowLifecycle.DRAFT
        LifecycleState.PENDING_REVIEW -> DocumentWorkflowLifecycle.PENDING_REVIEW
        else -> DocumentWorkflowLifecycle.of(
            "fileweft-${state.name.lowercase(Locale.ROOT).replace('_', '-')}"
        )
    }

    private fun semanticAction(action: DocumentWorkflowDocumentMutationAction): DocumentWorkflowAction? = when (action) {
        DocumentWorkflowDocumentMutationAction.SUBMIT_FOR_REVIEW -> DocumentWorkflowAction.SUBMIT
        DocumentWorkflowDocumentMutationAction.OPEN_REVISION_DRAFT ->
            DocumentWorkflowAction.REQUEST_SUBJECT_REVISION
        DocumentWorkflowDocumentMutationAction.SUBMIT_REVISION_FOR_REVIEW ->
            DocumentWorkflowAction.RESUME_SUBJECT_REVISION
        else -> null
    }

    private fun fileWeftAction(action: DocumentWorkflowAction): String? = when (action) {
        DocumentWorkflowAction.SUBMIT -> DOCUMENT_SUBMIT_ACTION
        DocumentWorkflowAction.REQUEST_SUBJECT_REVISION -> DOCUMENT_REVISE_ACTION
        DocumentWorkflowAction.RESUME_SUBJECT_REVISION -> DOCUMENT_SUBMIT_ACTION
        else -> null
    }

    private fun receiptDigest(
        request: DocumentWorkflowDocumentMutationRequest,
        snapshot: WorkflowSubjectSnapshot,
        hostReceiptDigest: String?,
    ): String = FileWeftDocumentWorkflowSupport.sha256(
        MUTATION_RECEIPT_DOMAIN,
        request.requestDigest,
        request.action.code,
        snapshot.ref.type,
        snapshot.ref.id,
        snapshot.revision,
        snapshot.digest,
        hostReceiptDigest,
    )

    private fun rejected(code: String): DocumentWorkflowDocumentMutationResult =
        DocumentWorkflowDocumentMutationResult.failure(DocumentWorkflowPortOutcome.REJECTED, code)

    private fun unknown(code: String): DocumentWorkflowDocumentMutationResult =
        DocumentWorkflowDocumentMutationResult.failure(DocumentWorkflowPortOutcome.OUTCOME_UNKNOWN, code)

    private fun snapshotUser(user: UserIdentity): UserIdentity = UserIdentity(
        user.id,
        user.displayName,
        Collections.unmodifiableMap(LinkedHashMap(user.attributes)),
    )

    private class CurrentSecurity(
        val tenantId: Identifier,
        val user: UserIdentity,
    ) {
        fun samePrincipal(other: CurrentSecurity): Boolean =
            tenantId == other.tenantId && user.id == other.user.id
    }

    private companion object {
        const val DOCUMENT_SUBJECT_TYPE = "document"
        const val USER_PRINCIPAL_TYPE = "user"
        const val FILEWEFT_USER_TYPE = "USER"
        const val FILEWEFT_DOCUMENT_TYPE = "DOCUMENT"
        const val DOCUMENT_SUBMIT_ACTION = "document:submit"
        const val DOCUMENT_REVISE_ACTION = "document:revise"
        const val MUTATION_PHASE = "host-application-mutation"
        const val SUBJECT_EVIDENCE_DOMAIN = "flowweft-document-fileweft-projection-v1"
        const val AUTHORITY_EVIDENCE_DOMAIN = "flowweft-document-fileweft-authority-v1"
        const val MUTATION_RECEIPT_DOMAIN = "flowweft-document-fileweft-mutation-receipt-v1"
        const val TRUSTED_CONTEXT_MISMATCH = "trusted-context-mismatch"
        const val TRUSTED_CONTEXT_DRIFT = "trusted-context-drift"
        const val SUBJECT_NOT_FOUND = "subject-not-found"
        const val SUBJECT_DRIFT = "subject-drift"
        const val SUBJECT_EVIDENCE_UNAVAILABLE = "subject-evidence-unavailable"
        const val LIFECYCLE_DRIFT = "lifecycle-drift"
        const val AUTHORIZATION_DENIED = "authorization-denied"
        const val UNSUPPORTED_ACTION = "unsupported-action"
        const val IDEMPOTENCY_EVIDENCE_UNAVAILABLE = "idempotency-evidence-unavailable"
        const val MUTATION_REJECTED = "mutation-rejected"
        const val MUTATION_OUTCOME_UNKNOWN = "mutation-outcome-unknown"
        const val MUTATION_RECEIPT_UNAVAILABLE = "mutation-receipt-unavailable"
        const val MUTATION_RECEIPT_DRIFT = "mutation-receipt-drift"
    }
}
