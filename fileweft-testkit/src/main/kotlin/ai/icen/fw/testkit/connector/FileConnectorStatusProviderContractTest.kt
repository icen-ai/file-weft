package ai.icen.fw.testkit.connector

import ai.icen.fw.spi.connector.ConnectorExternalState
import ai.icen.fw.spi.connector.ConnectorStatusQueryStatus
import ai.icen.fw.spi.connector.ConnectorStatusRequest
import ai.icen.fw.spi.connector.ConnectorStatusResult
import ai.icen.fw.spi.connector.FileConnectorStatusProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Reusable protocol and redaction contract for an external status provider. */
abstract class FileConnectorStatusProviderContractTest {
    protected abstract val statusProvider: FileConnectorStatusProvider

    protected abstract fun statusRequest(): ConnectorStatusRequest

    @Test
    fun `returns a coherent bounded status result`() {
        val result = statusProvider.status(statusRequest())

        assertTrue(ConnectorStatusQueryStatus.values().contains(result.queryStatus))
        assertTrue(ConnectorExternalState.values().contains(result.state))
        if (result.queryStatus != ConnectorStatusQueryStatus.SUCCESS) {
            assertEquals(ConnectorExternalState.UNKNOWN, result.state)
        }
        result.message?.let { diagnostic ->
            assertTrue(diagnostic.isNotBlank())
            assertTrue(diagnostic.length <= ConnectorStatusResult.MAX_DIAGNOSTIC_UTF16_LENGTH)
            assertFalse(diagnostic.any { character ->
                Character.isISOControl(character) || Character.getType(character) == Character.FORMAT.toInt()
            })
            assertTrue(isWellFormedUtf16(diagnostic))
        }
    }

    private fun isWellFormedUtf16(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            when {
                Character.isHighSurrogate(value[index]) -> {
                    if (index + 1 >= value.length || !Character.isLowSurrogate(value[index + 1])) return false
                    index += 2
                }
                Character.isLowSurrogate(value[index]) -> return false
                else -> index++
            }
        }
        return true
    }
}
