package com.fileweft.agent

import com.fileweft.application.agent.AgentResultRepository
import com.fileweft.application.agent.PersistedAgentResult
import com.fileweft.application.task.BackgroundTaskLease
import com.fileweft.application.task.LeasedTaskHandler
import com.fileweft.application.task.TaskLeaseLostException
import com.fileweft.application.task.TaskMutationRepository
import com.fileweft.application.transaction.ApplicationTransaction
import com.fileweft.core.id.Identifier
import com.fileweft.spi.ai.AgentCapability
import com.fileweft.spi.ai.AgentExecutionStatus
import com.fileweft.spi.ai.AgentResult
import com.fileweft.spi.ai.AgentTask
import com.fileweft.spi.task.TaskExecution
import com.fileweft.spi.task.TaskHandlingResult
import com.fileweft.spi.task.TaskHandlingStatus
import java.time.Clock

/** Executes optional agents through the durable task worker and projects evidence only. */
class AgentTaskHandler private constructor(
    private val orchestrator: AgentTaskOrchestrator,
    private val results: AgentResultRepository,
    private val transaction: ApplicationTransaction,
    private val clock: Clock,
    private val taskMutations: TaskMutationRepository?,
    private val fenced: Boolean,
) : LeasedTaskHandler {
    /** Retains the original direct, idempotent handler ABI. */
    constructor(
        orchestrator: AgentTaskOrchestrator,
        results: AgentResultRepository,
        transaction: ApplicationTransaction,
        clock: Clock,
    ) : this(orchestrator, results, transaction, clock, null, false)

    /** Strong production path that fences every Agent result projection. */
    constructor(
        orchestrator: AgentTaskOrchestrator,
        results: AgentResultRepository,
        transaction: ApplicationTransaction,
        clock: Clock,
        taskMutations: TaskMutationRepository,
    ) : this(orchestrator, results, transaction, clock, taskMutations, true)

    init {
        require(fenced == (taskMutations != null)) {
            "Agent task projection fencing requires a task mutation repository."
        }
    }

    override fun supports(task: TaskExecution): Boolean = task.type == TASK_TYPE

    override fun handle(task: TaskExecution): TaskHandlingResult {
        if (fenced) {
            return TaskHandlingResult(
                TaskHandlingStatus.PERMANENT_FAILURE,
                "Agent task projection requires the current persisted task lease.",
            )
        }
        return handleLegacy(task)
    }

    override fun handle(lease: BackgroundTaskLease): TaskHandlingResult {
        if (!fenced) return handleLegacy(lease.task.execution())
        requireToken(lease)
        val task = lease.task.execution()
        val agentTask = parse(task) ?: return TaskHandlingResult(TaskHandlingStatus.PERMANENT_FAILURE, "Agent task payload is invalid.")
        val result = orchestrator.execute(agentTask)
        persistFenced(lease, task, agentTask, result)
        return handlingResult(result)
    }

    private fun handleLegacy(task: TaskExecution): TaskHandlingResult {
        val agentTask = parse(task) ?: return TaskHandlingResult(TaskHandlingStatus.PERMANENT_FAILURE, "Agent task payload is invalid.")
        val result = orchestrator.execute(agentTask)
        persistLegacy(task, agentTask, result)
        return handlingResult(result)
    }

    private fun handlingResult(result: AgentResult): TaskHandlingResult =
        when (result.status) {
            AgentExecutionStatus.SUCCEEDED -> TaskHandlingResult(TaskHandlingStatus.SUCCEEDED)
            AgentExecutionStatus.UNSUPPORTED -> TaskHandlingResult(TaskHandlingStatus.PERMANENT_FAILURE, result.message ?: "No agent is installed.")
            AgentExecutionStatus.FAILED -> TaskHandlingResult(TaskHandlingStatus.RETRYABLE_FAILURE, result.message ?: "Agent execution failed.")
        }

    override fun onExhausted(task: TaskExecution, message: String) {
        if (fenced) return
        val agentTask = parse(task) ?: return
        persistLegacy(task, agentTask, exhaustedResult(task, message))
    }

    override fun onExhausted(lease: BackgroundTaskLease, message: String) {
        if (!fenced) {
            onExhausted(lease.task.execution(), message)
            return
        }
        val task = lease.task.execution()
        val agentTask = parse(task) ?: return
        val persisted = persistedResult(task, agentTask, exhaustedResult(task, message))
        transaction.execute {
            val state = requireNotNull(taskMutations).findForMutation(task.tenantId, task.id) ?: return@execute
            if (state.matchesFailedTask(lease)) {
                results.save(persisted)
            }
        }
    }

    private fun parse(task: TaskExecution): AgentTask? = try {
        val capability = AgentCapability.valueOf(task.payload[CAPABILITY_KEY] ?: return null)
        val sourceEventId = Identifier(task.payload[SOURCE_EVENT_ID_KEY] ?: return null)
        val sourceEventType = task.payload[SOURCE_EVENT_TYPE_KEY] ?: return null
        AgentTask(
            id = task.id,
            tenantId = task.tenantId,
            capability = capability,
            sourceEventId = sourceEventId,
            sourceEventType = sourceEventType,
            idempotencyKey = "agent:${capability.name}:${sourceEventId.value}",
            context = task.payload.filterKeys { it.startsWith(CONTEXT_PREFIX) }
                .mapKeys { (key, _) -> key.removePrefix(CONTEXT_PREFIX) },
            submittedAt = clock.millis(),
        )
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun persistLegacy(task: TaskExecution, agentTask: AgentTask, result: AgentResult) {
        val persisted = persistedResult(task, agentTask, result)
        transaction.execute {
            results.save(persisted)
        }
    }

    private fun persistFenced(
        lease: BackgroundTaskLease,
        task: TaskExecution,
        agentTask: AgentTask,
        result: AgentResult,
    ) {
        val persisted = persistedResult(task, agentTask, result)
        transaction.execute {
            val state = requireNotNull(taskMutations).findForMutation(task.tenantId, task.id)
                ?: throw TaskLeaseLostException("Agent task no longer exists in the current tenant.")
            state.requireCurrentLease(lease)
            results.save(persisted)
        }
    }

    private fun persistedResult(
        task: TaskExecution,
        agentTask: AgentTask,
        result: AgentResult,
    ): PersistedAgentResult = PersistedAgentResult(
        id = task.id,
        tenantId = task.tenantId,
        taskId = task.id,
        capability = agentTask.capability,
        sourceEventId = agentTask.sourceEventId,
        sourceEventType = agentTask.sourceEventType,
        result = result,
        createdAt = clock.millis(),
    )

    private fun exhaustedResult(task: TaskExecution, message: String): AgentResult =
        AgentResult(task.id, AgentExecutionStatus.FAILED, message = message, completedAt = clock.millis())

    private fun requireToken(lease: BackgroundTaskLease) {
        if (lease.leaseToken == null) {
            throw TaskLeaseLostException("Agent task projection requires a persisted lease token.")
        }
    }

    companion object {
        const val TASK_TYPE = "agent.execute"
        const val CAPABILITY_KEY = "agentCapability"
        const val SOURCE_EVENT_ID_KEY = "sourceEventId"
        const val SOURCE_EVENT_TYPE_KEY = "sourceEventType"
        const val CONTEXT_PREFIX = "context."
    }
}
