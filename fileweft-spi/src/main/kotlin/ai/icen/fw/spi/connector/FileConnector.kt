package ai.icen.fw.spi.connector

import ai.icen.fw.core.id.Identifier
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
 * A successful formal multi-target delivery must return a non-blank
 * [externalId] without ISO control characters and with no more than 512 UTF-16
 * code units. Legacy single-connector synchronization can retain a null
 * external id for compatibility; callers enforce the stricter rule at the
 * formal delivery boundary.
 */
data class ConnectorSyncResult(
    val status: ConnectorSyncStatus,
    val externalId: String? = null,
    val message: String? = null,
) {
    companion object {
        const val MAX_EXTERNAL_ID_UTF16_LENGTH = 512
    }
}

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
