package ai.icen.fw.adapter.dify

import ai.icen.fw.core.context.DoctorCheckContext
import ai.icen.fw.core.result.DoctorCheckResult
import ai.icen.fw.core.result.DoctorStatus
import ai.icen.fw.spi.connector.ConnectorHealthStatus
import ai.icen.fw.spi.doctor.DoctorChecker

/** Safe, fixed-vocabulary diagnostics for the maintained Dify reference integration. */
class DifyKnowledgeBaseDoctorChecker(
    private val connector: DifyKnowledgeBaseConnector,
    private val profile: DifyKnowledgeBaseProfile,
) : DoctorChecker {
    override fun name(): String = NAME

    override fun check(context: DoctorCheckContext): DoctorCheckResult {
        val evidence = linkedMapOf(
            "apiCompatibility" to profile.compatibility.supportedVersionRange,
            "createByFile" to "true",
            "canonicalFileUpdate" to "true",
            "statusReadback" to "true",
            "tenantIsolation" to "dedicated-dataset",
            "projectionStore" to "durable-required",
            "ambiguousWrite" to "fenced-unknown",
            "apiRedirects" to "disabled",
            "transportRetries" to "disabled",
            "privateApiAddresses" to if (profile.allowPrivateApiAddresses) "administrator-opt-in" else "blocked",
            "projectionMetadataWrite" to "unsupported-by-file-api",
            "verifiablePurge" to "false",
            "safeRetrieval" to "false",
        )
        if (context.tenantId != profile.dedicatedTenantId) {
            return DoctorCheckResult(
                NAME,
                DoctorStatus.ERROR,
                "Dify connector profile is not dedicated to this tenant.",
                evidence,
                "Select the tenant-owned Dify connector profile; shared datasets are unsupported.",
            )
        }
        val health = try {
            connector.health()
        } catch (_: Exception) {
            return DoctorCheckResult(
                NAME,
                DoctorStatus.ERROR,
                "Dify connector health check failed.",
                evidence,
                "Verify the server-side profile, secret reference, projection store, and Service API reachability.",
            )
        }
        return when (health.status) {
            ConnectorHealthStatus.HEALTHY -> DoctorCheckResult(
                NAME,
                DoctorStatus.HEALTHY,
                "Dify knowledge-base projection is healthy.",
                evidence,
            )
            ConnectorHealthStatus.DEGRADED -> DoctorCheckResult(
                NAME,
                DoctorStatus.WARNING,
                "Dify knowledge-base projection is degraded.",
                evidence,
                "Inspect the projection store and Dify Service API without exposing endpoint or credential data.",
            )
            ConnectorHealthStatus.UNHEALTHY -> DoctorCheckResult(
                NAME,
                DoctorStatus.ERROR,
                "Dify knowledge-base projection is unhealthy.",
                evidence,
                "Verify the server-side profile, secret reference, dataset API access, and projection store.",
            )
        }
    }

    companion object {
        const val NAME: String = "dify"
    }
}
