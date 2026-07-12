package ai.icen.fw.application.outbox

import ai.icen.fw.core.event.OutboxEvent

interface OutboxEventRepository {
    fun append(event: OutboxEvent)
}
