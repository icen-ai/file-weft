package ai.icen.fw.spi.ai

import ai.icen.fw.core.event.OutboxEvent
import ai.icen.fw.core.id.Identifier

/**
 * Declares which committed events should become agent tasks. Implementations
 * select capabilities explicitly so merely installing an agent never causes it
 * to inspect unrelated tenant data.
 */
interface AgentTaskTrigger {
    fun supports(event: OutboxEvent): Boolean

    fun capabilities(event: OutboxEvent): Collection<AgentCapability>

    fun businessId(event: OutboxEvent): Identifier? = null
}
