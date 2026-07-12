package ai.icen.fw.testkit.delivery

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.delivery.DeliveryRequirement
import ai.icen.fw.spi.delivery.DocumentDeliveryProfile
import ai.icen.fw.spi.delivery.DocumentDeliveryProfileProvider
import ai.icen.fw.spi.delivery.DocumentDeliveryTargetDefinition
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Reusable contract for tenant-scoped multi-downstream delivery configuration.
 * It validates provider consistency only; connector reachability remains the
 * responsibility of the integration and FileWeft Doctor.
 */
abstract class DocumentDeliveryProfileProviderContractTest {
    protected abstract val deliveryProfileProvider: DocumentDeliveryProfileProvider

    protected abstract fun tenantId(): Identifier

    @Test
    fun `lists unique profiles with valid target routes`() {
        val profiles = deliveryProfileProvider.listProfiles(tenantId())

        assertEquals(profiles.size, profiles.map { it.id }.distinct().size, "Delivery profile ids must be unique per tenant.")
        profiles.forEach(::assertProfileInvariants)
    }

    @Test
    fun `find profile is consistent with the tenant profile list`() {
        val tenantId = tenantId()
        val profiles = deliveryProfileProvider.listProfiles(tenantId)
        profiles.forEach { listed ->
            val found = deliveryProfileProvider.findProfile(tenantId, listed.id)
            assertTrue(found != null, "Listed delivery profile '${listed.id}' must be findable for the same tenant.")
            assertEquals(profileView(listed), profileView(requireNotNull(found)))
        }

        var missingId = "fileweft-contract-missing-profile"
        while (profiles.any { it.id == missingId }) missingId += "-x"
        assertNull(deliveryProfileProvider.findProfile(tenantId, missingId), "Unknown delivery profile ids must not resolve.")
    }

    @Test
    fun `default profile is absent or belongs to the tenant profile list`() {
        val tenantId = tenantId()
        val profiles = deliveryProfileProvider.listProfiles(tenantId)
        val defaultProfile = deliveryProfileProvider.defaultProfile(tenantId)

        if (defaultProfile != null) {
            assertProfileInvariants(defaultProfile)
            assertTrue(
                profiles.any { listed -> profileView(listed) == profileView(defaultProfile) },
                "Default delivery profile '${defaultProfile.id}' must belong to the tenant profile list.",
            )
        }
    }

    private fun assertProfileInvariants(profile: DocumentDeliveryProfile) {
        assertTrue(profile.id.isNotBlank(), "Delivery profile id must not be blank.")
        assertTrue(profile.displayName.isNotBlank(), "Delivery profile display name must not be blank.")
        assertTrue(profile.targets.isNotEmpty(), "Delivery profile '${profile.id}' must contain a target.")
        assertEquals(
            profile.targets.size,
            profile.targets.map { it.id }.distinct().size,
            "Delivery target ids must be unique in profile '${profile.id}'.",
        )
        assertTrue(
            profile.targets.any { it.requirement == DeliveryRequirement.REQUIRED },
            "Delivery profile '${profile.id}' must contain a required target.",
        )
        profile.targets.forEach { target ->
            assertTrue(target.id.isNotBlank(), "Delivery target id must not be blank.")
            assertTrue(target.displayName.isNotBlank(), "Delivery target display name must not be blank.")
            assertTrue(target.connectorId.isNotBlank(), "Delivery target connector id must not be blank.")
            val ownerRef = target.ownerRef
            assertTrue(ownerRef == null || ownerRef.isNotBlank(), "Delivery target owner reference must not be blank.")
        }
    }

    private fun profileView(profile: DocumentDeliveryProfile): ProfileView = ProfileView(
        profile.id,
        profile.displayName,
        profile.targets.map(::targetView),
    )

    private fun targetView(target: DocumentDeliveryTargetDefinition): TargetView = TargetView(
        target.id,
        target.displayName,
        target.connectorId,
        target.requirement,
        target.ownerRef,
    )

    private data class ProfileView(
        val id: String,
        val displayName: String,
        val targets: List<TargetView>,
    )

    private data class TargetView(
        val id: String,
        val displayName: String,
        val connectorId: String,
        val requirement: DeliveryRequirement,
        val ownerRef: String?,
    )
}
