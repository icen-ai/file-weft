package ai.icen.fw.dev.api.agent

import ai.icen.fw.core.event.OutboxEvent
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.ai.AgentCapability
import ai.icen.fw.spi.ai.AgentExecutionStatus
import ai.icen.fw.spi.ai.AgentResult
import ai.icen.fw.spi.ai.AgentSuggestion
import ai.icen.fw.spi.ai.AgentTask
import ai.icen.fw.spi.ai.AgentTaskTrigger
import ai.icen.fw.spi.ai.FileWeftAgent

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
