package com.fileweft.agent

import com.fileweft.core.id.Identifier
import com.fileweft.spi.ai.AgentExecutionStatus
import com.fileweft.spi.ai.AgentResult
import com.fileweft.spi.ai.AgentSuggestionConfirmation
import java.time.Clock

/**
 * Records an explicit acceptance of an agent suggestion. Applying the accepted
 * suggestion remains an application use case; this service never mutates a
 * domain aggregate.
 */
class AgentSuggestionConfirmationService(
    private val clock: Clock,
) {
    fun confirm(result: AgentResult, suggestionId: Identifier, confirmedBy: Identifier): AgentSuggestionConfirmation {
        require(result.status == AgentExecutionStatus.SUCCEEDED) {
            "Only successful agent results can be confirmed."
        }
        require(result.suggestions.any { it.id == suggestionId }) {
            "Suggestion does not belong to the supplied agent result."
        }
        return AgentSuggestionConfirmation(result.taskId, suggestionId, confirmedBy, clock.millis())
    }
}
