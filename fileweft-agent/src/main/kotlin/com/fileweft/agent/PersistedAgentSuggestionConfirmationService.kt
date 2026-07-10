package com.fileweft.agent

import com.fileweft.application.agent.AgentResultRepository
import com.fileweft.application.agent.PersistedAgentSuggestionConfirmation
import com.fileweft.application.transaction.ApplicationTransaction
import com.fileweft.core.id.Identifier
import com.fileweft.core.id.IdentifierGenerator
import com.fileweft.spi.ai.AgentSuggestionConfirmation
import java.time.Clock

/**
 * Persists the explicit user acceptance that is required before another
 * application use case may apply an agent suggestion to a domain aggregate.
 */
class PersistedAgentSuggestionConfirmationService(
    private val results: AgentResultRepository,
    private val transaction: ApplicationTransaction,
    private val identifierGenerator: IdentifierGenerator,
    private val clock: Clock,
) {
    fun confirm(
        tenantId: Identifier,
        taskId: Identifier,
        suggestionId: Identifier,
        confirmedBy: Identifier,
    ): AgentSuggestionConfirmation = transaction.execute {
        val persisted = requireNotNull(results.findByTask(tenantId, taskId)) {
            "Agent result does not exist in the current tenant."
        }
        val confirmation = AgentSuggestionConfirmationService(clock).confirm(persisted.result, suggestionId, confirmedBy)
        val saved = results.saveConfirmation(
            PersistedAgentSuggestionConfirmation(
                id = identifierGenerator.nextId(),
                tenantId = tenantId,
                taskId = taskId,
                suggestionId = suggestionId,
                confirmedBy = confirmedBy,
                confirmedAt = confirmation.confirmedAt,
            ),
        )
        AgentSuggestionConfirmation(saved.taskId, saved.suggestionId, saved.confirmedBy, saved.confirmedAt)
    }
}
