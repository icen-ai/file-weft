package ai.icen.fw.application.delivery

import ai.icen.fw.application.audit.AuditTrail
import ai.icen.fw.application.outbox.OutboxEventRepository
import ai.icen.fw.application.security.ApplicationAuthorization
import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.core.event.OutboxEvent
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.core.id.IdentifierGenerator
import ai.icen.fw.domain.document.DocumentRepository
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.identity.UserRealmProvider
import ai.icen.fw.spi.tenant.TenantProvider
import java.time.Clock

/** Explicit recovery path; it never rolls back targets that have already succeeded. */
class RetryDocumentDeliveryService(
    private val tenantProvider: TenantProvider,
    private val userRealmProvider: UserRealmProvider,
    authorizationProvider: AuthorizationProvider,
    private val documentRepository: DocumentRepository,
    private val deliveries: DocumentDeliveryTargetRepository,
    private val outbox: OutboxEventRepository,
    private val identifiers: IdentifierGenerator,
    private val transaction: ApplicationTransaction,
    private val clock: Clock,
    private val auditTrail: AuditTrail? = null,
) {
    private val authorization = ApplicationAuthorization(userRealmProvider, authorizationProvider)

    fun retry(deliveryId: Identifier): DocumentDeliveryTarget {
        val tenant = tenantProvider.currentTenant()
        val delivery = transaction.execute {
            deliveries.findById(tenant.tenantId, deliveryId)
                ?: throw NoSuchElementException("Delivery target ${deliveryId.value} was not found in the current tenant.")
        }
        val operator = userRealmProvider.currentUser()
        authorization.requireDocumentAction(tenant.tenantId, delivery.documentId, RETRY_ACTION)
        return transaction.execute {
            val current = deliveries.findById(tenant.tenantId, deliveryId)
                ?: throw NoSuchElementException("Delivery target ${deliveryId.value} was removed.")
            val document = documentRepository.findById(tenant.tenantId, current.documentId)
                ?: throw NoSuchElementException("Document ${current.documentId.value} was removed.")
            require(current.deliveryGeneration == document.deliveryGeneration) {
                "Historical delivery targets cannot be retried after a newer publication generation has started."
            }
            val eventId = identifiers.nextId()
            val eventType = when {
                current.status == DocumentDeliveryStatus.FAILED -> {
                    current.retryManually(eventId)
                    DocumentDeliveryPlanner.DELIVERY_REQUESTED_EVENT_TYPE
                }
                current.removalStatus == DocumentDeliveryRemovalStatus.FAILED -> {
                    current.retryRemovalManually(eventId)
                    DocumentDeliveryRemovalPlanner.DELIVERY_REMOVAL_REQUESTED_EVENT_TYPE
                }
                else -> throw IllegalArgumentException("Delivery target has no failed synchronization or downstream removal to retry.")
            }
            deliveries.save(current)
            outbox.append(
                OutboxEvent(
                    id = eventId,
                    tenantId = tenant.tenantId,
                    type = eventType,
                    payload = mapOf(
                        DocumentDeliveryPlanner.DOCUMENT_ID_PAYLOAD_KEY to current.documentId.value,
                        DocumentDeliveryPlanner.DELIVERY_ID_PAYLOAD_KEY to current.id.value,
                    ),
                    timestamp = clock.millis(),
                ),
            )
            auditTrail?.record(
                tenantId = tenant.tenantId,
                resourceType = "DOCUMENT",
                resourceId = current.documentId,
                action = RETRY_AUDIT_ACTION,
                operatorId = operator?.id,
                operatorName = operator?.displayName,
                details = mapOf(
                    "deliveryId" to current.id.value,
                    "targetId" to current.targetId,
                    "operation" to if (eventType == DocumentDeliveryRemovalPlanner.DELIVERY_REMOVAL_REQUESTED_EVENT_TYPE) "REMOVE" else "SYNC",
                ),
            )
            current
        }
    }

    companion object {
        const val RETRY_ACTION = "document:delivery:retry"
        const val RETRY_AUDIT_ACTION = "document:delivery:retry"
    }
}
