package ai.icen.fw.persistence.jdbc

import ai.icen.fw.core.id.Identifier
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.json.JsonMapper
import java.util.LinkedHashMap

class SecureDeletionPersistenceException @JvmOverloads constructor(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)

/**
 * Hardened wire codec for deletion evidence persisted in JSON columns.
 *
 * The host mapper is deliberately ignored. Persistence must not inherit host
 * default typing, naming, modules, coercion, pretty printing, or serializers.
 */
internal class SecureDeletionJsonCodec(
    @Suppress("UNUSED_PARAMETER") hostObjectMapper: ObjectMapper,
) {
    private val objectMapper: ObjectMapper = FLOWWEFT_OBJECT_MAPPER

    fun encodeIdentifierList(identifiers: List<Identifier>): String {
        require(identifiers.size <= MAX_IDENTIFIER_ITEMS) {
            "Secure-deletion identifier evidence exceeds the persisted boundary."
        }
        require(identifiers.distinct().size == identifiers.size) {
            "Secure-deletion identifier evidence must be unique."
        }
        val root = objectMapper.createArrayNode()
        identifiers.forEach { identifier ->
            validateText(identifier.value, "identifier", MAX_IDENTIFIER_LENGTH)
            root.add(identifier.value)
        }
        return encode(root)
    }

    fun decodeIdentifierList(rawJson: String): List<Identifier> {
        val root = decode(rawJson, "identifier evidence")
        if (!root.isArray || root.size() > MAX_IDENTIFIER_ITEMS) {
            throw SecureDeletionPersistenceException("Persisted secure-deletion identifier evidence is invalid.")
        }
        val seen = LinkedHashSet<String>()
        val values = ArrayList<Identifier>(root.size())
        root.forEach { node ->
            if (!node.isTextual) {
                throw SecureDeletionPersistenceException(
                    "Persisted secure-deletion identifier evidence contains a non-text value.",
                )
            }
            val value = node.textValue()
            validatePersistedText(value, "identifier", MAX_IDENTIFIER_LENGTH)
            if (!seen.add(value)) {
                throw SecureDeletionPersistenceException(
                    "Persisted secure-deletion identifier evidence contains a duplicate.",
                )
            }
            values += Identifier(value)
        }
        return values
    }

    fun encodeEvidence(evidence: Map<String, String>): String {
        require(evidence.size <= MAX_EVIDENCE_ENTRIES) {
            "Secure-deletion provider evidence exceeds the persisted boundary."
        }
        val root = objectMapper.createObjectNode()
        evidence.forEach { (key, value) ->
            validateText(key, "evidence key", MAX_EVIDENCE_KEY_LENGTH)
            validateText(value, "evidence value", MAX_EVIDENCE_VALUE_LENGTH)
            root.put(key, value)
        }
        return encode(root)
    }

    fun decodeEvidence(rawJson: String): Map<String, String> {
        val root = decode(rawJson, "provider evidence")
        if (!root.isObject || root.size() > MAX_EVIDENCE_ENTRIES) {
            throw SecureDeletionPersistenceException("Persisted secure-deletion provider evidence is invalid.")
        }
        val evidence = LinkedHashMap<String, String>()
        root.fields().forEach { (key, node) ->
            validatePersistedText(key, "evidence key", MAX_EVIDENCE_KEY_LENGTH)
            if (!node.isTextual) {
                throw SecureDeletionPersistenceException(
                    "Persisted secure-deletion provider evidence contains a non-text value.",
                )
            }
            val value = node.textValue()
            validatePersistedText(value, "evidence value", MAX_EVIDENCE_VALUE_LENGTH)
            evidence[key] = value
        }
        return evidence
    }

    private fun encode(node: JsonNode): String = try {
        objectMapper.writeValueAsString(node).also { raw ->
            check(raw.length <= MAX_JSON_LENGTH) {
                "Secure-deletion evidence exceeds the persisted JSON boundary."
            }
        }
    } catch (failure: SecureDeletionPersistenceException) {
        throw failure
    } catch (failure: Exception) {
        throw SecureDeletionPersistenceException("Secure-deletion evidence could not be encoded.", failure)
    }

    private fun decode(rawJson: String, description: String): JsonNode {
        if (rawJson.length > MAX_JSON_LENGTH) {
            throw SecureDeletionPersistenceException("Persisted secure-deletion $description is too large.")
        }
        return try {
            objectMapper.readTree(rawJson)
                ?: throw SecureDeletionPersistenceException("Persisted secure-deletion $description is empty.")
        } catch (failure: SecureDeletionPersistenceException) {
            throw failure
        } catch (failure: Exception) {
            throw SecureDeletionPersistenceException(
                "Persisted secure-deletion $description is not valid JSON.",
                failure,
            )
        }
    }

    private fun validateText(value: String, field: String, maximumLength: Int) {
        require(value.isNotBlank() && value.length <= maximumLength && value.none(::isUnsafeControl)) {
            "Secure-deletion $field is outside the persisted boundary."
        }
    }

    private fun validatePersistedText(value: String, field: String, maximumLength: Int) {
        if (value.isBlank() || value.length > maximumLength || value.any(::isUnsafeControl)) {
            throw SecureDeletionPersistenceException("Persisted secure-deletion $field is invalid.")
        }
    }

    private fun isUnsafeControl(character: Char): Boolean = character < ' ' || character == '\u007f'

    private companion object {
        val FLOWWEFT_OBJECT_MAPPER: ObjectMapper = JsonMapper.builder()
            .deactivateDefaultTyping()
            .disable(JsonGenerator.Feature.WRITE_NUMBERS_AS_STRINGS)
            .disable(SerializationFeature.INDENT_OUTPUT)
            .disable(SerializationFeature.WRAP_ROOT_VALUE)
            .disable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .disable(DeserializationFeature.UNWRAP_ROOT_VALUE)
            .disable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY)
            .disable(DeserializationFeature.ACCEPT_EMPTY_STRING_AS_NULL_OBJECT)
            .build()
            .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)

        const val MAX_JSON_LENGTH = 65_536
        const val MAX_IDENTIFIER_ITEMS = 256
        const val MAX_IDENTIFIER_LENGTH = 64
        const val MAX_EVIDENCE_ENTRIES = 64
        const val MAX_EVIDENCE_KEY_LENGTH = 128
        const val MAX_EVIDENCE_VALUE_LENGTH = 512
    }
}
