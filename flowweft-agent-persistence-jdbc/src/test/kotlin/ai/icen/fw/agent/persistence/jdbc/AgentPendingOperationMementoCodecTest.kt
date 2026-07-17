package ai.icen.fw.agent.persistence.jdbc

import ai.icen.fw.agent.api.ProviderId
import ai.icen.fw.agent.runtime.AgentDurableMementoCodec
import ai.icen.fw.agent.runtime.AgentPendingOperationMemento
import ai.icen.fw.agent.runtime.AgentRunLease
import ai.icen.fw.core.id.Identifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.security.MessageDigest

class AgentPendingOperationMementoCodecTest {
    private val codec = AgentDurableMementoCodec()

    @Test
    fun `operation frame round trips full pending evidence and fails closed on corruption`() {
        val initial = AgentDurableJdbcTestFixture.initial().state
        val claimed = initial.withClaimedLease(
            AgentRunLease(Identifier("lease-codec"), ProviderId("worker-codec"), 1L, 110L, 600L),
            110L,
        )
        val running = AgentDurableJdbcTestFixture.running(claimed).nextState
        val operation = requireNotNull(AgentDurableJdbcTestFixture.checkpointModel(running).nextState.pendingOperation)

        val encoded = codec.encodeOperation(operation)
        val restored = codec.decodeOperation(encoded)
        assertEquals(operation.operationId, restored.operationId)
        assertEquals(operation.stepId, restored.stepId)
        assertEquals(operation.attempt, restored.attempt)
        assertEquals(operation.operationDigest, restored.operationDigest)
        assertEquals(2, ByteBuffer.wrap(encoded.payload, 8, 4).int)
        assertTrue(encoded.payload.contentEquals(codec.encodeOperation(restored).payload))

        assertThrows(IllegalArgumentException::class.java) {
            AgentPendingOperationMemento.restore(encoded.payload, "0".repeat(64))
        }
        val truncated = encoded.payload.copyOf(encoded.payload.size - 1)
        assertThrows(IllegalArgumentException::class.java) {
            codec.decodeOperation(AgentPendingOperationMemento.restore(truncated, sha256(truncated)))
        }
        val forgedPreviousVersion = encoded.payload
        ByteBuffer.wrap(forgedPreviousVersion, 8, 4).putInt(1)
        assertThrows(IllegalArgumentException::class.java) {
            codec.decodeOperation(
                AgentPendingOperationMemento.restore(forgedPreviousVersion, sha256(forgedPreviousVersion)),
            )
        }
    }

    private fun sha256(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
