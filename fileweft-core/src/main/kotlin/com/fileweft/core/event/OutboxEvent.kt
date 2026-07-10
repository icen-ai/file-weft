package com.fileweft.core.event

import com.fileweft.core.id.Identifier
import java.util.Collections
import java.util.LinkedHashMap

class OutboxEvent @JvmOverloads constructor(
    override val id: Identifier,
    override val tenantId: Identifier,
    val type: String,
    payload: Map<String, String>,
    override val timestamp: Long,
    /** Correlation captured when the event was committed; never stored in business payload. */
    val traceId: Identifier? = null,
) : DomainEvent {
    val payload: Map<String, String> =
        Collections.unmodifiableMap(LinkedHashMap(payload))

    init {
        require(type.isNotBlank()) { "Outbox event type must not be blank." }
        require(timestamp >= 0) { "Outbox event timestamp must not be negative." }
    }
}
