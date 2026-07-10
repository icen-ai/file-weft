package com.fileweft.application.doctor

import com.fileweft.core.context.DoctorCheckContext
import com.fileweft.core.result.DoctorCheckResult
import com.fileweft.core.result.DoctorStatus
import com.fileweft.spi.connector.ConnectorHealthStatus
import com.fileweft.spi.connector.FileConnector
import com.fileweft.spi.doctor.DoctorChecker

/** Aggregates the health of the configured external file connectors. */
class ConnectorDoctorChecker(
    connectors: List<FileConnector>,
) : DoctorChecker {
    private val connectors: List<FileConnector> = ArrayList(connectors)

    override fun name(): String = NAME

    override fun check(context: DoctorCheckContext): DoctorCheckResult {
        if (connectors.isEmpty()) {
            return DoctorCheckResult(
                NAME,
                DoctorStatus.SKIPPED,
                "No file connector is configured.",
                repairSuggestion = "Register a FileConnector when document publication requires an external system.",
            )
        }
        val evidence = linkedMapOf<String, String>()
        var hasDegraded = false
        var hasUnhealthy = false
        connectors.forEachIndexed { index, connector ->
            try {
                val health = connector.health()
                evidence["connector.$index.status"] = health.status.name
                health.message?.let { evidence["connector.$index.message"] = it }
                hasDegraded = hasDegraded || health.status == ConnectorHealthStatus.DEGRADED
                hasUnhealthy = hasUnhealthy || health.status == ConnectorHealthStatus.UNHEALTHY
            } catch (failure: Throwable) {
                evidence["connector.$index.status"] = "CHECK_FAILED"
                evidence["connector.$index.exceptionType"] = failure.javaClass.name
                hasUnhealthy = true
            }
        }
        return when {
            hasUnhealthy -> DoctorCheckResult(
                NAME,
                DoctorStatus.ERROR,
                "At least one configured file connector is unhealthy.",
                evidence,
                "Inspect connector credentials, endpoint reachability, and retry policy before publishing again.",
            )

            hasDegraded -> DoctorCheckResult(
                NAME,
                DoctorStatus.WARNING,
                "At least one configured file connector is degraded.",
                evidence,
                "Inspect connector health details before relying on publication or synchronization.",
            )

            else -> DoctorCheckResult(
                NAME,
                DoctorStatus.HEALTHY,
                "All configured file connectors are healthy.",
                evidence,
            )
        }
    }

    companion object {
        const val NAME = "connector"
    }
}
