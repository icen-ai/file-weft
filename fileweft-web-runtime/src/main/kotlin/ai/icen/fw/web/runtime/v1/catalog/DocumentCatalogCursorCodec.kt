package ai.icen.fw.web.runtime.v1.catalog

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Base64

/**
 * Fixed-size cursor binding an index to the visible folder id at that index.
 * If an ACL or provider-tree change shifts the boundary, validation fails
 * closed instead of continuing from an attacker-controlled identifier.
 */
internal class DocumentCatalogCursorCodec {
    fun encode(position: Int, folderId: String): String {
        require(position >= 0) { INVALID_OUTPUT_MESSAGE }
        val payload = ByteBuffer.allocate(PAYLOAD_SIZE)
            .put(VERSION)
            .put(KIND)
            .putInt(position)
            .put(digest(folderId))
            .array()
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
    }

    fun decode(encoded: String): DocumentCatalogCursor = try {
        require(
            encoded.isNotBlank() &&
                encoded.length <= MAX_CURSOR_LENGTH &&
                encoded.all { character ->
                    character in 'A'..'Z' || character in 'a'..'z' ||
                        character in '0'..'9' || character == '-' || character == '_'
                },
        )
        val payload = Base64.getUrlDecoder().decode(encoded)
        require(payload.size == PAYLOAD_SIZE)
        val buffer = ByteBuffer.wrap(payload)
        require(buffer.get() == VERSION && buffer.get() == KIND)
        val position = buffer.int
        require(position >= 0)
        val expectedDigest = ByteArray(DIGEST_LENGTH)
        buffer.get(expectedDigest)
        DocumentCatalogCursor(position, expectedDigest)
    } catch (_: RuntimeException) {
        throw IllegalArgumentException(INVALID_INPUT_MESSAGE)
    }

    fun matches(cursor: DocumentCatalogCursor, folderId: String): Boolean =
        MessageDigest.isEqual(cursor.folderDigest, digest(folderId))

    private fun digest(folderId: String): ByteArray {
        val full = MessageDigest.getInstance("SHA-256").digest(folderId.toByteArray(StandardCharsets.UTF_8))
        return full.copyOf(DIGEST_LENGTH)
    }

    private companion object {
        const val VERSION: Byte = 1
        const val KIND: Byte = 4
        const val DIGEST_LENGTH = 16
        const val PAYLOAD_SIZE = 2 + Int.SIZE_BYTES + DIGEST_LENGTH
        const val MAX_CURSOR_LENGTH = 64
        const val INVALID_INPUT_MESSAGE = "Invalid document catalog page cursor."
        const val INVALID_OUTPUT_MESSAGE = "Document catalog returned an invalid page cursor."
    }
}

internal class DocumentCatalogCursor(
    val position: Int,
    val folderDigest: ByteArray,
)
