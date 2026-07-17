package ai.icen.fw.workflow.runtime

import ai.icen.fw.workflow.api.WorkflowDefinitionRef
import ai.icen.fw.workflow.api.WorkflowSubjectSnapshot
import ai.icen.fw.workflow.domain.WorkflowContinuationReceipt
import ai.icen.fw.workflow.domain.WorkflowEffectCompletionReceipt
import ai.icen.fw.workflow.domain.WorkflowHumanDecisionCode
import ai.icen.fw.workflow.domain.WorkflowInstanceControlAction
import ai.icen.fw.workflow.domain.WorkflowParticipantActivationReceipt

class WorkflowRuntimeStartRequest private constructor(
    val callContext: WorkflowTrustedCallContext,
    val options: WorkflowRuntimeCommandOptions,
    instanceId: String,
    definitionId: String,
    val definitionRef: WorkflowDefinitionRef,
    val subject: WorkflowSubjectSnapshot,
) {
    val action: WorkflowRuntimeAction = WorkflowRuntimeAction.START
    val instanceId: String = id(instanceId, "instance")
    val definitionId: String = id(definitionId, "definition")
    val requestDigest: String

    init {
        require(options.expectedInstanceVersion == 0L) { "Workflow starts require expected version zero." }
        requestDigest = baseDigest(action, callContext, options, this.instanceId)
            .text(this.definitionId)
            .text(definitionRef.key)
            .text(definitionRef.version)
            .text(definitionRef.digest)
            .text(subject.ref.type)
            .text(subject.ref.id)
            .text(subject.revision)
            .text(subject.digest)
            .finish()
    }

    override fun toString(): String = "WorkflowRuntimeStartRequest(<redacted>)"

    companion object {
        @JvmStatic fun of(
            callContext: WorkflowTrustedCallContext,
            options: WorkflowRuntimeCommandOptions,
            instanceId: String,
            definitionId: String,
            definitionRef: WorkflowDefinitionRef,
            subject: WorkflowSubjectSnapshot,
        ): WorkflowRuntimeStartRequest = WorkflowRuntimeStartRequest(
            callContext,
            options,
            instanceId,
            definitionId,
            definitionRef,
            subject,
        )
    }
}

class WorkflowRuntimeActivateHumanRuleRequest private constructor(
    val callContext: WorkflowTrustedCallContext,
    val options: WorkflowRuntimeCommandOptions,
    instanceId: String,
    workItemId: String,
    val receipt: WorkflowParticipantActivationReceipt,
    val deliveryLease: WorkflowEffectLease?,
) {
    val action: WorkflowRuntimeAction = WorkflowRuntimeAction.ACTIVATE_HUMAN_RULE
    val instanceId: String = id(instanceId, "instance")
    val workItemId: String = id(workItemId, "work item")
    val requestDigest: String = baseDigest(action, callContext, options, this.instanceId)
        .text(this.workItemId)
        .text(receipt.receiptDigest)
        .finish()

    init {
        require(receipt.instanceId == this.instanceId && receipt.workItemId == this.workItemId) {
            "Workflow participant receipt targets another runtime request."
        }
        require(deliveryLease == null ||
            deliveryLease.acquiredAt <= options.now && options.now < deliveryLease.expiresAt
        ) { "Workflow participant delivery lease is not valid at command time." }
    }

    override fun toString(): String = "WorkflowRuntimeActivateHumanRuleRequest(<redacted>)"

    companion object {
        @JvmStatic fun of(
            callContext: WorkflowTrustedCallContext,
            options: WorkflowRuntimeCommandOptions,
            instanceId: String,
            workItemId: String,
            receipt: WorkflowParticipantActivationReceipt,
        ): WorkflowRuntimeActivateHumanRuleRequest = WorkflowRuntimeActivateHumanRuleRequest(
            callContext,
            options,
            instanceId,
            workItemId,
            receipt,
            null,
        )

        /** Worker-only form that fences the final domain acknowledgement to the queue lease. */
        @JvmStatic fun fenced(
            callContext: WorkflowTrustedCallContext,
            options: WorkflowRuntimeCommandOptions,
            instanceId: String,
            workItemId: String,
            receipt: WorkflowParticipantActivationReceipt,
            deliveryLease: WorkflowEffectLease,
        ): WorkflowRuntimeActivateHumanRuleRequest = WorkflowRuntimeActivateHumanRuleRequest(
            callContext,
            options,
            instanceId,
            workItemId,
            receipt,
            deliveryLease,
        )
    }
}

class WorkflowRuntimeHumanDecisionRequest private constructor(
    val callContext: WorkflowTrustedCallContext,
    val options: WorkflowRuntimeCommandOptions,
    instanceId: String,
    workItemId: String,
    val decision: WorkflowHumanDecisionCode,
    expectedWorkItemVersion: Long,
) {
    val action: WorkflowRuntimeAction = WorkflowRuntimeAction.DECIDE_HUMAN_TASK
    val instanceId: String = id(instanceId, "instance")
    val workItemId: String = id(workItemId, "work item")
    val expectedWorkItemVersion: Long = WorkflowRuntimeSupport.nonNegative(
        expectedWorkItemVersion,
        "Workflow expected work-item version is invalid.",
    )
    val requestDigest: String = baseDigest(action, callContext, options, this.instanceId)
        .text(this.workItemId)
        .text(decision.code)
        .longValue(this.expectedWorkItemVersion)
        .finish()

    override fun toString(): String = "WorkflowRuntimeHumanDecisionRequest(<redacted>)"

    companion object {
        @JvmStatic fun of(
            callContext: WorkflowTrustedCallContext,
            options: WorkflowRuntimeCommandOptions,
            instanceId: String,
            workItemId: String,
            decision: WorkflowHumanDecisionCode,
            expectedWorkItemVersion: Long,
        ): WorkflowRuntimeHumanDecisionRequest = WorkflowRuntimeHumanDecisionRequest(
            callContext,
            options,
            instanceId,
            workItemId,
            decision,
            expectedWorkItemVersion,
        )
    }
}

class WorkflowRuntimeCompleteEffectRequest private constructor(
    val callContext: WorkflowTrustedCallContext,
    val options: WorkflowRuntimeCommandOptions,
    instanceId: String,
    val receipt: WorkflowEffectCompletionReceipt,
) {
    val action: WorkflowRuntimeAction = WorkflowRuntimeAction.COMPLETE_EFFECT
    val instanceId: String = id(instanceId, "instance")
    val requestDigest: String = baseDigest(action, callContext, options, this.instanceId)
        .text(receipt.receiptDigest)
        .finish()

    init {
        require(receipt.instanceId == this.instanceId) { "Workflow effect receipt targets another instance." }
    }

    override fun toString(): String = "WorkflowRuntimeCompleteEffectRequest(<redacted>)"

    companion object {
        @JvmStatic fun of(
            callContext: WorkflowTrustedCallContext,
            options: WorkflowRuntimeCommandOptions,
            instanceId: String,
            receipt: WorkflowEffectCompletionReceipt,
        ): WorkflowRuntimeCompleteEffectRequest = WorkflowRuntimeCompleteEffectRequest(
            callContext,
            options,
            instanceId,
            receipt,
        )
    }
}

class WorkflowRuntimeContinueRequest private constructor(
    val callContext: WorkflowTrustedCallContext,
    val options: WorkflowRuntimeCommandOptions,
    instanceId: String,
    val receipt: WorkflowContinuationReceipt,
) {
    val action: WorkflowRuntimeAction = WorkflowRuntimeAction.CONTINUE_EXECUTION
    val instanceId: String = id(instanceId, "instance")
    val requestDigest: String = baseDigest(action, callContext, options, this.instanceId)
        .text(receipt.receiptDigest)
        .finish()

    init {
        require(receipt.instanceId == this.instanceId) { "Workflow continuation receipt targets another instance." }
    }

    override fun toString(): String = "WorkflowRuntimeContinueRequest(<redacted>)"

    companion object {
        @JvmStatic fun of(
            callContext: WorkflowTrustedCallContext,
            options: WorkflowRuntimeCommandOptions,
            instanceId: String,
            receipt: WorkflowContinuationReceipt,
        ): WorkflowRuntimeContinueRequest = WorkflowRuntimeContinueRequest(
            callContext,
            options,
            instanceId,
            receipt,
        )
    }
}

/** One exact, tenant/principal/version-bound operational lifecycle request. */
class WorkflowRuntimeControlInstanceRequest private constructor(
    val callContext: WorkflowTrustedCallContext,
    val options: WorkflowRuntimeCommandOptions,
    instanceId: String,
    val controlAction: WorkflowInstanceControlAction,
    reasonDigest: String,
) {
    val action: WorkflowRuntimeAction = runtimeAction(controlAction)
    val instanceId: String = id(instanceId, "instance")
    val reasonDigest: String = WorkflowRuntimeSupport.sha256(
        reasonDigest,
        "Workflow runtime control reason digest is invalid.",
    )
    val requestDigest: String = baseDigest(action, callContext, options, this.instanceId)
        .text(controlAction.code)
        .text(this.reasonDigest)
        .finish()

    override fun toString(): String = "WorkflowRuntimeControlInstanceRequest(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            callContext: WorkflowTrustedCallContext,
            options: WorkflowRuntimeCommandOptions,
            instanceId: String,
            controlAction: WorkflowInstanceControlAction,
            reasonDigest: String,
        ): WorkflowRuntimeControlInstanceRequest = WorkflowRuntimeControlInstanceRequest(
            callContext,
            options,
            instanceId,
            controlAction,
            reasonDigest,
        )

        private fun runtimeAction(action: WorkflowInstanceControlAction): WorkflowRuntimeAction = when (action) {
            WorkflowInstanceControlAction.SUSPEND -> WorkflowRuntimeAction.SUSPEND_INSTANCE
            WorkflowInstanceControlAction.RESUME -> WorkflowRuntimeAction.RESUME_INSTANCE
            WorkflowInstanceControlAction.CANCEL -> WorkflowRuntimeAction.CANCEL_INSTANCE
            WorkflowInstanceControlAction.TERMINATE -> WorkflowRuntimeAction.TERMINATE_INSTANCE
            else -> throw IllegalArgumentException("Unknown workflow instance control action is unsupported.")
        }
    }
}

private fun baseDigest(
    action: WorkflowRuntimeAction,
    context: WorkflowTrustedCallContext,
    options: WorkflowRuntimeCommandOptions,
    instanceId: String,
): WorkflowRuntimeSupport.Digest = WorkflowRuntimeSupport.digest("flowweft-workflow-runtime-logical-request-v1")
    .text(action.code)
    .text(context.tenantId)
    .text(context.actor.type)
    .text(context.actor.id)
    .text(instanceId)
    .text(options.idempotencyKey)
    .longValue(options.expectedInstanceVersion)
    .integer(options.iterationBudget)

private fun id(value: String, label: String): String = WorkflowRuntimeSupport.text(
    value,
    WorkflowRuntimeSupport.MAX_ID_BYTES,
    "Workflow runtime $label id is invalid.",
)
