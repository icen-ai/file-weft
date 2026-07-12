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
    private val permissionCheckerName: String = permissionChecker.name()
    private val checkers: List<RegisteredDoctorChecker> = checkers.map { checker ->
        RegisteredDoctorChecker(checker.name(), checker)
    }

    init {
        val names = listOf(permissionCheckerName) + this.checkers.map { it.name }
        require(this.checkers.size <= MAX_CHECKERS) { "Doctor checker count exceeds the bounded execution contract." }
        names.forEach(::requireSafeCheckerName)
        require(names.distinct().size == names.size) { "Doctor checker names must be unique." }
    }

    fun inspectDocument(documentId: Identifier): DoctorReport {
        val tenant = tenantProvider.currentTenant()
        val context = DoctorCheckContext(tenant.tenantId, documentId)
        val permission = runChecker(permissionCheckerName, permissionChecker, context)
        if (permission.status == DoctorStatus.ERROR) {
            return observe(DoctorReport(tenant.tenantId, documentId, listOf(permission), clock.millis()))
        }
        val results = ArrayList<DoctorCheckResult>(checkers.size + 1)
        results += permission
        checkers.forEach { registered -> results += runChecker(registered.name, registered.checker, context) }
        return observe(DoctorReport(tenant.tenantId, documentId, results, clock.millis()))
    }

    /**
     * Background tasks run without an authenticated user. Authorization is
     * performed before queuing; this path executes only the read-only technical
     * checks and deliberately excludes the interactive permission checker.
     */
    fun inspectDocumentAsSystem(tenantId: Identifier, documentId: Identifier): DoctorReport {
        val context = DoctorCheckContext(tenantId, documentId)
        val results = checkers.map { registered ->
            runChecker(registered.name, registered.checker, context)
        }
        return observe(DoctorReport(tenantId, documentId, results, clock.millis()))
    }

    /**
     * Entry used only after a formal application boundary has authenticated,
     * authorized and checked current catalog visibility.
     */
    @JvmSynthetic
    internal fun inspectAuthorizedDocument(tenantId: Identifier, documentId: Identifier): DoctorReport {
        val context = DoctorCheckContext(tenantId, documentId)
        val results = ArrayList<DoctorCheckResult>(checkers.size + 1)
        results += DoctorCheckResult(
            checkerName = PermissionDoctorChecker.NAME,
            status = DoctorStatus.HEALTHY,
            reason = "The current request passed the document diagnosis authorization boundary.",
        )
        checkers.forEach { registered ->
            results += runChecker(registered.name, registered.checker, context)
        }
        return observe(DoctorReport(tenantId, documentId, results, clock.millis()))
    }

    /** Tenant-scoped technical diagnosis used after explicit system authorization. */
    @JvmSynthetic
    internal fun inspectAuthorizedSystem(tenantId: Identifier): DoctorReport {
        val context = DoctorCheckContext(tenantId)
        val results = checkers.map { registered ->
            runChecker(registered.name, registered.checker, context)
        }
        return observe(DoctorReport(tenantId, checks = results, inspectedAt = clock.millis()))
    }

    private fun runChecker(
        name: String,
        checker: DoctorChecker,
        context: DoctorCheckContext,
    ): DoctorCheckResult {
        return try {
            val result = checker.check(context)
            if (result.checkerName == name) {
                normalize(result)
            } else {
                DoctorCheckResult(
                    checkerName = name,
                    status = DoctorStatus.ERROR,
                    reason = "Doctor checker returned a result with a different checker name.",
                    evidence = mapOf(
                        "returnedCheckerName" to safeText(
                            result.checkerName,
                            MAX_CHECKER_NAME_LENGTH,
                            "unavailable",
                        ),
                    ),
                    repairSuggestion = "Return a result whose checkerName matches the checker contract name.",
                )
            }
        } catch (failure: Exception) {
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
                metrics?.increment(FileWeftMetric.DOCTOR_FAILURE)
            } catch (_: Exception) {
                // Diagnostics must remain available when a metrics backend fails.
            }
        }
        return report
    }

    private fun normalize(result: DoctorCheckResult): DoctorCheckResult = DoctorCheckResult(
        checkerName = result.checkerName,
        status = result.status,
        reason = safeText(result.reason, MAX_REASON_LENGTH, "Doctor checker returned no safe diagnostic reason."),
        evidence = result.evidence.entries.asSequence()
            .filterNot { (key, _) -> isSensitiveEvidenceKey(key) }
            .take(MAX_EVIDENCE_ENTRIES)
            .associate { (key, value) ->
                safeText(key, MAX_EVIDENCE_KEY_LENGTH, "evidence") to
                    safeText(value, MAX_EVIDENCE_VALUE_LENGTH, "unavailable")
            },
        repairSuggestion = result.repairSuggestion?.let { suggestion ->
            safeText(suggestion, MAX_REPAIR_LENGTH, "Inspect the component logs and run diagnosis again.")
        },
    )

    private fun safeText(value: String, maximumLength: Int, fallback: String): String {
        val safe = value.asSequence()
            .filterNot { character ->
                Character.isISOControl(character) || Character.getType(character) == Character.FORMAT.toInt()
            }
            .joinToString("")
            .trim()
            .take(maximumLength)
        return safe.ifBlank { fallback }
    }

    private fun isSensitiveEvidenceKey(key: String): Boolean {
        val normalized = key.lowercase().filter(Char::isLetterOrDigit)
        return SENSITIVE_EVIDENCE_MARKERS.any(normalized::contains)
    }

    private fun requireSafeCheckerName(name: String) {
        require(name.isNotBlank() && name.length <= MAX_CHECKER_NAME_LENGTH) {
            "Doctor checker name must be non-blank and bounded."
        }
        require(name.none { character ->
            Character.isISOControl(character) || Character.getType(character) == Character.FORMAT.toInt()
        }) { "Doctor checker name must not contain unsafe characters." }
    }

    private companion object {
        const val MAX_CHECKERS = 64
        const val MAX_CHECKER_NAME_LENGTH = 128
        const val MAX_REASON_LENGTH = 2_048
        const val MAX_REPAIR_LENGTH = 4_096
        const val MAX_EVIDENCE_ENTRIES = 32
        const val MAX_EVIDENCE_KEY_LENGTH = 128
        const val MAX_EVIDENCE_VALUE_LENGTH = 1_024
        val SENSITIVE_EVIDENCE_MARKERS = setOf(
            "password", "secret", "token", "credential", "authorization", "accesskey", "privatekey",
        )
    }

    private class RegisteredDoctorChecker(
        val name: String,
        val checker: DoctorChecker,
    )
}
