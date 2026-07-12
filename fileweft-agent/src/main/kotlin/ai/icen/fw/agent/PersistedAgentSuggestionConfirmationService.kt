package ai.icen.fw.agent

import ai.icen.fw.application.agent.AgentResultRepository
import ai.icen.fw.application.agent.PersistedAgentSuggestionConfirmation
import ai.icen.fw.application.task.BackgroundTaskStatus
import ai.icen.fw.application.task.TaskRepository
import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.core.id.IdentifierGenerator
import ai.icen.fw.spi.ai.AgentSuggestionConfirmation
import java.time.Clock

/**
 * Persists the explicit user acceptance that is required before another
 * application use case may apply an agent suggestion to a domain aggregate.
 */
class PersistedAgentSuggestionConfirmationService private constructor(
    private val results: AgentResultRepository,
    private val transaction: ApplicationTransaction,
    private val identifierGenerator: IdentifierGenerator,
    private val clock: Clock,
    private val tasks: TaskRepository?,
    private val fenced: Boolean,
) {
    /** Retains the original direct confirmation ABI. */
    constructor(
        results: AgentResultRepository,
        transaction: ApplicationTransaction,
        identifierGenerator: IdentifierGenerator,
        clock: Clock,
    ) : this(results, transaction, identifierGenerator, clock, null, false)

    /** Strong path that confirms only evidence from an acknowledged task. */
    constructor(
        results: AgentResultRepository,
        transaction: ApplicationTransaction,
        identifierGenerator: IdentifierGenerator,
        clock: Clock,
        tasks: TaskRepository,
    ) : this(results, transaction, identifierGenerator, clock, tasks, true)

    init {
        require(fenced == (tasks != null)) {
            "Agent suggestion confirmation fencing requires a task repository."
        }
    }

    fun confirm(
        tenantId: Identifier,
        taskId: Identifier,
        suggestionId: Identifier,
        confirmedBy: Identifier,
    ): AgentSuggestionConfirmation = transaction.execute {
        requireSuccessfulTask(tenantId, taskId)
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

    private fun requireSuccessfulTask(tenantId: Identifier, taskId: Identifier) {
        if (!fenced) return
        val task = requireNotNull(tasks).findById(tenantId, taskId)
            ?: throw NoSuchElementException("Agent task does not exist in the current tenant.")
        check(task.status == BackgroundTaskStatus.SUCCESS) {
            "Agent suggestions can be confirmed only after their durable task succeeds."
        }
    }
}
