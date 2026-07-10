package com.fileweft.application.delivery

import com.fileweft.application.audit.AuditTrail
import com.fileweft.application.outbox.OutboxEventRepository
import com.fileweft.application.security.ApplicationAuthorization
import com.fileweft.application.transaction.ApplicationTransaction
import com.fileweft.core.event.OutboxEvent
import com.fileweft.core.id.Identifier
import com.fileweft.core.id.IdentifierGenerator
import com.fileweft.spi.authorization.AuthorizationProvider
import com.fileweft.spi.identity.UserRealmProvider
import com.fileweft.spi.tenant.TenantProvider
import java.time.Clock

/** Explicit recovery path; it never rolls back targets that have already succeeded. */
class RetryDocumentDeliveryService(
    private val tenantProvider: TenantProvider,
    private val userRealmProvider: UserRealmProvider,
    authorizationProvider: AuthorizationProvider,
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
            current.retryManually()
            deliveries.save(current)
            outbox.append(
                OutboxEvent(
                    id = identifiers.nextId(),
                    tenantId = tenant.tenantId,
                    type = DocumentDeliveryPlanner.DELIVERY_REQUESTED_EVENT_TYPE,
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
                details = mapOf("deliveryId" to current.id.value, "targetId" to current.targetId),
            )
            current
        }
    }

    companion object {
        const val RETRY_ACTION = "document:delivery:retry"
        const val RETRY_AUDIT_ACTION = "document:delivery:retry"
    }
}
