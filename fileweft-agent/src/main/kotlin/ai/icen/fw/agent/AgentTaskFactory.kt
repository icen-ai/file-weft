package ai.icen.fw.agent

import ai.icen.fw.core.event.OutboxEvent
import ai.icen.fw.core.id.IdentifierGenerator
import ai.icen.fw.spi.ai.AgentCapability
import ai.icen.fw.spi.ai.AgentTask
import java.time.Clock

/** Converts a persisted event into a tenant-scoped, idempotent agent task. */
class AgentTaskFactory(
    private val identifierGenerator: IdentifierGenerator,
    private val clock: Clock,
) {
    fun create(sourceEvent: OutboxEvent, capability: AgentCapability): AgentTask = AgentTask(
        id = identifierGenerator.nextId(),
        tenantId = sourceEvent.tenantId,
        capability = capability,
        sourceEventId = sourceEvent.id,
        sourceEventType = sourceEvent.type,
        idempotencyKey = "agent:${capability.name}:${sourceEvent.id.value}",
        context = sourceEvent.payload,
        submittedAt = clock.millis(),
    )
}
