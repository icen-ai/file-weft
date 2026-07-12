package ai.icen.fw.application.doctor

import ai.icen.fw.core.context.DoctorCheckContext
import ai.icen.fw.core.result.DoctorCheckResult
import ai.icen.fw.core.result.DoctorStatus
import ai.icen.fw.spi.doctor.DoctorChecker

/** Diagnoses explicitly enabled single-node bootstrap adapters without exposing tenant or path values. */
class DeploymentSafetyDoctorChecker @JvmOverloads constructor(
    private val fixedTenantProviderActive: Boolean = false,
    private val localStorageAdapterActive: Boolean = false,
) : DoctorChecker {
    override fun name(): String = NAME

    override fun check(context: DoctorCheckContext): DoctorCheckResult {
        if (!fixedTenantProviderActive && !localStorageAdapterActive) {
            return DoctorCheckResult(
                checkerName = NAME,
                status = DoctorStatus.HEALTHY,
                reason = "No fixed-tenant or local-filesystem bootstrap adapter is active.",
            )
        }

        val activeModes = ArrayList<String>(2)
        if (fixedTenantProviderActive) activeModes += "fixed-tenant"
        if (localStorageAdapterActive) activeModes += "local-filesystem"
        return DoctorCheckResult(
            checkerName = NAME,
            status = DoctorStatus.WARNING,
            reason = "Explicit bootstrap adapters are active and require deployment review.",
            evidence = mapOf("activeModes" to activeModes.joinToString(",")),
            repairSuggestion = repairSuggestion(),
        )
    }

    private fun repairSuggestion(): String {
        val actions = ArrayList<String>(2)
        if (fixedTenantProviderActive) {
            actions += "register a trusted request-scoped TenantProvider for multi-tenant deployments"
        }
        if (localStorageAdapterActive) {
            actions += "register a durable shared StorageAdapter before running multiple application nodes"
        }
        return actions.joinToString(prefix = "For production, ", separator = "; ", postfix = ".")
    }

    companion object {
        const val NAME: String = "deployment-safety"
    }
}
