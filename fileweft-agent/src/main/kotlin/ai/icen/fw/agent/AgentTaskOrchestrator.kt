package ai.icen.fw.agent

import ai.icen.fw.spi.ai.AgentCapability
import ai.icen.fw.spi.ai.AgentExecutionStatus
import ai.icen.fw.spi.ai.AgentResult
import ai.icen.fw.spi.ai.AgentTask
import ai.icen.fw.spi.ai.FileWeftAgent
import java.time.Clock
import java.util.LinkedHashMap

/**
 * Executes exactly one matching agent and converts runtime failures into safe
 * task results. It does not receive any domain mutation dependency.
 */
class AgentTaskOrchestrator(
    agents: List<FileWeftAgent>,
    private val clock: Clock,
) {
    private val agentsByCapability: Map<AgentCapability, FileWeftAgent> =
        LinkedHashMap<AgentCapability, FileWeftAgent>().also { registered ->
            agents.forEach { agent ->
                val existing = registered.put(agent.capability(), agent)
                require(existing == null) { "Only one agent may be registered for each capability." }
            }
        }

    fun execute(task: AgentTask): AgentResult {
        val agent = agentsByCapability[task.capability] ?: return AgentResult(
            task.id,
            AgentExecutionStatus.UNSUPPORTED,
            message = "No agent is registered for capability ${task.capability.name}.",
            completedAt = clock.millis(),
        )
        return try {
            val result = agent.execute(task)
            if (result.taskId == task.id) {
                result
            } else {
                AgentResult(
                    task.id,
                    AgentExecutionStatus.FAILED,
                    message = "Agent returned a result for a different task.",
                    completedAt = clock.millis(),
                )
            }
        } catch (_: Exception) {
            AgentResult(
                task.id,
                AgentExecutionStatus.FAILED,
                message = "Agent execution failed.",
                completedAt = clock.millis(),
            )
        }
    }
}
