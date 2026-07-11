package com.fileweft.testkit.delivery

import com.fileweft.core.id.Identifier
import com.fileweft.spi.delivery.DeliveryRequirement
import com.fileweft.spi.delivery.DocumentDeliveryProfile
import com.fileweft.spi.delivery.DocumentDeliveryProfileProvider
import com.fileweft.spi.delivery.DocumentDeliveryTargetDefinition

/** Exercises the reusable profile contract against a deterministic tenant-aware fixture. */
class DocumentDeliveryProfileProviderContractTestBehaviorTest : DocumentDeliveryProfileProviderContractTest() {
    override val deliveryProfileProvider: DocumentDeliveryProfileProvider = object : DocumentDeliveryProfileProvider {
        override fun listProfiles(tenantId: Identifier): List<DocumentDeliveryProfile> = when (tenantId.value) {
            "tenant-contract" -> listOf(profile("regulated"), profile("internal"))
            else -> emptyList()
        }

        override fun defaultProfile(tenantId: Identifier): DocumentDeliveryProfile? =
            listProfiles(tenantId).firstOrNull { it.id == "regulated" }
    }

    override fun tenantId(): Identifier = Identifier("tenant-contract")

    private fun profile(id: String): DocumentDeliveryProfile = DocumentDeliveryProfile(
        id = id,
        displayName = id,
        targets = listOf(
            DocumentDeliveryTargetDefinition(
                id = "$id-required",
                displayName = "Required $id",
                connectorId = "$id-connector",
                requirement = DeliveryRequirement.REQUIRED,
                ownerRef = "delivery-ops",
            ),
        ),
    )
}
