package com.fileweft.testkit.connector

import com.fileweft.core.id.Identifier
import com.fileweft.spi.connector.ConnectorFileSource
import com.fileweft.spi.connector.ConnectorHealth
import com.fileweft.spi.connector.ConnectorHealthStatus
import com.fileweft.spi.connector.ConnectorInvocation
import com.fileweft.spi.connector.ConnectorRemoveRequest
import com.fileweft.spi.connector.ConnectorSyncRequest
import com.fileweft.spi.connector.ConnectorSyncResult
import com.fileweft.spi.connector.ConnectorSyncStatus
import com.fileweft.spi.connector.FileConnector
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
