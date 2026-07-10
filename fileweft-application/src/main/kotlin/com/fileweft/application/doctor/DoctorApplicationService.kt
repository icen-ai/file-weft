package com.fileweft.application.doctor

import com.fileweft.core.context.DoctorCheckContext
import com.fileweft.core.id.Identifier
import com.fileweft.core.result.DoctorCheckResult
import com.fileweft.core.result.DoctorReport
import com.fileweft.core.result.DoctorStatus
import com.fileweft.spi.doctor.DoctorChecker
import com.fileweft.spi.observability.FileWeftMetric
import com.fileweft.spi.observability.FileWeftMetrics
import com.fileweft.spi.tenant.TenantProvider
import java.time.Clock

/** Read-only orchestration for document diagnostics. */
class DoctorApplicationService(
    private val tenantProvider: TenantProvider,
    private val permissionChecker: PermissionDoctorChecker,
    checkers: List<DoctorChecker>,
    private val clock: Clock,
    private val metrics: FileWeftMetrics? = null,
) {
    private val checkers: List<DoctorChecker> = ArrayList(checkers)

    init {
        val names = listOf(permissionChecker.name()) + this.checkers.map { it.name() }
        require(names.distinct().size == names.size) { "Doctor checker names must be unique." }
    }

    fun inspectDocument(documentId: Identifier): DoctorReport {
        val tenant = tenantProvider.currentTenant()
        val context = DoctorCheckContext(tenant.tenantId, documentId)
        val permission = runChecker(permissionChecker, context)
        if (permission.status == DoctorStatus.ERROR) {
            return observe(DoctorReport(tenant.tenantId, documentId, listOf(permission), clock.millis()))
        }
        val results = ArrayList<DoctorCheckResult>(checkers.size + 1)
        results += permission
        checkers.forEach { results += runChecker(it, context) }
        return observe(DoctorReport(tenant.tenantId, documentId, results, clock.millis()))
    }

    private fun runChecker(checker: DoctorChecker, context: DoctorCheckContext): DoctorCheckResult {
        val name = checker.name()
        return try {
            val result = checker.check(context)
            if (result.checkerName == name) {
                result
            } else {
                DoctorCheckResult(
                    checkerName = name,
                    status = DoctorStatus.ERROR,
                    reason = "Doctor checker returned a result with a different checker name.",
                    evidence = mapOf("returnedCheckerName" to result.checkerName),
                    repairSuggestion = "Return a result whose checkerName matches the checker contract name.",
                )
            }
        } catch (failure: Throwable) {
            DoctorCheckResult(
                checkerName = name,
                status = DoctorStatus.ERROR,
                reason = "Doctor checker could not complete.",
                evidence = mapOf("exceptionType" to failure.javaClass.name),
                repairSuggestion = "Inspect the checker dependency and its logs, then run diagnosis again.",
            )
        }
    }

    private fun observe(report: DoctorReport): DoctorReport {
        if (report.status == DoctorStatus.ERROR) {
            try {
                metrics?.increment(FileWeftMetric.DOCTOR_FAILURE, mapOf("tenantId" to report.tenantId.value))
            } catch (_: Exception) {
                // Diagnostics must remain available when a metrics backend fails.
            }
        }
        return report
    }
}
