package ai.icen.fw.dev.api.web

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.dev.api.service.DevAccessService
import ai.icen.fw.spi.delivery.DocumentDeliveryProfileProvider
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class DevDeliveryProfileView(
    val id: String,
    val displayName: String,
    val targets: List<DevDeliveryProfileTargetView>,
)

data class DevDeliveryProfileTargetView(
    val id: String,
    val displayName: String,
    val connectorId: String,
    val requirement: String,
    val ownerRef: String?,
)

/** Exposes only the tenant's selectable, SPI-provided delivery profiles. */
@RestController
@RequestMapping("/api/delivery-profiles")
class DevDeliveryController(
    private val profiles: DocumentDeliveryProfileProvider,
    private val access: DevAccessService,
    private val tenants: ai.icen.fw.spi.tenant.TenantProvider,
) {
    @GetMapping
    fun list(): List<DevDeliveryProfileView> {
        access.requireAction(Identifier("delivery-profiles"), "DELIVERY_PROFILE", "document:read")
        return profiles.listProfiles(tenants.currentTenant().tenantId).map { profile ->
            DevDeliveryProfileView(
                profile.id,
                profile.displayName,
                profile.targets.map { target ->
                    DevDeliveryProfileTargetView(
                        target.id, target.displayName, target.connectorId, target.requirement.name, target.ownerRef,
                    )
                },
            )
        }
    }
}
