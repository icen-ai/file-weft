package ai.icen.fw.governance.runtime

import ai.icen.fw.governance.api.GovernanceCapability
import ai.icen.fw.governance.api.GovernanceCapabilityProvider
import ai.icen.fw.governance.api.GovernanceCapabilityRequest
import ai.icen.fw.governance.api.GovernanceCapabilityResult
import ai.icen.fw.governance.api.GovernanceCapabilitySnapshot
import ai.icen.fw.governance.api.GovernanceCapabilityStatus
import ai.icen.fw.governance.api.GovernanceDeletionPlan
import ai.icen.fw.governance.api.GovernanceDeletionStage
import ai.icen.fw.governance.api.GovernanceDoctor
import ai.icen.fw.governance.api.GovernanceDoctorFinding
import ai.icen.fw.governance.api.GovernanceDoctorRequest
import ai.icen.fw.governance.api.GovernanceDoctorResult
import ai.icen.fw.governance.api.GovernanceDoctorSeverity
import ai.icen.fw.governance.api.GovernanceDoctorStatus
import ai.icen.fw.governance.api.GovernanceFailure
import ai.icen.fw.governance.api.GovernanceFailureClass
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

class ProviderNeutralGovernanceCapabilityProvider(
    private val clock: GovernanceRuntimeClockPort,
    private val providers: GovernanceDeletionProviderRegistry,
    implementationId: String,
    implementationRevision: String,
    private val maximumHoldsPerResolution: Int = 256,
    private val snapshotTtlMillis: Long = 60_000L,
) : GovernanceCapabilityProvider {
    private val implementationId: String = GovernanceRuntimeSupport.code(
        implementationId, "Governance runtime implementation id is invalid.",
    )
    private val implementationRevision: String = GovernanceRuntimeSupport.text(
        implementationRevision,
        GovernanceRuntimeSupport.MAX_REVISION_BYTES,
        "Governance runtime implementation revision is invalid.",
    )

    init {
        require(maximumHoldsPerResolution in 1..4_096 && snapshotTtlMillis > 0L) {
            "Governance runtime capability limits are invalid."
        }
    }

    override fun capabilities(request: GovernanceCapabilityRequest): CompletionStage<GovernanceCapabilityResult> {
        val now = try {
            clock.nowEpochMilli().also { observed ->
                require(observed in request.context.requestedAtEpochMilli..request.context.deadlineEpochMilli)
            }
        } catch (_: RuntimeException) {
            return completed(
                GovernanceCapabilityResult.failure(
                    request,
                    GovernanceCapabilityStatus.UNAVAILABLE,
                    GovernanceFailure.of(
                        GovernanceFailureClass.TEMPORARY_UNAVAILABLE,
                        "capability-clock-unavailable",
                        true,
                        false,
                    ),
                    request.context.requestedAtEpochMilli,
                ),
            )
        }
        val availableStages = GovernanceDeletionPlan.REQUIRED_STAGE_ORDER.filter { stage -> providers.find(stage) != null }
        val supported = linkedSetOf(
            GovernanceCapability.RETENTION_EVALUATION,
            GovernanceCapability.LEGAL_HOLD_RESOLUTION,
            GovernanceCapability.DRY_RUN,
        )
        if (GovernanceDeletionStage.PERSIST_TOMBSTONE in availableStages &&
            GovernanceDeletionStage.FINALIZE_METADATA in availableStages) {
            supported += GovernanceCapability.METADATA_TOMBSTONE
        }
        if (GovernanceDeletionStage.ENQUEUE_PURGE_OUTBOX in availableStages) {
            supported += GovernanceCapability.OUTBOX_TOMBSTONE
        }
        if (GovernanceDeletionStage.PURGE_INDEX_PROJECTIONS in availableStages) {
            supported += GovernanceCapability.INDEX_PURGE
        }
        if (GovernanceDeletionStage.PURGE_OBJECT_CONTENT in availableStages) {
            supported += GovernanceCapability.OBJECT_PURGE
        }
        if (availableStages == GovernanceDeletionPlan.REQUIRED_STAGE_ORDER) {
            supported += GovernanceCapability.SECURE_DELETION
            supported += GovernanceCapability.RECONCILIATION
        }
        if (!supported.containsAll(request.required)) {
            return completed(
                GovernanceCapabilityResult.failure(
                    request,
                    GovernanceCapabilityStatus.UNSUPPORTED,
                    GovernanceFailure.of(
                        GovernanceFailureClass.UNSUPPORTED,
                        "required-capability-unsupported",
                        false,
                        false,
                    ),
                    now,
                ),
            )
        }
        val snapshot = GovernanceCapabilitySnapshot.of(
            implementationId,
            implementationRevision,
            supported,
            maximumHoldsPerResolution,
            GovernanceDeletionPlan.REQUIRED_STAGE_ORDER.size,
            now,
            now + snapshotTtlMillis,
        )
        return completed(GovernanceCapabilityResult.available(request, snapshot, now))
    }
}

/** Doctor aggregation is read-only and accepts only bounded value-free findings. */
class ProviderNeutralGovernanceDoctor(
    private val clock: GovernanceRuntimeClockPort,
    sources: Collection<GovernanceRuntimeDiagnosticSource>,
    private val providers: GovernanceDeletionProviderRegistry,
    private val resultTtlMillis: Long = 60_000L,
) : GovernanceDoctor {
    private val sources: List<GovernanceRuntimeDiagnosticSource> = GovernanceRuntimeSupport.immutable(
        sources, 64, "Governance runtime Doctor sources are invalid.",
    )

    init {
        require(resultTtlMillis > 0L) { "Governance runtime Doctor TTL is invalid." }
    }

    override fun inspect(request: GovernanceDoctorRequest): CompletionStage<GovernanceDoctorResult> {
        var clockHealthy = true
        val now = try {
            clock.nowEpochMilli().also { observed ->
                require(observed in request.context.requestedAtEpochMilli..request.context.deadlineEpochMilli)
            }
        } catch (_: RuntimeException) {
            clockHealthy = false
            request.context.requestedAtEpochMilli
        }
        val findings = mutableListOf<GovernanceDoctorFinding>()
        findings += GovernanceDoctorFinding.of("runtime-configured", GovernanceDoctorSeverity.INFO, 1L)
        if (!clockHealthy) {
            findings += GovernanceDoctorFinding.of(
                "runtime-clock-unavailable", GovernanceDoctorSeverity.ERROR, 1L,
            )
        }
        val missingProviders = GovernanceDeletionPlan.REQUIRED_STAGE_ORDER.count { stage -> providers.find(stage) == null }
        if (missingProviders > 0) {
            findings += GovernanceDoctorFinding.of(
                "deletion-provider-incomplete", GovernanceDoctorSeverity.ERROR, missingProviders.toLong(),
            )
        }
        sources.forEach { source ->
            val observed = try {
                source.findings(request.mode, now)
            } catch (_: RuntimeException) {
                listOf(GovernanceDoctorFinding.of("diagnostic-source-unavailable", GovernanceDoctorSeverity.ERROR, 1L))
            }
            observed.forEach { finding ->
                if (isValueFreeCode(finding.code)) {
                    findings += finding
                } else {
                    findings += GovernanceDoctorFinding.of(
                        "diagnostic-source-invalid", GovernanceDoctorSeverity.ERROR, 1L,
                    )
                }
            }
        }
        val normalized = findings.groupBy { finding -> finding.code }.map { (_, sameCode) ->
            val severity = when {
                sameCode.any { it.severity == GovernanceDoctorSeverity.ERROR } -> GovernanceDoctorSeverity.ERROR
                sameCode.any { it.severity == GovernanceDoctorSeverity.WARNING } -> GovernanceDoctorSeverity.WARNING
                else -> GovernanceDoctorSeverity.INFO
            }
            GovernanceDoctorFinding.of(sameCode.first().code, severity, sameCode.sumOf { it.count })
        }.sortedBy { finding -> finding.code }
        val status = when {
            normalized.any { finding -> finding.severity == GovernanceDoctorSeverity.ERROR } ->
                GovernanceDoctorStatus.NOT_READY
            normalized.any { finding -> finding.severity == GovernanceDoctorSeverity.WARNING } ->
                GovernanceDoctorStatus.DEGRADED
            else -> GovernanceDoctorStatus.READY
        }
        return completed(
            GovernanceDoctorResult.of(request, status, normalized, now, now + resultTtlMillis),
        )
    }

    private fun isValueFreeCode(code: String): Boolean = code.isNotEmpty() && code.all { character ->
        character in 'A'..'Z' || character in 'a'..'z' || character in '0'..'9' ||
            character == '.' || character == '_' || character == '-'
    }
}

class GovernanceOutboxRelayResult private constructor(
    val claimed: Int,
    val signalled: Int,
    val acknowledged: Int,
) {
    init {
        require(claimed >= 0 && signalled in 0..claimed && acknowledged in 0..signalled) {
            "Governance outbox relay counters are invalid."
        }
    }

    override fun toString(): String =
        "GovernanceOutboxRelayResult(claimed=$claimed, signalled=$signalled, acknowledged=$acknowledged)"

    companion object {
        @JvmStatic
        fun of(claimed: Int, signalled: Int, acknowledged: Int): GovernanceOutboxRelayResult =
            GovernanceOutboxRelayResult(claimed, signalled, acknowledged)
    }
}

/** Claims durable outbox rows in a short transaction, then signals workers outside that transaction. */
class GovernanceOutboxRelay(
    private val outbox: GovernanceOutboxRepository,
    private val workers: GovernanceWorkerSignalPort,
) {
    fun relay(request: GovernanceOutboxClaimRequest): CompletionStage<GovernanceOutboxRelayResult> {
        val claims = try {
            outbox.claimReady(request)
        } catch (_: RuntimeException) {
            return completed(GovernanceOutboxRelayResult.of(0, 0, 0))
        }
        var stage: CompletionStage<RelayCounters> = completed(RelayCounters(0, 0))
        claims.forEach { claim ->
            stage = stage.thenCompose { counters ->
                val signal = try {
                    workers.signal(claim.record)
                } catch (_: RuntimeException) {
                    return@thenCompose completed(counters)
                }
                signal.handle { _, throwable ->
                    if (throwable != null) {
                        counters
                    } else {
                        val acknowledged = try {
                            outbox.acknowledge(claim, request.nowEpochMilli)
                        } catch (_: RuntimeException) {
                            false
                        }
                        RelayCounters(counters.signalled + 1, counters.acknowledged + if (acknowledged) 1 else 0)
                    }
                }
            }
        }
        return stage.thenApply { counters ->
            GovernanceOutboxRelayResult.of(claims.size, counters.signalled, counters.acknowledged)
        }
    }

    private class RelayCounters(val signalled: Int, val acknowledged: Int)
}

private fun <T> completed(value: T): CompletionStage<T> = CompletableFuture.completedFuture(value)
