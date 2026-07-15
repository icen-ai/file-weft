package ai.icen.fw.workflow.runtime

import ai.icen.fw.workflow.api.WorkflowDefinitionRef
import ai.icen.fw.workflow.api.WorkflowHumanCollaborationAction
import ai.icen.fw.workflow.api.WorkflowHumanTaskEvidenceBinding
import ai.icen.fw.workflow.api.WorkflowPrincipalRef
import ai.icen.fw.workflow.api.WorkflowSubjectSnapshot
import ai.icen.fw.workflow.domain.WorkflowHumanCollaborationAuthorizationReceipt
import ai.icen.fw.workflow.domain.WorkflowHumanWorkItemState
import ai.icen.fw.workflow.domain.WorkflowInstanceState

/** Exact optimistic collaboration request constructed from a trusted call context. */
class WorkflowRuntimeHumanCollaborationRequest private constructor(
    val callContext: WorkflowTrustedCallContext,
    val options: WorkflowRuntimeCommandOptions,
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
    val collaborationAction: WorkflowHumanCollaborationAction,
    val target: WorkflowPrincipalRef?,
    expectedWorkItemVersion: Long,
    executionNonce: String,
) {
    val action: WorkflowRuntimeAction = runtimeAction(collaborationAction)
    val instanceId: String = id(instanceId, "instance")
    val definitionId: String = id(definitionId, "definition")
    val workItemId: String = id(workItemId, "work item")
    val nodeId: String = WorkflowRuntimeSupport.code(nodeId, "Workflow collaboration node id is invalid.")
    val policyDigest: String = sha(policyDigest, "policy")
    val activeRuleDigest: String = sha(activeRuleDigest, "active rule")
    val activationDigest: String = sha(activationDigest, "activation")
    val expectedWorkItemVersion: Long = WorkflowRuntimeSupport.nonNegative(
        expectedWorkItemVersion,
        "Workflow collaboration expected work-item version is invalid.",
    )
    val executionNonce: String = id(executionNonce, "execution nonce")
    val requestDigest: String

    init {
        require(activeRuleIndex >= 0) { "Workflow collaboration active rule index is invalid." }
        require((collaborationAction == WorkflowHumanCollaborationAction.DELEGATE ||
            collaborationAction == WorkflowHumanCollaborationAction.TRANSFER ||
            collaborationAction == WorkflowHumanCollaborationAction.ADD_SIGN ||
            collaborationAction == WorkflowHumanCollaborationAction.RETURN) == (target != null)
        ) { "Workflow collaboration target binding is invalid." }
        val writer = WorkflowRuntimeSupport.digest("flowweft-workflow-runtime-human-collaboration-request-v1")
            .text(action.code)
            .text(callContext.tenantId)
            .text(callContext.actor.type)
            .text(callContext.actor.id)
            .text(this.instanceId)
            .text(options.idempotencyKey)
            .longValue(options.expectedInstanceVersion)
            .integer(options.iterationBudget)
            .text(this.definitionId)
            .text(definitionRef.key)
            .text(definitionRef.version)
            .text(definitionRef.digest)
            .text(subject.ref.type)
            .text(subject.ref.id)
            .text(subject.revision)
            .text(subject.digest)
            .text(this.workItemId)
            .text(this.nodeId)
            .text(this.policyDigest)
            .text(evidenceBinding.contentDigest)
            .integer(activeRuleIndex)
            .text(this.activeRuleDigest)
            .text(this.activationDigest)
            .text(collaborationAction.code)
            .bool(target != null)
        target?.let { writer.text(it.type).text(it.id) }
        requestDigest = writer.longValue(this.expectedWorkItemVersion)
            .text(this.executionNonce)
            .finish()
    }

    override fun toString(): String = "WorkflowRuntimeHumanCollaborationRequest(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            callContext: WorkflowTrustedCallContext,
            options: WorkflowRuntimeCommandOptions,
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
            collaborationAction: WorkflowHumanCollaborationAction,
            target: WorkflowPrincipalRef?,
            expectedWorkItemVersion: Long,
            executionNonce: String,
        ): WorkflowRuntimeHumanCollaborationRequest = WorkflowRuntimeHumanCollaborationRequest(
            callContext,
            options,
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
            collaborationAction,
            target,
            expectedWorkItemVersion,
            executionNonce,
        )

        private fun runtimeAction(value: WorkflowHumanCollaborationAction): WorkflowRuntimeAction = when (value) {
            WorkflowHumanCollaborationAction.CLAIM -> WorkflowRuntimeAction.CLAIM_HUMAN_TASK
            WorkflowHumanCollaborationAction.UNCLAIM -> WorkflowRuntimeAction.UNCLAIM_HUMAN_TASK
            WorkflowHumanCollaborationAction.DELEGATE -> WorkflowRuntimeAction.DELEGATE_HUMAN_TASK
            WorkflowHumanCollaborationAction.TRANSFER -> WorkflowRuntimeAction.TRANSFER_HUMAN_TASK
            WorkflowHumanCollaborationAction.ADD_SIGN -> WorkflowRuntimeAction.ADD_SIGN_HUMAN_TASK
            WorkflowHumanCollaborationAction.RETURN -> WorkflowRuntimeAction.RETURN_HUMAN_TASK
            else -> throw IllegalArgumentException("Unknown workflow human collaboration action is unsupported.")
        }

        private fun id(value: String, label: String): String = WorkflowRuntimeSupport.text(
            value,
            WorkflowRuntimeSupport.MAX_ID_BYTES,
            "Workflow collaboration $label is invalid.",
        )

        private fun sha(value: String, label: String): String = WorkflowRuntimeSupport.sha256(
            value,
            "Workflow collaboration $label digest is invalid.",
        )
    }
}

/** Current-membership receipt question issued only for a fresh, exact command. */
class WorkflowRuntimeHumanCollaborationReceiptRequest private constructor(
    val callContext: WorkflowTrustedCallContext,
    val state: WorkflowInstanceState,
    val workItem: WorkflowHumanWorkItemState,
    val request: WorkflowRuntimeHumanCollaborationRequest,
    authorizationRequestDigest: String,
    evaluatedAt: Long,
) {
    val authorizationRequestDigest: String = WorkflowRuntimeSupport.sha256(
        authorizationRequestDigest,
        "Workflow collaboration authorization request digest is invalid.",
    )
    val evaluatedAt: Long = WorkflowRuntimeSupport.nonNegative(
        evaluatedAt,
        "Workflow collaboration authorization time is invalid.",
    )

    init {
        require(callContext.tenantId == state.tenantId && callContext.actor == request.callContext.actor &&
            state.instanceId == request.instanceId && workItem.workItemId == request.workItemId
        ) { "Workflow collaboration authorization context binding is invalid." }
    }

    override fun toString(): String = "WorkflowRuntimeHumanCollaborationReceiptRequest(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            callContext: WorkflowTrustedCallContext,
            state: WorkflowInstanceState,
            workItem: WorkflowHumanWorkItemState,
            request: WorkflowRuntimeHumanCollaborationRequest,
            authorizationRequestDigest: String,
            evaluatedAt: Long,
        ): WorkflowRuntimeHumanCollaborationReceiptRequest = WorkflowRuntimeHumanCollaborationReceiptRequest(
            callContext,
            state,
            workItem,
            request,
            authorizationRequestDigest,
            evaluatedAt,
        )
    }
}

/** Separate additive port so existing runtime authorization implementations remain compatible. */
interface WorkflowRuntimeHumanCollaborationAuthorizationPort {
    fun issueHumanCollaborationReceipt(
        request: WorkflowRuntimeHumanCollaborationReceiptRequest,
    ): WorkflowHumanCollaborationAuthorizationReceipt
}
