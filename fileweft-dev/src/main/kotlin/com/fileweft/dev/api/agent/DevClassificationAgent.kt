package com.fileweft.dev.api.agent

import com.fileweft.core.event.OutboxEvent
import com.fileweft.core.id.Identifier
import com.fileweft.spi.ai.AgentCapability
import com.fileweft.spi.ai.AgentExecutionStatus
import com.fileweft.spi.ai.AgentResult
import com.fileweft.spi.ai.AgentSuggestion
import com.fileweft.spi.ai.AgentTask
import com.fileweft.spi.ai.AgentTaskTrigger
import com.fileweft.spi.ai.FileWeftAgent

/** Deterministic development agent used to exercise the complete consent path without a model dependency. */
class DevClassificationAgent : FileWeftAgent {
    override fun capability(): AgentCapability = AgentCapability.CLASSIFICATION

    override fun execute(task: AgentTask): AgentResult = AgentResult(
        taskId = task.id,
        status = AgentExecutionStatus.SUCCEEDED,
        suggestions = listOf(
            AgentSuggestion(
                id = Identifier("${task.id.value}-classification"),
                type = "document.classification",
                payload = mapOf("classification" to "INTERNAL", "retentionYears" to "7"),
                explanation = "Development classification evidence generated from a committed publish event.",
            ),
        ),
        completedAt = System.currentTimeMillis(),
    )
}

class DevPublishClassificationTrigger : AgentTaskTrigger {
    override fun supports(event: OutboxEvent): Boolean = event.type == DELIVERY_TARGET_REQUESTED_EVENT_TYPE

    override fun capabilities(event: OutboxEvent): Collection<AgentCapability> = listOf(AgentCapability.CLASSIFICATION)

    override fun businessId(event: OutboxEvent): Identifier? = event.payload[DOCUMENT_ID_KEY]?.let(::Identifier)

    private companion object {
        const val DELIVERY_TARGET_REQUESTED_EVENT_TYPE = "document.delivery.target.requested"
        const val DOCUMENT_ID_KEY = "documentId"
    }
}
