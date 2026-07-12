package ai.icen.fw.agent

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.ai.AgentExecutionStatus
import ai.icen.fw.spi.ai.AgentResult
import ai.icen.fw.spi.ai.AgentSuggestion
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AgentSuggestionConfirmationServiceTest {
    private val service = AgentSuggestionConfirmationService(Clock.fixed(Instant.ofEpochMilli(10), ZoneOffset.UTC))

    @Test
    fun `records explicit confirmation only for a suggestion returned by a successful task`() {
        val result = AgentResult(
            Identifier("task-1"),
            AgentExecutionStatus.SUCCEEDED,
            listOf(AgentSuggestion(Identifier("suggestion-1"), "document.classification")),
            completedAt = 1,
        )

        val confirmation = service.confirm(result, Identifier("suggestion-1"), Identifier("operator-1"))

        assertEquals("task-1", confirmation.taskId.value)
        assertEquals("operator-1", confirmation.confirmedBy.value)
        assertEquals(10, confirmation.confirmedAt)
    }

    @Test
    fun `rejects non successful results and unknown suggestions`() {
        val unsupported = AgentResult(Identifier("task-1"), AgentExecutionStatus.UNSUPPORTED, completedAt = 1)
        val successful = AgentResult(
            Identifier("task-1"), AgentExecutionStatus.SUCCEEDED,
            listOf(AgentSuggestion(Identifier("suggestion-1"), "document.classification")), completedAt = 1,
        )

        assertFailsWith<IllegalArgumentException> {
            service.confirm(unsupported, Identifier("suggestion-1"), Identifier("operator-1"))
        }
        assertFailsWith<IllegalArgumentException> {
            service.confirm(successful, Identifier("other-suggestion"), Identifier("operator-1"))
        }
    }
}
