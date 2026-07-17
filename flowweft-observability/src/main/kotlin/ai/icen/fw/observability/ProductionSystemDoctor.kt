package ai.icen.fw.observability

/**
 * Permission-first, deadline-bounded aggregation for production readiness and
 * operator diagnosis. It never calls a database, queue, provider or filesystem
 * directly; adapters expose aggregate-only probes through the registry.
 */
class ProductionSystemDoctor @JvmOverloads constructor(
    private val authorization: SystemDoctorAuthorizationPort,
    private val topology: SystemDoctorTopology,
    private val probes: SystemDoctorProbeRegistry,
    private val execution: SystemDoctorProbeExecutionPort,
    private val observationSink: SystemDoctorObservationSink = SystemDoctorObservationSink.NOOP,
    private val clock: SystemDoctorClock = SystemDoctorClock.SYSTEM,
) {
    fun inspectTenant(request: SystemDoctorRequest): SystemDoctorReport {
        require(request.scope == SystemDoctorScope.TENANT) {
            "Tenant System Doctor entry requires a tenant-scoped request."
        }
        return inspect(request)
    }

    fun inspectSystem(request: SystemDoctorRequest): SystemDoctorReport {
        require(request.scope == SystemDoctorScope.SYSTEM) {
            "System System Doctor entry requires a system-scoped request."
        }
        return inspect(request)
    }

    private fun inspect(request: SystemDoctorRequest): SystemDoctorReport {
        val findings = SystemDoctorFindingAccumulator()
        val startedAt = clock.currentTimeMillis()
        if (startedAt < request.requestedAt || startedAt >= request.deadlineAt) {
            findings.addAuthorization(
                SystemDoctorSeverity.ERROR,
                SystemDoctorCode.REQUEST_EXPIRED,
                SystemDoctorBucket.STALE,
                SystemDoctorRepairAction.RETRY_DIAGNOSTIC,
            )
            return finish(request, findings, 0)
        }

        val decision = try {
            authorization.authorize(request)
        } catch (_: Exception) {
            findings.addAuthorization(
                SystemDoctorSeverity.ERROR,
                SystemDoctorCode.AUTHORIZATION_UNAVAILABLE,
                SystemDoctorBucket.UNAVAILABLE,
                SystemDoctorRepairAction.RETRY_DIAGNOSTIC,
            )
            return finish(request, findings, 0)
        }
        val authorizedAt = clock.currentTimeMillis()
        when {
            !decision.matches(request) -> {
                findings.addAuthorization(
                    SystemDoctorSeverity.ERROR,
                    SystemDoctorCode.AUTHORIZATION_MISMATCH,
                    SystemDoctorBucket.DRIFTED,
                    SystemDoctorRepairAction.REFRESH_AUTHORIZATION,
                )
                return finish(request, findings, 0)
            }
            !decision.allowed -> {
                findings.addAuthorization(
                    SystemDoctorSeverity.ERROR,
                    SystemDoctorCode.AUTHORIZATION_DENIED,
                    SystemDoctorBucket.UNAVAILABLE,
                    SystemDoctorRepairAction.GRANT_DIAGNOSTIC_PERMISSION,
                )
                return finish(request, findings, 0)
            }
            !decision.isCurrent(authorizedAt) -> {
                findings.addAuthorization(
                    SystemDoctorSeverity.ERROR,
                    SystemDoctorCode.AUTHORIZATION_EXPIRED,
                    SystemDoctorBucket.STALE,
                    SystemDoctorRepairAction.REFRESH_AUTHORIZATION,
                )
                return finish(request, findings, 0)
            }
        }

        var healthyRequired = 0
        for (requirement in topology.requirements) {
            val now = clock.currentTimeMillis()
            if (now >= request.deadlineAt) {
                findings.addCapability(
                    requirement,
                    severityFor(requirement, SystemDoctorSeverity.ERROR),
                    SystemDoctorCode.DIAGNOSTIC_DEADLINE_EXCEEDED,
                    1L,
                    SystemDoctorBucket.TIMED_OUT,
                    SystemDoctorRepairAction.RETRY_DIAGNOSTIC,
                )
                break
            }
            if (!decision.isCurrent(now)) {
                findings.addAuthorization(
                    SystemDoctorSeverity.ERROR,
                    SystemDoctorCode.AUTHORIZATION_EXPIRED,
                    SystemDoctorBucket.STALE,
                    SystemDoctorRepairAction.REFRESH_AUTHORIZATION,
                )
                break
            }
            if (inspectRequirement(request, requirement, decision.expiresAt, findings)) {
                if (requirement.required) healthyRequired++
            }
        }
        return finish(request, findings, healthyRequired)
    }

    private fun inspectRequirement(
        request: SystemDoctorRequest,
        requirement: SystemDoctorProbeRequirement,
        authorizationExpiresAt: Long,
        findings: SystemDoctorFindingAccumulator,
    ): Boolean {
        val probe = try {
            probes.find(requirement.capability, requirement.probeId)
        } catch (_: Exception) {
            findings.addCapability(
                requirement,
                severityFor(requirement, SystemDoctorSeverity.ERROR),
                SystemDoctorCode.PROBE_FAILED,
                1L,
                SystemDoctorBucket.UNAVAILABLE,
                SystemDoctorRepairAction.CONFIGURE_PROBE,
            )
            return false
        }
        if (probe == null) {
            findings.addCapability(
                requirement,
                SystemDoctorSeverity.UNSUPPORTED,
                SystemDoctorCode.PROBE_MISSING,
                1L,
                SystemDoctorBucket.MISSING,
                SystemDoctorRepairAction.CONFIGURE_PROBE,
            )
            return false
        }

        val issuedAt = clock.currentTimeMillis()
        val probeDeadline = minOf(
            request.deadlineAt,
            authorizationExpiresAt,
            safeDeadline(issuedAt, requirement.timeoutMillis),
        )
        if (probeDeadline <= issuedAt) {
            findings.addCapability(
                requirement,
                severityFor(requirement, SystemDoctorSeverity.ERROR),
                SystemDoctorCode.PROBE_TIMED_OUT,
                1L,
                SystemDoctorBucket.TIMED_OUT,
                SystemDoctorRepairAction.RETRY_DIAGNOSTIC,
            )
            return false
        }
        val probeRequest = SystemDoctorProbeRequest.from(request, requirement, issuedAt, probeDeadline)
        val outcome = try {
            execution.execute(probe, probeRequest, probeDeadline - issuedAt)
        } catch (_: Exception) {
            SystemDoctorProbeExecution.failed()
        }
        return when (outcome.state) {
            SystemDoctorProbeExecutionState.TIMED_OUT -> {
                findings.addCapability(
                    requirement,
                    severityFor(requirement, SystemDoctorSeverity.ERROR),
                    SystemDoctorCode.PROBE_TIMED_OUT,
                    1L,
                    SystemDoctorBucket.TIMED_OUT,
                    SystemDoctorRepairAction.RETRY_DIAGNOSTIC,
                )
                false
            }
            SystemDoctorProbeExecutionState.FAILED -> {
                findings.addCapability(
                    requirement,
                    severityFor(requirement, SystemDoctorSeverity.ERROR),
                    SystemDoctorCode.PROBE_FAILED,
                    1L,
                    SystemDoctorBucket.UNAVAILABLE,
                    SystemDoctorRepairAction.RETRY_DIAGNOSTIC,
                )
                false
            }
            SystemDoctorProbeExecutionState.COMPLETED -> inspectResult(
                requirement,
                probeRequest,
                requireNotNull(outcome.result),
                findings,
            )
        }
    }

    private fun inspectResult(
        requirement: SystemDoctorProbeRequirement,
        request: SystemDoctorProbeRequest,
        result: SystemDoctorProbeResult,
        findings: SystemDoctorFindingAccumulator,
    ): Boolean {
        val observedNow = clock.currentTimeMillis()
        if (observedNow >= request.deadlineAt) {
            findings.addCapability(
                requirement,
                severityFor(requirement, SystemDoctorSeverity.ERROR),
                SystemDoctorCode.PROBE_TIMED_OUT,
                1L,
                SystemDoctorBucket.TIMED_OUT,
                SystemDoctorRepairAction.RETRY_DIAGNOSTIC,
            )
            return false
        }
        if (result.probeBindingDigest != request.probeBindingDigest || result.capability != requirement.capability ||
            result.observedAt > observedNow || result.observedAt >= request.deadlineAt
        ) {
            findings.addCapability(
                requirement,
                severityFor(requirement, SystemDoctorSeverity.ERROR),
                SystemDoctorCode.PROBE_BINDING_MISMATCH,
                1L,
                SystemDoctorBucket.DRIFTED,
                SystemDoctorRepairAction.CONFIGURE_PROBE,
            )
            return false
        }
        if (result.contractVersion != requirement.contractVersion) {
            findings.addCapability(
                requirement,
                severityFor(requirement, SystemDoctorSeverity.ERROR),
                SystemDoctorCode.PROBE_VERSION_DRIFT,
                1L,
                SystemDoctorBucket.DRIFTED,
                SystemDoctorRepairAction.ALIGN_PROBE_VERSION,
            )
            return false
        }
        if (result.configurationDigest != requirement.configurationDigest) {
            findings.addCapability(
                requirement,
                severityFor(requirement, SystemDoctorSeverity.ERROR),
                SystemDoctorCode.PROBE_CONFIGURATION_DRIFT,
                1L,
                SystemDoctorBucket.DRIFTED,
                SystemDoctorRepairAction.ALIGN_PROBE_CONFIGURATION,
            )
            return false
        }
        if (observedNow < result.observedAt || observedNow - result.observedAt > requirement.maximumSnapshotAgeMillis) {
            findings.addCapability(
                requirement,
                severityFor(requirement, SystemDoctorSeverity.ERROR),
                SystemDoctorCode.PROBE_SNAPSHOT_STALE,
                1L,
                SystemDoctorBucket.STALE,
                SystemDoctorRepairAction.RETRY_DIAGNOSTIC,
            )
            return false
        }

        val healthyState = when (result.state) {
            SystemDoctorProbeState.HEALTHY -> {
                findings.addCapability(
                    requirement,
                    SystemDoctorSeverity.HEALTHY,
                    SystemDoctorCode.PROBE_HEALTHY,
                    1L,
                    SystemDoctorBucket.AVAILABLE,
                    SystemDoctorRepairAction.NONE,
                )
                true
            }
            SystemDoctorProbeState.DEGRADED -> {
                findings.addCapability(
                    requirement,
                    severityFor(requirement, SystemDoctorSeverity.WARNING),
                    SystemDoctorCode.PROBE_DEGRADED,
                    1L,
                    SystemDoctorBucket.PARTIAL,
                    SystemDoctorRepairAction.RESTORE_DEPENDENCY,
                )
                false
            }
            SystemDoctorProbeState.UNAVAILABLE -> {
                findings.addCapability(
                    requirement,
                    severityFor(requirement, SystemDoctorSeverity.ERROR),
                    SystemDoctorCode.PROBE_UNAVAILABLE,
                    1L,
                    SystemDoctorBucket.UNAVAILABLE,
                    SystemDoctorRepairAction.RESTORE_DEPENDENCY,
                )
                false
            }
            SystemDoctorProbeState.UNSUPPORTED -> {
                findings.addCapability(
                    requirement,
                    SystemDoctorSeverity.UNSUPPORTED,
                    SystemDoctorCode.PROBE_UNSUPPORTED,
                    1L,
                    SystemDoctorBucket.UNSUPPORTED,
                    SystemDoctorRepairAction.CONFIGURE_PROBE,
                )
                false
            }
        }
        var healthySignals = true
        result.signals.forEach { signal ->
            val severity = severityFor(requirement, signal.severity)
            if (severity != SystemDoctorSeverity.HEALTHY) healthySignals = false
            findings.addCapability(
                requirement,
                severity,
                signal.code,
                signal.count,
                signal.bucket,
                signal.repairAction,
            )
        }
        if (result.truncated) {
            healthySignals = false
            findings.addCapability(
                requirement,
                severityFor(requirement, SystemDoctorSeverity.WARNING),
                SystemDoctorCode.PROBE_RESULT_TRUNCATED,
                1L,
                SystemDoctorBucket.TRUNCATED,
                SystemDoctorRepairAction.CONFIGURE_PROBE,
            )
        }
        return healthyState && healthySignals
    }

    private fun finish(
        request: SystemDoctorRequest,
        findings: SystemDoctorFindingAccumulator,
        healthyRequired: Int,
    ): SystemDoctorReport {
        val report = SystemDoctorReport(
            request.scope,
            findings.snapshot(),
            if (healthyRequired == topology.requiredProbeCount) {
                SystemDoctorReadiness.READY
            } else {
                SystemDoctorReadiness.NOT_READY
            },
            topology.requiredProbeCount,
            healthyRequired,
            clock.currentTimeMillis(),
        )
        report.findings.forEach { finding ->
            try {
                observationSink.observe(finding)
            } catch (_: Exception) {
                // Observation cannot alter readiness or leak adapter failures.
            }
        }
        return report
    }

    private fun severityFor(
        requirement: SystemDoctorProbeRequirement,
        severity: SystemDoctorSeverity,
    ): SystemDoctorSeverity = if (!requirement.required && severity == SystemDoctorSeverity.ERROR) {
        SystemDoctorSeverity.WARNING
    } else {
        severity
    }

    private fun safeDeadline(now: Long, timeoutMillis: Long): Long =
        if (Long.MAX_VALUE - now < timeoutMillis) Long.MAX_VALUE else now + timeoutMillis
}

private data class SystemDoctorFindingKey(
    val area: SystemDoctorFindingArea,
    val capability: SystemDoctorCapability?,
    val severity: SystemDoctorSeverity,
    val code: SystemDoctorCode,
    val bucket: SystemDoctorBucket,
    val repairAction: SystemDoctorRepairAction,
    val required: Boolean,
)

private class SystemDoctorFindingAccumulator {
    private val counts = LinkedHashMap<SystemDoctorFindingKey, Long>()

    fun addAuthorization(
        severity: SystemDoctorSeverity,
        code: SystemDoctorCode,
        bucket: SystemDoctorBucket,
        repairAction: SystemDoctorRepairAction,
    ) {
        add(
            SystemDoctorFindingKey(
                SystemDoctorFindingArea.AUTHORIZATION,
                null,
                severity,
                code,
                bucket,
                repairAction,
                false,
            ),
            1L,
        )
    }

    fun addCapability(
        requirement: SystemDoctorProbeRequirement,
        severity: SystemDoctorSeverity,
        code: SystemDoctorCode,
        count: Long,
        bucket: SystemDoctorBucket,
        repairAction: SystemDoctorRepairAction,
    ) {
        add(
            SystemDoctorFindingKey(
                SystemDoctorFindingArea.CAPABILITY,
                requirement.capability,
                severity,
                code,
                bucket,
                repairAction,
                requirement.required,
            ),
            count,
        )
    }

    private fun add(key: SystemDoctorFindingKey, count: Long) {
        require(count in 0L..SystemDoctorLimits.MAX_COUNT) {
            "System Doctor aggregate count is invalid."
        }
        val current = counts[key] ?: 0L
        counts[key] = minOf(SystemDoctorLimits.MAX_COUNT, current + count)
    }

    fun snapshot(): List<SystemDoctorFinding> = counts.map { (key, count) ->
        SystemDoctorFinding(
            key.area,
            key.capability,
            key.severity,
            key.code,
            count,
            key.bucket,
            key.repairAction,
            key.required,
        )
    }
}
