package ai.icen.fw.testkit.connector

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.connector.ConnectorFileSource
import ai.icen.fw.spi.connector.ConnectorHealth
import ai.icen.fw.spi.connector.ConnectorHealthStatus
import ai.icen.fw.spi.connector.ConnectorInvocation
import ai.icen.fw.spi.connector.ConnectorRemoveRequest
import ai.icen.fw.spi.connector.ConnectorSyncRequest
import ai.icen.fw.spi.connector.ConnectorSyncResult
import ai.icen.fw.spi.connector.ConnectorSyncStatus
import ai.icen.fw.spi.connector.FileConnector
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.net.URI
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

class FileConnectorContractTestBehaviorTest : FileConnectorContractTest() {
    private val connector = IdempotentConnector()

    override val fileConnector: FileConnector = connector

    override fun syncRequest(): ConnectorSyncRequest = ConnectorSyncRequest(
        tenantId = Identifier("tenant-contract"),
        businessId = Identifier("document-contract"),
        source = ConnectorFileSource(URI("https://storage.example/contract"), "contract.txt"),
        invocation = ConnectorInvocation("sync-contract-1", Duration.ofSeconds(1)),
    )

    override fun removalRequest(): ConnectorRemoveRequest = ConnectorRemoveRequest(
        tenantId = Identifier("tenant-contract"),
        businessId = Identifier("document-contract"),
        externalId = "external-sync-contract-1",
        invocation = ConnectorInvocation("remove-contract-1", Duration.ofSeconds(1)),
    )

    @Test
    fun `fixture applies each idempotency key only once`() {
        fileConnector.sync(syncRequest())
        fileConnector.sync(syncRequest())
        fileConnector.remove(removalRequest())
        fileConnector.remove(removalRequest())

        assertEquals(1, connector.createdExternalIds.size)
        assertEquals(1, connector.removedExternalIds.size)
    }

    private class IdempotentConnector : FileConnector {
        val createdExternalIds = ConcurrentHashMap<String, String>()
        val removedExternalIds = ConcurrentHashMap.newKeySet<String>()

        override fun sync(request: ConnectorSyncRequest): ConnectorSyncResult {
            val externalId = createdExternalIds.computeIfAbsent(request.invocation.idempotencyKey) {
                "external-${request.invocation.idempotencyKey}"
            }
            return ConnectorSyncResult(ConnectorSyncStatus.SUCCESS, externalId)
        }

        override fun remove(request: ConnectorRemoveRequest): ConnectorSyncResult {
            removedExternalIds.add(request.externalId)
            return ConnectorSyncResult(ConnectorSyncStatus.SUCCESS)
        }

        override fun health(): ConnectorHealth = ConnectorHealth(ConnectorHealthStatus.UNHEALTHY, "controlled test endpoint is offline")
    }
}
