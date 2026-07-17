package ai.icen.fw.persistence.jdbc

import ai.icen.fw.core.id.Identifier
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.databind.jsontype.impl.LaissezFaireSubTypeValidator
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SecureDeletionJsonCodecTest {
    @Test
    fun `uses a canonical codec independent of hostile host mapper settings`() {
        val hostMapper = ObjectMapper()
            .setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .enable(JsonGenerator.Feature.WRITE_NUMBERS_AS_STRINGS)
            .activateDefaultTyping(
                LaissezFaireSubTypeValidator.instance,
                ObjectMapper.DefaultTyping.EVERYTHING,
                JsonTypeInfo.As.PROPERTY,
            )
        val codec = SecureDeletionJsonCodec(hostMapper)

        val identifiers = codec.encodeIdentifierList(listOf(Identifier("hold-1"), Identifier("hold-2")))
        val evidence = codec.encodeEvidence(linkedMapOf("requestId" to "opaque-1", "state" to "absent"))

        assertEquals(listOf("hold-1", "hold-2"), codec.decodeIdentifierList(identifiers).map { it.value })
        assertEquals(mapOf("requestId" to "opaque-1", "state" to "absent"), codec.decodeEvidence(evidence))
        assertTrue(identifiers.startsWith("["))
        assertTrue(evidence.startsWith("{"))
        assertFalse(identifiers.contains("@class"))
        assertFalse(evidence.contains("@class"))
    }

    @Test
    fun `rejects non textual duplicate and oversized identifier evidence`() {
        val codec = SecureDeletionJsonCodec(ObjectMapper())

        assertFailsWith<SecureDeletionPersistenceException> {
            codec.decodeIdentifierList("[\"hold-1\",1]")
        }
        assertFailsWith<SecureDeletionPersistenceException> {
            codec.decodeIdentifierList("[\"hold-1\",\"hold-1\"]")
        }
        assertFailsWith<SecureDeletionPersistenceException> {
            codec.decodeIdentifierList("[\"${"x".repeat(65)}\"]")
        }
        assertFailsWith<IllegalArgumentException> {
            codec.encodeIdentifierList(List(257) { Identifier("hold-$it") })
        }
    }

    @Test
    fun `rejects duplicate keys nested values and oversized provider evidence`() {
        val codec = SecureDeletionJsonCodec(ObjectMapper())

        assertFailsWith<SecureDeletionPersistenceException> {
            codec.decodeEvidence("{\"state\":\"absent\",\"state\":\"present\"}")
        }
        assertFailsWith<SecureDeletionPersistenceException> {
            codec.decodeEvidence("{\"state\":{\"nested\":\"not-allowed\"}}")
        }
        assertFailsWith<SecureDeletionPersistenceException> {
            codec.decodeEvidence("{\"state\":1}")
        }
        assertFailsWith<IllegalArgumentException> {
            codec.encodeEvidence(mapOf("state" to "x".repeat(513)))
        }
        assertFailsWith<IllegalArgumentException> {
            codec.encodeEvidence(mapOf("state" to "absent\nforged"))
        }
        assertFailsWith<SecureDeletionPersistenceException> {
            codec.decodeEvidence("{\"state\":\"absent\\u0000forged\"}")
        }
    }
}
