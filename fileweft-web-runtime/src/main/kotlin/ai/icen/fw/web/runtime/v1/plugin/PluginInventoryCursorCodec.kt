package ai.icen.fw.web.runtime.v1.plugin

import ai.icen.fw.application.plugin.PluginInventoryCursor
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.Base64

/** Versioned transport-private cursor containing only the stable plugin sort key. */
internal class PluginInventoryCursorCodec {
    fun encode(cursor: PluginInventoryCursor): String = try {
        requireSafePluginId(cursor.pluginId)
        val idBytes = cursor.pluginId.toByteArray(StandardCharsets.UTF_16BE)
        require(idBytes.isNotEmpty() && idBytes.size <= MAX_ID_BYTES && idBytes.size % 2 == 0) {
            INVALID_OUTPUT_CURSOR_MESSAGE
        }
        require(String(idBytes, StandardCharsets.UTF_16BE) == cursor.pluginId) { INVALID_OUTPUT_CURSOR_MESSAGE }
        val payload = ByteBuffer.allocate(HEADER_SIZE + idBytes.size)
            .put(CURSOR_VERSION)
            .put(CURSOR_KIND)
            .putShort(idBytes.size.toShort())
            .put(idBytes)
            .array()
        Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
    } catch (_: IllegalArgumentException) {
        throw IllegalStateException(INVALID_OUTPUT_CURSOR_MESSAGE)
    }

    fun decode(encoded: String): PluginInventoryCursor = try {
        requireSafeEncodedCursor(encoded)
        val payload = Base64.getUrlDecoder().decode(encoded)
        if (payload.size < HEADER_SIZE) invalidCursor()
        val buffer = ByteBuffer.wrap(payload)
        if (buffer.get() != CURSOR_VERSION || buffer.get() != CURSOR_KIND) invalidCursor()
        val idLength = buffer.short.toInt() and UNSIGNED_SHORT_MASK
        if (idLength <= 0 || idLength > MAX_ID_BYTES || idLength % 2 != 0 || buffer.remaining() != idLength) invalidCursor()
        val idBytes = ByteArray(idLength)
        buffer.get(idBytes)
        val pluginId = String(idBytes, StandardCharsets.UTF_16BE)
        if (!pluginId.toByteArray(StandardCharsets.UTF_16BE).contentEquals(idBytes)) invalidCursor()
        requireSafePluginId(pluginId)
        PluginInventoryCursor(pluginId)
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

    private fun requireSafePluginId(value: String) {
        require(value.isNotBlank() && value.length <= MAX_PLUGIN_ID_LENGTH) { INVALID_CURSOR_MESSAGE }
        require(!isPluginIdBoundaryWhitespace(value.first()) && !isPluginIdBoundaryWhitespace(value.last())) {
            INVALID_CURSOR_MESSAGE
        }
        require(value.none(::isUnsafePluginIdCharacter)) { INVALID_CURSOR_MESSAGE }
    }

    private fun invalidCursor(): Nothing = throw IllegalArgumentException(INVALID_CURSOR_MESSAGE)

    private companion object {
        const val CURSOR_VERSION: Byte = 1
        const val CURSOR_KIND: Byte = 3
        const val HEADER_SIZE: Int = 4
        const val MAX_PLUGIN_ID_LENGTH: Int = 128
        const val MAX_ID_BYTES: Int = MAX_PLUGIN_ID_LENGTH * 2
        const val MAX_CURSOR_LENGTH: Int = 512
        const val UNSIGNED_SHORT_MASK: Int = 0xffff
        const val INVALID_CURSOR_MESSAGE: String = "Invalid plugin inventory cursor."
        const val INVALID_OUTPUT_CURSOR_MESSAGE: String = "Plugin inventory returned an invalid page cursor."
    }
}

private fun isUnsafeControlCharacter(character: Char): Boolean =
    character.code in 0..31 || character.code in 127..159

private fun isUnsafePluginIdCharacter(character: Char): Boolean =
    Character.isISOControl(character) || Character.getType(character) == Character.FORMAT.toInt()

private fun isPluginIdBoundaryWhitespace(character: Char): Boolean =
    Character.isWhitespace(character) || Character.isSpaceChar(character)
