package ai.icen.fw.agent

import ai.icen.fw.application.task.BackgroundTask
import ai.icen.fw.application.task.TaskRepository
import ai.icen.fw.core.event.OutboxEvent
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.core.id.IdentifierGenerator
import ai.icen.fw.spi.ai.AgentCapability
import java.time.Clock

/** Converts a committed event into an idempotent, recoverable agent task. */
class AgentTaskScheduler(
    private val identifierGenerator: IdentifierGenerator,
    private val clock: Clock,
) {
    fun schedule(
        sourceEvent: OutboxEvent,
        capability: AgentCapability,
        tasks: TaskRepository,
        businessId: Identifier? = null,
    ): BackgroundTask {
        val task = BackgroundTask(
            id = identifierGenerator.nextId(),
            tenantId = sourceEvent.tenantId,
            type = AgentTaskHandler.TASK_TYPE,
            idempotencyKey = "agent:${capability.name}:${sourceEvent.id.value}",
            businessId = businessId,
            payload = linkedMapOf(
                AgentTaskHandler.CAPABILITY_KEY to capability.name,
                AgentTaskHandler.SOURCE_EVENT_ID_KEY to sourceEvent.id.value,
                AgentTaskHandler.SOURCE_EVENT_TYPE_KEY to sourceEvent.type,
            ).apply {
                sourceEvent.payload.filter { (key, value) -> key.isNotBlank() && value.isNotBlank() }
                    .forEach { (key, value) -> put(AgentTaskHandler.CONTEXT_PREFIX + key, value) }
            },
            nextAttemptTime = clock.millis(),
        )
        tasks.enqueue(task)
        return task
    }
}
