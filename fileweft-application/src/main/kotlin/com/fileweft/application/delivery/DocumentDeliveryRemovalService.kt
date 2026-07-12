package com.fileweft.application.delivery

import com.fileweft.application.audit.AuditTrail
import com.fileweft.application.outbox.OutboxEventLease
import com.fileweft.application.outbox.OutboxEventMutationRepository
import com.fileweft.application.outbox.OutboxEventStatus
import com.fileweft.application.outbox.OutboxLeaseLostException
import com.fileweft.application.transaction.ApplicationTransaction
import com.fileweft.core.event.OutboxEvent
import com.fileweft.core.id.Identifier
import com.fileweft.domain.document.DocumentRepository
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

    fun remove(sourceEvent: OutboxEvent): OutboxHandlingResult = remove(sourceEvent, null, null, null)

    /** Strong worker path with document -> target -> Outbox lease locking. */
    @JvmSynthetic
    internal fun remove(
        lease: OutboxEventLease,
        outboxMutations: OutboxEventMutationRepository,
        documents: DocumentRepository,
    ): OutboxHandlingResult = remove(lease.event, lease, outboxMutations, documents)

    private fun remove(
        sourceEvent: OutboxEvent,
        lease: OutboxEventLease?,
        outboxMutations: OutboxEventMutationRepository?,
        documents: DocumentRepository?,
    ): OutboxHandlingResult {
        val deliveryId = sourceEvent.deliveryIdOrNull()
            ?: return OutboxHandlingResult(OutboxHandlingStatus.PERMANENT_FAILURE, "Delivery removal event does not contain deliveryId.")
        val preparation = resolveConnector(
            transaction.execute { freeze(sourceEvent, deliveryId) },
        )
        val handling = when (preparation) {
            is Preparation.AlreadyRemoved -> OutboxHandlingResult(OutboxHandlingStatus.SUCCEEDED, "Delivery target is already removed downstream.")
            is Preparation.Frozen -> error("Frozen delivery removal preparation must be resolved before it is handled.")
            is Preparation.Failure -> complete(
                sourceEvent,
                deliveryId,
                preparation.expectation,
                ConnectorSyncResult(preparation.status, message = preparation.message),
                lease,
                outboxMutations,
                documents,
            )
            is Preparation.Ready -> complete(
                sourceEvent,
                deliveryId,
                preparation.expectation,
                invokeConnector(preparation),
                lease,
                outboxMutations,
                documents,
            )
        }
        recordMetric(preparation, handling, sourceEvent.tenantId.value)
        return handling
    }

    fun exhaust(sourceEvent: OutboxEvent, message: String) = exhaust(sourceEvent, message, null, null)

    @JvmSynthetic
    internal fun exhaust(
        sourceEvent: OutboxEvent,
        message: String,
        documents: DocumentRepository?,
        outboxMutations: OutboxEventMutationRepository?,
    ) {
        val deliveryId = sourceEvent.deliveryIdOrNull() ?: return
        val diagnostic = DeliveryDiagnosticMessage.normalize(message) ?: "Delivery removal retry limit was reached."
        transaction.execute {
            val expectedDocumentId = sourceEvent.payload[DocumentDeliveryPlanner.DOCUMENT_ID_PAYLOAD_KEY]
                ?.takeIf { it.isNotBlank() }?.let(::Identifier) ?: return@execute
            documents?.findForMutation(sourceEvent.tenantId, expectedDocumentId)
            val mutationDeliveries = deliveries as? DocumentDeliveryTargetMutationRepository
                ?: throw IllegalStateException("Delivery removal requires a mutation-capable target repository.")
            val delivery = mutationDeliveries.findForMutation(sourceEvent.tenantId, deliveryId) ?: return@execute
            val fence = delivery.currentDispatchFence
            val durableFailure = outboxMutations == null || outboxMutations
                .findForMutation(sourceEvent.tenantId, sourceEvent.id)
                ?.let { state ->
                    state.id == sourceEvent.id &&
                        state.tenantId == sourceEvent.tenantId &&
                        state.eventType == DocumentDeliveryRemovalPlanner.DELIVERY_REMOVAL_REQUESTED_EVENT_TYPE &&
                        state.status == OutboxEventStatus.FAILED
                } == true
            if (
                durableFailure &&
                delivery.documentId == expectedDocumentId &&
                fence != null &&
                delivery.matchesDispatch(sourceEvent.id, DeliveryDispatchOperation.REMOVAL, fence.sequence) &&
                delivery.removalStatus in ACTIVE_REMOVAL_STATUSES
            ) {
                delivery.markRemovalFailed(diagnostic)
                deliveries.save(delivery)
                audit(sourceEvent, delivery, DELIVERY_REMOVAL_FAILED_AUDIT_ACTION, mapOf("message" to diagnostic))
            }
        }
    }

    /** Freezes FileWeft-owned target state before resolving an integration SPI. */
    private fun freeze(sourceEvent: OutboxEvent, deliveryId: Identifier): Preparation {
        val tenantId = sourceEvent.tenantId
        val delivery = deliveries.findById(tenantId, deliveryId)
            ?: return Preparation.Failure(ConnectorSyncStatus.PERMANENT_FAILURE, "Delivery target was not found in the event tenant.")
        val fence = delivery.currentDispatchFence
            ?: return Preparation.Failure(ConnectorSyncStatus.PERMANENT_FAILURE, "Delivery target is missing its dispatch fence.")
        if (!delivery.matchesDispatch(sourceEvent.id, DeliveryDispatchOperation.REMOVAL, fence.sequence)) {
            return Preparation.AlreadyRemoved(delivery.connectorId)
        }
        val target = DeliverySnapshot(delivery)
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
                CompletionExpectation(target, setOf(DocumentDeliveryRemovalStatus.NOT_REQUESTED)),
            )
            DocumentDeliveryRemovalStatus.PENDING, DocumentDeliveryRemovalStatus.RETRYING -> Unit
        }
        return Preparation.Frozen(
            CompletionExpectation(target, ACTIVE_REMOVAL_STATUSES),
        )
    }

    /** Resolves the connector after [freeze] has completed, never while a transaction is open. */
    private fun resolveConnector(preparation: Preparation): Preparation = when (preparation) {
        is Preparation.Frozen -> {
            val connector = try {
                connectors.findConnector(preparation.expectation.target.connectorId)
            } catch (_: Exception) {
                return Preparation.Failure(
                    ConnectorSyncStatus.RETRYABLE_FAILURE,
                    "Delivery connector resolution could not complete.",
                    preparation.connectorId,
                    preparation.expectation,
                )
            }
            if (connector == null) {
                Preparation.Failure(
                    ConnectorSyncStatus.PERMANENT_FAILURE,
                    "Delivery connector '${preparation.expectation.target.connectorId}' is no longer configured.",
                    preparation.connectorId,
                    preparation.expectation,
                )
            } else {
                Preparation.Ready(preparation.expectation, connector)
            }
        }

        else -> preparation
    }

    private fun invokeConnector(preparation: Preparation.Ready): ConnectorSyncResult = try {
        val delivery = preparation.expectation.target
        preparation.connector.remove(
            ConnectorRemoveRequest(
                tenantId = delivery.tenantId,
                businessId = delivery.documentId,
                externalId = delivery.externalId,
                invocation = ConnectorInvocation("delivery-remove:${delivery.id.value}", connectorTimeout),
            ),
        )
    } catch (_: Exception) {
        ConnectorSyncResult(ConnectorSyncStatus.RETRYABLE_FAILURE, message = "Connector removal invocation could not complete.")
    }

    private fun complete(
        sourceEvent: OutboxEvent,
        deliveryId: Identifier,
        expectation: CompletionExpectation?,
        result: ConnectorSyncResult,
        lease: OutboxEventLease?,
        outboxMutations: OutboxEventMutationRepository?,
        documents: DocumentRepository?,
    ): OutboxHandlingResult = transaction.execute {
        val normalizedResult = result.copy(message = DeliveryDiagnosticMessage.normalize(result.message))
        if (expectation == null) {
            return@execute normalizedResult.toOutboxHandlingResult()
        }
        documents?.findForMutation(sourceEvent.tenantId, expectation.target.documentId)
        val mutationDeliveries = deliveries as? DocumentDeliveryTargetMutationRepository
            ?: throw IllegalStateException("Delivery removal requires a mutation-capable target repository.")
        val delivery = mutationDeliveries.findForMutation(sourceEvent.tenantId, deliveryId)
            ?: return@execute OutboxHandlingResult(OutboxHandlingStatus.PERMANENT_FAILURE, "Delivery target was removed before downstream withdrawal.")
        if (!expectation.target.matches(delivery)) {
            return@execute OutboxHandlingResult(OutboxHandlingStatus.SUCCEEDED, "Delivery target changed before downstream withdrawal completed.")
        }
        if (delivery.removalStatus == DocumentDeliveryRemovalStatus.SUCCEEDED) {
            return@execute OutboxHandlingResult(OutboxHandlingStatus.SUCCEEDED, "Delivery target is already removed downstream.")
        }
        if (
            delivery.status != DocumentDeliveryStatus.SUCCEEDED ||
            delivery.removalStatus !in expectation.allowedRemovalStatuses
        ) {
            return@execute OutboxHandlingResult(OutboxHandlingStatus.SUCCEEDED, "Delivery target changed before downstream withdrawal completed.")
        }
        if (lease != null) {
            val mutationRepository = outboxMutations
                ?: throw IllegalStateException("Fenced delivery removal requires an Outbox mutation repository.")
            val state = mutationRepository.findForMutation(sourceEvent.tenantId, sourceEvent.id)
                ?: throw OutboxLeaseLostException("Delivery removal Outbox event no longer exists in the current tenant.")
            state.requireCurrentLease(lease)
        }
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

    private class DeliverySnapshot(delivery: DocumentDeliveryTarget) {
        val id: Identifier = delivery.id
        val tenantId: Identifier = delivery.tenantId
        val documentId: Identifier = delivery.documentId
        val profileId: String = delivery.profileId
        val targetId: String = delivery.targetId
        val displayName: String = delivery.displayName
        val connectorId: String = delivery.connectorId
        val externalId: String = delivery.externalId ?: delivery.documentId.value
        val deliveryGeneration: Int = delivery.deliveryGeneration
        val dispatchFence: DeliveryDispatchFence = requireNotNull(delivery.currentDispatchFence) {
            "Delivery target is missing its dispatch fence."
        }

        fun matches(current: DocumentDeliveryTarget): Boolean =
            current.id == id &&
                current.tenantId == tenantId &&
                current.documentId == documentId &&
                current.profileId == profileId &&
                current.targetId == targetId &&
                current.displayName == displayName &&
                current.connectorId == connectorId &&
                (current.externalId ?: current.documentId.value) == externalId &&
                current.deliveryGeneration == deliveryGeneration &&
                current.matchesDispatch(dispatchFence.eventId, dispatchFence.operation, dispatchFence.sequence)
    }

    private class CompletionExpectation(
        val target: DeliverySnapshot,
        val allowedRemovalStatuses: Set<DocumentDeliveryRemovalStatus>,
    )

    private sealed class Preparation {
        abstract val connectorId: String?

        data class AlreadyRemoved(override val connectorId: String) : Preparation()
        data class Frozen(
            val expectation: CompletionExpectation,
        ) : Preparation() {
            override val connectorId: String = expectation.target.connectorId
        }

        data class Ready(
            val expectation: CompletionExpectation,
            val connector: com.fileweft.spi.connector.FileConnector,
        ) : Preparation() {
            override val connectorId: String = expectation.target.connectorId
        }

        data class Failure(
            val status: ConnectorSyncStatus,
            val message: String,
            override val connectorId: String? = null,
            val expectation: CompletionExpectation? = null,
        ) : Preparation()
    }

    private companion object {
        val ACTIVE_REMOVAL_STATUSES = setOf(
            DocumentDeliveryRemovalStatus.PENDING,
            DocumentDeliveryRemovalStatus.RETRYING,
        )
        const val DOCUMENT_RESOURCE_TYPE = "DOCUMENT"
        const val DELIVERY_REMOVAL_SUCCEEDED_AUDIT_ACTION = "document:delivery:remove:succeeded"
        const val DELIVERY_REMOVAL_FAILED_AUDIT_ACTION = "document:delivery:remove:failed"
    }
}
