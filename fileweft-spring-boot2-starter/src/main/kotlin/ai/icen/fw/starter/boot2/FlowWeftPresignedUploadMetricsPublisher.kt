package ai.icen.fw.starter.boot2

import ai.icen.fw.application.upload.PresignedUploadDiagnosticsService
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import java.util.concurrent.atomic.AtomicLong

/** Worker-role, global, low-cardinality direct-upload gauges. */
class FlowWeftPresignedUploadMetricsPublisher(
    private val diagnostics: PresignedUploadDiagnosticsService,
    registry: MeterRegistry,
) {
    private val active = gauge(registry, "flowweft.presigned_upload.active")
    private val stuckClaims = gauge(registry, "flowweft.presigned_upload.stuck_claims")
    private val cleanupDue = gauge(registry, "flowweft.presigned_upload.cleanup_due")
    private val cleanupFailures = gauge(registry, "flowweft.presigned_upload.cleanup_failures")
    private val orphanRisk = gauge(registry, "flowweft.presigned_upload.orphan_risk")
    private val oldestMaintenanceAge = gauge(registry, "flowweft.presigned_upload.oldest_maintenance_age_seconds")
    private val observationFailure = gauge(registry, "flowweft.presigned_upload.observation_failure").apply {
        // An unobserved process must never look like a successful observation.
        set(1)
    }

    fun publish() {
        try {
            val snapshot = diagnostics.inspectGlobal()
            active.set(snapshot.activeCount)
            stuckClaims.set(snapshot.stuckClaimCount)
            cleanupDue.set(snapshot.cleanupDueCount)
            cleanupFailures.set(snapshot.cleanupFailureCount)
            orphanRisk.set(snapshot.orphanRiskCount)
            oldestMaintenanceAge.set(snapshot.oldestMaintenanceAgeSeconds)
            observationFailure.set(0)
        } catch (_: Exception) {
            observationFailure.set(1)
        }
    }

    private fun gauge(registry: MeterRegistry, name: String): AtomicLong = AtomicLong().also { value ->
        Gauge.builder(name, value) { current -> current.get().toDouble() }.register(registry)
    }
}
