package ai.icen.fw.web.runtime.v1.plugin

import ai.icen.fw.application.plugin.PluginInventoryCursor
import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PluginInventoryCursorCodecTest {
    private val codec = PluginInventoryCursorCodec()

    @Test
    fun `round trips a safe unicode plugin id`() {
        val cursor = PluginInventoryCursor("检索-plugin")

        assertEquals(cursor.pluginId, codec.decode(codec.encode(cursor)).pluginId)
    }

    @Test
    fun `refuses to emit unsafe plugin ids`() {
        unsafePluginIds().forEach { pluginId ->
            assertFailsWith<IllegalStateException> {
                codec.encode(PluginInventoryCursor(pluginId))
            }
        }
    }

    @Test
    fun `rejects forged cursors containing unsafe plugin ids`() {
        unsafePluginIds().forEach { pluginId ->
            assertFailsWith<IllegalArgumentException> {
                codec.decode(forgedCursor(pluginId))
            }
        }
    }

    private fun unsafePluginIds(): List<String> = listOf(
        " plugin",
        "plugin ",
        "\u00A0plugin",
        "plugin\u2007",
        "\u202Fplugin",
        "unsafe\u0000plugin",
        "unsafe\u200Bplugin",
        "unsafe\u202Eplugin",
    )

    private fun forgedCursor(pluginId: String): String {
        val idBytes = pluginId.toByteArray(StandardCharsets.UTF_16BE)
        val payload = ByteBuffer.allocate(4 + idBytes.size)
            .put(1.toByte())
            .put(3.toByte())
            .putShort(idBytes.size.toShort())
            .put(idBytes)
            .array()
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload)
    }
}
