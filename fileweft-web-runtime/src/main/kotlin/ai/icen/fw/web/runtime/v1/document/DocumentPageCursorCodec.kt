package ai.icen.fw.web.runtime.v1.document

import ai.icen.fw.application.document.DocumentPageCursor
import ai.icen.fw.core.id.Identifier
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Transport-private cursor codec for the v1 document list.
 *
 * The payload has a one-byte format version followed by the stable sort keys
 * only: update time and document id. It is intentionally opaque but not a
 * secrecy or integrity primitive; tenant filtering and authorization remain in
 * [ai.icen.fw.application.document.DocumentQueryService].
 */
internal class DocumentPageCursorCodec {
    fun encode(cursor: DocumentPageCursor): String = try {
        val documentId = cursor.id.value
        requireSafeDocumentId(documentId)
        val idBytes = documentId.toByteArray(StandardCharsets.UTF_16BE)
        require(idBytes.isNotEmpty() && idBytes.size <= MAX_IDENTIFIER_BYTES && idBytes.size % 2 == 0) {
            "Document page cursor id is invalid."
        }
        require(String(idBytes, StandardCharsets.UTF_16BE) == documentId) {
            "Document page cursor id is invalid."
        }
        val payload = ByteBuffer.allocate(HEADER_SIZE + idBytes.size)
            .put(CURSOR_VERSION)
            .putLong(cursor.updatedTime)
            .putShort(idBytes.size.toShort())
            .put(idBytes)
            .array()
        Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
    } catch (_: IllegalArgumentException) {
        throw IllegalStateException(INVALID_OUTPUT_CURSOR_MESSAGE)
    }

    fun decode(encoded: String): DocumentPageCursor = try {
        requireSafeEncodedCursor(encoded)
        val payload = Base64.getUrlDecoder().decode(encoded)
        if (payload.size < HEADER_SIZE) invalidCursor()
        val buffer = ByteBuffer.wrap(payload)
        if (buffer.get() != CURSOR_VERSION) invalidCursor()
        val updatedTime = buffer.long
        if (updatedTime < 0) invalidCursor()
        val idLength = buffer.short.toInt() and UNSIGNED_SHORT_MASK
        if (idLength <= 0 || idLength > MAX_IDENTIFIER_BYTES || idLength % 2 != 0 || buffer.remaining() != idLength) {
            invalidCursor()
        }
        val idBytes = ByteArray(idLength)
        buffer.get(idBytes)
        val documentId = String(idBytes, StandardCharsets.UTF_16BE)
        if (!documentId.toByteArray(StandardCharsets.UTF_16BE).contentEquals(idBytes)) invalidCursor()
        requireSafeDocumentId(documentId)
        DocumentPageCursor(updatedTime, Identifier(documentId))
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
        ) {
            invalidCursor()
        }
    }

    private fun requireSafeDocumentId(value: String) {
        require(value.isNotBlank()) { "Document page cursor id is invalid." }
        require(value.length <= MAX_DOCUMENT_ID_LENGTH) { "Document page cursor id is invalid." }
        require(value.none(::isUnsafeControlCharacter)) { "Document page cursor id is invalid." }
    }

    private fun invalidCursor(): Nothing = throw IllegalArgumentException(INVALID_CURSOR_MESSAGE)

    private companion object {
        const val CURSOR_VERSION: Byte = 1
        const val HEADER_SIZE: Int = 1 + Long.SIZE_BYTES + Short.SIZE_BYTES
        const val MAX_DOCUMENT_ID_LENGTH: Int = 128
        const val MAX_IDENTIFIER_BYTES: Int = MAX_DOCUMENT_ID_LENGTH * 2
        const val MAX_CURSOR_LENGTH: Int = 512
        const val UNSIGNED_SHORT_MASK: Int = 0xffff
        const val INVALID_CURSOR_MESSAGE: String = "Invalid document page cursor."
        const val INVALID_OUTPUT_CURSOR_MESSAGE: String = "Document query returned an invalid page cursor."
    }
}

private fun isUnsafeControlCharacter(character: Char): Boolean =
    character.code in 0..31 || character.code in 127..159
