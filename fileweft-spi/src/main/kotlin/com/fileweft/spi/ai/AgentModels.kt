package com.fileweft.spi.ai

import com.fileweft.core.id.Identifier
import java.util.Collections
import java.util.LinkedHashMap

enum class AgentCapability {
    METADATA,
    DUPLICATE,
    CLASSIFICATION,
    SECURITY,
}

enum class AgentExecutionStatus {
    SUCCEEDED,
    FAILED,
    UNSUPPORTED,
}

/** Tenant-scoped, idempotent unit of work derived from a domain event. */
class AgentTask @JvmOverloads constructor(
    val id: Identifier,
    val tenantId: Identifier,
    val capability: AgentCapability,
    val sourceEventId: Identifier,
    val sourceEventType: String,
    val idempotencyKey: String,
    context: Map<String, String> = emptyMap(),
    val submittedAt: Long,
) {
    val context: Map<String, String> = Collections.unmodifiableMap(LinkedHashMap(context))

    init {
        require(sourceEventType.isNotBlank()) { "Agent task source event type must not be blank." }
        require(idempotencyKey.isNotBlank()) { "Agent task idempotency key must not be blank." }
        require(submittedAt >= 0) { "Agent task submission time must not be negative." }
    }
}

/** Candidate output that requires an explicit application-level confirmation. */
class AgentSuggestion @JvmOverloads constructor(
    val id: Identifier,
    val type: String,
    payload: Map<String, String> = emptyMap(),
    val explanation: String? = null,
    val confirmationRequired: Boolean = true,
) {
    val payload: Map<String, String> = Collections.unmodifiableMap(LinkedHashMap(payload))

    init {
        require(type.isNotBlank()) { "Agent suggestion type must not be blank." }
        require(explanation == null || explanation.isNotBlank()) {
            "Agent suggestion explanation must not be blank when provided."
        }
        require(confirmationRequired) { "FileWeft agent suggestions must require explicit confirmation." }
    }
}

class AgentResult @JvmOverloads constructor(
    val taskId: Identifier,
    val status: AgentExecutionStatus,
    suggestions: List<AgentSuggestion> = emptyList(),
    val message: String? = null,
    val completedAt: Long,
) {
    val suggestions: List<AgentSuggestion> = Collections.unmodifiableList(ArrayList(suggestions))

    init {
        require(message == null || message.isNotBlank()) { "Agent result message must not be blank when provided." }
        require(completedAt >= 0) { "Agent result completion time must not be negative." }
        require(status == AgentExecutionStatus.SUCCEEDED || suggestions.isEmpty()) {
            "Only successful agent results may contain suggestions."
        }
        require(suggestions.map { it.id }.distinct().size == suggestions.size) {
            "Agent result suggestion identifiers must be unique."
        }
    }
}

/** Immutable audit intent created when a user accepts a suggestion. */
class AgentSuggestionConfirmation(
    val taskId: Identifier,
    val suggestionId: Identifier,
    val confirmedBy: Identifier,
    val confirmedAt: Long,
) {
    init {
        require(confirmedAt >= 0) { "Agent suggestion confirmation time must not be negative." }
    }
}
