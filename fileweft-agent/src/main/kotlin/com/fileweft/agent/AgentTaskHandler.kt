package com.fileweft.agent

import com.fileweft.application.agent.AgentResultRepository
import com.fileweft.application.agent.PersistedAgentResult
import com.fileweft.application.transaction.ApplicationTransaction
import com.fileweft.core.id.Identifier
import com.fileweft.spi.ai.AgentCapability
import com.fileweft.spi.ai.AgentExecutionStatus
import com.fileweft.spi.ai.AgentResult
import com.fileweft.spi.ai.AgentTask
import com.fileweft.spi.task.FileWeftTaskHandler
import com.fileweft.spi.task.TaskExecution
import com.fileweft.spi.task.TaskHandlingResult
import com.fileweft.spi.task.TaskHandlingStatus
import java.time.Clock

/** Executes optional agents through the durable task worker and projects evidence only. */
class AgentTaskHandler(
    private val orchestrator: AgentTaskOrchestrator,
    private val results: AgentResultRepository,
    private val transaction: ApplicationTransaction,
    private val clock: Clock,
) : FileWeftTaskHandler {
    override fun supports(task: TaskExecution): Boolean = task.type == TASK_TYPE

    override fun handle(task: TaskExecution): TaskHandlingResult {
        val agentTask = parse(task) ?: return TaskHandlingResult(TaskHandlingStatus.PERMANENT_FAILURE, "Agent task payload is invalid.")
        val result = orchestrator.execute(agentTask)
        persist(task, agentTask, result)
        return when (result.status) {
            AgentExecutionStatus.SUCCEEDED -> TaskHandlingResult(TaskHandlingStatus.SUCCEEDED)
            AgentExecutionStatus.UNSUPPORTED -> TaskHandlingResult(TaskHandlingStatus.PERMANENT_FAILURE, result.message ?: "No agent is installed.")
            AgentExecutionStatus.FAILED -> TaskHandlingResult(TaskHandlingStatus.RETRYABLE_FAILURE, result.message ?: "Agent execution failed.")
        }
    }

    override fun onExhausted(task: TaskExecution, message: String) {
        val agentTask = parse(task) ?: return
        persist(task, agentTask, AgentResult(task.id, AgentExecutionStatus.FAILED, message = message, completedAt = clock.millis()))
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

    private fun persist(task: TaskExecution, agentTask: AgentTask, result: AgentResult) {
        transaction.execute {
            results.save(
                PersistedAgentResult(
                    id = task.id,
                    tenantId = task.tenantId,
                    taskId = task.id,
                    capability = agentTask.capability,
                    sourceEventId = agentTask.sourceEventId,
                    sourceEventType = agentTask.sourceEventType,
                    result = result,
                    createdAt = clock.millis(),
                ),
            )
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
