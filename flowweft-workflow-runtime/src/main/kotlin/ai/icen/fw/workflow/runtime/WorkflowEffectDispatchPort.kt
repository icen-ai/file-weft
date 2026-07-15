package ai.icen.fw.workflow.runtime

/** Wake-up only: effect payloads remain durable and are claimed from persistence by workers. */
class WorkflowEffectDispatchSignal private constructor(
    tenantId: String,
    instanceId: String,
    committedVersion: Long,
    val effectCount: Int,
    val reason: WorkflowDispatchReason,
) {
    val tenantId: String = WorkflowRuntimeSupport.text(
        tenantId,
        WorkflowRuntimeSupport.MAX_ID_BYTES,
        "Workflow dispatch tenant id is invalid.",
    )
    val instanceId: String = WorkflowRuntimeSupport.text(
        instanceId,
        WorkflowRuntimeSupport.MAX_ID_BYTES,
        "Workflow dispatch instance id is invalid.",
    )
    val committedVersion: Long = WorkflowRuntimeSupport.nonNegative(
        committedVersion,
        "Workflow dispatch version is invalid.",
    )

    init {
        require(this.committedVersion > 0L && effectCount > 0) { "Workflow dispatch signal is invalid." }
    }

    override fun toString(): String = "WorkflowEffectDispatchSignal(<redacted>)"

    companion object {
        @JvmStatic fun of(
            tenantId: String,
            instanceId: String,
            committedVersion: Long,
            effectCount: Int,
            reason: WorkflowDispatchReason,
        ): WorkflowEffectDispatchSignal = WorkflowEffectDispatchSignal(
            tenantId,
            instanceId,
            committedVersion,
            effectCount,
            reason,
        )
    }
}

interface WorkflowEffectDispatchPort {
    /** Called only after a commit is known durable, or after an authorized replay observes it. */
    fun signal(signal: WorkflowEffectDispatchSignal)
}
