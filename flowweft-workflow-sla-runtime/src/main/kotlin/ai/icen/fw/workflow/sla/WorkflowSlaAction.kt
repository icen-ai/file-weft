package ai.icen.fw.workflow.sla

import ai.icen.fw.workflow.api.WorkflowDefinitionRef
import ai.icen.fw.workflow.api.WorkflowSubjectSnapshot
import ai.icen.fw.workflow.runtime.WorkflowTrustedCallContext
import java.util.concurrent.CompletionStage

class WorkflowSlaActionOutcome private constructor(code: String) {
    val code: String = slaMachineCode(code, "action outcome")

    override fun equals(other: Any?): Boolean =
        this === other || other is WorkflowSlaActionOutcome && code == other.code

    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "WorkflowSlaActionOutcome(<redacted>)"

    companion object {
        @JvmField val SUCCEEDED = WorkflowSlaActionOutcome("succeeded")
        @JvmField val NOT_APPLIED_RETRYABLE = WorkflowSlaActionOutcome("not-applied-retryable")
        @JvmField val PERMANENT_FAILURE = WorkflowSlaActionOutcome("permanent-failure")
        @JvmField val SUPPRESSED = WorkflowSlaActionOutcome("suppressed")
        @JvmField val OUTCOME_UNKNOWN = WorkflowSlaActionOutcome("outcome-unknown")

        @JvmStatic
        fun of(code: String): WorkflowSlaActionOutcome = when (code) {
            SUCCEEDED.code -> SUCCEEDED
            NOT_APPLIED_RETRYABLE.code -> NOT_APPLIED_RETRYABLE
            PERMANENT_FAILURE.code -> PERMANENT_FAILURE
            SUPPRESSED.code -> SUPPRESSED
            OUTCOME_UNKNOWN.code -> OUTCOME_UNKNOWN
            else -> throw IllegalArgumentException("Unknown Workflow SLA action outcomes require typed support.")
        }
    }
}

/**
 * Minimal provider request. It contains stable workflow references but no form values, comment
 * text, recipient list or credentials. The provider resolves current recipients through host
 * application ports and MUST recheck task/activity/access immediately before a side effect.
 */
class WorkflowSlaActionRequest private constructor(
    val callContext: WorkflowTrustedCallContext,
    scheduleId: String,
    val definitionRef: WorkflowDefinitionRef,
    instanceId: String,
    workItemId: String,
    nodeId: String,
    val subject: WorkflowSubjectSnapshot,
    taskRevision: Long,
    taskDigest: String,
    policyDigest: String,
    val milestoneKind: WorkflowSlaMilestoneKind,
    val actionKind: WorkflowSlaActionKind,
    val actionProfile: WorkflowSlaActionProfile,
    val attempt: Int,
    authorizationEvidenceDigest: String,
    val requestedAt: Long,
    val deadline: Long,
) {
    val scheduleId: String = slaIdentifier(scheduleId, "action schedule id")
    val instanceId: String = slaIdentifier(instanceId, "action instance id")
    val workItemId: String = slaIdentifier(workItemId, "action work-item id")
    val nodeId: String = slaMachineCode(nodeId, "action node id")
    val taskRevision: Long = WorkflowSlaSupport.nonNegative(
        taskRevision,
        "Workflow SLA action task revision is invalid.",
    )
    val taskDigest: String = slaDigest(taskDigest, "action task")
    val policyDigest: String = slaDigest(policyDigest, "action policy")
    val authorizationEvidenceDigest: String = slaDigest(
        authorizationEvidenceDigest,
        "action authorization evidence",
    )
    val requestDigest: String

    init {
        require(attempt > 0 && requestedAt >= 0L && deadline > requestedAt) {
            "Workflow SLA action attempt or time window is invalid."
        }
        requestDigest = WorkflowSlaSupport.digest("flowweft-workflow-sla-action-request-v1")
            .text(callContext.contextDigest)
            .text(this.scheduleId)
            .text(definitionRef.key)
            .text(definitionRef.version)
            .text(definitionRef.digest)
            .text(this.instanceId)
            .text(this.workItemId)
            .text(this.nodeId)
            .text(subject.ref.type)
            .text(subject.ref.id)
            .text(subject.revision)
            .text(subject.digest)
            .longValue(this.taskRevision)
            .text(this.taskDigest)
            .text(this.policyDigest)
            .text(milestoneKind.code)
            .text(actionKind.code)
            .text(actionProfile.bindingDigest)
            .integer(attempt)
            .text(this.authorizationEvidenceDigest)
            .longValue(requestedAt)
            .longValue(deadline)
            .finish()
    }

    override fun toString(): String = "WorkflowSlaActionRequest(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            callContext: WorkflowTrustedCallContext,
            scheduleId: String,
            definitionRef: WorkflowDefinitionRef,
            instanceId: String,
            workItemId: String,
            nodeId: String,
            subject: WorkflowSubjectSnapshot,
            taskRevision: Long,
            taskDigest: String,
            policyDigest: String,
            milestoneKind: WorkflowSlaMilestoneKind,
            actionKind: WorkflowSlaActionKind,
            actionProfile: WorkflowSlaActionProfile,
            attempt: Int,
            authorizationEvidenceDigest: String,
            requestedAt: Long,
            deadline: Long,
        ): WorkflowSlaActionRequest = WorkflowSlaActionRequest(
            callContext,
            scheduleId,
            definitionRef,
            instanceId,
            workItemId,
            nodeId,
            subject,
            taskRevision,
            taskDigest,
            policyDigest,
            milestoneKind,
            actionKind,
            actionProfile,
            attempt,
            authorizationEvidenceDigest,
            requestedAt,
            deadline,
        )
    }
}

/** Content-free receipt. Only NOT_APPLIED_RETRYABLE can enter automatic retry. */
class WorkflowSlaActionReceipt private constructor(
    scheduleId: String,
    val milestoneKind: WorkflowSlaMilestoneKind,
    providerId: String,
    providerRevision: String,
    actionProfileDigest: String,
    requestDigest: String,
    val outcome: WorkflowSlaActionOutcome,
    resultEvidenceDigest: String,
    failureCode: String?,
    val requestedAt: Long,
    val deadline: Long,
    val completedAt: Long,
    val expiresAt: Long,
) {
    val scheduleId: String = slaIdentifier(scheduleId, "receipt schedule id")
    val providerId: String = slaMachineCode(providerId, "receipt provider id")
    val providerRevision: String = slaBoundedText(providerRevision, "receipt provider revision")
    val actionProfileDigest: String = slaDigest(actionProfileDigest, "receipt action profile")
    val requestDigest: String = slaDigest(requestDigest, "receipt request")
    val resultEvidenceDigest: String = slaDigest(resultEvidenceDigest, "receipt result evidence")
    val failureCode: String? = failureCode?.let { slaMachineCode(it, "receipt failure code") }
    val receiptDigest: String

    init {
        require(requestedAt >= 0L && deadline > requestedAt &&
            completedAt in requestedAt..deadline && expiresAt in completedAt..deadline
        ) {
            "Workflow SLA action receipt time window is invalid."
        }
        require((outcome == WorkflowSlaActionOutcome.SUCCEEDED ||
            outcome == WorkflowSlaActionOutcome.SUPPRESSED) == (this.failureCode == null)
        ) { "Workflow SLA action receipt failure shape is inconsistent." }
        receiptDigest = WorkflowSlaSupport.digest("flowweft-workflow-sla-action-receipt-v1")
            .text(this.scheduleId)
            .text(milestoneKind.code)
            .text(this.providerId)
            .text(this.providerRevision)
            .text(this.actionProfileDigest)
            .text(this.requestDigest)
            .text(outcome.code)
            .text(this.resultEvidenceDigest)
            .optional(this.failureCode)
            .longValue(requestedAt)
            .longValue(deadline)
            .longValue(completedAt)
            .longValue(expiresAt)
            .finish()
    }

    fun matches(request: WorkflowSlaActionRequest, now: Long): Boolean =
        scheduleId == request.scheduleId && milestoneKind == request.milestoneKind &&
            providerId == request.actionProfile.providerId &&
            providerRevision == request.actionProfile.providerRevision &&
            actionProfileDigest == request.actionProfile.bindingDigest &&
            requestDigest == request.requestDigest &&
            requestedAt == request.requestedAt && deadline == request.deadline &&
            completedAt in request.requestedAt..now && now <= expiresAt && now <= request.deadline

    override fun toString(): String = "WorkflowSlaActionReceipt(<redacted>)"

    companion object {
        @JvmStatic
        fun success(
            request: WorkflowSlaActionRequest,
            resultEvidenceDigest: String,
            completedAt: Long,
            expiresAt: Long,
        ): WorkflowSlaActionReceipt = create(
            request,
            WorkflowSlaActionOutcome.SUCCEEDED,
            resultEvidenceDigest,
            null,
            completedAt,
            expiresAt,
        )

        @JvmStatic
        fun suppressed(
            request: WorkflowSlaActionRequest,
            resultEvidenceDigest: String,
            completedAt: Long,
            expiresAt: Long,
        ): WorkflowSlaActionReceipt = create(
            request,
            WorkflowSlaActionOutcome.SUPPRESSED,
            resultEvidenceDigest,
            null,
            completedAt,
            expiresAt,
        )

        @JvmStatic
        fun failure(
            request: WorkflowSlaActionRequest,
            outcome: WorkflowSlaActionOutcome,
            resultEvidenceDigest: String,
            failureCode: String,
            completedAt: Long,
            expiresAt: Long,
        ): WorkflowSlaActionReceipt {
            require(outcome == WorkflowSlaActionOutcome.NOT_APPLIED_RETRYABLE ||
                outcome == WorkflowSlaActionOutcome.PERMANENT_FAILURE ||
                outcome == WorkflowSlaActionOutcome.OUTCOME_UNKNOWN
            ) { "Workflow SLA success and suppression use dedicated receipt factories." }
            return create(
                request,
                outcome,
                resultEvidenceDigest,
                failureCode,
                completedAt,
                expiresAt,
            )
        }

        /**
         * Rehydrates a content-free receipt from trusted durable storage. All constructor
         * invariants and the canonical receipt digest are recomputed; callers cannot supply a
         * persisted digest to bypass validation.
         */
        @JvmStatic
        fun restore(
            scheduleId: String,
            milestoneKind: WorkflowSlaMilestoneKind,
            providerId: String,
            providerRevision: String,
            actionProfileDigest: String,
            requestDigest: String,
            outcome: WorkflowSlaActionOutcome,
            resultEvidenceDigest: String,
            failureCode: String?,
            requestedAt: Long,
            deadline: Long,
            completedAt: Long,
            expiresAt: Long,
        ): WorkflowSlaActionReceipt = WorkflowSlaActionReceipt(
            scheduleId,
            milestoneKind,
            providerId,
            providerRevision,
            actionProfileDigest,
            requestDigest,
            outcome,
            resultEvidenceDigest,
            failureCode,
            requestedAt,
            deadline,
            completedAt,
            expiresAt,
        )

        private fun create(
            request: WorkflowSlaActionRequest,
            outcome: WorkflowSlaActionOutcome,
            resultEvidenceDigest: String,
            failureCode: String?,
            completedAt: Long,
            expiresAt: Long,
        ): WorkflowSlaActionReceipt = WorkflowSlaActionReceipt(
            request.scheduleId,
            request.milestoneKind,
            request.actionProfile.providerId,
            request.actionProfile.providerRevision,
            request.actionProfile.bindingDigest,
            request.requestDigest,
            outcome,
            resultEvidenceDigest,
            failureCode,
            request.requestedAt,
            request.deadline,
            completedAt,
            expiresAt,
        )
    }
}

interface WorkflowSlaActionPort {
    /**
     * Executes outside every database transaction and after a durable call checkpoint. The
     * implementation must re-read current task/audience authority immediately before any side
     * effect and return SUPPRESSED when the task completed or access was revoked.
     */
    fun execute(request: WorkflowSlaActionRequest): CompletionStage<WorkflowSlaActionReceipt>
}
