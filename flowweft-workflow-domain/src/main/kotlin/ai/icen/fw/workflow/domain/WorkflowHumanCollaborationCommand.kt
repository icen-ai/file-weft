package ai.icen.fw.workflow.domain

import ai.icen.fw.workflow.api.WorkflowHumanCollaborationAction
import ai.icen.fw.workflow.api.WorkflowHumanTaskEvidenceBinding
import ai.icen.fw.workflow.api.WorkflowPrincipalRef

/** Optimistic, one-use receipt-bound claim/delegation/transfer command. */
class WorkflowHumanCollaborationCommand private constructor(
    val context: WorkflowCommandContext,
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
    expectedWorkItemVersion: Long,
    executionNonce: String,
    val authorizationReceipt: WorkflowHumanCollaborationAuthorizationReceipt,
) {
    val code: WorkflowCommandCode = WorkflowCommandCode.COLLABORATE_HUMAN_TASK
    val workItemId: String = text(workItemId, "work item")
    val nodeId: String = WorkflowDomainSupport.requireCode(nodeId, "Workflow collaboration node id is invalid.")
    val policyDigest: String = sha(policyDigest, "policy")
    val activeRuleDigest: String = sha(activeRuleDigest, "active rule")
    val activationDigest: String = sha(activationDigest, "activation")
    val expectedWorkItemVersion: Long = WorkflowDomainSupport.requireVersion(
        expectedWorkItemVersion,
        "Workflow collaboration expected work-item version is invalid.",
    )
    val executionNonce: String = text(executionNonce, "execution nonce")
    val authorizationRequestDigest: String = authorizationRequestDigest(
        this.workItemId,
        this.nodeId,
        this.policyDigest,
        evidenceBinding,
        activeRuleIndex,
        this.activeRuleDigest,
        this.activationDigest,
        action,
        actor,
        target,
        this.expectedWorkItemVersion,
        this.executionNonce,
    )
    val commandDigest: String

    init {
        require(activeRuleIndex >= 0) { "Workflow collaboration active rule index is invalid." }
        require((action == WorkflowHumanCollaborationAction.DELEGATE ||
            action == WorkflowHumanCollaborationAction.TRANSFER ||
            action == WorkflowHumanCollaborationAction.ADD_SIGN ||
            action == WorkflowHumanCollaborationAction.RETURN) == (target != null)
        ) { "Workflow collaboration target binding is invalid." }
        require(authorizationReceipt.workItemId == this.workItemId &&
            authorizationReceipt.nodeId == this.nodeId &&
            authorizationReceipt.policyDigest == this.policyDigest &&
            authorizationReceipt.evidenceBinding == evidenceBinding &&
            authorizationReceipt.activeRuleIndex == activeRuleIndex &&
            authorizationReceipt.activeRuleDigest == this.activeRuleDigest &&
            authorizationReceipt.activationDigest == this.activationDigest &&
            authorizationReceipt.action == action &&
            authorizationReceipt.actor == actor &&
            authorizationReceipt.target == target &&
            authorizationReceipt.expectedWorkItemVersion == this.expectedWorkItemVersion &&
            authorizationReceipt.executionNonce == this.executionNonce &&
            authorizationReceipt.authorizationRequestDigest == authorizationRequestDigest
        ) { "Workflow collaboration authorization receipt does not bind the command." }
        commandDigest = WorkflowDomainSupport.digest("flowweft-workflow-domain-human-collaboration-command-v1")
            .text(code.code)
            .text(context.inputDigest)
            .text(authorizationRequestDigest)
            .text(authorizationReceipt.receiptDigest)
            .finish()
    }

    override fun toString(): String = "WorkflowHumanCollaborationCommand(<redacted>)"

    companion object {
        @JvmStatic
        fun authorizationRequestDigest(
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
            expectedWorkItemVersion: Long,
            executionNonce: String,
        ): String {
            require(activeRuleIndex >= 0) { "Workflow collaboration active rule index is invalid." }
            return WorkflowDomainSupport.digest(
                "flowweft-workflow-domain-human-collaboration-authorization-request-v1",
            )
                .text(text(workItemId, "work item"))
                .text(WorkflowDomainSupport.requireCode(nodeId, "Workflow collaboration node id is invalid."))
                .text(sha(policyDigest, "policy"))
                .text(evidenceBinding.contentDigest)
                .integer(activeRuleIndex)
                .text(sha(activeRuleDigest, "active rule"))
                .text(sha(activationDigest, "activation"))
                .text(action.code)
                .text(actor.type)
                .text(actor.id)
                .booleanValue(target != null)
                .also { writer -> target?.let { writer.text(it.type).text(it.id) } }
                .longValue(WorkflowDomainSupport.requireVersion(
                    expectedWorkItemVersion,
                    "Workflow collaboration expected work-item version is invalid.",
                ))
                .text(text(executionNonce, "execution nonce"))
                .finish()
        }

        @JvmStatic
        fun of(
            context: WorkflowCommandContext,
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
            expectedWorkItemVersion: Long,
            executionNonce: String,
            authorizationReceipt: WorkflowHumanCollaborationAuthorizationReceipt,
        ): WorkflowHumanCollaborationCommand = WorkflowHumanCollaborationCommand(
            context,
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
            expectedWorkItemVersion,
            executionNonce,
            authorizationReceipt,
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
    }
}
