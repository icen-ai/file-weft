package ai.icen.fw.workflow.runtime

private fun runtimeCode(value: String, label: String): String = WorkflowRuntimeSupport.code(
    value,
    "Workflow runtime $label code is invalid.",
)

class WorkflowRuntimeAction private constructor(code: String) {
    val code: String = runtimeCode(code, "action")
    override fun equals(other: Any?): Boolean = this === other || other is WorkflowRuntimeAction && code == other.code
    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "WorkflowRuntimeAction(<redacted>)"

    companion object {
        @JvmField val START = WorkflowRuntimeAction("start")
        @JvmField val ACTIVATE_HUMAN_RULE = WorkflowRuntimeAction("activate-human-rule")
        @JvmField val RESOLVE_PARTICIPANTS = WorkflowRuntimeAction("resolve-participants")
        @JvmField val DECIDE_HUMAN_TASK = WorkflowRuntimeAction("decide-human-task")
        @JvmField val CLAIM_HUMAN_TASK = WorkflowRuntimeAction("claim-human-task")
        @JvmField val UNCLAIM_HUMAN_TASK = WorkflowRuntimeAction("unclaim-human-task")
        @JvmField val DELEGATE_HUMAN_TASK = WorkflowRuntimeAction("delegate-human-task")
        @JvmField val TRANSFER_HUMAN_TASK = WorkflowRuntimeAction("transfer-human-task")
        @JvmField val ADD_SIGN_HUMAN_TASK = WorkflowRuntimeAction("add-sign-human-task")
        @JvmField val RETURN_HUMAN_TASK = WorkflowRuntimeAction("return-human-task")
        @JvmField val COMPLETE_EFFECT = WorkflowRuntimeAction("complete-effect")
        @JvmField val CONTINUE_EXECUTION = WorkflowRuntimeAction("continue-execution")
        @JvmField val SUSPEND_INSTANCE = WorkflowRuntimeAction("suspend-instance")
        @JvmField val RESUME_INSTANCE = WorkflowRuntimeAction("resume-instance")
        @JvmField val CANCEL_INSTANCE = WorkflowRuntimeAction("cancel-instance")
        @JvmField val TERMINATE_INSTANCE = WorkflowRuntimeAction("terminate-instance")
        @JvmField val CLAIM_EFFECT = WorkflowRuntimeAction("claim-effect")
        @JvmField val CHECKPOINT_EFFECT = WorkflowRuntimeAction("checkpoint-effect")
        @JvmField val RECORD_EFFECT_OUTCOME = WorkflowRuntimeAction("record-effect-outcome")
        @JvmField val RETRY_EFFECT = WorkflowRuntimeAction("retry-effect")
        @JvmField val RECONCILE_EFFECT = WorkflowRuntimeAction("reconcile-effect")

        @JvmStatic fun of(code: String): WorkflowRuntimeAction = builtIns.firstOrNull { it.code == code }
            ?: WorkflowRuntimeAction(code)

        private val builtIns = listOf(
            START,
            ACTIVATE_HUMAN_RULE,
            RESOLVE_PARTICIPANTS,
            DECIDE_HUMAN_TASK,
            CLAIM_HUMAN_TASK,
            UNCLAIM_HUMAN_TASK,
            DELEGATE_HUMAN_TASK,
            TRANSFER_HUMAN_TASK,
            ADD_SIGN_HUMAN_TASK,
            RETURN_HUMAN_TASK,
            COMPLETE_EFFECT,
            CONTINUE_EXECUTION,
            SUSPEND_INSTANCE,
            RESUME_INSTANCE,
            CANCEL_INSTANCE,
            TERMINATE_INSTANCE,
            CLAIM_EFFECT,
            CHECKPOINT_EFFECT,
            RECORD_EFFECT_OUTCOME,
            RETRY_EFFECT,
            RECONCILE_EFFECT,
        )
    }
}

class WorkflowRuntimeAuthorizationStatus private constructor(code: String) {
    val code: String = runtimeCode(code, "authorization status")
    override fun equals(other: Any?): Boolean =
        this === other || other is WorkflowRuntimeAuthorizationStatus && code == other.code
    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "WorkflowRuntimeAuthorizationStatus(<redacted>)"

    companion object {
        @JvmField val AUTHORIZED = WorkflowRuntimeAuthorizationStatus("authorized")
        @JvmField val DENIED = WorkflowRuntimeAuthorizationStatus("denied")
    }
}

class WorkflowRuntimeResultCode private constructor(code: String) {
    val code: String = runtimeCode(code, "result")
    override fun equals(other: Any?): Boolean = this === other || other is WorkflowRuntimeResultCode && code == other.code
    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "WorkflowRuntimeResultCode(<redacted>)"

    companion object {
        @JvmField val COMMITTED = WorkflowRuntimeResultCode("committed")
        @JvmField val COMMITTED_DISPATCH_DEFERRED = WorkflowRuntimeResultCode("committed-dispatch-deferred")
        @JvmField val REPLAYED = WorkflowRuntimeResultCode("replayed")
        @JvmField val AUTHORIZATION_DENIED = WorkflowRuntimeResultCode("authorization-denied")
        @JvmField val NOT_FOUND = WorkflowRuntimeResultCode("not-found")
        @JvmField val VERSION_CONFLICT = WorkflowRuntimeResultCode("version-conflict")
        @JvmField val IDEMPOTENCY_CONFLICT = WorkflowRuntimeResultCode("idempotency-conflict")
        @JvmField val EFFECT_CONFLICT = WorkflowRuntimeResultCode("effect-conflict")
        @JvmField val DOMAIN_REJECTED = WorkflowRuntimeResultCode("domain-rejected")
        @JvmField val COMMIT_OUTCOME_UNKNOWN = WorkflowRuntimeResultCode("commit-outcome-unknown")
    }
}

class WorkflowRuntimeCommitCode private constructor(code: String) {
    val code: String = runtimeCode(code, "commit")
    override fun equals(other: Any?): Boolean = this === other || other is WorkflowRuntimeCommitCode && code == other.code
    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "WorkflowRuntimeCommitCode(<redacted>)"

    companion object {
        @JvmField val COMMITTED = WorkflowRuntimeCommitCode("committed")
        @JvmField val VERSION_CONFLICT = WorkflowRuntimeCommitCode("version-conflict")
        @JvmField val IDEMPOTENCY_CONFLICT = WorkflowRuntimeCommitCode("idempotency-conflict")
        @JvmField val EFFECT_CONFLICT = WorkflowRuntimeCommitCode("effect-conflict")
    }
}

class WorkflowDispatchReason private constructor(code: String) {
    val code: String = runtimeCode(code, "dispatch reason")
    override fun equals(other: Any?): Boolean = this === other || other is WorkflowDispatchReason && code == other.code
    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "WorkflowDispatchReason(<redacted>)"

    companion object {
        @JvmField val NEW_COMMIT = WorkflowDispatchReason("new-commit")
        @JvmField val REPLAY_RECOVERY = WorkflowDispatchReason("replay-recovery")
    }
}
