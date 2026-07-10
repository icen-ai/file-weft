package com.fileweft.application.outbox

import com.fileweft.core.event.OutboxEvent

interface OutboxEventRepository {
    fun append(event: OutboxEvent)
}
