package ai.icen.fw.sample.host

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.connector.ConnectorHealth
import ai.icen.fw.spi.connector.ConnectorHealthStatus
import ai.icen.fw.spi.connector.ConnectorRemoveRequest
import ai.icen.fw.spi.connector.ConnectorSyncRequest
import ai.icen.fw.spi.connector.ConnectorSyncResult
import ai.icen.fw.spi.connector.ConnectorSyncStatus
import ai.icen.fw.spi.connector.FileConnector
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Sample host downstream connector that records synchronizations in memory.
 * Replays of the same business id return the same stable external id.
 */
class SampleFileConnector : FileConnector {

    private val externalIds = ConcurrentHashMap<Identifier, String>()

    override fun sync(request: ConnectorSyncRequest): ConnectorSyncResult {
        val externalId = externalIds.computeIfAbsent(request.businessId) {
            UUID.randomUUID().toString()
        }
        return ConnectorSyncResult(
            status = ConnectorSyncStatus.SUCCESS,
            externalId = externalId,
            message = "Synchronized to sample downstream.",
        )
    }

    override fun remove(request: ConnectorRemoveRequest): ConnectorSyncResult {
        externalIds.remove(request.businessId)
        return ConnectorSyncResult(
            status = ConnectorSyncStatus.SUCCESS,
            message = "Removed from sample downstream.",
        )
    }

    override fun health(): ConnectorHealth {
        return ConnectorHealth(status = ConnectorHealthStatus.HEALTHY, message = "Sample connector is healthy.")
    }
}
