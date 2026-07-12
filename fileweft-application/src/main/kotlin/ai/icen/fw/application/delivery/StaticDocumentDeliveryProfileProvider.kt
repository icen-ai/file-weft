package ai.icen.fw.application.delivery

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.connector.FileConnector
import ai.icen.fw.spi.delivery.DeliveryConnectorResolver
import ai.icen.fw.spi.delivery.DocumentDeliveryProfile
import ai.icen.fw.spi.delivery.DocumentDeliveryProfileProvider

/**
 * Configuration-backed profile provider suitable for a starter default.
 * Integrators that need tenant-specific rules can replace this bean with an
 * implementation backed by their own configuration service.
 */
class StaticDocumentDeliveryProfileProvider(
    profiles: List<DocumentDeliveryProfile>,
    private val defaultProfileId: String? = null,
) : DocumentDeliveryProfileProvider {
    private val profiles = profiles.toList()

    init {
        require(this.profiles.isNotEmpty()) { "At least one document delivery profile is required." }
        require(this.profiles.map { it.id }.distinct().size == this.profiles.size) {
            "Document delivery profile ids must be unique."
        }
        require(defaultProfileId == null || this.profiles.any { it.id == defaultProfileId }) {
            "Default document delivery profile must exist."
        }
    }

    override fun listProfiles(tenantId: Identifier): List<DocumentDeliveryProfile> = profiles

    override fun defaultProfile(tenantId: Identifier): DocumentDeliveryProfile? =
        defaultProfileId?.let { id -> profiles.firstOrNull { it.id == id } } ?: profiles.firstOrNull()
}

/** Resolves opaque connector identifiers from the integrating application's registry. */
class MapDeliveryConnectorResolver(
    connectors: Map<String, FileConnector>,
    private val legacyConnectorId: String? = null,
) : DeliveryConnectorResolver {
    private val connectors = connectors.toMap()

    override fun findConnector(connectorId: String): FileConnector? =
        connectors[connectorId]
            ?: legacyConnectorId?.takeIf { it == connectorId && connectors.size == 1 }?.let { connectors.values.single() }
}
