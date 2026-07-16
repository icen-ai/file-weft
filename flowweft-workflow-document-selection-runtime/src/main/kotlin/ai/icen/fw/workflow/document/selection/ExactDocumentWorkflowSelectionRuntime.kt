package ai.icen.fw.workflow.document.selection

import ai.icen.fw.workflow.document.DocumentWorkflowSelection
import ai.icen.fw.workflow.document.DocumentWorkflowSelectionApplicationPort
import ai.icen.fw.workflow.document.DocumentWorkflowSelectionRequest

/**
 * Provider-neutral, fail-closed exact document workflow selection.
 *
 * The runtime deliberately has no cache. Every invocation performs PREPARE authorization, resolves
 * current host facts, evaluates the pinned provider profile, performs COMMIT authorization, resolves
 * facts again, and re-reads the provider descriptor. This makes revocation, document revision,
 * metadata, authority, and provider-profile drift immediately visible. A future cache must key at
 * least tenant + document revision/digest + actor + authorization revision + fact authority revision
 * + provider descriptor and must be evicted on revocation; weakening that rule is not compatible.
 */
class ExactDocumentWorkflowSelectionRuntime @JvmOverloads constructor(
    private val factsPort: DocumentSelectionFactsPort,
    private val authorizationPort: DocumentSelectionAuthorizationPort,
    private val policyProvider: DocumentSelectionPolicyProvider? = null,
) : DocumentWorkflowSelectionApplicationPort {

    override fun select(request: DocumentWorkflowSelectionRequest): DocumentWorkflowSelection? {
        if (!validTrustedRequest(request)) return null
        val provider = policyProvider ?: return null
        val descriptor = try {
            provider.descriptor()
        } catch (_: RuntimeException) {
            return null
        }

        val prepareRequest = DocumentSelectionAuthorizationRequest.of(
            request,
            DocumentSelectionAuthorizationPhase.PREPARE,
        )
        val prepare = authorize(prepareRequest) ?: return null
        val prepareFactsRequest = DocumentSelectionFactsRequest.of(request, prepare.decisionDigest)
        val prepareFacts = resolveFacts(prepareFactsRequest) ?: return null

        val policyRequest = try {
            DocumentSelectionPolicyRequest.of(
                request,
                prepareFacts,
                descriptor,
                prepare.decisionDigest,
                prepare.authorityRevision,
            )
        } catch (_: RuntimeException) {
            return null
        }
        val policyResult = try {
            provider.select(policyRequest)
        } catch (_: RuntimeException) {
            return null
        }
        if (policyResult.status != DocumentSelectionPolicyStatus.SELECTED ||
            !policyResult.matches(policyRequest)
        ) return null
        val selected = policyResult.selection ?: return null
        if (selected != request.expectedSelection || !isPinned(selected)) return null
        val exactAuthorityRevision = try {
            DocumentSelectionAuthority.revision(
                descriptor,
                prepareFacts,
                policyResult.policyRevision,
                prepare.authorityRevision,
            )
        } catch (_: RuntimeException) {
            return null
        }
        if (selected.authorityRevision != exactAuthorityRevision) return null

        val commitRequest = DocumentSelectionAuthorizationRequest.of(
            request,
            DocumentSelectionAuthorizationPhase.COMMIT,
        )
        val commit = authorize(commitRequest) ?: return null
        if (commit.authorityRevision != prepare.authorityRevision) return null
        val commitFactsRequest = DocumentSelectionFactsRequest.of(request, commit.decisionDigest)
        val commitFacts = resolveFacts(commitFactsRequest) ?: return null
        if (commitFacts.contentDigest != prepareFacts.contentDigest) return null
        val currentDescriptor = try {
            provider.descriptor()
        } catch (_: RuntimeException) {
            return null
        }
        if (currentDescriptor != descriptor ||
            currentDescriptor.descriptorDigest != descriptor.descriptorDigest
        ) return null
        return selected
    }

    private fun authorize(
        request: DocumentSelectionAuthorizationRequest,
    ): DocumentSelectionAuthorizationDecision? {
        val decision = try {
            authorizationPort.authorize(request)
        } catch (_: RuntimeException) {
            return null
        }
        return decision.takeIf {
            it.status == DocumentSelectionAuthorizationStatus.AUTHORIZED && it.matches(request)
        }
    }

    private fun resolveFacts(request: DocumentSelectionFactsRequest): DocumentSelectionFacts? {
        val facts = try {
            factsPort.resolve(request)
        } catch (_: RuntimeException) {
            return null
        } ?: return null
        return facts.takeIf { it.matches(request) }
    }

    private fun validTrustedRequest(request: DocumentWorkflowSelectionRequest): Boolean =
        request.callContext.tenantId == request.subjectRecord.tenantId &&
            request.callContext.actor == request.subjectRecord.resolvedForActor &&
            request.subjectRecord.snapshot.ref.type == DOCUMENT_SUBJECT_TYPE &&
            request.subjectRecord.validUntilEpochMilli >= request.evaluatedAtEpochMilli

    private fun isPinned(selection: DocumentWorkflowSelection): Boolean =
        !selection.definitionRef.version.equals(LATEST, ignoreCase = true) &&
            !selection.templateRef.revision.equals(LATEST, ignoreCase = true) &&
            !selection.revisionPolicy.revision.equals(LATEST, ignoreCase = true)

    private companion object {
        const val DOCUMENT_SUBJECT_TYPE = "document"
        const val LATEST = "latest"
    }
}
