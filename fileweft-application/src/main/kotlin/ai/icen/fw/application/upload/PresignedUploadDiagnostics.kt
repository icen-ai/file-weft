package ai.icen.fw.application.upload

import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.core.context.DoctorCheckContext
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.core.result.DoctorCheckResult
import ai.icen.fw.core.result.DoctorStatus
import ai.icen.fw.spi.doctor.DoctorChecker
import java.time.Clock

/** Low-cardinality durable state needed by Doctor and worker-role metrics. */
class PresignedUploadDiagnosticsSnapshot(
    val activeCount: Long,
    val stuckClaimCount: Long,
    val cleanupDueCount: Long,
    val cleanupFailureCount: Long,
    val orphanRiskCount: Long,
    val oldestMaintenanceAgeSeconds: Long,
) {
    init {
        require(
            activeCount >= 0 &&
                stuckClaimCount >= 0 &&
                cleanupDueCount >= 0 &&
                cleanupFailureCount >= 0 &&
                orphanRiskCount >= 0 &&
                oldestMaintenanceAgeSeconds >= 0,
        ) { "Presigned upload diagnostic values must not be negative." }
    }
}

/** Null tenant means an operator-only global worker observation. */
interface PresignedUploadDiagnosticsRepository {
    fun snapshot(tenantId: Identifier?, now: Long): PresignedUploadDiagnosticsSnapshot
}

class PresignedUploadDiagnosticsService(
    private val repository: PresignedUploadDiagnosticsRepository,
    private val transaction: ApplicationTransaction,
    private val clock: Clock,
) {
    fun inspectTenant(tenantId: Identifier): PresignedUploadDiagnosticsSnapshot =
        transaction.execute { repository.snapshot(tenantId, clock.millis()) }

    fun inspectGlobal(): PresignedUploadDiagnosticsSnapshot =
        transaction.execute { repository.snapshot(null, clock.millis()) }
}

/** Tenant-scoped, side-effect-free readiness and orphan-risk diagnosis. */
class PresignedUploadDoctorChecker(
    private val diagnostics: PresignedUploadDiagnosticsService,
) : DoctorChecker {
    override fun name(): String = NAME

    override fun check(context: DoctorCheckContext): DoctorCheckResult = try {
        val snapshot = diagnostics.inspectTenant(context.tenantId)
        val unhealthy = snapshot.cleanupFailureCount > 0 || snapshot.orphanRiskCount > 0
        val delayed = snapshot.stuckClaimCount > 0 || snapshot.cleanupDueCount > 0
        DoctorCheckResult(
            checkerName = NAME,
            status = if (unhealthy || delayed) DoctorStatus.WARNING else DoctorStatus.HEALTHY,
            reason = when {
                snapshot.orphanRiskCount > 0 ->
                    "Completed direct uploads are waiting for an atomic FileObject/FileAsset claim capability."
                snapshot.cleanupFailureCount > 0 -> "Direct-upload staging cleanup has retryable failures."
                delayed -> "Direct-upload worker maintenance has durable work waiting."
                else -> "Direct-upload persistence and maintenance state are healthy."
            },
            evidence = mapOf(
                "activeCount" to snapshot.activeCount.toString(),
                "stuckClaimCount" to snapshot.stuckClaimCount.toString(),
                "cleanupDueCount" to snapshot.cleanupDueCount.toString(),
                "cleanupFailureCount" to snapshot.cleanupFailureCount.toString(),
                "orphanRiskCount" to snapshot.orphanRiskCount.toString(),
                "oldestMaintenanceAgeSeconds" to snapshot.oldestMaintenanceAgeSeconds.toString(),
            ),
            repairSuggestion = if (unhealthy || delayed) {
                "Run the explicit worker role, inspect sanitized server logs, and keep public finalization disabled " +
                    "until atomic asset claiming is configured."
            } else {
                null
            },
        )
    } catch (_: Exception) {
        DoctorCheckResult(
            checkerName = NAME,
            status = DoctorStatus.ERROR,
            reason = "Direct-upload migration history or persistence diagnostics are unavailable.",
            repairSuggestion = "Validate FlowWeft database migrations V034/V035 and the configured JDBC transaction boundary.",
        )
    }

    companion object {
        const val NAME: String = "presigned-upload"
    }
}
