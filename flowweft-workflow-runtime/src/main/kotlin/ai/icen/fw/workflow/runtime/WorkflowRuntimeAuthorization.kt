package ai.icen.fw.workflow.runtime

import ai.icen.fw.workflow.api.WorkflowDefinitionRef
import ai.icen.fw.workflow.api.WorkflowPrincipalRef
import ai.icen.fw.workflow.api.WorkflowSubjectSnapshot
import ai.icen.fw.workflow.domain.WorkflowHumanDecisionAuthorizationReceipt
import ai.icen.fw.workflow.domain.WorkflowHumanDecisionCode
import ai.icen.fw.workflow.domain.WorkflowHumanWorkItemState
import ai.icen.fw.workflow.domain.WorkflowInstanceState

/** Exact authorization question asked before every fresh command and every replay. */
class WorkflowRuntimeAuthorizationRequest private constructor(
    val callContext: WorkflowTrustedCallContext,
    val action: WorkflowRuntimeAction,
    instanceId: String,
    definitionId: String?,
    val definitionRef: WorkflowDefinitionRef?,
    val subject: WorkflowSubjectSnapshot?,
    requestDigest: String,
    evaluatedAt: Long,
) {
    val instanceId: String = text(instanceId, "instance")
    val definitionId: String? = definitionId?.let { value -> text(value, "definition") }
    val requestDigest: String = WorkflowRuntimeSupport.sha256(
        requestDigest,
        "Workflow authorization request digest is invalid.",
    )
    val evaluatedAt: Long = WorkflowRuntimeSupport.nonNegative(
        evaluatedAt,
        "Workflow authorization request time is invalid.",
    )

    init {
        require((this.definitionId == null) == (definitionRef == null)) {
            "Workflow authorization definition binding is incomplete."
        }
    }

    override fun toString(): String = "WorkflowRuntimeAuthorizationRequest(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            callContext: WorkflowTrustedCallContext,
            action: WorkflowRuntimeAction,
            instanceId: String,
            definitionId: String?,
            definitionRef: WorkflowDefinitionRef?,
            subject: WorkflowSubjectSnapshot?,
            requestDigest: String,
            evaluatedAt: Long,
        ): WorkflowRuntimeAuthorizationRequest = WorkflowRuntimeAuthorizationRequest(
            callContext,
            action,
            instanceId,
            definitionId,
            definitionRef,
            subject,
            requestDigest,
            evaluatedAt,
        )

        private fun text(value: String, label: String): String = WorkflowRuntimeSupport.text(
            value,
            WorkflowRuntimeSupport.MAX_ID_BYTES,
            "Workflow authorization $label id is invalid.",
        )
    }
}

/** Provider result bound to the exact runtime request; it carries no mutation permission. */
class WorkflowRuntimeAuthorizationDecision private constructor(
    authorizationId: String,
    tenantId: String,
    val actor: WorkflowPrincipalRef,
    val action: WorkflowRuntimeAction,
    instanceId: String,
    requestDigest: String,
    val status: WorkflowRuntimeAuthorizationStatus,
    authorityRevision: String,
    authorityDigest: String,
    evaluatedAt: Long,
    validUntil: Long,
) {
    val authorizationId: String = text(authorizationId, "authorization")
    val tenantId: String = text(tenantId, "tenant")
    val instanceId: String = text(instanceId, "instance")
    val requestDigest: String = sha(requestDigest, "request")
    val authorityRevision: String = WorkflowRuntimeSupport.text(
        authorityRevision,
        WorkflowRuntimeSupport.MAX_TEXT_BYTES,
        "Workflow authority revision is invalid.",
    )
    val authorityDigest: String = sha(authorityDigest, "authority")
    val evaluatedAt: Long = WorkflowRuntimeSupport.nonNegative(
        evaluatedAt,
        "Workflow authorization evaluation time is invalid.",
    )
    val validUntil: Long = WorkflowRuntimeSupport.nonNegative(
        validUntil,
        "Workflow authorization expiry is invalid.",
    )

    init {
        require(status == WorkflowRuntimeAuthorizationStatus.AUTHORIZED ||
            status == WorkflowRuntimeAuthorizationStatus.DENIED
        ) { "Unknown workflow runtime authorization status is unsupported." }
        require(this.validUntil >= this.evaluatedAt) { "Workflow authorization window is invalid." }
    }

    fun matches(request: WorkflowRuntimeAuthorizationRequest, now: Long): Boolean =
        tenantId == request.callContext.tenantId &&
            actor == request.callContext.actor &&
            action == request.action &&
            instanceId == request.instanceId &&
            requestDigest == request.requestDigest &&
            evaluatedAt <= now && now <= validUntil

    override fun toString(): String = "WorkflowRuntimeAuthorizationDecision(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            authorizationId: String,
            tenantId: String,
            actor: WorkflowPrincipalRef,
            action: WorkflowRuntimeAction,
            instanceId: String,
            requestDigest: String,
            status: WorkflowRuntimeAuthorizationStatus,
            authorityRevision: String,
            authorityDigest: String,
            evaluatedAt: Long,
            validUntil: Long,
        ): WorkflowRuntimeAuthorizationDecision = WorkflowRuntimeAuthorizationDecision(
            authorizationId,
            tenantId,
            actor,
            action,
            instanceId,
            requestDigest,
            status,
            authorityRevision,
            authorityDigest,
            evaluatedAt,
            validUntil,
        )

        private fun text(value: String, label: String): String = WorkflowRuntimeSupport.text(
            value,
            WorkflowRuntimeSupport.MAX_ID_BYTES,
            "Workflow $label id is invalid.",
        )

        private fun sha(value: String, label: String): String = WorkflowRuntimeSupport.sha256(
            value,
            "Workflow $label digest is invalid.",
        )
    }
}

/** Fresh domain receipt request made only after replay/idempotency checks select a new command. */
class WorkflowRuntimeHumanDecisionReceiptRequest private constructor(
    val callContext: WorkflowTrustedCallContext,
    val state: WorkflowInstanceState,
    val workItem: WorkflowHumanWorkItemState,
    val actor: WorkflowPrincipalRef,
    val decision: WorkflowHumanDecisionCode,
    expectedWorkItemVersion: Long,
    authorizationRequestDigest: String,
    evaluatedAt: Long,
) {
    val expectedWorkItemVersion: Long = WorkflowRuntimeSupport.nonNegative(
        expectedWorkItemVersion,
        "Workflow expected work-item version is invalid.",
    )
    val authorizationRequestDigest: String = WorkflowRuntimeSupport.sha256(
        authorizationRequestDigest,
        "Workflow human authorization request digest is invalid.",
    )
    val evaluatedAt: Long = WorkflowRuntimeSupport.nonNegative(
        evaluatedAt,
        "Workflow human authorization time is invalid.",
    )

    init {
        require(callContext.tenantId == state.tenantId && actor == callContext.actor) {
            "Workflow human authorization context binding is invalid."
        }
        require(workItem.workItemId.isNotEmpty() && workItem.revision == this.expectedWorkItemVersion) {
            "Workflow human authorization work-item binding is invalid."
        }
    }

    override fun toString(): String = "WorkflowRuntimeHumanDecisionReceiptRequest(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            callContext: WorkflowTrustedCallContext,
            state: WorkflowInstanceState,
            workItem: WorkflowHumanWorkItemState,
            actor: WorkflowPrincipalRef,
            decision: WorkflowHumanDecisionCode,
            expectedWorkItemVersion: Long,
            authorizationRequestDigest: String,
            evaluatedAt: Long,
        ): WorkflowRuntimeHumanDecisionReceiptRequest = WorkflowRuntimeHumanDecisionReceiptRequest(
            callContext,
            state,
            workItem,
            actor,
            decision,
            expectedWorkItemVersion,
            authorizationRequestDigest,
            evaluatedAt,
        )
    }
}

interface WorkflowRuntimeAuthorizationPort {
    /** Called for every request, including an exact idempotent replay. */
    fun authorize(request: WorkflowRuntimeAuthorizationRequest): WorkflowRuntimeAuthorizationDecision

    /** Called only for a fresh human decision after generic authorization succeeds. */
    fun issueHumanDecisionReceipt(
        request: WorkflowRuntimeHumanDecisionReceiptRequest,
    ): WorkflowHumanDecisionAuthorizationReceipt
}
