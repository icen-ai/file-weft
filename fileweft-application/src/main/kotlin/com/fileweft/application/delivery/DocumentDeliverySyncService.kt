package com.fileweft.application.delivery

import com.fileweft.application.audit.AuditTrail
import com.fileweft.application.transaction.ApplicationTransaction
import com.fileweft.core.event.OutboxEvent
import com.fileweft.core.id.Identifier
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
import com.fileweft.spi.delivery.DeliveryConnectorResolver
import com.fileweft.spi.delivery.DeliveryRequirement
import com.fileweft.spi.event.OutboxHandlingResult
import com.fileweft.spi.event.OutboxHandlingStatus
import com.fileweft.spi.observability.FileWeftMetric
import com.fileweft.spi.observability.FileWeftMetrics
import com.fileweft.spi.storage.StorageAdapter
import com.fileweft.spi.storage.StorageObjectLocation
import java.time.Duration

/**
 * Delivers one frozen target outside the business transaction.  Each target
 * keeps its own idempotency key, so retrying one downstream never replays the
 * other successful destinations.
 */
class DocumentDeliverySyncService @JvmOverloads constructor(
    private val documentRepository: DocumentRepository,
    private val fileObjectRepository: FileObjectRepository,
    private val storageAdapter: StorageAdapter,
    private val connectors: DeliveryConnectorResolver,
    private val deliveries: DocumentDeliveryTargetRepository,
    private val transaction: ApplicationTransaction,
    private val connectorTimeout: Duration = Duration.ofSeconds(30),
    private val auditTrail: AuditTrail? = null,
    private val removalPlanner: DocumentDeliveryRemovalPlanner? = null,
    private val metrics: FileWeftMetrics? = null,
) {
    init {
        require(!connectorTimeout.isNegative && !connectorTimeout.isZero) { "Connector timeout must be positive." }
    }

    fun synchronize(sourceEvent: OutboxEvent): OutboxHandlingResult {
        val deliveryId = sourceEvent.payload[DocumentDeliveryPlanner.DELIVERY_ID_PAYLOAD_KEY]
            ?.takeIf { it.isNotBlank() }?.let(::Identifier)
            ?: return OutboxHandlingResult(OutboxHandlingStatus.PERMANENT_FAILURE, "Delivery event does not contain deliveryId.")
        val preparation = transaction.execute { prepare(sourceEvent.tenantId, deliveryId) }
        val handling = when (preparation) {
            is Preparation.AlreadySucceeded -> OutboxHandlingResult(OutboxHandlingStatus.SUCCEEDED, "Delivery target is already synchronized.")
            is Preparation.Superseded -> OutboxHandlingResult(OutboxHandlingStatus.SUCCEEDED, "Delivery target belongs to a superseded publication generation.")
            is Preparation.Failure -> complete(
                sourceEvent,
                deliveryId,
                ConnectorSyncResult(preparation.status, message = preparation.message),
            )
            is Preparation.Ready -> complete(
                sourceEvent,
                deliveryId,
                invokeConnector(preparation),
            )
        }
        recordMetric(preparation, handling, sourceEvent.tenantId.value)
        return handling
    }

    /** Records a terminal worker outcome without invoking a connector again. */
    fun exhaust(sourceEvent: OutboxEvent, message: String) {
        val deliveryId = sourceEvent.payload[DocumentDeliveryPlanner.DELIVERY_ID_PAYLOAD_KEY]
            ?.takeIf { it.isNotBlank() }?.let(::Identifier) ?: return
        val diagnostic = DeliveryDiagnosticMessage.normalize(message) ?: "Delivery retry limit was reached."
        transaction.execute {
            val delivery = deliveries.findById(sourceEvent.tenantId, deliveryId) ?: return@execute
            if (delivery.status != DocumentDeliveryStatus.SUCCEEDED) {
                delivery.markFailed(diagnostic)
                deliveries.save(delivery)
                reconcileDocument(sourceEvent.tenantId, delivery.documentId)
                auditTrail?.record(
                    tenantId = sourceEvent.tenantId,
                    resourceType = DOCUMENT_RESOURCE_TYPE,
                    resourceId = delivery.documentId,
                    action = DELIVERY_FAILED_AUDIT_ACTION,
                    details = mapOf("deliveryId" to delivery.id.value, "targetId" to delivery.targetId, "message" to diagnostic),
                )
            }
        }
    }

    private fun prepare(tenantId: Identifier, deliveryId: Identifier): Preparation {
        val delivery = deliveries.findById(tenantId, deliveryId)
            ?: return Preparation.Failure(ConnectorSyncStatus.PERMANENT_FAILURE, "Delivery target was not found in the event tenant.")
        if (delivery.status == DocumentDeliveryStatus.SUCCEEDED) return Preparation.AlreadySucceeded(delivery.connectorId)
        if (delivery.status == DocumentDeliveryStatus.FAILED) {
            return Preparation.Failure(
                ConnectorSyncStatus.PERMANENT_FAILURE,
                "Delivery target requires a manual retry.",
                delivery.connectorId,
            )
        }
        val document = documentRepository.findById(tenantId, delivery.documentId)
            ?: return Preparation.Failure(
                ConnectorSyncStatus.PERMANENT_FAILURE,
                "Document was not found in the event tenant.",
                delivery.connectorId,
            )
        if (delivery.deliveryGeneration != document.deliveryGeneration) return Preparation.Superseded(delivery.connectorId)
        if (document.lifecycleState !in setOf(LifecycleState.PUBLISHING, LifecycleState.SYNC_ERROR, LifecycleState.PUBLISHED)) {
            return Preparation.Failure(
                ConnectorSyncStatus.PERMANENT_FAILURE,
                "Document is not available for delivery from lifecycle state ${document.lifecycleState.name}.",
                delivery.connectorId,
            )
        }
        val version = currentVersion(document)
            ?: return Preparation.Failure(
                ConnectorSyncStatus.PERMANENT_FAILURE,
                "Document has no active version.",
                delivery.connectorId,
            )
        val fileObject = fileObjectRepository.findById(tenantId, version.fileObjectId)
            ?: return Preparation.Failure(
                ConnectorSyncStatus.PERMANENT_FAILURE,
                "Active document version references a missing file object.",
                delivery.connectorId,
            )
        val connector = connectors.findConnector(delivery.connectorId)
            ?: return Preparation.Failure(
                ConnectorSyncStatus.PERMANENT_FAILURE,
                "Delivery connector '${delivery.connectorId}' is no longer configured.",
                delivery.connectorId,
            )
        return Preparation.Ready(delivery, document.id, fileObject, connector)
    }

    private fun invokeConnector(preparation: Preparation.Ready): ConnectorSyncResult = try {
        val fileObject = preparation.fileObject
        preparation.connector.sync(
            ConnectorSyncRequest(
                tenantId = preparation.delivery.tenantId,
                businessId = preparation.documentId,
                source = ConnectorFileSource(
                    downloadUri = storageAdapter.accessUrl(
                        StorageObjectLocation(fileObject.storageType, fileObject.storagePath),
                        connectorTimeout,
                    ),
                    fileName = fileObject.fileName,
                    contentType = fileObject.contentType,
                    contentHash = fileObject.contentHash,
                ),
                invocation = ConnectorInvocation(preparation.delivery.id.value, connectorTimeout),
                attributes = mapOf(
                    "deliveryProfileId" to preparation.delivery.profileId,
                    "deliveryTargetId" to preparation.delivery.targetId,
                ),
            ),
        )
    } catch (_: Exception) {
        ConnectorSyncResult(ConnectorSyncStatus.RETRYABLE_FAILURE, message = "Connector invocation could not complete.")
    }

    private fun complete(
        sourceEvent: OutboxEvent,
        deliveryId: Identifier,
        result: ConnectorSyncResult,
    ): OutboxHandlingResult = transaction.execute {
        val normalizedResult = result.copy(message = DeliveryDiagnosticMessage.normalize(result.message))
        val delivery = deliveries.findById(sourceEvent.tenantId, deliveryId)
            ?: return@execute OutboxHandlingResult(OutboxHandlingStatus.PERMANENT_FAILURE, "Delivery target was removed before synchronization completed.")
        when (normalizedResult.status) {
            ConnectorSyncStatus.SUCCESS -> delivery.markSucceeded(normalizedResult.externalId)
            ConnectorSyncStatus.RETRYABLE_FAILURE -> delivery.markRetrying(normalizedResult.message)
            ConnectorSyncStatus.PERMANENT_FAILURE -> delivery.markFailed(normalizedResult.message)
        }
        deliveries.save(delivery)
        val document = documentRepository.findById(sourceEvent.tenantId, delivery.documentId)
        if (
            normalizedResult.status == ConnectorSyncStatus.SUCCESS &&
            document != null &&
            delivery.deliveryGeneration == document.deliveryGeneration &&
            document.lifecycleState in setOf(LifecycleState.OFFLINE, LifecycleState.HISTORY)
        ) {
            removalPlanner?.plan(document)
        }
        reconcileDocument(sourceEvent.tenantId, delivery.documentId)
        auditTrail?.record(
            tenantId = sourceEvent.tenantId,
            resourceType = DOCUMENT_RESOURCE_TYPE,
            resourceId = delivery.documentId,
            action = if (normalizedResult.status == ConnectorSyncStatus.SUCCESS) DELIVERY_SUCCEEDED_AUDIT_ACTION else DELIVERY_FAILED_AUDIT_ACTION,
            details = linkedMapOf<String, String>().apply {
                put("deliveryId", delivery.id.value)
                put("targetId", delivery.targetId)
                put("connector", delivery.connectorId)
                put("status", normalizedResult.status.name)
                normalizedResult.externalId?.let { put("externalId", it) }
                normalizedResult.message?.let { put("message", it) }
            },
        )
        normalizedResult.toOutboxHandlingResult()
    }

    private fun reconcileDocument(tenantId: Identifier, documentId: Identifier) {
        val document = documentRepository.findById(tenantId, documentId) ?: return
        val required = deliveries.findByDocumentGeneration(tenantId, documentId, document.deliveryGeneration)
            .filter { it.requirement == DeliveryRequirement.REQUIRED }
        if (required.isEmpty()) return
        val allRequiredSucceeded = required.all { it.status == DocumentDeliveryStatus.SUCCEEDED }
        val requiredHasFailure = required.any { it.status == DocumentDeliveryStatus.RETRYING || it.status == DocumentDeliveryStatus.FAILED }
        when {
            allRequiredSucceeded && document.lifecycleState == LifecycleState.PUBLISHING -> document.transition(LifecycleCommand.PUBLISH_SUCCEEDED)
            allRequiredSucceeded && document.lifecycleState == LifecycleState.SYNC_ERROR -> {
                document.transition(LifecycleCommand.RETRY_SYNC)
                document.transition(LifecycleCommand.PUBLISH_SUCCEEDED)
            }

            requiredHasFailure && document.lifecycleState == LifecycleState.PUBLISHING -> document.transition(LifecycleCommand.SYNC_FAILED)
            !requiredHasFailure && document.lifecycleState == LifecycleState.SYNC_ERROR -> document.transition(LifecycleCommand.RETRY_SYNC)
            else -> return
        }
        documentRepository.save(document)
    }

    private fun currentVersion(document: Document): DocumentVersion? =
        document.currentVersionId?.let { currentVersionId -> document.versions.firstOrNull { it.id == currentVersionId } }

    private fun ConnectorSyncResult.toOutboxHandlingResult(): OutboxHandlingResult = when (status) {
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

    /** Metrics are emitted after all database work, and must not affect acknowledgement semantics. */
    private fun recordMetric(preparation: Preparation, handling: OutboxHandlingResult, tenantId: String) {
        if (preparation is Preparation.AlreadySucceeded || preparation is Preparation.Superseded) return
        val metric = if (handling.status == OutboxHandlingStatus.SUCCEEDED) {
            FileWeftMetric.SYNC_SUCCESS
        } else {
            FileWeftMetric.SYNC_FAILURE
        }
        try {
            metrics?.increment(
                metric,
                buildMap {
                    put("tenantId", tenantId)
                    preparation.connectorId?.let { connectorId -> put("connector", connectorId) }
                },
            )
        } catch (_: Exception) {
            // Observability failures must never alter outbox acknowledgement semantics.
        }
    }

    private sealed class Preparation {
        abstract val connectorId: String?

        data class AlreadySucceeded(override val connectorId: String) : Preparation()
        data class Superseded(override val connectorId: String) : Preparation()
        data class Ready(
            val delivery: DocumentDeliveryTarget,
            val documentId: Identifier,
            val fileObject: FileObject,
            val connector: com.fileweft.spi.connector.FileConnector,
        ) : Preparation() {
            override val connectorId: String = delivery.connectorId
        }

        data class Failure(
            val status: ConnectorSyncStatus,
            val message: String,
            override val connectorId: String? = null,
        ) : Preparation()
    }

    private companion object {
        const val DOCUMENT_RESOURCE_TYPE = "DOCUMENT"
        const val DELIVERY_SUCCEEDED_AUDIT_ACTION = "document:delivery:succeeded"
        const val DELIVERY_FAILED_AUDIT_ACTION = "document:delivery:failed"
    }
}
