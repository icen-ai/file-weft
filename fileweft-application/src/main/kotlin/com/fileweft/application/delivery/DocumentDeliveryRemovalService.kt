package com.fileweft.application.delivery

import com.fileweft.application.audit.AuditTrail
import com.fileweft.application.transaction.ApplicationTransaction
import com.fileweft.core.event.OutboxEvent
import com.fileweft.core.id.Identifier
import com.fileweft.spi.connector.ConnectorInvocation
import com.fileweft.spi.connector.ConnectorRemoveRequest
import com.fileweft.spi.connector.ConnectorSyncResult
import com.fileweft.spi.connector.ConnectorSyncStatus
import com.fileweft.spi.delivery.DeliveryConnectorResolver
import com.fileweft.spi.event.OutboxHandlingResult
import com.fileweft.spi.event.OutboxHandlingStatus
import com.fileweft.spi.observability.FileWeftMetric
import com.fileweft.spi.observability.FileWeftMetrics
import java.time.Duration

/** Removes one frozen downstream target outside the transaction that marked a document unavailable. */
class DocumentDeliveryRemovalService @JvmOverloads constructor(
    private val connectors: DeliveryConnectorResolver,
    private val deliveries: DocumentDeliveryTargetRepository,
    private val transaction: ApplicationTransaction,
    private val connectorTimeout: Duration = Duration.ofSeconds(30),
    private val auditTrail: AuditTrail? = null,
    private val metrics: FileWeftMetrics? = null,
) {
    init {
        require(!connectorTimeout.isNegative && !connectorTimeout.isZero) { "Connector timeout must be positive." }
    }

    fun remove(sourceEvent: OutboxEvent): OutboxHandlingResult {
        val deliveryId = sourceEvent.deliveryIdOrNull()
            ?: return OutboxHandlingResult(OutboxHandlingStatus.PERMANENT_FAILURE, "Delivery removal event does not contain deliveryId.")
        val preparation = transaction.execute { prepare(sourceEvent.tenantId, deliveryId) }
        val handling = when (preparation) {
            is Preparation.AlreadyRemoved -> OutboxHandlingResult(OutboxHandlingStatus.SUCCEEDED, "Delivery target is already removed downstream.")
            is Preparation.Failure -> complete(sourceEvent, deliveryId, ConnectorSyncResult(preparation.status, message = preparation.message))
            is Preparation.Ready -> complete(sourceEvent, deliveryId, invokeConnector(preparation))
        }
        recordMetric(preparation, handling, sourceEvent.tenantId.value)
        return handling
    }

    fun exhaust(sourceEvent: OutboxEvent, message: String) {
        val deliveryId = sourceEvent.deliveryIdOrNull() ?: return
        val diagnostic = DeliveryDiagnosticMessage.normalize(message) ?: "Delivery removal retry limit was reached."
        transaction.execute {
            val delivery = deliveries.findById(sourceEvent.tenantId, deliveryId) ?: return@execute
            if (delivery.removalStatus != DocumentDeliveryRemovalStatus.SUCCEEDED) {
                delivery.markRemovalFailed(diagnostic)
                deliveries.save(delivery)
                audit(sourceEvent, delivery, DELIVERY_REMOVAL_FAILED_AUDIT_ACTION, mapOf("message" to diagnostic))
            }
        }
    }

    private fun prepare(tenantId: Identifier, deliveryId: Identifier): Preparation {
        val delivery = deliveries.findById(tenantId, deliveryId)
            ?: return Preparation.Failure(ConnectorSyncStatus.PERMANENT_FAILURE, "Delivery target was not found in the event tenant.")
        when (delivery.removalStatus) {
            DocumentDeliveryRemovalStatus.SUCCEEDED -> return Preparation.AlreadyRemoved(delivery.connectorId)
            DocumentDeliveryRemovalStatus.FAILED -> return Preparation.Failure(
                ConnectorSyncStatus.PERMANENT_FAILURE,
                "Delivery target removal requires a manual retry.",
                delivery.connectorId,
            )
            DocumentDeliveryRemovalStatus.NOT_REQUESTED -> return Preparation.Failure(
                ConnectorSyncStatus.PERMANENT_FAILURE,
                "Delivery target removal was not requested.",
                delivery.connectorId,
            )
            DocumentDeliveryRemovalStatus.PENDING, DocumentDeliveryRemovalStatus.RETRYING -> Unit
        }
        val connector = connectors.findConnector(delivery.connectorId) ?: return Preparation.Failure(
            ConnectorSyncStatus.PERMANENT_FAILURE,
            "Delivery connector '${delivery.connectorId}' is no longer configured.",
            delivery.connectorId,
        )
        return Preparation.Ready(delivery, connector)
    }

    private fun invokeConnector(preparation: Preparation.Ready): ConnectorSyncResult = try {
        val delivery = preparation.delivery
        preparation.connector.remove(
            ConnectorRemoveRequest(
                tenantId = delivery.tenantId,
                businessId = delivery.documentId,
                externalId = delivery.externalId ?: delivery.documentId.value,
                invocation = ConnectorInvocation("delivery-remove:${delivery.id.value}", connectorTimeout),
            ),
        )
    } catch (_: Exception) {
        ConnectorSyncResult(ConnectorSyncStatus.RETRYABLE_FAILURE, message = "Connector removal invocation could not complete.")
    }

    private fun complete(sourceEvent: OutboxEvent, deliveryId: Identifier, result: ConnectorSyncResult): OutboxHandlingResult = transaction.execute {
        val normalizedResult = result.copy(message = DeliveryDiagnosticMessage.normalize(result.message))
        val delivery = deliveries.findById(sourceEvent.tenantId, deliveryId)
            ?: return@execute OutboxHandlingResult(OutboxHandlingStatus.PERMANENT_FAILURE, "Delivery target was removed before downstream withdrawal.")
        when (normalizedResult.status) {
            ConnectorSyncStatus.SUCCESS -> delivery.markRemovalSucceeded()
            ConnectorSyncStatus.RETRYABLE_FAILURE -> delivery.markRemovalRetrying(normalizedResult.message)
            ConnectorSyncStatus.PERMANENT_FAILURE -> delivery.markRemovalFailed(normalizedResult.message)
        }
        deliveries.save(delivery)
        audit(
            sourceEvent,
            delivery,
            if (normalizedResult.status == ConnectorSyncStatus.SUCCESS) DELIVERY_REMOVAL_SUCCEEDED_AUDIT_ACTION else DELIVERY_REMOVAL_FAILED_AUDIT_ACTION,
            linkedMapOf<String, String>().apply {
                put("status", normalizedResult.status.name)
                normalizedResult.message?.let { put("message", it) }
            },
        )
        normalizedResult.toOutboxHandlingResult()
    }

    private fun audit(sourceEvent: OutboxEvent, delivery: DocumentDeliveryTarget, action: String, details: Map<String, String>) {
        auditTrail?.record(
            tenantId = sourceEvent.tenantId,
            resourceType = DOCUMENT_RESOURCE_TYPE,
            resourceId = delivery.documentId,
            action = action,
            details = linkedMapOf<String, String>().apply {
                put("deliveryId", delivery.id.value)
                put("targetId", delivery.targetId)
                put("connector", delivery.connectorId)
                putAll(details)
            },
        )
    }

    private fun OutboxEvent.deliveryIdOrNull(): Identifier? = payload[DocumentDeliveryPlanner.DELIVERY_ID_PAYLOAD_KEY]
        ?.takeIf { it.isNotBlank() }?.let(::Identifier)

    private fun ConnectorSyncResult.toOutboxHandlingResult(): OutboxHandlingResult = when (status) {
        ConnectorSyncStatus.SUCCESS -> OutboxHandlingResult(OutboxHandlingStatus.SUCCEEDED, message)
        ConnectorSyncStatus.RETRYABLE_FAILURE -> OutboxHandlingResult(
            OutboxHandlingStatus.RETRYABLE_FAILURE,
            message ?: "Connector removal should be retried.",
        )
        ConnectorSyncStatus.PERMANENT_FAILURE -> OutboxHandlingResult(
            OutboxHandlingStatus.PERMANENT_FAILURE,
            message ?: "Connector removal cannot succeed without intervention.",
        )
    }

    /** Metrics are emitted after all database work, and must not affect acknowledgement semantics. */
    private fun recordMetric(preparation: Preparation, handling: OutboxHandlingResult, tenantId: String) {
        if (preparation is Preparation.AlreadyRemoved) return
        val metric = if (handling.status == OutboxHandlingStatus.SUCCEEDED) {
            FileWeftMetric.DELIVERY_REMOVAL_SUCCESS
        } else {
            FileWeftMetric.DELIVERY_REMOVAL_FAILURE
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

        data class AlreadyRemoved(override val connectorId: String) : Preparation()
        data class Ready(
            val delivery: DocumentDeliveryTarget,
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
        const val DELIVERY_REMOVAL_SUCCEEDED_AUDIT_ACTION = "document:delivery:remove:succeeded"
        const val DELIVERY_REMOVAL_FAILED_AUDIT_ACTION = "document:delivery:remove:failed"
    }
}
