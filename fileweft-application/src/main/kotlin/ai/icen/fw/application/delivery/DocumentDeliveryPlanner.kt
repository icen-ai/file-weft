package ai.icen.fw.application.delivery

import ai.icen.fw.application.outbox.OutboxEventRepository
import ai.icen.fw.core.event.OutboxEvent
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.core.id.IdentifierGenerator
import ai.icen.fw.domain.document.Document
import ai.icen.fw.domain.document.LifecycleState
import ai.icen.fw.spi.delivery.DeliveryConnectorResolver
import ai.icen.fw.spi.delivery.DeliveryRequirement
import ai.icen.fw.spi.delivery.DocumentDeliveryProfile
import ai.icen.fw.spi.delivery.DocumentDeliveryProfileProvider
import java.time.Clock
import java.util.Collections

class DocumentDeliveryPlan(
    val profileId: String,
    val deliveries: List<DocumentDeliveryTarget>,
)

/**
 * An immutable, tenant-scoped delivery profile snapshot resolved before a
 * FlowWeft business transaction begins. A provider or connector resolver may
 * consult a remote policy system, so callers must obtain this preparation
 * outside their database transaction and later pass it to [DocumentDeliveryPlanner.plan].
 */
class DocumentDeliveryPreparation(
    val tenantId: Identifier,
    val profileId: String,
    targets: List<DocumentDeliveryTargetPreparation>,
) {
    val targets: List<DocumentDeliveryTargetPreparation> =
        Collections.unmodifiableList(ArrayList(targets))

    init {
        require(profileId.isNotBlank()) { "Delivery profile id must not be blank." }
        require(this.targets.isNotEmpty()) { "Delivery preparation must contain at least one target." }
        require(this.targets.map { it.id }.distinct().size == this.targets.size) {
            "Delivery preparation target ids must be unique."
        }
        require(this.targets.any { it.requirement == DeliveryRequirement.REQUIRED }) {
            "Delivery preparation must contain at least one required target."
        }
    }
}

/** Immutable target data copied from an integration-owned delivery profile. */
class DocumentDeliveryTargetPreparation(
    val id: String,
    val displayName: String,
    val connectorId: String,
    val requirement: DeliveryRequirement,
    val ownerRef: String? = null,
) {
    init {
        require(id.isNotBlank()) { "Delivery target id must not be blank." }
        require(displayName.isNotBlank()) { "Delivery target display name must not be blank." }
        require(connectorId.isNotBlank()) { "Delivery connector id must not be blank." }
        require(ownerRef == null || ownerRef.isNotBlank()) {
            "Delivery target owner reference must not be blank when provided."
        }
    }
}

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
    /**
     * Resolves an integration-owned profile and validates its connector ids.
     * This may call a remote policy or registry implementation and must run
     * outside a FlowWeft database transaction.
     */
    fun prepare(tenantId: Identifier, profileId: String?): DocumentDeliveryPreparation {
        val profile = resolveProfile(tenantId, profileId)
        return DocumentDeliveryPreparation(
            tenantId = tenantId,
            profileId = profile.id,
            targets = profile.targets.map { definition ->
                require(connectors.findConnector(definition.connectorId) != null) {
                    "Delivery connector '${definition.connectorId}' is not configured for profile '${profile.id}'."
                }
                DocumentDeliveryTargetPreparation(
                    id = definition.id,
                    displayName = definition.displayName,
                    connectorId = definition.connectorId,
                    requirement = definition.requirement,
                    ownerRef = definition.ownerRef,
                )
            },
        )
    }

    /**
     * Freezes an already-resolved delivery profile into target and outbox rows.
     * This method performs persistence only and never resolves an external SPI.
     */
    fun plan(document: Document, preparation: DocumentDeliveryPreparation): DocumentDeliveryPlan {
        require(document.lifecycleState == LifecycleState.PUBLISHING) { "Document must be publishing before delivery is planned." }
        require(document.tenantId == preparation.tenantId) {
            "Delivery preparation tenant must match the document tenant."
        }
        val planned = preparation.targets.map { definition ->
            val deliveryId = identifiers.nextId()
            val eventId = identifiers.nextId()
            DocumentDeliveryTarget(
                id = deliveryId,
                tenantId = document.tenantId,
                documentId = document.id,
                deliveryGeneration = document.deliveryGeneration,
                profileId = preparation.profileId,
                targetId = definition.id,
                displayName = definition.displayName,
                connectorId = definition.connectorId,
                requirement = definition.requirement,
                ownerRef = definition.ownerRef,
            ).also { target ->
                target.bindInitialDelivery(eventId)
                deliveries.save(target)
                outbox.append(
                    OutboxEvent(
                        id = eventId,
                        tenantId = document.tenantId,
                        type = DELIVERY_REQUESTED_EVENT_TYPE,
                        payload = mapOf(DOCUMENT_ID_PAYLOAD_KEY to document.id.value, DELIVERY_ID_PAYLOAD_KEY to target.id.value),
                        timestamp = clock.millis(),
                    ),
                )
            }
        }
        return DocumentDeliveryPlan(preparation.profileId, planned)
    }

    /**
     * Compatibility convenience for callers that already operate outside a
     * transaction. New publication paths should explicitly call [prepare]
     * before entering a business transaction.
     */
    @Deprecated("Resolve delivery preparation outside a transaction before planning publication.")
    fun plan(document: Document, profileId: String?): DocumentDeliveryPlan {
        return plan(document, prepare(document.tenantId, profileId))
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
