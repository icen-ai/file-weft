package ai.icen.fw.testkit.plugin

import ai.icen.fw.spi.plugin.FileWeftPlugin
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Reusable boundary contract for a plugin before registry-level collision
 * checks are run by the host runtime. Legacy Agent contribution getters are
 * deliberately not queried: they are retained for ABI compatibility but are
 * not part of the supported plugin product surface.
 */
abstract class FileWeftPluginContractTest {
    protected abstract val fileWeftPlugin: FileWeftPlugin

    @Test
    fun `declares a stable non-blank plugin id`() {
        val first = fileWeftPlugin.id()
        val replay = fileWeftPlugin.id()

        assertTrue(first.isNotBlank(), "Plugin id must not be blank.")
        assertTrue(first == replay, "Plugin id must remain stable for the process lifetime.")
    }

    @Test
    fun `exposes non-null contribution snapshots with valid connector ids`() {
        assertDoesNotThrow {
            assertNoNulls("storage adapter", fileWeftPlugin.storageAdapters())
            assertNoNulls("Doctor checker", fileWeftPlugin.doctorCheckers())
            assertNoNulls("Outbox event handler", fileWeftPlugin.outboxEventHandlers())
            assertNoNulls("task handler", fileWeftPlugin.taskHandlers())
            assertNoNulls("review route provider", fileWeftPlugin.reviewRouteProviders())

            val connectors = fileWeftPlugin.connectors()
            assertNotNull(connectors, "Connector contributions must not be null.")
            connectors.forEach { (id, connector) ->
                assertFalse(id.isBlank(), "Connector contribution id must not be blank.")
                assertNotNull(connector, "Connector contribution $id must not be null.")
            }
        }
    }

    private fun assertNoNulls(name: String, contributions: List<*>) {
        assertNotNull(contributions, "$name contributions must not be null.")
        assertFalse(contributions.any { it == null }, "$name contributions must not contain null entries.")
    }
}
