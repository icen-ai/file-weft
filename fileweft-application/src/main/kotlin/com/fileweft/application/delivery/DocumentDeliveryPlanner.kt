package com.fileweft.application.delivery

import com.fileweft.application.outbox.OutboxEventRepository
import com.fileweft.core.event.OutboxEvent
import com.fileweft.core.id.Identifier
import com.fileweft.core.id.IdentifierGenerator
import com.fileweft.domain.document.Document
import com.fileweft.domain.document.LifecycleState
import com.fileweft.spi.delivery.DeliveryConnectorResolver
import com.fileweft.spi.delivery.DocumentDeliveryProfile
import com.fileweft.spi.delivery.DocumentDeliveryProfileProvider
import java.time.Clock

class DocumentDeliveryPlan(
    val profileId: String,
    val deliveries: List<DocumentDeliveryTarget>,
)

/**
 * Freezes a delivery profile and writes one independent outbox event per target
 * inside the publication transaction.
 */
class DocumentDeliveryPlanner(
    private val profiles: DocumentDeliveryProfileProvider,
    private val connectors: DeliveryConnectorResolver,
    private val deliveries: DocumentDeliveryTargetRepository,
    private val outbox: OutboxEventRepository,
    private val identifiers: IdentifierGenerator,
    private val clock: Clock,
) {
    fun plan(document: Document, profileId: String?): DocumentDeliveryPlan {
        require(document.lifecycleState == LifecycleState.PUBLISHING) { "Document must be publishing before delivery is planned." }
        val profile = resolveProfile(document.tenantId, profileId)
        val planned = profile.targets.map { definition ->
            require(connectors.findConnector(definition.connectorId) != null) {
                "Delivery connector '${definition.connectorId}' is not configured for profile '${profile.id}'."
            }
            DocumentDeliveryTarget(
                id = identifiers.nextId(),
                tenantId = document.tenantId,
                documentId = document.id,
                profileId = profile.id,
                targetId = definition.id,
                displayName = definition.displayName,
                connectorId = definition.connectorId,
                requirement = definition.requirement,
                ownerRef = definition.ownerRef,
            ).also { target ->
                deliveries.save(target)
                outbox.append(
                    OutboxEvent(
                        id = identifiers.nextId(),
                        tenantId = document.tenantId,
                        type = DELIVERY_REQUESTED_EVENT_TYPE,
                        payload = mapOf(DOCUMENT_ID_PAYLOAD_KEY to document.id.value, DELIVERY_ID_PAYLOAD_KEY to target.id.value),
                        timestamp = clock.millis(),
                    ),
                )
            }
        }
        return DocumentDeliveryPlan(profile.id, planned)
    }

    private fun resolveProfile(tenantId: Identifier, profileId: String?): DocumentDeliveryProfile {
        val requestedProfileId = profileId?.trim()?.takeIf { it.isNotEmpty() }
        return if (requestedProfileId != null) {
            profiles.findProfile(tenantId, requestedProfileId)
                ?: throw IllegalArgumentException("Delivery profile '$requestedProfileId' is not available for the current tenant.")
        } else {
            profiles.defaultProfile(tenantId)
                ?: throw IllegalArgumentException("No delivery profile is configured for the current tenant.")
        }
    }

    companion object {
        const val DELIVERY_REQUESTED_EVENT_TYPE = "document.delivery.target.requested"
        const val DOCUMENT_ID_PAYLOAD_KEY = "documentId"
        const val DELIVERY_ID_PAYLOAD_KEY = "deliveryId"
    }
}
