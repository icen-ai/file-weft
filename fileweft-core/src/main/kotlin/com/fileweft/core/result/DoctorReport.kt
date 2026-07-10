package com.fileweft.core.result

import com.fileweft.core.id.Identifier
import java.util.Collections

/** Aggregated, immutable output of all diagnostic checks for a tenant scope. */
class DoctorReport @JvmOverloads constructor(
    val tenantId: Identifier,
    val documentId: Identifier? = null,
    checks: List<DoctorCheckResult>,
    val inspectedAt: Long,
) {
    val checks: List<DoctorCheckResult> = Collections.unmodifiableList(ArrayList(checks))

    val status: DoctorStatus = aggregateStatus(this.checks)

    init {
        require(inspectedAt >= 0) { "Doctor report inspection time must not be negative." }
        require(this.checks.map { it.checkerName }.distinct().size == this.checks.size) {
            "Doctor report checker names must be unique."
        }
    }

    private fun aggregateStatus(checks: List<DoctorCheckResult>): DoctorStatus = when {
        checks.any { it.status == DoctorStatus.ERROR } -> DoctorStatus.ERROR
        checks.any { it.status == DoctorStatus.WARNING } -> DoctorStatus.WARNING
        checks.any { it.status == DoctorStatus.HEALTHY } -> DoctorStatus.HEALTHY
        else -> DoctorStatus.SKIPPED
    }
}
