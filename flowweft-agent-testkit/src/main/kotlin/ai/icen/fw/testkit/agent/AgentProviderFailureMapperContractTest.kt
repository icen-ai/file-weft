package ai.icen.fw.testkit.agent

import ai.icen.fw.agent.api.AgentProviderFailureMapper
import ai.icen.fw.agent.api.AgentProviderFailures
import ai.icen.fw.agent.api.AgentProviderOperationId
import ai.icen.fw.agent.api.ProviderId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotSame
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Contract proving raw SDK failures are normalized to provider-bound, non-sensitive evidence. */
abstract class AgentProviderFailureMapperContractTest {
    protected abstract val failureMapper: AgentProviderFailureMapper

    protected abstract fun providerId(): ProviderId

    protected open fun operationId(): AgentProviderOperationId = AgentProviderOperationId.TOOL

    /** Marker embedded in [rawFailure] that must not cross the safe failure boundary. */
    protected open fun sensitiveMarker(): String = "testkit-sensitive-provider-payload"

    protected open fun rawFailure(): Throwable = IllegalStateException(sensitiveMarker())

    @Test
    fun `normalizes a raw provider failure without leaking its payload or cause`() {
        val providerId = providerId()
        val raw = rawFailure()
        val normalized = AgentProviderFailures.normalize(providerId, operationId(), failureMapper, raw)

        assertEquals(providerId, normalized.providerId)
        assertNotSame(raw, normalized)
        assertNull(normalized.cause, "A raw SDK failure must not remain attached as a cause.")
        assertTrue(normalized.suppressed.isEmpty(), "Raw provider failures must not survive as suppressed exceptions.")
        val visible = listOfNotNull(normalized.code, normalized.message, normalized.toString()).joinToString("\n")
        assertFalse(
            visible.contains(sensitiveMarker(), ignoreCase = true),
            "The mapped provider failure leaked the configured sensitive marker.",
        )
    }
}
