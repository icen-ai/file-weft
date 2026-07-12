package ai.icen.fw.application.sync

import ai.icen.fw.application.audit.AuditTrail
import ai.icen.fw.application.delivery.DeliveryDiagnosticMessage
import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.core.event.OutboxEvent
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.core.id.IdentifierGenerator
import ai.icen.fw.domain.document.Document
import ai.icen.fw.domain.document.DocumentRepository
import ai.icen.fw.domain.document.DocumentVersion
import ai.icen.fw.domain.document.LifecycleCommand
import ai.icen.fw.domain.document.LifecycleState
import ai.icen.fw.domain.file.FileObject
import ai.icen.fw.domain.file.FileObjectRepository
import ai.icen.fw.spi.connector.ConnectorFileSource
import ai.icen.fw.spi.connector.ConnectorInvocation
import ai.icen.fw.spi.connector.ConnectorSyncRequest
import ai.icen.fw.spi.connector.ConnectorSyncResult
import ai.icen.fw.spi.connector.ConnectorSyncStatus
import ai.icen.fw.spi.connector.FileConnector
import ai.icen.fw.spi.event.OutboxHandlingResult
import ai.icen.fw.spi.event.OutboxHandlingStatus
import ai.icen.fw.spi.observability.FileWeftMetric
import ai.icen.fw.spi.observability.FileWeftMetrics
import ai.icen.fw.spi.storage.StorageAdapter
import ai.icen.fw.spi.storage.StorageObjectLocation
import java.time.Duration

/**
 * Synchronizes a published document for one connector. Database reads and
 * lifecycle writes are isolated in short transactions; storage and connector
 * calls always happen after the preparation transaction has completed.
 */
class DocumentSyncService @JvmOverloads constructor(
    private val documentRepository: DocumentRepository,
    private val fileObjectRepository: FileObjectRepository,
    private val storageAdapter: StorageAdapter,
    private val connector: FileConnector,
    private val connectorName: String,
    private val syncRecordRepository: SyncRecordRepository,
    private val identifierGenerator: IdentifierGenerator,
    private val transaction: ApplicationTransaction,
    private val connectorTimeout: Duration = Duration.ofSeconds(30),
    private val auditTrail: AuditTrail? = null,
    private val metrics: FileWeftMetrics? = null,
    /**
     * Lifetime of the storage URL handed to a downstream connector. This is
     * intentionally independent from [connectorTimeout]: an asynchronous
     * downstream can acknowledge the RPC before it fetches the file.
     */
    private val sourceAccessUrlTtl: Duration = Duration.ofMinutes(15),
) {
    init {
        require(connectorName.isNotBlank()) { "Connector name must not be blank." }
        require(!connectorTimeout.isNegative && !connectorTimeout.isZero) { "Connector timeout must be positive." }
        require(!sourceAccessUrlTtl.isNegative && !sourceAccessUrlTtl.isZero) {
            "Source access URL TTL must be positive."
        }
        require(sourceAccessUrlTtl >= connectorTimeout) {
            "Source access URL TTL must be at least the connector timeout."
        }
    }

    fun synchronize(sourceEvent: OutboxEvent): OutboxHandlingResult {
        val documentId = sourceEvent.payload[DOCUMENT_ID_PAYLOAD_KEY]?.takeIf { it.isNotBlank() }?.let(::Identifier)
            ?: return OutboxHandlingResult(OutboxHandlingStatus.PERMANENT_FAILURE, "Published document event does not contain documentId.")
        val handling = when (val preparation = transaction.execute { prepare(sourceEvent.tenantId, documentId) }) {
            is Preparation.AlreadyPublished -> OutboxHandlingResult(OutboxHandlingStatus.SUCCEEDED, "Document is already published.")
            is Preparation.Failure -> complete(sourceEvent, documentId, preparation.status, null, preparation.message)
            is Preparation.Ready -> {
                val result = invokeConnector(sourceEvent, documentId, preparation.fileObject)
                complete(sourceEvent, documentId, result.status, result.externalId, result.message)
            }
        }
        recordMetric(handling.status, sourceEvent.tenantId.value)
        return handling
    }

    private fun prepare(tenantId: Identifier, documentId: Identifier): Preparation {
        var document = documentRepository.findById(tenantId, documentId)
            ?: return Preparation.Failure(ConnectorSyncStatus.PERMANENT_FAILURE, "Document was not found in the event tenant.")
        if (document.lifecycleState == LifecycleState.SYNC_ERROR) {
            document = documentRepository.findForMutation(tenantId, documentId)
                ?: return Preparation.Failure(ConnectorSyncStatus.PERMANENT_FAILURE, "Document was removed before synchronization preparation completed.")
        }
        when (document.lifecycleState) {
            LifecycleState.PUBLISHED -> return Preparation.AlreadyPublished
            LifecycleState.SYNC_ERROR -> {
                document.transition(LifecycleCommand.RETRY_SYNC)
                documentRepository.save(document)
            }

            LifecycleState.PUBLISHING -> Unit
            else -> return Preparation.Failure(
                ConnectorSyncStatus.PERMANENT_FAILURE,
                "Document is not ready for synchronization from lifecycle state ${document.lifecycleState.name}.",
            )
        }
        val version = currentVersion(document)
            ?: return Preparation.Failure(ConnectorSyncStatus.PERMANENT_FAILURE, "Published document has no active version.")
        val fileObject = fileObjectRepository.findById(tenantId, version.fileObjectId)
            ?: return Preparation.Failure(ConnectorSyncStatus.PERMANENT_FAILURE, "Active document version references a missing file object.")
        return Preparation.Ready(fileObject)
    }

    private fun invokeConnector(sourceEvent: OutboxEvent, documentId: Identifier, fileObject: FileObject): ConnectorSyncResult = try {
        val source = ConnectorFileSource(
            downloadUri = storageAdapter.accessUrl(
                StorageObjectLocation(fileObject.storageType, fileObject.storagePath),
                sourceAccessUrlTtl,
            ),
            fileName = fileObject.fileName,
            contentType = fileObject.contentType,
            contentHash = fileObject.contentHash,
        )
        connector.sync(
            ConnectorSyncRequest(
                tenantId = sourceEvent.tenantId,
                businessId = documentId,
                source = source,
                invocation = ConnectorInvocation(sourceEvent.id.value, connectorTimeout),
            ),
        )
    } catch (_: Exception) {
        ConnectorSyncResult(ConnectorSyncStatus.RETRYABLE_FAILURE, message = "Connector invocation could not complete.")
    }

    private fun complete(
        sourceEvent: OutboxEvent,
        documentId: Identifier,
        status: ConnectorSyncStatus,
        externalId: String?,
        message: String?,
    ): OutboxHandlingResult = transaction.execute {
        val normalizedMessage = DeliveryDiagnosticMessage.normalize(message)
        val document = documentRepository.findForMutation(sourceEvent.tenantId, documentId)
        if (document == null) {
            return@execute OutboxHandlingResult(OutboxHandlingStatus.PERMANENT_FAILURE, "Document was removed before synchronization completed.")
        }
        if (document.lifecycleState == LifecycleState.PUBLISHING) {
            document.transition(
                if (status == ConnectorSyncStatus.SUCCESS) LifecycleCommand.PUBLISH_SUCCEEDED else LifecycleCommand.SYNC_FAILED,
            )
            documentRepository.save(document)
        }
        val previous = syncRecordRepository.findBySourceEvent(sourceEvent.tenantId, sourceEvent.id, connectorName)
        syncRecordRepository.save(
            SyncRecord(
                id = previous?.id ?: identifierGenerator.nextId(),
                tenantId = sourceEvent.tenantId,
                documentId = documentId,
                sourceEventId = sourceEvent.id,
                connectorName = connectorName,
                status = status,
                externalId = externalId ?: previous?.externalId,
                errorMessage = normalizedMessage,
                retryCount = (previous?.retryCount ?: 0) + if (status == ConnectorSyncStatus.SUCCESS) 0 else 1,
            ),
        )
        auditTrail?.record(
            tenantId = sourceEvent.tenantId,
            resourceType = "DOCUMENT",
            resourceId = documentId,
            action = "document.sync",
            operatorName = "SYSTEM",
            details = linkedMapOf<String, String>().apply {
                put("sourceEventId", sourceEvent.id.value)
                put("connector", connectorName)
                put("status", status.name)
                externalId?.let { put("externalId", it) }
                normalizedMessage?.let { put("message", it) }
            },
        )
        status.toOutboxHandlingResult(normalizedMessage)
    }

    private fun currentVersion(document: Document): DocumentVersion? =
        document.currentVersionId?.let { currentVersionId -> document.versions.firstOrNull { it.id == currentVersionId } }

    private fun ConnectorSyncStatus.toOutboxHandlingResult(message: String?): OutboxHandlingResult = when (this) {
        ConnectorSyncStatus.SUCCESS -> OutboxHandlingResult(OutboxHandlingStatus.SUCCEEDED, message)
        ConnectorSyncStatus.RETRYABLE_FAILURE -> OutboxHandlingResult(
            OutboxHandlingStatus.RETRYABLE_FAILURE,
            message ?: "Connector synchronization should be retried.",
        )

        ConnectorSyncStatus.PERMANENT_FAILURE -> OutboxHandlingResult(
            OutboxHandlingStatus.PERMANENT_FAILURE,
            message ?: "Connector synchronization cannot succeed without intervention.",
        )
    }

    private fun recordMetric(status: OutboxHandlingStatus, tenantId: String) {
        val metric = if (status == OutboxHandlingStatus.SUCCEEDED) FileWeftMetric.SYNC_SUCCESS else FileWeftMetric.SYNC_FAILURE
        try {
            metrics?.increment(metric, mapOf("tenantId" to tenantId, "connector" to connectorName))
        } catch (_: Exception) {
            // Metrics must not alter outbox acknowledgement semantics.
        }
    }

    private sealed class Preparation {
        data object AlreadyPublished : Preparation()
        data class Ready(val fileObject: FileObject) : Preparation()
        data class Failure(val status: ConnectorSyncStatus, val message: String) : Preparation()
    }

    private companion object {
        const val DOCUMENT_ID_PAYLOAD_KEY = "documentId"
    }
}
