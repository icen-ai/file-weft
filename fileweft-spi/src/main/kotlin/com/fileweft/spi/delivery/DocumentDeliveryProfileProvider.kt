package com.fileweft.spi.delivery

import com.fileweft.core.id.Identifier
import com.fileweft.spi.connector.FileConnector

/** Defines the completion rule for one downstream target in a publication profile. */
enum class DeliveryRequirement {
    REQUIRED,
    OPTIONAL,
}

/**
 * Immutable target configuration supplied by the integrating system.
 *
 * [id], [connectorId] and [ownerRef] are opaque strings so applications can
 * retain their own identifiers and responsibility model.
 */
class DocumentDeliveryTargetDefinition(
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
        require(ownerRef == null || ownerRef.isNotBlank()) { "Delivery target owner reference must not be blank when provided." }
    }
}

/** A publishable, tenant-aware set of required and optional delivery targets. */
class DocumentDeliveryProfile(
    val id: String,
    val displayName: String,
    val targets: List<DocumentDeliveryTargetDefinition>,
) {
    init {
        require(id.isNotBlank()) { "Delivery profile id must not be blank." }
        require(displayName.isNotBlank()) { "Delivery profile display name must not be blank." }
        require(targets.isNotEmpty()) { "Delivery profile must define at least one target." }
        require(targets.map { it.id }.distinct().size == targets.size) { "Delivery profile target ids must be unique." }
        require(targets.any { it.requirement == DeliveryRequirement.REQUIRED }) {
            "Delivery profile must define at least one required target."
        }
    }
}

/**
 * Resolves the profiles a tenant may select at publication time. The selected
 * profile is snapshotted into delivery records, so later configuration changes
 * never alter an in-flight release.
 */
interface DocumentDeliveryProfileProvider {
    fun listProfiles(tenantId: Identifier): List<DocumentDeliveryProfile>

    fun findProfile(tenantId: Identifier, profileId: String): DocumentDeliveryProfile? =
        listProfiles(tenantId).firstOrNull { it.id == profileId }

    fun defaultProfile(tenantId: Identifier): DocumentDeliveryProfile? = listProfiles(tenantId).firstOrNull()
}

/** Resolves a configured connector ID without exposing the integrating DI container to FileWeft. */
interface DeliveryConnectorResolver {
    fun findConnector(connectorId: String): FileConnector?
}
