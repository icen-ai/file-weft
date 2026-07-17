package ai.icen.fw.workflow.cycle.guard

import ai.icen.fw.workflow.api.WorkflowDefinitionRef
import ai.icen.fw.workflow.api.WorkflowSubjectSnapshot
import ai.icen.fw.workflow.runtime.WorkflowRuntimeAction
import ai.icen.fw.workflow.runtime.WorkflowTrustedCallContext

/** Stable guarded operation codes. Unknown extensions are rejected until a policy/runtime supports them. */
class WorkflowCycleGuardOperation private constructor(
    code: String,
    val authorizationAction: WorkflowRuntimeAction,
) {
    val code: String = WorkflowCycleGuardSupport.code(code, "Cycle guard operation")

    override fun equals(other: Any?): Boolean =
        this === other || other is WorkflowCycleGuardOperation && code == other.code

    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "WorkflowCycleGuardOperation(<redacted>)"

    companion object {
        @JvmField val RETURN = WorkflowCycleGuardOperation(
            "return",
            WorkflowRuntimeAction.RETURN_HUMAN_TASK,
        )
        @JvmField val RESUBMIT = WorkflowCycleGuardOperation(
            "resubmit",
            WorkflowRuntimeAction.of("resubmit-human-task"),
        )
        @JvmField val ADD_SIGN = WorkflowCycleGuardOperation(
            "add-sign",
            WorkflowRuntimeAction.ADD_SIGN_HUMAN_TASK,
        )
        @JvmField val SUBJECT_REVISION = WorkflowCycleGuardOperation(
            "subject-revision",
            WorkflowRuntimeAction.of("request-subject-revision"),
        )

        @JvmStatic fun of(code: String): WorkflowCycleGuardOperation = when (code) {
            RETURN.code -> RETURN
            RESUBMIT.code -> RESUBMIT
            ADD_SIGN.code -> ADD_SIGN
            SUBJECT_REVISION.code -> SUBJECT_REVISION
            else -> WorkflowCycleGuardOperation(
                code,
                WorkflowRuntimeAction.of(code),
            )
        }

        internal fun supported(value: WorkflowCycleGuardOperation): Boolean =
            value == RETURN || value == RESUBMIT || value == ADD_SIGN || value == SUBJECT_REVISION
    }
}

/** Exact durable counter scope. Definition version/digest and optional subject revision are pinned. */
class WorkflowCycleGuardScope private constructor(
    tenantId: String,
    instanceId: String,
    definitionId: String,
    val definitionRef: WorkflowDefinitionRef,
    nodeId: String,
    val operation: WorkflowCycleGuardOperation,
    cycleNumber: Long,
    val subject: WorkflowSubjectSnapshot?,
) {
    val tenantId: String = WorkflowCycleGuardSupport.text(tenantId, "Cycle guard tenant")
    val instanceId: String = WorkflowCycleGuardSupport.text(instanceId, "Cycle guard instance")
    val definitionId: String = WorkflowCycleGuardSupport.text(definitionId, "Cycle guard definition")
    val nodeId: String = WorkflowCycleGuardSupport.text(nodeId, "Cycle guard node")
    val cycleNumber: Long = WorkflowCycleGuardSupport.nonNegative(cycleNumber, "Cycle guard cycle")
    val scopeDigest: String = WorkflowCycleGuardSupport.sha256(
        "flowweft-workflow-cycle-guard-scope-v1",
        this.tenantId,
        this.instanceId,
        this.definitionId,
        definitionRef.key,
        definitionRef.version,
        definitionRef.digest,
        this.nodeId,
        operation.code,
        this.cycleNumber.toString(),
        subject?.ref?.type,
        subject?.ref?.id,
        subject?.revision,
        subject?.digest,
    )

    init {
        require(!definitionRef.version.equals("latest", ignoreCase = true) &&
            subject?.revision?.equals("latest", ignoreCase = true) != true
        ) { "Cycle guard scope must use exact definition and subject revisions." }
    }

    override fun equals(other: Any?): Boolean =
        this === other || other is WorkflowCycleGuardScope && scopeDigest == other.scopeDigest

    override fun hashCode(): Int = scopeDigest.hashCode()
    override fun toString(): String = "WorkflowCycleGuardScope(<redacted>)"

    companion object {
        @JvmStatic fun of(
            tenantId: String,
            instanceId: String,
            definitionId: String,
            definitionRef: WorkflowDefinitionRef,
            nodeId: String,
            operation: WorkflowCycleGuardOperation,
            cycleNumber: Long,
            subject: WorkflowSubjectSnapshot?,
        ): WorkflowCycleGuardScope = WorkflowCycleGuardScope(
            tenantId,
            instanceId,
            definitionId,
            definitionRef,
            nodeId,
            operation,
            cycleNumber,
            subject,
        )
    }
}

/** One exact attempt to consume one unit of a durable cycle budget. */
class WorkflowCycleGuardCommand private constructor(
    val callContext: WorkflowTrustedCallContext,
    val scope: WorkflowCycleGuardScope,
    commandId: String,
    idempotencyKey: String,
    expectedInstanceVersion: Long,
    expectedGuardRevision: Long,
    reasonDigest: String,
    requestedAtEpochMilli: Long,
) {
    val commandId: String = WorkflowCycleGuardSupport.text(commandId, "Cycle guard command")
    val idempotencyKey: String = WorkflowCycleGuardSupport.text(
        idempotencyKey,
        "Cycle guard idempotency key",
        128,
    )
    val expectedInstanceVersion: Long = WorkflowCycleGuardSupport.nonNegative(
        expectedInstanceVersion,
        "Cycle guard expected instance version",
    )
    val expectedGuardRevision: Long = WorkflowCycleGuardSupport.nonNegative(
        expectedGuardRevision,
        "Cycle guard expected counter revision",
    )
    val reasonDigest: String = WorkflowCycleGuardSupport.sha(reasonDigest, "Cycle guard reason digest")
    val requestedAtEpochMilli: Long = WorkflowCycleGuardSupport.nonNegative(
        requestedAtEpochMilli,
        "Cycle guard request time",
    )
    val requestDigest: String = WorkflowCycleGuardSupport.sha256(
        "flowweft-workflow-cycle-guard-command-v1",
        callContext.contextDigest,
        scope.scopeDigest,
        this.commandId,
        this.idempotencyKey,
        this.expectedInstanceVersion.toString(),
        this.expectedGuardRevision.toString(),
        this.reasonDigest,
        this.requestedAtEpochMilli.toString(),
    )

    init {
        require(callContext.tenantId == scope.tenantId && this.expectedInstanceVersion > 0L) {
            "Cycle guard command is not bound to the trusted instance context."
        }
        require(this.expectedGuardRevision < Long.MAX_VALUE) {
            "Cycle guard expected counter revision is exhausted."
        }
    }

    override fun toString(): String = "WorkflowCycleGuardCommand(<redacted>)"

    companion object {
        @JvmStatic fun of(
            callContext: WorkflowTrustedCallContext,
            scope: WorkflowCycleGuardScope,
            commandId: String,
            idempotencyKey: String,
            expectedInstanceVersion: Long,
            expectedGuardRevision: Long,
            reasonDigest: String,
            requestedAtEpochMilli: Long,
        ): WorkflowCycleGuardCommand = WorkflowCycleGuardCommand(
            callContext,
            scope,
            commandId,
            idempotencyKey,
            expectedInstanceVersion,
            expectedGuardRevision,
            reasonDigest,
            requestedAtEpochMilli,
        )
    }
}

class WorkflowCycleGuardResultCode private constructor(code: String) {
    val code: String = WorkflowCycleGuardSupport.code(code, "Cycle guard result")
    override fun equals(other: Any?): Boolean =
        this === other || other is WorkflowCycleGuardResultCode && code == other.code
    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "WorkflowCycleGuardResultCode(<redacted>)"

    companion object {
        @JvmField val ALLOWED = WorkflowCycleGuardResultCode("allowed")
        @JvmField val REPLAYED = WorkflowCycleGuardResultCode("replayed")
        @JvmField val LIMIT_REACHED = WorkflowCycleGuardResultCode("limit-reached")
        @JvmField val AUTHORIZATION_DENIED = WorkflowCycleGuardResultCode("authorization-denied")
        @JvmField val UNSUPPORTED = WorkflowCycleGuardResultCode("unsupported")
        @JvmField val POLICY_CONFLICT = WorkflowCycleGuardResultCode("policy-conflict")
        @JvmField val VERSION_CONFLICT = WorkflowCycleGuardResultCode("version-conflict")
        @JvmField val IDEMPOTENCY_CONFLICT = WorkflowCycleGuardResultCode("idempotency-conflict")
        @JvmField val OUTCOME_UNKNOWN = WorkflowCycleGuardResultCode("outcome-unknown")
        @JvmField val RECEIPT_DRIFT = WorkflowCycleGuardResultCode("receipt-drift")
        @JvmField val NOT_COMMITTED = WorkflowCycleGuardResultCode("not-committed")
    }
}

class WorkflowCycleGuardResult private constructor(
    val code: WorkflowCycleGuardResultCode,
    val record: WorkflowCycleGuardRecord?,
    diagnosticCode: String?,
) {
    val diagnosticCode: String? = diagnosticCode?.let {
        WorkflowCycleGuardSupport.code(it, "Cycle guard diagnostic")
    }

    init {
        val success = code == WorkflowCycleGuardResultCode.ALLOWED ||
            code == WorkflowCycleGuardResultCode.REPLAYED
        require(success == (record != null)) { "Cycle guard result shape is invalid." }
        require(success || this.diagnosticCode != null) {
            "Cycle guard failure requires a stable diagnostic code."
        }
    }

    override fun toString(): String = "WorkflowCycleGuardResult(<redacted>)"

    companion object {
        @JvmStatic fun success(
            code: WorkflowCycleGuardResultCode,
            record: WorkflowCycleGuardRecord,
        ): WorkflowCycleGuardResult = WorkflowCycleGuardResult(code, record, null)

        @JvmStatic fun failure(
            code: WorkflowCycleGuardResultCode,
            diagnosticCode: String,
        ): WorkflowCycleGuardResult = WorkflowCycleGuardResult(code, null, diagnosticCode)
    }
}

class WorkflowCycleGuardReconciliationRequest private constructor(
    val callContext: WorkflowTrustedCallContext,
    val command: WorkflowCycleGuardCommand,
    observedAtEpochMilli: Long,
) {
    val observedAtEpochMilli: Long = WorkflowCycleGuardSupport.nonNegative(
        observedAtEpochMilli,
        "Cycle guard reconciliation time",
    )
    val requestDigest: String = WorkflowCycleGuardSupport.sha256(
        "flowweft-workflow-cycle-guard-reconciliation-v1",
        callContext.contextDigest,
        command.requestDigest,
        this.observedAtEpochMilli.toString(),
    )

    init {
        require(callContext.tenantId == command.scope.tenantId &&
            callContext.actor == command.callContext.actor
        ) { "Cycle guard reconciliation context is invalid." }
    }

    override fun toString(): String = "WorkflowCycleGuardReconciliationRequest(<redacted>)"

    companion object {
        @JvmStatic fun of(
            callContext: WorkflowTrustedCallContext,
            command: WorkflowCycleGuardCommand,
            observedAtEpochMilli: Long,
        ): WorkflowCycleGuardReconciliationRequest = WorkflowCycleGuardReconciliationRequest(
            callContext,
            command,
            observedAtEpochMilli,
        )
    }
}

class WorkflowCycleGuardDiagnosticQuery private constructor(
    val callContext: WorkflowTrustedCallContext,
    val scope: WorkflowCycleGuardScope,
    observedAtEpochMilli: Long,
) {
    val observedAtEpochMilli: Long = WorkflowCycleGuardSupport.nonNegative(
        observedAtEpochMilli,
        "Cycle guard diagnostic time",
    )
    val requestDigest: String = WorkflowCycleGuardSupport.sha256(
        "flowweft-workflow-cycle-guard-diagnostic-query-v1",
        callContext.contextDigest,
        scope.scopeDigest,
        this.observedAtEpochMilli.toString(),
    )

    init {
        require(callContext.tenantId == scope.tenantId) {
            "Cycle guard diagnostic query is not tenant bound."
        }
    }

    override fun toString(): String = "WorkflowCycleGuardDiagnosticQuery(<redacted>)"

    companion object {
        @JvmStatic fun of(
            callContext: WorkflowTrustedCallContext,
            scope: WorkflowCycleGuardScope,
            observedAtEpochMilli: Long,
        ): WorkflowCycleGuardDiagnosticQuery = WorkflowCycleGuardDiagnosticQuery(
            callContext,
            scope,
            observedAtEpochMilli,
        )
    }
}
