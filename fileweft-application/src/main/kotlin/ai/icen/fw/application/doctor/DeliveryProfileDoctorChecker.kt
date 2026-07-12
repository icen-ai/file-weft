package ai.icen.fw.application.doctor

import ai.icen.fw.core.context.DoctorCheckContext
import ai.icen.fw.core.result.DoctorCheckResult
import ai.icen.fw.core.result.DoctorStatus
import ai.icen.fw.spi.delivery.DeliveryConnectorResolver
import ai.icen.fw.spi.delivery.DocumentDeliveryProfileProvider
import ai.icen.fw.spi.doctor.DoctorChecker

/**
 * Verifies that every delivery target exposed to a tenant can be resolved before
 * a user starts a publication transaction. It deliberately does not invoke a
 * connector; [ConnectorDoctorChecker] remains responsible for connector health.
 */
class DeliveryProfileDoctorChecker(
    private val profiles: DocumentDeliveryProfileProvider,
    private val connectors: DeliveryConnectorResolver,
) : DoctorChecker {
    override fun name(): String = NAME

    override fun check(context: DoctorCheckContext): DoctorCheckResult {
        val configuredProfiles = try {
            profiles.listProfiles(context.tenantId)
        } catch (failure: Throwable) {
            return DoctorCheckResult(
                NAME,
                DoctorStatus.ERROR,
                "Delivery profile configuration could not be loaded.",
                mapOf("exceptionType" to failure.javaClass.name),
                "Inspect the tenant delivery-profile provider and run diagnosis again.",
            )
        }
        if (configuredProfiles.isEmpty()) {
            return DoctorCheckResult(
                NAME,
                DoctorStatus.ERROR,
                "No delivery profile is available for the current tenant.",
                repairSuggestion = "Configure at least one delivery profile before publishing documents.",
            )
        }

        val evidence = linkedMapOf<String, String>()
        var unresolvedTargetCount = 0
        configuredProfiles.forEachIndexed { profileIndex, profile ->
            val profileKey = "profile.$profileIndex"
            evidence["$profileKey.id"] = profile.id
            evidence["$profileKey.targetCount"] = profile.targets.size.toString()
            profile.targets.forEachIndexed { targetIndex, target ->
                val targetKey = "$profileKey.target.$targetIndex"
                evidence["$targetKey.id"] = target.id
                evidence["$targetKey.connectorId"] = target.connectorId
                evidence["$targetKey.requirement"] = target.requirement.name
                target.ownerRef?.let { evidence["$targetKey.ownerRef"] = it }
                try {
                    if (connectors.findConnector(target.connectorId) == null) {
                        evidence["$targetKey.status"] = "UNRESOLVED"
                        unresolvedTargetCount++
                    } else {
                        evidence["$targetKey.status"] = "RESOLVED"
                    }
                } catch (failure: Throwable) {
                    evidence["$targetKey.status"] = "RESOLUTION_FAILED"
                    evidence["$targetKey.exceptionType"] = failure.javaClass.name
                    unresolvedTargetCount++
                }
            }
        }
        evidence["profileCount"] = configuredProfiles.size.toString()
        evidence["unresolvedTargetCount"] = unresolvedTargetCount.toString()
        return if (unresolvedTargetCount > 0) {
            DoctorCheckResult(
                NAME,
                DoctorStatus.ERROR,
                "One or more delivery targets cannot resolve a configured file connector.",
                evidence,
                "Register each listed connector id, or remove the unavailable target from the tenant delivery profile.",
            )
        } else {
            DoctorCheckResult(
                NAME,
                DoctorStatus.HEALTHY,
                "All configured delivery targets resolve to a file connector.",
                evidence,
            )
        }
    }

    companion object {
        const val NAME = "delivery-profile"
    }
}
