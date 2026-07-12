package ai.icen.fw.testkit.delivery

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.delivery.DeliveryRequirement
import ai.icen.fw.spi.delivery.DocumentDeliveryProfile
import ai.icen.fw.spi.delivery.DocumentDeliveryProfileProvider
import ai.icen.fw.spi.delivery.DocumentDeliveryTargetDefinition

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
