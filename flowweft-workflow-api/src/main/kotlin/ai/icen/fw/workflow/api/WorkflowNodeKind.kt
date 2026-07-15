package ai.icen.fw.workflow.api

/**
 * Extensible, non-executable code describing a neutral workflow node shape.
 *
 * Unknown values and [EXTENSION] are representation hooks only. A runtime must fail closed until
 * an explicitly registered provider accepts the exact descriptor and payload digests.
 */
class WorkflowNodeKind private constructor(code: String) {
    val code: String = WorkflowContractSupport.requireMachineCode(
        code,
        "Workflow node kind is invalid.",
    )

    override fun equals(other: Any?): Boolean =
        this === other || other is WorkflowNodeKind && code == other.code

    override fun hashCode(): Int = code.hashCode()

    override fun toString(): String = "WorkflowNodeKind(<redacted>)"

    companion object {
        @JvmField
        val START = WorkflowNodeKind("start")

        @JvmField
        val END = WorkflowNodeKind("end")

        @JvmField
        val HUMAN_TASK = WorkflowNodeKind("human-task")

        @JvmField
        val SERVICE_TASK = WorkflowNodeKind("service-task")

        @JvmField
        val DECISION = WorkflowNodeKind("decision")

        @JvmField
        val EXCLUSIVE_GATEWAY = WorkflowNodeKind("exclusive-gateway")

        @JvmField
        val PARALLEL_SPLIT = WorkflowNodeKind("parallel-split")

        @JvmField
        val PARALLEL_JOIN = WorkflowNodeKind("parallel-join")

        @JvmField
        val TIMER_WAIT = WorkflowNodeKind("timer-wait")

        @JvmField
        val SUBPROCESS = WorkflowNodeKind("subprocess")

        @JvmField
        val EXTENSION = WorkflowNodeKind("extension")

        @JvmStatic
        fun of(code: String): WorkflowNodeKind = when (code) {
            START.code -> START
            END.code -> END
            HUMAN_TASK.code -> HUMAN_TASK
            SERVICE_TASK.code -> SERVICE_TASK
            DECISION.code -> DECISION
            EXCLUSIVE_GATEWAY.code -> EXCLUSIVE_GATEWAY
            PARALLEL_SPLIT.code -> PARALLEL_SPLIT
            PARALLEL_JOIN.code -> PARALLEL_JOIN
            TIMER_WAIT.code -> TIMER_WAIT
            SUBPROCESS.code -> SUBPROCESS
            EXTENSION.code -> EXTENSION
            else -> WorkflowNodeKind(code)
        }
    }
}
