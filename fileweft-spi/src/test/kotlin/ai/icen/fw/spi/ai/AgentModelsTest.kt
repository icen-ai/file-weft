package ai.icen.fw.spi.ai

import ai.icen.fw.core.id.Identifier
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AgentModelsTest {
    @Test
    fun `defensively copies agent task context and result suggestions`() {
        val context = linkedMapOf("fileObjectId" to "file-1")
        val payload = linkedMapOf("classification" to "contract")
        val suggestion = AgentSuggestion(Identifier("suggestion-1"), "document.classification", payload)
        val suggestions = mutableListOf(suggestion)
        val task = AgentTask(
            Identifier("task-1"), Identifier("tenant-1"), AgentCapability.CLASSIFICATION,
            Identifier("event-1"), "file.uploaded", "event-1:CLASSIFICATION", context, 1,
        )
        val result = AgentResult(Identifier("task-1"), AgentExecutionStatus.SUCCEEDED, suggestions, completedAt = 2)
        context["fileObjectId"] = "changed"
        payload["classification"] = "changed"
        suggestions.clear()

        assertEquals("file-1", task.context["fileObjectId"])
        assertEquals("contract", result.suggestions.single().payload["classification"])
        assertEquals(1, result.suggestions.size)
    }

    @Test
    fun `rejects automatic suggestions and failed results with suggestions`() {
        assertFailsWith<IllegalArgumentException> {
            AgentSuggestion(Identifier("suggestion-1"), "document.classification", confirmationRequired = false)
        }
        assertFailsWith<IllegalArgumentException> {
            AgentResult(
                Identifier("task-1"),
                AgentExecutionStatus.FAILED,
                listOf(AgentSuggestion(Identifier("suggestion-1"), "document.classification")),
                completedAt = 1,
            )
        }
    }

    @Test
    fun `exposes a Java friendly agent execution contract`() {
        val agent = object : FileWeftAgent {
            override fun capability(): AgentCapability = AgentCapability.METADATA
            override fun execute(task: AgentTask): AgentResult =
                AgentResult(task.id, AgentExecutionStatus.SUCCEEDED, completedAt = 3)
        }
        val task = AgentTask(
            Identifier("task-1"), Identifier("tenant-1"), AgentCapability.METADATA,
            Identifier("event-1"), "file.uploaded", "event-1:METADATA", submittedAt = 1,
        )

        assertEquals(AgentCapability.METADATA, agent.capability())
        assertEquals(AgentExecutionStatus.SUCCEEDED, agent.execute(task).status)
    }
}
