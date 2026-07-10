package com.fileweft.core.event

import com.fileweft.core.id.Identifier

interface DomainEvent {
    val id: Identifier
    val tenantId: Identifier
    val timestamp: Long
}
