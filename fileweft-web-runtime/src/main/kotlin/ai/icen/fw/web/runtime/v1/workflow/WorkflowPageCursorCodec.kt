package ai.icen.fw.web.runtime.v1.workflow

import ai.icen.fw.core.id.Identifier
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.Base64

internal class WorkflowPageCursorCodec(private val kind: Byte) {
    init {
        require(kind == TASK_KIND || kind == HISTORY_KIND || kind == EVIDENCE_KIND) {
            "Unsupported workflow cursor kind."
        }
    }

    fun encode(createdTime: Long, id: Identifier): String = try {
        require(createdTime >= 0) { INVALID_OUTPUT_MESSAGE }
        val idBytes = safeIdBytes(id.value)
        val payload = ByteBuffer.allocate(HEADER_SIZE + idBytes.size)
            .put(VERSION)
            .put(kind)
            .putLong(createdTime)
            .putShort(idBytes.size.toShort())
            .put(idBytes)
            .array()
        Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
    } catch (_: RuntimeException) {
        throw IllegalStateException(INVALID_OUTPUT_MESSAGE)
    }

    fun decode(encoded: String): DecodedWorkflowCursor = try {
        requireSafeEncoded(encoded)
        val payload = Base64.getUrlDecoder().decode(encoded)
        if (payload.size < HEADER_SIZE) invalid()
        val buffer = ByteBuffer.wrap(payload)
        if (buffer.get() != VERSION || buffer.get() != kind) invalid()
        val createdTime = buffer.long
        if (createdTime < 0) invalid()
        val idLength = buffer.short.toInt() and UNSIGNED_SHORT_MASK
        if (idLength <= 0 || idLength > MAX_ID_BYTES || idLength % 2 != 0 || buffer.remaining() != idLength) invalid()
        val idBytes = ByteArray(idLength)
        buffer.get(idBytes)
        val id = String(idBytes, StandardCharsets.UTF_16BE)
        if (!id.toByteArray(StandardCharsets.UTF_16BE).contentEquals(idBytes)) invalid()
        requireSafeId(id)
        DecodedWorkflowCursor(createdTime, Identifier(id))
    } catch (_: RuntimeException) {
        throw IllegalArgumentException(INVALID_MESSAGE)
    }

    private fun safeIdBytes(value: String): ByteArray {
        requireSafeId(value)
        val bytes = value.toByteArray(StandardCharsets.UTF_16BE)
        require(bytes.isNotEmpty() && bytes.size <= MAX_ID_BYTES && bytes.size % 2 == 0) { INVALID_OUTPUT_MESSAGE }
        require(String(bytes, StandardCharsets.UTF_16BE) == value) { INVALID_OUTPUT_MESSAGE }
        return bytes
    }

    private fun requireSafeId(value: String) {
        require(value.isNotBlank() && value.length <= MAX_ID_LENGTH) { INVALID_MESSAGE }
        require(value.none(::unsafeCharacter)) { INVALID_MESSAGE }
    }

    private fun requireSafeEncoded(value: String) {
        if (
            value.isBlank() || value.length > MAX_CURSOR_LENGTH || value.any(::unsafeCharacter) ||
            value.any { character ->
                !(character in 'A'..'Z' || character in 'a'..'z' || character in '0'..'9' || character == '-' || character == '_')
            }
        ) invalid()
    }

    private fun invalid(): Nothing = throw IllegalArgumentException(INVALID_MESSAGE)

    companion object {
        const val TASK_KIND: Byte = 1
        const val HISTORY_KIND: Byte = 2
        const val EVIDENCE_KIND: Byte = 3
        private const val VERSION: Byte = 1
        private const val HEADER_SIZE: Int = 2 + Long.SIZE_BYTES + Short.SIZE_BYTES
        private const val MAX_ID_LENGTH: Int = 128
        private const val MAX_ID_BYTES: Int = MAX_ID_LENGTH * 2
        private const val MAX_CURSOR_LENGTH: Int = 512
        private const val UNSIGNED_SHORT_MASK: Int = 0xffff
        private const val INVALID_MESSAGE: String = "Invalid workflow page cursor."
        private const val INVALID_OUTPUT_MESSAGE: String = "Workflow query returned an invalid page cursor."
    }
}

internal class DecodedWorkflowCursor(
    val createdTime: Long,
    val id: Identifier,
)

private fun unsafeCharacter(character: Char): Boolean =
    Character.isISOControl(character) || Character.getType(character) == Character.FORMAT.toInt()
