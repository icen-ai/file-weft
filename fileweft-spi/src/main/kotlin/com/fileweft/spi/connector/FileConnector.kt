package com.fileweft.spi.connector

import com.fileweft.core.id.Identifier
import java.net.URI

data class ConnectorFileSource(
    val downloadUri: URI,
    val fileName: String,
    val contentType: String? = null,
    val contentHash: String? = null,
) {
    init {
        require(fileName.isNotBlank()) { "File name must not be blank." }
    }
}

data class ConnectorSyncRequest(
    val tenantId: Identifier,
    val businessId: Identifier,
    val source: ConnectorFileSource,
    val invocation: ConnectorInvocation,
    val attributes: Map<String, String> = emptyMap(),
)

data class ConnectorRemoveRequest(
    val tenantId: Identifier,
    val businessId: Identifier,
    val externalId: String,
    val invocation: ConnectorInvocation,
) {
    init {
        require(externalId.isNotBlank()) { "External identifier must not be blank." }
    }
}

enum class ConnectorSyncStatus {
    SUCCESS,
    RETRYABLE_FAILURE,
    PERMANENT_FAILURE,
}

/**
 * A connector diagnostic must be brief, credential-free, and safe for a
 * platform operator to inspect. FileWeft bounds persisted delivery messages.
 */
data class ConnectorSyncResult(
    val status: ConnectorSyncStatus,
    val externalId: String? = null,
    val message: String? = null,
)

enum class ConnectorHealthStatus {
    HEALTHY,
    DEGRADED,
    UNHEALTHY,
}

data class ConnectorHealth(
    val status: ConnectorHealthStatus,
    val message: String? = null,
)

interface FileConnector {
    fun sync(request: ConnectorSyncRequest): ConnectorSyncResult

    fun remove(request: ConnectorRemoveRequest): ConnectorSyncResult

    fun health(): ConnectorHealth
}
