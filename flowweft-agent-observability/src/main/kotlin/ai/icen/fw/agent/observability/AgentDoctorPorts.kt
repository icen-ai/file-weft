package ai.icen.fw.agent.observability

import ai.icen.fw.agent.api.ProviderId
import java.util.Collections

fun interface AgentDoctorAuthorizationPort {
    fun authorize(request: AgentDoctorRequest): AgentDoctorAuthorization
}

enum class AgentProviderProbeState {
    AVAILABLE,
    UNAVAILABLE,
    UNSUPPORTED,
}

/** Expected, secret-free identity of one configured provider contract. */
class AgentProviderDiagnosticExpectation @JvmOverloads constructor(
    val providerId: ProviderId,
    val providerKind: AgentProviderKind,
    descriptorDigest: String,
    capabilityDigest: String,
    configurationDigest: String,
    val required: Boolean = true,
) {
    val descriptorDigest: String = requireDoctorDigest(
        descriptorDigest,
        "Agent provider expected descriptor digest is invalid.",
    )
    val capabilityDigest: String = requireDoctorDigest(
        capabilityDigest,
        "Agent provider expected capability digest is invalid.",
    )
    val configurationDigest: String = requireDoctorDigest(
        configurationDigest,
        "Agent provider expected configuration digest is invalid.",
    )

    override fun toString(): String =
        "AgentProviderDiagnosticExpectation(providerKind=$providerKind, required=$required, <redacted>)"
}

/** Bounded inventory snapshot. Empty, explicitly covered kinds mean that kind is not configured. */
class AgentProviderTopologySnapshot(
    requestBindingDigest: String,
    expectations: Collection<AgentProviderDiagnosticExpectation>,
    coveredKinds: Collection<AgentProviderKind>,
) {
    val requestBindingDigest: String = requireDoctorDigest(
        requestBindingDigest,
        "Agent provider topology request binding is invalid.",
    )
    val expectations: List<AgentProviderDiagnosticExpectation>
    val coveredKinds: Set<AgentProviderKind>

    init {
        val expectationSnapshot = ArrayList(expectations)
        require(expectationSnapshot.size <= AgentDoctorLimits.MAX_PROVIDER_PROBES) {
            "Agent provider topology contains too many expectations."
        }
        require(expectationSnapshot.distinctBy { expectation ->
            expectation.providerKind to expectation.providerId
        }.size == expectationSnapshot.size) { "Agent provider topology contains duplicate expectations." }
        val coverageSnapshot = LinkedHashSet(coveredKinds)
        require(coverageSnapshot.size == coveredKinds.size) { "Agent provider topology contains duplicate coverage." }
        this.expectations = Collections.unmodifiableList(expectationSnapshot)
        this.coveredKinds = Collections.unmodifiableSet(coverageSnapshot)
    }

    override fun toString(): String =
        "AgentProviderTopologySnapshot(expectationCount=${expectations.size}, coveredKinds=$coveredKinds)"
}

fun interface AgentProviderTopologyPort {
    fun inspect(request: AgentDoctorProbeRequest): AgentProviderTopologySnapshot
}

fun interface AgentProviderDiagnosticProbeRegistry {
    fun find(providerKind: AgentProviderKind, providerId: ProviderId): AgentProviderDiagnosticProbe?
}

fun interface AgentProviderDiagnosticProbe {
    fun inspect(request: AgentDoctorProbeRequest): AgentProviderDiagnosticProbeResult
}

/** Probe result has no endpoint, provider payload, exception or credential field. */
class AgentProviderDiagnosticProbeResult @JvmOverloads constructor(
    requestBindingDigest: String,
    val providerId: ProviderId,
    val providerKind: AgentProviderKind,
    val state: AgentProviderProbeState,
    val observedAt: Long,
    descriptorDigest: String? = null,
    capabilityDigest: String? = null,
    configurationDigest: String? = null,
) {
    val requestBindingDigest: String = requireDoctorDigest(
        requestBindingDigest,
        "Agent provider probe request binding is invalid.",
    )
    val descriptorDigest: String? = descriptorDigest?.let { digest ->
        requireDoctorDigest(digest, "Agent provider observed descriptor digest is invalid.")
    }
    val capabilityDigest: String? = capabilityDigest?.let { digest ->
        requireDoctorDigest(digest, "Agent provider observed capability digest is invalid.")
    }
    val configurationDigest: String? = configurationDigest?.let { digest ->
        requireDoctorDigest(digest, "Agent provider observed configuration digest is invalid.")
    }

    init {
        require(observedAt >= 0L) { "Agent provider probe observation time is invalid." }
        require(state != AgentProviderProbeState.AVAILABLE ||
            this.descriptorDigest != null && this.capabilityDigest != null && this.configurationDigest != null
        ) { "Available Agent providers require complete drift evidence." }
    }

    override fun toString(): String =
        "AgentProviderDiagnosticProbeResult(providerKind=$providerKind, state=$state, <redacted>)"
}

enum class AgentDurableWorkloadKind {
    AGENT_RUN,
    EVALUATION_RUN,
}

enum class AgentDoctorWindow {
    RECENT_5_MINUTES,
    RECENT_1_HOUR,
    RECENT_24_HOURS,
}

class AgentDurableDiagnosticRequest internal constructor(
    val context: AgentDoctorProbeRequest,
    val workloadKind: AgentDurableWorkloadKind,
    val window: AgentDoctorWindow,
    val maximumCostMicros: Long,
    val maximumLatencyMillis: Long,
) {
    val requestBindingDigest: String

    init {
        require(maximumCostMicros >= 0L) { "Agent durable diagnostic cost threshold is invalid." }
        require(maximumLatencyMillis > 0L) { "Agent durable diagnostic latency threshold is invalid." }
        requestBindingDigest = AgentDoctorDigest("flowweft.agent.doctor.durable-request.v1")
            .add(context.requestBindingDigest)
            .add(workloadKind.name)
            .add(window.name)
            .add(maximumCostMicros)
            .add(maximumLatencyMillis)
            .finish()
    }

    override fun toString(): String = "AgentDurableDiagnosticRequest(workloadKind=$workloadKind, window=$window, <redacted>)"

    companion object {
        internal fun from(
            context: AgentDoctorProbeRequest,
            workloadKind: AgentDurableWorkloadKind,
            policy: AgentDoctorPolicy,
        ): AgentDurableDiagnosticRequest = AgentDurableDiagnosticRequest(
            context,
            workloadKind,
            policy.window,
            if (workloadKind == AgentDurableWorkloadKind.AGENT_RUN) {
                policy.maximumAgentRunCostMicros
            } else {
                policy.maximumEvaluationRunCostMicros
            },
            if (workloadKind == AgentDurableWorkloadKind.AGENT_RUN) {
                policy.maximumAgentRunLatencyMillis
            } else {
                policy.maximumEvaluationRunLatencyMillis
            },
        )
    }
}

fun interface AgentDurableDiagnosticProbe {
    /** Implementations must aggregate within [AgentDurableDiagnosticRequest.context]'s deadline. */
    fun inspect(request: AgentDurableDiagnosticRequest): AgentDurableDiagnosticSnapshot
}

fun interface AgentDurableDiagnosticProbeRegistry {
    fun find(workloadKind: AgentDurableWorkloadKind): AgentDurableDiagnosticProbe?
}

/**
 * Aggregate-only snapshot. A persistence adapter computes these values without returning run IDs,
 * prompts, tool arguments, provider tokens, URLs or failure text.
 */
class AgentDurableDiagnosticSnapshot @JvmOverloads constructor(
    requestBindingDigest: String,
    val workloadKind: AgentDurableWorkloadKind,
    val window: AgentDoctorWindow,
    val observedAt: Long,
    val queuedCount: Long = 0L,
    val runningCount: Long = 0L,
    val failedCount: Long = 0L,
    val cancelledCount: Long = 0L,
    val expiredCount: Long = 0L,
    val expiredLeaseCount: Long = 0L,
    val outcomeUnknownCount: Long = 0L,
    val reconciliationPendingCount: Long = 0L,
    val overCostLimitCount: Long = 0L,
    val overLatencyLimitCount: Long = 0L,
    val unknownCostCount: Long = 0L,
    val unknownLatencyCount: Long = 0L,
    val evaluationCaseCount: Long = 0L,
    val evaluationCaseFailedCount: Long = 0L,
    val evaluationRetrievalFailedCount: Long = 0L,
    val evaluationCitationFailedCount: Long = 0L,
    val evaluationToolFailedCount: Long = 0L,
    val evaluationRefusalFailedCount: Long = 0L,
    val evaluationObservationUnknownCount: Long = 0L,
    val truncated: Boolean = false,
) {
    val requestBindingDigest: String = requireDoctorDigest(
        requestBindingDigest,
        "Agent durable probe request binding is invalid.",
    )

    init {
        listOf(
            queuedCount,
            runningCount,
            failedCount,
            cancelledCount,
            expiredCount,
            expiredLeaseCount,
            outcomeUnknownCount,
            reconciliationPendingCount,
            overCostLimitCount,
            overLatencyLimitCount,
            unknownCostCount,
            unknownLatencyCount,
            evaluationCaseCount,
            evaluationCaseFailedCount,
            evaluationRetrievalFailedCount,
            evaluationCitationFailedCount,
            evaluationToolFailedCount,
            evaluationRefusalFailedCount,
            evaluationObservationUnknownCount,
        ).forEach { count ->
            require(count in 0L..AgentDoctorLimits.MAX_COUNT) { "Agent durable diagnostic count is invalid." }
        }
        require(observedAt >= 0L) { "Agent durable diagnostic observation time is invalid." }
        require(workloadKind != AgentDurableWorkloadKind.AGENT_RUN ||
            evaluationCaseCount == 0L && evaluationCaseFailedCount == 0L &&
            evaluationRetrievalFailedCount == 0L && evaluationCitationFailedCount == 0L &&
            evaluationToolFailedCount == 0L && evaluationRefusalFailedCount == 0L &&
            evaluationObservationUnknownCount == 0L
        ) { "Agent-run diagnostics cannot contain evaluation quality counts." }
        require(evaluationCaseFailedCount <= evaluationCaseCount) {
            "Agent evaluation failed-case count exceeds the evaluated-case count."
        }
        require(evaluationObservationUnknownCount <= evaluationCaseCount) {
            "Agent evaluation unknown-observation count exceeds the evaluated-case count."
        }
        listOf(
            evaluationRetrievalFailedCount,
            evaluationCitationFailedCount,
            evaluationToolFailedCount,
            evaluationRefusalFailedCount,
        ).forEach { failedCount ->
            require(failedCount <= evaluationCaseFailedCount) {
                "Agent evaluation category failure count exceeds the failed-case count."
            }
        }
    }

    override fun toString(): String =
        "AgentDurableDiagnosticSnapshot(workloadKind=$workloadKind, window=$window, truncated=$truncated)"
}

/** Narrow aggregate sink; adapters may bridge this to Micrometer, OTel or structured logs. */
fun interface AgentDoctorObservationSink {
    fun observe(finding: AgentDoctorFinding)

    companion object {
        @JvmField val NOOP = AgentDoctorObservationSink { }
    }
}

fun interface AgentDoctorClock {
    fun currentTimeMillis(): Long

    companion object {
        @JvmField val SYSTEM = AgentDoctorClock(System::currentTimeMillis)
    }
}

class AgentDoctorPolicy @JvmOverloads constructor(
    val window: AgentDoctorWindow = AgentDoctorWindow.RECENT_1_HOUR,
    val maximumQueuedAgentRuns: Long = 100L,
    val maximumQueuedEvaluationRuns: Long = 20L,
    val maximumFailedAgentRuns: Long = 0L,
    val maximumFailedEvaluationRuns: Long = 0L,
    val maximumCancelledAgentRuns: Long = 0L,
    val maximumCancelledEvaluationRuns: Long = 0L,
    val maximumAgentRunCostMicros: Long = 1_000_000_000L,
    val maximumEvaluationRunCostMicros: Long = 10_000_000_000L,
    val maximumAgentRunLatencyMillis: Long = 60_000L,
    val maximumEvaluationRunLatencyMillis: Long = 600_000L,
    val maximumSnapshotAgeMillis: Long = 60_000L,
    val maximumProviderProbes: Int = AgentDoctorLimits.MAX_PROVIDER_PROBES,
) {
    init {
        listOf(
            maximumQueuedAgentRuns,
            maximumQueuedEvaluationRuns,
            maximumFailedAgentRuns,
            maximumFailedEvaluationRuns,
            maximumCancelledAgentRuns,
            maximumCancelledEvaluationRuns,
            maximumAgentRunCostMicros,
            maximumEvaluationRunCostMicros,
        ).forEach { threshold ->
            require(threshold in 0L..AgentDoctorLimits.MAX_COUNT) { "Agent Doctor threshold is invalid." }
        }
        require(maximumAgentRunLatencyMillis in 1L..86_400_000L &&
            maximumEvaluationRunLatencyMillis in 1L..86_400_000L
        ) { "Agent Doctor latency threshold is invalid." }
        require(maximumSnapshotAgeMillis in 1L..86_400_000L) { "Agent Doctor snapshot age limit is invalid." }
        require(maximumProviderProbes in 1..AgentDoctorLimits.MAX_PROVIDER_PROBES) {
            "Agent Doctor provider-probe policy limit is invalid."
        }
    }
}
