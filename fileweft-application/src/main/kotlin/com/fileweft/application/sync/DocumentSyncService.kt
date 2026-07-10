package com.fileweft.application.sync

import com.fileweft.application.audit.AuditTrail
import com.fileweft.application.transaction.ApplicationTransaction
import com.fileweft.core.event.OutboxEvent
import com.fileweft.core.id.Identifier
import com.fileweft.core.id.IdentifierGenerator
import com.fileweft.domain.document.Document
import com.fileweft.domain.document.DocumentRepository
import com.fileweft.domain.document.DocumentVersion
import com.fileweft.domain.document.LifecycleCommand
import com.fileweft.domain.document.LifecycleState
import com.fileweft.domain.file.FileObject
import com.fileweft.domain.file.FileObjectRepository
import com.fileweft.spi.connector.ConnectorFileSource
import com.fileweft.spi.connector.ConnectorInvocation
import com.fileweft.spi.connector.ConnectorSyncRequest
import com.fileweft.spi.connector.ConnectorSyncResult
import com.fileweft.spi.connector.ConnectorSyncStatus
import com.fileweft.spi.connector.FileConnector
import com.fileweft.spi.event.OutboxHandlingResult
import com.fileweft.spi.event.OutboxHandlingStatus
import com.fileweft.spi.storage.StorageAdapter
import com.fileweft.spi.storage.StorageObjectLocation
import java.time.Duration

/**
 * Synchronizes a published document for one connector. Database reads and
 * lifecycle writes are isolated in short transactions; storage and connector
 * calls always happen after the preparation transaction has completed.
 */
class DocumentSyncService(
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
) {
    init {
        require(connectorName.isNotBlank()) { "Connector name must not be blank." }
        require(!connectorTimeout.isNegative && !connectorTimeout.isZero) { "Connector timeout must be positive." }
    }

    fun synchronize(sourceEvent: OutboxEvent): OutboxHandlingResult {
        val documentId = sourceEvent.payload[DOCUMENT_ID_PAYLOAD_KEY]?.takeIf { it.isNotBlank() }?.let(::Identifier)
            ?: return OutboxHandlingResult(OutboxHandlingStatus.PERMANENT_FAILURE, "Published document event does not contain documentId.")
        return when (val preparation = transaction.execute { prepare(sourceEvent.tenantId, documentId) }) {
            is Preparation.AlreadyPublished -> OutboxHandlingResult(OutboxHandlingStatus.SUCCEEDED, "Document is already published.")
            is Preparation.Failure -> complete(sourceEvent, documentId, preparation.status, null, preparation.message)
            is Preparation.Ready -> {
                val result = invokeConnector(sourceEvent, documentId, preparation.fileObject)
                complete(sourceEvent, documentId, result.status, result.externalId, result.message)
            }
        }
    }

    private fun prepare(tenantId: Identifier, documentId: Identifier): Preparation {
        val document = documentRepository.findById(tenantId, documentId)
            ?: return Preparation.Failure(ConnectorSyncStatus.PERMANENT_FAILURE, "Document was not found in the event tenant.")
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
                connectorTimeout,
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
        val document = documentRepository.findById(sourceEvent.tenantId, documentId)
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
                errorMessage = message,
                retryCount = (previous?.retryCount ?: 0) + if (status == ConnectorSyncStatus.SUCCESS) 0 else 1,
            ),
        )
        auditTrail?.record(
            tenantId = sourceEvent.tenantId,
            resourceType = "DOCUMENT",
            resourceId = documentId,
            action = "document.sync",
            details = linkedMapOf<String, String>().apply {
                put("sourceEventId", sourceEvent.id.value)
                put("connector", connectorName)
                put("status", status.name)
                externalId?.let { put("externalId", it) }
                message?.let { put("message", it) }
            },
        )
        status.toOutboxHandlingResult(message)
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

    private sealed class Preparation {
        data object AlreadyPublished : Preparation()
        data class Ready(val fileObject: FileObject) : Preparation()
        data class Failure(val status: ConnectorSyncStatus, val message: String) : Preparation()
    }

    private companion object {
        const val DOCUMENT_ID_PAYLOAD_KEY = "documentId"
    }
}
