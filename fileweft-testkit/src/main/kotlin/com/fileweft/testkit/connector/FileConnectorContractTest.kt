package com.fileweft.testkit.connector

import com.fileweft.spi.connector.ConnectorHealthStatus
import com.fileweft.spi.connector.ConnectorRemoveRequest
import com.fileweft.spi.connector.ConnectorSyncRequest
import com.fileweft.spi.connector.ConnectorSyncStatus
import com.fileweft.spi.connector.FileConnector
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

abstract class FileConnectorContractTest {
    protected abstract val fileConnector: FileConnector

    protected abstract fun syncRequest(): ConnectorSyncRequest

    protected abstract fun removalRequest(): ConnectorRemoveRequest

    @Test
    fun `reports its health`() {
        val health = fileConnector.health()

        assertTrue(health.status != ConnectorHealthStatus.UNHEALTHY, health.message)
    }

    @Test
    fun `synchronizes a request idempotently with a stable external id`() {
        val request = syncRequest()
        val result = fileConnector.sync(request)
        val replay = fileConnector.sync(request)

        assertEquals(ConnectorSyncStatus.SUCCESS, result.status, result.message)
        assertTrue(!result.externalId.isNullOrBlank(), "Successful synchronization must return an external id.")
        assertEquals(ConnectorSyncStatus.SUCCESS, replay.status, replay.message)
        assertEquals(result.externalId, replay.externalId, "An idempotent replay must retain the external id.")
    }

    @Test
    fun `removes a request idempotently`() {
        val request = removalRequest()
        val result = fileConnector.remove(request)
        val replay = fileConnector.remove(request)

        assertEquals(ConnectorSyncStatus.SUCCESS, result.status, result.message)
        assertEquals(ConnectorSyncStatus.SUCCESS, replay.status, replay.message)
    }
}
