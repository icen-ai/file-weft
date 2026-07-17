package ai.icen.fw.agent.persistence.jdbc

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

class AgentEvaluationStateMementoCodecTest {
    private val codec = AgentEvaluationStateMementoCodec()

    @Test
    fun `round trip reconstructs through validated runtime contracts without fixture or output bytes`() {
        val state = AgentEvaluationJdbcTestFixture.completed(
            AgentEvaluationJdbcTestFixture.progressed(
                AgentEvaluationJdbcTestFixture.claimed(AgentEvaluationJdbcTestFixture.initial()),
            ),
        )

        val memento = codec.encode(state)
        val restored = codec.decode(memento)

        assertEquals(state.evaluationId, restored.evaluationId)
        assertEquals(state.requestBindingDigest, restored.requestBindingDigest)
        assertEquals(state.suite.suiteDigest, restored.suite.suiteDigest)
        assertEquals(state.providerSnapshot.snapshotDigest, restored.providerSnapshot.snapshotDigest)
        assertEquals(state.status, restored.status)
        assertEquals(state.stateVersion, restored.stateVersion)
        assertEquals(state.evidence.single().evidenceDigest, restored.evidence.single().evidenceDigest)
        assertEquals(64, memento.digest.length)
        assertFalse(
            String(memento.payload(), StandardCharsets.ISO_8859_1).contains("fixture bytes must remain ephemeral"),
        )
    }

    @Test
    fun `database digest internal checksum schema version and enums fail closed`() {
        val state = AgentEvaluationJdbcTestFixture.completed(
            AgentEvaluationJdbcTestFixture.progressed(
                AgentEvaluationJdbcTestFixture.claimed(AgentEvaluationJdbcTestFixture.initial()),
            ),
        )
        val memento = codec.encode(state)
        val corrupted = memento.payload().also { payload -> payload[12] = (payload[12].toInt() xor 1).toByte() }

        assertThrows(IllegalArgumentException::class.java) { codec.decode(corrupted, memento.digest) }
        val corruptedExternalDigest = sha256(corrupted).joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
        assertThrows(IllegalArgumentException::class.java) {
            codec.decode(corrupted, corruptedExternalDigest)
        }

        val unknownVersion = reseal(memento.payload()) { content -> content[7] = 2 }
        assertThrows(IllegalArgumentException::class.java) {
            codec.decode(unknownVersion.first, unknownVersion.second)
        }

        val unknownSchema = reseal(memento.payload()) { content -> content[0] = 0 }
        assertThrows(IllegalArgumentException::class.java) {
            codec.decode(unknownSchema.first, unknownSchema.second)
        }

        val unknownStatus = reseal(memento.payload()) { content ->
            val known = "COMPLETED".toByteArray(StandardCharsets.UTF_8)
            val replacement = "UNKNOWN__".toByteArray(StandardCharsets.UTF_8)
            val index = content.indexOf(known)
            assertTrue(index >= 0)
            replacement.copyInto(content, index)
        }
        assertThrows(IllegalStateException::class.java) {
            codec.decode(unknownStatus.first, unknownStatus.second)
        }
    }

    private fun reseal(payload: ByteArray, mutate: (ByteArray) -> Unit): Pair<ByteArray, String> {
        val content = payload.copyOfRange(0, payload.size - 32)
        mutate(content)
        val sealed = content + sha256(content)
        return sealed to sha256(sealed).joinToString(separator = "") { byte ->
            "%02x".format(byte.toInt() and 0xff)
        }
    }

    private fun ByteArray.indexOf(needle: ByteArray): Int {
        for (offset in 0..size - needle.size) {
            if (needle.indices.all { index -> this[offset + index] == needle[index] }) return offset
        }
        return -1
    }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)
}
