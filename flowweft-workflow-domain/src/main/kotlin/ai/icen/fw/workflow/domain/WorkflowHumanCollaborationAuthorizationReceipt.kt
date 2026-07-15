package ai.icen.fw.workflow.domain

import ai.icen.fw.workflow.api.WorkflowDefinitionRef
import ai.icen.fw.workflow.api.WorkflowHumanCollaborationAction
import ai.icen.fw.workflow.api.WorkflowHumanTaskEvidenceBinding
import ai.icen.fw.workflow.api.WorkflowPrincipalRef
import ai.icen.fw.workflow.api.WorkflowSubjectSnapshot

/**
 * One-use current authorization and candidate-membership evidence for a collaboration command.
 * The runtime obtains this only after generic action authorization and an exact aggregate read.
 */
class WorkflowHumanCollaborationAuthorizationReceipt private constructor(
    receiptId: String,
    tenantId: String,
    instanceId: String,
    definitionId: String,
    val definitionRef: WorkflowDefinitionRef,
    val subject: WorkflowSubjectSnapshot,
    workItemId: String,
    nodeId: String,
    policyDigest: String,
    val evidenceBinding: WorkflowHumanTaskEvidenceBinding,
    val activeRuleIndex: Int,
    activeRuleDigest: String,
    activationDigest: String,
    val action: WorkflowHumanCollaborationAction,
    val actor: WorkflowPrincipalRef,
    val target: WorkflowPrincipalRef?,
    val currentClaimOwner: WorkflowPrincipalRef?,
    val currentActiveDelegate: WorkflowPrincipalRef?,
    collaborationStateDigest: String,
    expectedWorkItemVersion: Long,
    executionNonce: String,
    authorizationRequestDigest: String,
    val status: WorkflowAuthorizationStatus,
    val actorCurrentlyEligible: Boolean,
    val targetCurrentlyEligible: Boolean,
    val privilegedUnclaim: Boolean,
    val separationOfDutiesSatisfied: Boolean,
    candidateRevision: String,
    candidateDigest: String,
    authorityRevision: String,
    authorityDigest: String,
    evaluatedAt: Long,
    validUntil: Long,
) {
    val receiptId: String = text(receiptId, "receipt")
    val tenantId: String = text(tenantId, "tenant")
    val instanceId: String = text(instanceId, "instance")
    val definitionId: String = text(definitionId, "definition")
    val workItemId: String = text(workItemId, "work item")
    val nodeId: String = WorkflowDomainSupport.requireCode(nodeId, "Workflow collaboration node id is invalid.")
    val policyDigest: String = sha(policyDigest, "policy")
    val activeRuleDigest: String = sha(activeRuleDigest, "active rule")
    val activationDigest: String = sha(activationDigest, "activation")
    val collaborationStateDigest: String = sha(collaborationStateDigest, "collaboration state")
    val expectedWorkItemVersion: Long = WorkflowDomainSupport.requireVersion(
        expectedWorkItemVersion,
        "Workflow collaboration expected work-item version is invalid.",
    )
    val executionNonce: String = text(executionNonce, "execution nonce")
    val authorizationRequestDigest: String = sha(authorizationRequestDigest, "authorization request")
    val candidateRevision: String = WorkflowDomainSupport.requireText(
        candidateRevision,
        WorkflowDomainSupport.MAX_REVISION_UTF8_BYTES,
        "Workflow collaboration candidate revision is invalid.",
    )
    val candidateDigest: String = sha(candidateDigest, "candidate")
    val authorityRevision: String = WorkflowDomainSupport.requireText(
        authorityRevision,
        WorkflowDomainSupport.MAX_REVISION_UTF8_BYTES,
        "Workflow collaboration authority revision is invalid.",
    )
    val authorityDigest: String = sha(authorityDigest, "authority")
    val evaluatedAt: Long = WorkflowDomainSupport.requireTime(
        evaluatedAt,
        "Workflow collaboration authorization time is invalid.",
    )
    val validUntil: Long = WorkflowDomainSupport.requireTime(
        validUntil,
        "Workflow collaboration authorization expiry is invalid.",
    )
    val receiptDigest: String

    init {
        require(activeRuleIndex >= 0) { "Workflow collaboration active rule index is invalid." }
        require(status == WorkflowAuthorizationStatus.AUTHORIZED || status == WorkflowAuthorizationStatus.DENIED) {
            "Unknown workflow collaboration authorization status is unsupported."
        }
        require(this.validUntil >= this.evaluatedAt) { "Workflow collaboration authorization window is invalid." }
        require((action == WorkflowHumanCollaborationAction.DELEGATE ||
            action == WorkflowHumanCollaborationAction.TRANSFER ||
            action == WorkflowHumanCollaborationAction.ADD_SIGN ||
            action == WorkflowHumanCollaborationAction.RETURN) == (target != null)
        ) { "Workflow collaboration target binding is invalid." }
        require(currentClaimOwner != null || currentActiveDelegate == null) {
            "Workflow collaboration assignment binding is invalid."
        }
        require(action == WorkflowHumanCollaborationAction.UNCLAIM || !privilegedUnclaim) {
            "Privileged collaboration evidence is valid only for unclaim."
        }
        val writer = WorkflowDomainSupport.digest(
            "flowweft-workflow-domain-human-collaboration-authorization-v1",
        )
            .text(this.receiptId)
            .text(this.tenantId)
            .text(this.instanceId)
            .text(this.definitionId)
            .definition(definitionRef)
            .subject(subject)
            .text(this.workItemId)
            .text(this.nodeId)
            .text(this.policyDigest)
            .text(evidenceBinding.contentDigest)
            .integer(activeRuleIndex)
            .text(this.activeRuleDigest)
            .text(this.activationDigest)
            .text(action.code)
            .principal(actor)
            .optionalPrincipal(target)
            .optionalPrincipal(currentClaimOwner)
            .optionalPrincipal(currentActiveDelegate)
            .text(this.collaborationStateDigest)
            .longValue(this.expectedWorkItemVersion)
            .text(this.executionNonce)
            .text(this.authorizationRequestDigest)
            .text(status.code)
            .booleanValue(actorCurrentlyEligible)
            .booleanValue(targetCurrentlyEligible)
            .booleanValue(privilegedUnclaim)
            .booleanValue(separationOfDutiesSatisfied)
            .text(this.candidateRevision)
            .text(this.candidateDigest)
            .text(this.authorityRevision)
            .text(this.authorityDigest)
            .longValue(this.evaluatedAt)
            .longValue(this.validUntil)
        receiptDigest = writer.finish()
    }

    override fun toString(): String = "WorkflowHumanCollaborationAuthorizationReceipt(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            receiptId: String,
            tenantId: String,
            instanceId: String,
            definitionId: String,
            definitionRef: WorkflowDefinitionRef,
            subject: WorkflowSubjectSnapshot,
            workItemId: String,
            nodeId: String,
            policyDigest: String,
            evidenceBinding: WorkflowHumanTaskEvidenceBinding,
            activeRuleIndex: Int,
            activeRuleDigest: String,
            activationDigest: String,
            action: WorkflowHumanCollaborationAction,
            actor: WorkflowPrincipalRef,
            target: WorkflowPrincipalRef?,
            currentClaimOwner: WorkflowPrincipalRef?,
            currentActiveDelegate: WorkflowPrincipalRef?,
            collaborationStateDigest: String,
            expectedWorkItemVersion: Long,
            executionNonce: String,
            authorizationRequestDigest: String,
            status: WorkflowAuthorizationStatus,
            actorCurrentlyEligible: Boolean,
            targetCurrentlyEligible: Boolean,
            privilegedUnclaim: Boolean,
            separationOfDutiesSatisfied: Boolean,
            candidateRevision: String,
            candidateDigest: String,
            authorityRevision: String,
            authorityDigest: String,
            evaluatedAt: Long,
            validUntil: Long,
        ): WorkflowHumanCollaborationAuthorizationReceipt = WorkflowHumanCollaborationAuthorizationReceipt(
            receiptId,
            tenantId,
            instanceId,
            definitionId,
            definitionRef,
            subject,
            workItemId,
            nodeId,
            policyDigest,
            evidenceBinding,
            activeRuleIndex,
            activeRuleDigest,
            activationDigest,
            action,
            actor,
            target,
            currentClaimOwner,
            currentActiveDelegate,
            collaborationStateDigest,
            expectedWorkItemVersion,
            executionNonce,
            authorizationRequestDigest,
            status,
            actorCurrentlyEligible,
            targetCurrentlyEligible,
            privilegedUnclaim,
            separationOfDutiesSatisfied,
            candidateRevision,
            candidateDigest,
            authorityRevision,
            authorityDigest,
            evaluatedAt,
            validUntil,
        )

        private fun text(value: String, label: String): String = WorkflowDomainSupport.requireText(
            value,
            WorkflowDomainSupport.MAX_ID_UTF8_BYTES,
            "Workflow collaboration $label is invalid.",
        )

        private fun sha(value: String, label: String): String = WorkflowDomainSupport.requireSha256(
            value,
            "Workflow collaboration $label digest is invalid.",
        )

        private fun WorkflowDomainSupport.DigestWriter.definition(value: WorkflowDefinitionRef) =
            text(value.key).text(value.version).text(value.digest)

        private fun WorkflowDomainSupport.DigestWriter.subject(value: WorkflowSubjectSnapshot) =
            text(value.ref.type).text(value.ref.id).text(value.revision).text(value.digest)

        private fun WorkflowDomainSupport.DigestWriter.principal(value: WorkflowPrincipalRef) =
            text(value.type).text(value.id)

        private fun WorkflowDomainSupport.DigestWriter.optionalPrincipal(value: WorkflowPrincipalRef?) =
            booleanValue(value != null).also { writer -> value?.let { writer.principal(it) } }
    }
}
