package ai.icen.fw.agent.observability

/**
 * Provider-neutral production diagnostic coordinator.
 *
 * Authorization is evaluated before inventory or persistence probes. Probe failures are converted
 * to stable codes; exception types and messages are deliberately discarded.
 */
class ProductionAgentDoctor @JvmOverloads constructor(
    private val authorization: AgentDoctorAuthorizationPort,
    private val providerTopology: AgentProviderTopologyPort,
    private val providerProbes: AgentProviderDiagnosticProbeRegistry,
    private val durableProbes: AgentDurableDiagnosticProbeRegistry,
    private val policy: AgentDoctorPolicy = AgentDoctorPolicy(),
    private val observationSink: AgentDoctorObservationSink = AgentDoctorObservationSink.NOOP,
    private val clock: AgentDoctorClock = AgentDoctorClock.SYSTEM,
) {
    fun diagnose(request: AgentDoctorRequest): AgentDoctorReport {
        val findings = FindingAccumulator()
        val startedAt = clock.currentTimeMillis()
        if (startedAt < request.requestedAt || startedAt >= request.deadlineAt) {
            findings.add(
                AgentDoctorCategory.AUTHORIZATION,
                AgentDoctorStatus.ERROR,
                AgentDoctorCode.REQUEST_EXPIRED,
                1L,
                AgentDoctorBucket.STALE,
                AgentDoctorRepairAction.RETRY_DIAGNOSTIC,
            )
            return finish(findings)
        }

        val grant = try {
            authorization.authorize(request)
        } catch (_: Exception) {
            findings.add(
                AgentDoctorCategory.AUTHORIZATION,
                AgentDoctorStatus.ERROR,
                AgentDoctorCode.AUTHORIZATION_UNAVAILABLE,
                1L,
                AgentDoctorBucket.UNAVAILABLE,
                AgentDoctorRepairAction.RETRY_DIAGNOSTIC,
            )
            return finish(findings)
        }
        if (!grant.allowed) {
            findings.add(
                AgentDoctorCategory.AUTHORIZATION,
                AgentDoctorStatus.ERROR,
                AgentDoctorCode.AUTHORIZATION_DENIED,
                1L,
                AgentDoctorBucket.UNAVAILABLE,
                AgentDoctorRepairAction.GRANT_DIAGNOSTIC_PERMISSION,
            )
            return finish(findings)
        }
        if (!grant.matches(request)) {
            findings.add(
                AgentDoctorCategory.AUTHORIZATION,
                AgentDoctorStatus.ERROR,
                AgentDoctorCode.AUTHORIZATION_MISMATCH,
                1L,
                AgentDoctorBucket.DRIFTED,
                AgentDoctorRepairAction.REFRESH_AUTHORIZATION,
            )
            return finish(findings)
        }
        if (!grant.isCurrent(startedAt) || grant.expiresAt < request.deadlineAt) {
            findings.add(
                AgentDoctorCategory.AUTHORIZATION,
                AgentDoctorStatus.ERROR,
                AgentDoctorCode.AUTHORIZATION_EXPIRED,
                1L,
                AgentDoctorBucket.STALE,
                AgentDoctorRepairAction.REFRESH_AUTHORIZATION,
            )
            return finish(findings)
        }

        val probeRequest = AgentDoctorProbeRequest.from(request)
        inspectProviders(probeRequest, grant, findings)
        if (!deadlineExceeded(probeRequest, findings)) {
            AgentDurableWorkloadKind.values().forEach { workload ->
                if (!deadlineExceeded(probeRequest, findings)) {
                    inspectDurable(workload, probeRequest, findings)
                }
            }
        }
        return finish(findings)
    }

    private fun inspectProviders(
        request: AgentDoctorProbeRequest,
        grant: AgentDoctorAuthorization,
        findings: FindingAccumulator,
    ) {
        val topology = try {
            providerTopology.inspect(request)
        } catch (_: Exception) {
            findings.add(
                AgentDoctorCategory.PROVIDER,
                AgentDoctorStatus.ERROR,
                AgentDoctorCode.PROVIDER_INVENTORY_UNAVAILABLE,
                1L,
                AgentDoctorBucket.UNAVAILABLE,
                AgentDoctorRepairAction.CONFIGURE_PROVIDER_INVENTORY,
            )
            return
        }
        if (deadlineExceeded(request, findings)) return
        if (topology.requestBindingDigest != request.requestBindingDigest) {
            findings.add(
                AgentDoctorCategory.PROVIDER,
                AgentDoctorStatus.ERROR,
                AgentDoctorCode.PROVIDER_PROBE_MISMATCH,
                1L,
                AgentDoctorBucket.DRIFTED,
                AgentDoctorRepairAction.CONFIGURE_PROVIDER_INVENTORY,
            )
            return
        }

        AgentProviderKind.values().forEach { kind ->
            if (kind !in topology.coveredKinds) {
                findings.add(
                    AgentDoctorCategory.PROVIDER,
                    AgentDoctorStatus.UNSUPPORTED,
                    AgentDoctorCode.PROVIDER_INVENTORY_UNSUPPORTED,
                    1L,
                    AgentDoctorBucket.MISSING,
                    AgentDoctorRepairAction.CONFIGURE_PROVIDER_INVENTORY,
                    kind,
                )
            } else {
                val configured = topology.expectations.count { expectation -> expectation.providerKind == kind }.toLong()
                findings.add(
                    AgentDoctorCategory.PROVIDER,
                    AgentDoctorStatus.HEALTHY,
                    AgentDoctorCode.PROVIDER_INVENTORY_CHECKED,
                    configured,
                    if (configured == 0L) AgentDoctorBucket.NOT_CONFIGURED else AgentDoctorBucket.COMPLETE,
                    AgentDoctorRepairAction.NONE,
                    kind,
                )
            }
        }

        val allowedCount = minOf(policy.maximumProviderProbes, grant.maximumProviderProbes)
        if (topology.expectations.size > allowedCount) {
            findings.add(
                AgentDoctorCategory.PROVIDER,
                AgentDoctorStatus.ERROR,
                AgentDoctorCode.PROVIDER_INVENTORY_TRUNCATED,
                (topology.expectations.size - allowedCount).toLong(),
                AgentDoctorBucket.TRUNCATED,
                AgentDoctorRepairAction.RETRY_DIAGNOSTIC,
            )
        }
        topology.expectations.take(allowedCount).forEach { expectation ->
            if (expectation.providerKind in topology.coveredKinds && !deadlineExceeded(request, findings)) {
                inspectProvider(expectation, request, findings)
            }
        }
    }

    private fun inspectProvider(
        expectation: AgentProviderDiagnosticExpectation,
        request: AgentDoctorProbeRequest,
        findings: FindingAccumulator,
    ) {
        val probe = try {
            providerProbes.find(expectation.providerKind, expectation.providerId)
        } catch (_: Exception) {
            findings.addProviderFailure(expectation, AgentDoctorCode.PROVIDER_PROBE_FAILED, AgentDoctorBucket.UNAVAILABLE)
            return
        }
        if (probe == null) {
            findings.addProviderFailure(expectation, AgentDoctorCode.PROVIDER_PROBE_MISSING, AgentDoctorBucket.MISSING)
            return
        }
        val result = try {
            probe.inspect(request)
        } catch (_: Exception) {
            findings.addProviderFailure(expectation, AgentDoctorCode.PROVIDER_PROBE_FAILED, AgentDoctorBucket.UNAVAILABLE)
            return
        }
        if (deadlineExceeded(request, findings)) return
        if (result.requestBindingDigest != request.requestBindingDigest ||
            result.providerKind != expectation.providerKind || result.providerId != expectation.providerId ||
            result.observedAt !in request.requestedAt..request.deadlineAt
        ) {
            findings.addProviderFailure(expectation, AgentDoctorCode.PROVIDER_PROBE_MISMATCH, AgentDoctorBucket.DRIFTED)
            return
        }
        when (result.state) {
            AgentProviderProbeState.UNSUPPORTED -> {
                findings.addProviderFailure(
                    expectation,
                    AgentDoctorCode.PROVIDER_PROBE_UNSUPPORTED,
                    AgentDoctorBucket.MISSING,
                )
                return
            }
            AgentProviderProbeState.UNAVAILABLE -> {
                findings.addProviderFailure(
                    expectation,
                    AgentDoctorCode.PROVIDER_PROBE_UNAVAILABLE,
                    AgentDoctorBucket.UNAVAILABLE,
                )
                return
            }
            AgentProviderProbeState.AVAILABLE -> Unit
        }

        var drifted = false
        if (result.descriptorDigest != expectation.descriptorDigest) {
            drifted = true
            findings.addProviderDrift(
                expectation,
                AgentDoctorCode.PROVIDER_DESCRIPTOR_DRIFT,
                AgentDoctorRepairAction.ALIGN_PROVIDER_DESCRIPTOR,
            )
        }
        if (result.capabilityDigest != expectation.capabilityDigest) {
            drifted = true
            findings.addProviderDrift(
                expectation,
                AgentDoctorCode.PROVIDER_CAPABILITY_DRIFT,
                AgentDoctorRepairAction.ALIGN_PROVIDER_CAPABILITIES,
            )
        }
        if (result.configurationDigest != expectation.configurationDigest) {
            drifted = true
            findings.addProviderDrift(
                expectation,
                AgentDoctorCode.PROVIDER_CONFIGURATION_DRIFT,
                AgentDoctorRepairAction.ALIGN_PROVIDER_CONFIGURATION,
            )
        }
        if (!drifted) {
            findings.add(
                AgentDoctorCategory.PROVIDER,
                AgentDoctorStatus.HEALTHY,
                AgentDoctorCode.PROVIDER_AVAILABLE,
                1L,
                AgentDoctorBucket.AVAILABLE,
                AgentDoctorRepairAction.NONE,
                expectation.providerKind,
            )
        }
    }

    private fun inspectDurable(
        workload: AgentDurableWorkloadKind,
        request: AgentDoctorProbeRequest,
        findings: FindingAccumulator,
    ) {
        val category = workload.category()
        val probe = try {
            durableProbes.find(workload)
        } catch (_: Exception) {
            null
        }
        if (probe == null) {
            findings.add(
                category,
                AgentDoctorStatus.UNSUPPORTED,
                AgentDoctorCode.DURABLE_PROBE_MISSING,
                1L,
                AgentDoctorBucket.MISSING,
                AgentDoctorRepairAction.CONFIGURE_DURABLE_PROBE,
            )
            return
        }
        val durableRequest = AgentDurableDiagnosticRequest.from(request, workload, policy)
        val snapshot = try {
            probe.inspect(durableRequest)
        } catch (_: Exception) {
            findings.add(
                category,
                AgentDoctorStatus.ERROR,
                AgentDoctorCode.DURABLE_PROBE_FAILED,
                1L,
                AgentDoctorBucket.UNAVAILABLE,
                AgentDoctorRepairAction.RETRY_DIAGNOSTIC,
            )
            return
        }
        if (deadlineExceeded(request, findings)) return
        if (snapshot.requestBindingDigest != durableRequest.requestBindingDigest || snapshot.workloadKind != workload ||
            snapshot.window != policy.window || snapshot.observedAt !in request.requestedAt..request.deadlineAt
        ) {
            findings.add(
                category,
                AgentDoctorStatus.ERROR,
                AgentDoctorCode.DURABLE_PROBE_MISMATCH,
                1L,
                AgentDoctorBucket.DRIFTED,
                AgentDoctorRepairAction.CONFIGURE_DURABLE_PROBE,
            )
            return
        }
        val now = clock.currentTimeMillis()
        if (now < snapshot.observedAt || now - snapshot.observedAt > policy.maximumSnapshotAgeMillis) {
            findings.add(
                category,
                AgentDoctorStatus.ERROR,
                AgentDoctorCode.DURABLE_SNAPSHOT_STALE,
                1L,
                AgentDoctorBucket.STALE,
                AgentDoctorRepairAction.RETRY_DIAGNOSTIC,
            )
            return
        }
        if (snapshot.truncated) {
            findings.add(
                category,
                AgentDoctorStatus.WARNING,
                AgentDoctorCode.DURABLE_SNAPSHOT_TRUNCATED,
                1L,
                AgentDoctorBucket.TRUNCATED,
                AgentDoctorRepairAction.CONFIGURE_DURABLE_PROBE,
            )
        }

        val backlogLimit = if (workload == AgentDurableWorkloadKind.AGENT_RUN) {
            policy.maximumQueuedAgentRuns
        } else {
            policy.maximumQueuedEvaluationRuns
        }
        if (snapshot.queuedCount > backlogLimit) {
            findings.add(
                category,
                AgentDoctorStatus.WARNING,
                AgentDoctorCode.DURABLE_BACKLOG_HIGH,
                snapshot.queuedCount,
                AgentDoctorBucket.ABOVE_LIMIT,
                AgentDoctorRepairAction.SCALE_DURABLE_WORKERS,
            )
        } else if (!snapshot.truncated) {
            findings.add(
                category,
                AgentDoctorStatus.HEALTHY,
                AgentDoctorCode.DURABLE_BACKLOG_WITHIN_LIMIT,
                snapshot.queuedCount,
                AgentDoctorBucket.WITHIN_LIMIT,
                AgentDoctorRepairAction.NONE,
            )
        }
        findings.add(
            category,
            AgentDoctorStatus.HEALTHY,
            AgentDoctorCode.DURABLE_RUNNING,
            snapshot.runningCount,
            AgentDoctorBucket.CURRENT,
            AgentDoctorRepairAction.NONE,
        )
        if (snapshot.expiredLeaseCount > 0L) {
            findings.add(
                category,
                AgentDoctorStatus.ERROR,
                AgentDoctorCode.DURABLE_EXPIRED_LEASE,
                snapshot.expiredLeaseCount,
                AgentDoctorBucket.ABOVE_LIMIT,
                AgentDoctorRepairAction.RECOVER_EXPIRED_LEASES,
            )
        }
        if (snapshot.outcomeUnknownCount > 0L) {
            findings.add(
                category,
                AgentDoctorStatus.WARNING,
                AgentDoctorCode.DURABLE_OUTCOME_UNKNOWN,
                snapshot.outcomeUnknownCount,
                AgentDoctorBucket.UNKNOWN,
                AgentDoctorRepairAction.RECONCILE_UNKNOWN_OUTCOMES,
            )
        }
        if (snapshot.reconciliationPendingCount > 0L) {
            findings.add(
                category,
                AgentDoctorStatus.WARNING,
                AgentDoctorCode.DURABLE_RECONCILIATION_PENDING,
                snapshot.reconciliationPendingCount,
                AgentDoctorBucket.ABOVE_LIMIT,
                AgentDoctorRepairAction.RECONCILE_UNKNOWN_OUTCOMES,
            )
        }
        addTerminalFindings(workload, snapshot, category, findings)
        if (workload == AgentDurableWorkloadKind.EVALUATION_RUN) {
            addEvaluationQualityFindings(snapshot, findings)
        }
        addThresholdFindings(snapshot, category, findings)
    }

    private fun addEvaluationQualityFindings(
        snapshot: AgentDurableDiagnosticSnapshot,
        findings: FindingAccumulator,
    ) {
        val category = AgentDoctorCategory.EVALUATION_RUN
        if (snapshot.evaluationObservationUnknownCount > 0L) {
            findings.add(
                category,
                AgentDoctorStatus.UNSUPPORTED,
                AgentDoctorCode.EVALUATION_OBSERVATION_UNKNOWN,
                snapshot.evaluationObservationUnknownCount,
                AgentDoctorBucket.UNKNOWN,
                AgentDoctorRepairAction.CONFIGURE_DURABLE_PROBE,
            )
        }
        if (snapshot.evaluationCaseFailedCount > 0L) {
            findings.add(
                category,
                AgentDoctorStatus.WARNING,
                AgentDoctorCode.EVALUATION_REGRESSION_FAILED,
                snapshot.evaluationCaseFailedCount,
                AgentDoctorBucket.ABOVE_LIMIT,
                AgentDoctorRepairAction.INSPECT_EVALUATION_REGRESSIONS,
            )
        } else if (snapshot.evaluationObservationUnknownCount == 0L && !snapshot.truncated) {
            findings.add(
                category,
                AgentDoctorStatus.HEALTHY,
                AgentDoctorCode.EVALUATION_REGRESSION_WITHIN_LIMIT,
                snapshot.evaluationCaseCount,
                AgentDoctorBucket.WITHIN_LIMIT,
                AgentDoctorRepairAction.NONE,
            )
        }
        addEvaluationCategoryFailure(
            findings,
            AgentDoctorCode.EVALUATION_RETRIEVAL_FAILED,
            snapshot.evaluationRetrievalFailedCount,
            AgentDoctorRepairAction.REVIEW_RETRIEVAL_PROVIDER,
        )
        addEvaluationCategoryFailure(
            findings,
            AgentDoctorCode.EVALUATION_CITATION_FAILED,
            snapshot.evaluationCitationFailedCount,
            AgentDoctorRepairAction.REVIEW_CITATION_POLICY,
        )
        addEvaluationCategoryFailure(
            findings,
            AgentDoctorCode.EVALUATION_TOOL_FAILED,
            snapshot.evaluationToolFailedCount,
            AgentDoctorRepairAction.REVIEW_TOOL_POLICY,
        )
        addEvaluationCategoryFailure(
            findings,
            AgentDoctorCode.EVALUATION_REFUSAL_FAILED,
            snapshot.evaluationRefusalFailedCount,
            AgentDoctorRepairAction.REVIEW_REFUSAL_POLICY,
        )
    }

    private fun addEvaluationCategoryFailure(
        findings: FindingAccumulator,
        code: AgentDoctorCode,
        count: Long,
        repairAction: AgentDoctorRepairAction,
    ) {
        if (count == 0L) return
        findings.add(
            AgentDoctorCategory.EVALUATION_RUN,
            AgentDoctorStatus.WARNING,
            code,
            count,
            AgentDoctorBucket.ABOVE_LIMIT,
            repairAction,
        )
    }

    private fun addTerminalFindings(
        workload: AgentDurableWorkloadKind,
        snapshot: AgentDurableDiagnosticSnapshot,
        category: AgentDoctorCategory,
        findings: FindingAccumulator,
    ) {
        val failureLimit = if (workload == AgentDurableWorkloadKind.AGENT_RUN) {
            policy.maximumFailedAgentRuns
        } else {
            policy.maximumFailedEvaluationRuns
        }
        val cancellationLimit = if (workload == AgentDurableWorkloadKind.AGENT_RUN) {
            policy.maximumCancelledAgentRuns
        } else {
            policy.maximumCancelledEvaluationRuns
        }
        if (snapshot.failedCount > failureLimit) {
            findings.add(
                category,
                AgentDoctorStatus.WARNING,
                AgentDoctorCode.DURABLE_FAILED,
                snapshot.failedCount,
                AgentDoctorBucket.ABOVE_LIMIT,
                AgentDoctorRepairAction.INSPECT_FAILED_RUNS,
            )
        }
        if (snapshot.cancelledCount > cancellationLimit) {
            findings.add(
                category,
                AgentDoctorStatus.WARNING,
                AgentDoctorCode.DURABLE_CANCELLED,
                snapshot.cancelledCount,
                AgentDoctorBucket.ABOVE_LIMIT,
                AgentDoctorRepairAction.INSPECT_CANCELLED_RUNS,
            )
        }
        if (snapshot.expiredCount > 0L) {
            findings.add(
                category,
                AgentDoctorStatus.WARNING,
                AgentDoctorCode.DURABLE_EXPIRED,
                snapshot.expiredCount,
                AgentDoctorBucket.ABOVE_LIMIT,
                AgentDoctorRepairAction.INSPECT_FAILED_RUNS,
            )
        }
    }

    private fun addThresholdFindings(
        snapshot: AgentDurableDiagnosticSnapshot,
        category: AgentDoctorCategory,
        findings: FindingAccumulator,
    ) {
        when {
            snapshot.unknownCostCount > 0L -> findings.add(
                category,
                AgentDoctorStatus.UNSUPPORTED,
                AgentDoctorCode.COST_OBSERVATION_UNKNOWN,
                snapshot.unknownCostCount,
                AgentDoctorBucket.UNKNOWN,
                AgentDoctorRepairAction.CONFIGURE_COST_OBSERVATION,
            )
            snapshot.overCostLimitCount > 0L -> findings.add(
                category,
                AgentDoctorStatus.WARNING,
                AgentDoctorCode.COST_LIMIT_EXCEEDED,
                snapshot.overCostLimitCount,
                AgentDoctorBucket.ABOVE_LIMIT,
                AgentDoctorRepairAction.REDUCE_COST,
            )
            !snapshot.truncated -> findings.add(
                category,
                AgentDoctorStatus.HEALTHY,
                AgentDoctorCode.COST_WITHIN_LIMIT,
                0L,
                AgentDoctorBucket.WITHIN_LIMIT,
                AgentDoctorRepairAction.NONE,
            )
        }
        when {
            snapshot.unknownLatencyCount > 0L -> findings.add(
                category,
                AgentDoctorStatus.UNSUPPORTED,
                AgentDoctorCode.LATENCY_OBSERVATION_UNKNOWN,
                snapshot.unknownLatencyCount,
                AgentDoctorBucket.UNKNOWN,
                AgentDoctorRepairAction.CONFIGURE_LATENCY_OBSERVATION,
            )
            snapshot.overLatencyLimitCount > 0L -> findings.add(
                category,
                AgentDoctorStatus.WARNING,
                AgentDoctorCode.LATENCY_LIMIT_EXCEEDED,
                snapshot.overLatencyLimitCount,
                AgentDoctorBucket.ABOVE_LIMIT,
                AgentDoctorRepairAction.REDUCE_LATENCY,
            )
            !snapshot.truncated -> findings.add(
                category,
                AgentDoctorStatus.HEALTHY,
                AgentDoctorCode.LATENCY_WITHIN_LIMIT,
                0L,
                AgentDoctorBucket.WITHIN_LIMIT,
                AgentDoctorRepairAction.NONE,
            )
        }
    }

    private fun deadlineExceeded(
        request: AgentDoctorProbeRequest,
        findings: FindingAccumulator,
    ): Boolean {
        if (clock.currentTimeMillis() < request.deadlineAt) return false
        findings.add(
            AgentDoctorCategory.AUTHORIZATION,
            AgentDoctorStatus.ERROR,
            AgentDoctorCode.DIAGNOSTIC_DEADLINE_EXCEEDED,
            1L,
            AgentDoctorBucket.STALE,
            AgentDoctorRepairAction.RETRY_DIAGNOSTIC,
        )
        return true
    }

    private fun finish(findings: FindingAccumulator): AgentDoctorReport {
        val report = AgentDoctorReport.of(findings.snapshot())
        report.findings.forEach { finding ->
            try {
                observationSink.observe(finding)
            } catch (_: Exception) {
                // Observability must never alter or disclose the diagnostic result.
            }
        }
        return report
    }
}

private data class FindingKey(
    val category: AgentDoctorCategory,
    val status: AgentDoctorStatus,
    val code: AgentDoctorCode,
    val bucket: AgentDoctorBucket,
    val repairAction: AgentDoctorRepairAction,
    val providerKind: AgentProviderKind?,
)

private class FindingAccumulator {
    private val counts = LinkedHashMap<FindingKey, Long>()

    fun add(
        category: AgentDoctorCategory,
        status: AgentDoctorStatus,
        code: AgentDoctorCode,
        count: Long,
        bucket: AgentDoctorBucket,
        repairAction: AgentDoctorRepairAction,
        providerKind: AgentProviderKind? = null,
    ) {
        require(count in 0L..AgentDoctorLimits.MAX_COUNT) { "Agent Doctor aggregate count is invalid." }
        val key = FindingKey(category, status, code, bucket, repairAction, providerKind)
        val current = counts[key] ?: 0L
        counts[key] = minOf(AgentDoctorLimits.MAX_COUNT, current + count)
    }

    fun addProviderFailure(
        expectation: AgentProviderDiagnosticExpectation,
        code: AgentDoctorCode,
        bucket: AgentDoctorBucket,
    ) {
        val status = if (!expectation.required) {
            AgentDoctorStatus.WARNING
        } else if (code == AgentDoctorCode.PROVIDER_PROBE_MISSING ||
            code == AgentDoctorCode.PROVIDER_PROBE_UNSUPPORTED
        ) {
            AgentDoctorStatus.UNSUPPORTED
        } else {
            AgentDoctorStatus.ERROR
        }
        add(
            AgentDoctorCategory.PROVIDER,
            status,
            code,
            1L,
            bucket,
            if (code == AgentDoctorCode.PROVIDER_PROBE_UNAVAILABLE) {
                AgentDoctorRepairAction.RESTORE_PROVIDER
            } else {
                AgentDoctorRepairAction.CONFIGURE_PROVIDER_PROBE
            },
            expectation.providerKind,
        )
    }

    fun addProviderDrift(
        expectation: AgentProviderDiagnosticExpectation,
        code: AgentDoctorCode,
        repairAction: AgentDoctorRepairAction,
    ) {
        add(
            AgentDoctorCategory.PROVIDER,
            if (expectation.required) AgentDoctorStatus.ERROR else AgentDoctorStatus.WARNING,
            code,
            1L,
            AgentDoctorBucket.DRIFTED,
            repairAction,
            expectation.providerKind,
        )
    }

    fun snapshot(): List<AgentDoctorFinding> = counts.map { (key, count) ->
        AgentDoctorFinding(
            key.category,
            key.status,
            key.code,
            count,
            key.bucket,
            key.repairAction,
            key.providerKind,
        )
    }
}

private fun AgentDurableWorkloadKind.category(): AgentDoctorCategory = when (this) {
    AgentDurableWorkloadKind.AGENT_RUN -> AgentDoctorCategory.DURABLE_RUN
    AgentDurableWorkloadKind.EVALUATION_RUN -> AgentDoctorCategory.EVALUATION_RUN
}
