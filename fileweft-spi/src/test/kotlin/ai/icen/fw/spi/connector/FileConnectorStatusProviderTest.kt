package ai.icen.fw.spi.connector

import ai.icen.fw.core.id.Identifier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import java.time.Duration

class FileConnectorStatusProviderTest {
    @Test
    fun `status request binds tenant business external identity and invocation`() {
        val invocation = ConnectorInvocation("status-1", Duration.ofSeconds(2))
        val request = ConnectorStatusRequest(
            Identifier("tenant-a"),
            Identifier("document-a"),
            "projection:v1:1",
            invocation,
        )

        assertEquals("tenant-a", request.tenantId.value)
        assertEquals("document-a", request.businessId.value)
        assertEquals("projection:v1:1", request.externalId)
        assertEquals(invocation, request.invocation)
    }

    @Test
    fun `external identity and diagnostics reject unsafe or unbounded text`() {
        val invocation = ConnectorInvocation("status-1", Duration.ofSeconds(2))
        assertThrows(IllegalArgumentException::class.java) {
            ConnectorStatusRequest(Identifier("tenant-a"), Identifier("document-a"), " ", invocation)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ConnectorStatusRequest(Identifier("tenant-a"), Identifier("document-a"), "bad\r\nid", invocation)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ConnectorStatusRequest(Identifier("tenant-a"), Identifier("document-a"), "bad\uD800id", invocation)
        }
        assertThrows(IllegalArgumentException::class.java) {
            ConnectorStatusResult(
                ConnectorStatusQueryStatus.SUCCESS,
                ConnectorExternalState.UNKNOWN,
                "x".repeat(ConnectorStatusResult.MAX_DIAGNOSTIC_UTF16_LENGTH + 1),
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            ConnectorStatusResult(
                ConnectorStatusQueryStatus.SUCCESS,
                ConnectorExternalState.UNKNOWN,
                "bad\uDC00diagnostic",
            )
        }
    }

    @Test
    fun `failed query cannot claim a stale provider state`() {
        ConnectorStatusQueryStatus.values()
            .filter { it != ConnectorStatusQueryStatus.SUCCESS }
            .forEach { queryStatus ->
                assertThrows(IllegalArgumentException::class.java) {
                    ConnectorStatusResult(queryStatus, ConnectorExternalState.AVAILABLE)
                }
                assertEquals(
                    ConnectorExternalState.UNKNOWN,
                    ConnectorStatusResult(queryStatus, ConnectorExternalState.UNKNOWN).state,
                )
            }
    }
}
