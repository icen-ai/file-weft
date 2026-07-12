package ai.icen.fw.web.runtime.v1.audit

import ai.icen.fw.application.audit.DocumentAuditLogPageCursor
import ai.icen.fw.core.id.Identifier
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.Base64

/** Versioned, kind-specific opaque cursor containing only the audit ordering keys. */
internal class DocumentAuditLogCursorCodec {
    fun encode(cursor: DocumentAuditLogPageCursor): String = try {
        requireSafeId(cursor.id.value)
        val idBytes = cursor.id.value.toByteArray(StandardCharsets.UTF_16BE)
        require(idBytes.isNotEmpty() && idBytes.size <= MAX_ID_BYTES && idBytes.size % 2 == 0) {
            INVALID_OUTPUT_CURSOR_MESSAGE
        }
        require(String(idBytes, StandardCharsets.UTF_16BE) == cursor.id.value) { INVALID_OUTPUT_CURSOR_MESSAGE }
        val payload = ByteBuffer.allocate(HEADER_SIZE + idBytes.size)
            .put(CURSOR_VERSION)
            .put(CURSOR_KIND)
            .putLong(cursor.createdTime)
            .putShort(idBytes.size.toShort())
            .put(idBytes)
            .array()
        Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
    } catch (_: IllegalArgumentException) {
        throw IllegalStateException(INVALID_OUTPUT_CURSOR_MESSAGE)
    }

    fun decode(encoded: String): DocumentAuditLogPageCursor = try {
        requireSafeEncodedCursor(encoded)
        val payload = Base64.getUrlDecoder().decode(encoded)
        if (payload.size < HEADER_SIZE) invalidCursor()
        val buffer = ByteBuffer.wrap(payload)
        if (buffer.get() != CURSOR_VERSION || buffer.get() != CURSOR_KIND) invalidCursor()
        val createdTime = buffer.long
        if (createdTime < 0) invalidCursor()
        val idLength = buffer.short.toInt() and UNSIGNED_SHORT_MASK
        if (idLength <= 0 || idLength > MAX_ID_BYTES || idLength % 2 != 0 || buffer.remaining() != idLength) {
            invalidCursor()
        }
        val idBytes = ByteArray(idLength)
        buffer.get(idBytes)
        val id = String(idBytes, StandardCharsets.UTF_16BE)
        if (!id.toByteArray(StandardCharsets.UTF_16BE).contentEquals(idBytes)) invalidCursor()
        requireSafeId(id)
        DocumentAuditLogPageCursor(createdTime, Identifier(id))
    } catch (_: RuntimeException) {
        throw IllegalArgumentException(INVALID_CURSOR_MESSAGE)
    }

    private fun requireSafeEncodedCursor(value: String) {
        if (
            value.isBlank() ||
            value.length > MAX_CURSOR_LENGTH ||
            value.any(::isUnsafeControlCharacter) ||
            value.any { character ->
                !(character in 'A'..'Z' || character in 'a'..'z' || character in '0'..'9' || character == '-' || character == '_')
            }
        ) invalidCursor()
    }

    private fun requireSafeId(value: String) {
        require(value.isNotBlank() && value.length <= MAX_ID_LENGTH) { INVALID_CURSOR_MESSAGE }
        require(value.none(::isUnsafeControlCharacter)) { INVALID_CURSOR_MESSAGE }
    }

    private fun invalidCursor(): Nothing = throw IllegalArgumentException(INVALID_CURSOR_MESSAGE)

    private companion object {
        const val CURSOR_VERSION: Byte = 1
        const val CURSOR_KIND: Byte = 4
        const val HEADER_SIZE: Int = 12
        const val MAX_ID_LENGTH: Int = 64
        const val MAX_ID_BYTES: Int = MAX_ID_LENGTH * 2
        const val MAX_CURSOR_LENGTH: Int = 512
        const val UNSIGNED_SHORT_MASK: Int = 0xffff
        const val INVALID_CURSOR_MESSAGE: String = "Invalid document audit-log cursor."
        const val INVALID_OUTPUT_CURSOR_MESSAGE: String = "Document audit-log query returned an invalid page cursor."
    }
}

private fun isUnsafeControlCharacter(character: Char): Boolean =
    character.code in 0..31 || character.code in 127..159
