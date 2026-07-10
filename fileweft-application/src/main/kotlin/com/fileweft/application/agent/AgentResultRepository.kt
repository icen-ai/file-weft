package com.fileweft.application.agent

import com.fileweft.core.id.Identifier
import com.fileweft.spi.ai.AgentCapability
import com.fileweft.spi.ai.AgentResult

/** Durable, tenant-scoped evidence produced by a background agent task. */
class PersistedAgentResult(
    val id: Identifier,
    val tenantId: Identifier,
    val taskId: Identifier,
    val capability: AgentCapability,
    val sourceEventId: Identifier,
    val sourceEventType: String,
    val result: AgentResult,
    val createdAt: Long,
) {
    init {
        require(result.taskId == taskId) { "Persisted agent result task must match its task id." }
        require(sourceEventType.isNotBlank()) { "Agent result source event type must not be blank." }
        require(createdAt >= 0) { "Agent result creation time must not be negative." }
    }
}

/** A tenant-scoped, immutable confirmation record for one agent suggestion. */
class PersistedAgentSuggestionConfirmation(
    val id: Identifier,
    val tenantId: Identifier,
    val taskId: Identifier,
    val suggestionId: Identifier,
    val confirmedBy: Identifier,
    val confirmedAt: Long,
) {
    init {
        require(confirmedAt >= 0) { "Agent suggestion confirmation time must not be negative." }
    }
}

interface AgentResultRepository {
    /** Repeated delivery of the same task must overwrite only its own result projection. */
    fun save(result: PersistedAgentResult)

    fun findByTask(tenantId: Identifier, taskId: Identifier): PersistedAgentResult?

    /** Returns the original confirmation when the same suggestion is confirmed again. */
    fun saveConfirmation(confirmation: PersistedAgentSuggestionConfirmation): PersistedAgentSuggestionConfirmation

    fun findConfirmations(tenantId: Identifier, taskId: Identifier): List<PersistedAgentSuggestionConfirmation>
}
