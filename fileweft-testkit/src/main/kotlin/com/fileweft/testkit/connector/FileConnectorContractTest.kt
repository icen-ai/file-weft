package com.fileweft.testkit.connector

import com.fileweft.spi.connector.ConnectorHealthStatus
import com.fileweft.spi.connector.ConnectorSyncRequest
import com.fileweft.spi.connector.ConnectorSyncStatus
import com.fileweft.spi.connector.FileConnector
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

abstract class FileConnectorContractTest {
    protected abstract val fileConnector: FileConnector

    protected abstract fun syncRequest(): ConnectorSyncRequest

    @Test
    fun `reports its health`() {
        val health = fileConnector.health()

        assertTrue(health.status != ConnectorHealthStatus.UNHEALTHY, health.message)
    }

    @Test
    fun `synchronizes a request successfully`() {
        val result = fileConnector.sync(syncRequest())

        assertEquals(ConnectorSyncStatus.SUCCESS, result.status, result.message)
        assertTrue(!result.externalId.isNullOrBlank(), "Successful synchronization must return an external id.")
    }
}
