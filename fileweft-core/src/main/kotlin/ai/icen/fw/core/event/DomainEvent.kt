package ai.icen.fw.core.event

import ai.icen.fw.core.id.Identifier

interface DomainEvent {
    val id: Identifier
    val tenantId: Identifier
    val timestamp: Long
}
