package ai.icen.fw.observability

import ai.icen.fw.core.id.Identifier
import java.util.Collections

fun interface SystemDoctorAuthorizationPort {
    fun authorize(request: SystemDoctorRequest): SystemDoctorAuthorization
}

/** One closed slot in the production topology. */
class SystemDoctorProbeRequirement(
    val capability: SystemDoctorCapability,
    probeId: String,
    val required: Boolean,
    contractVersion: String,
    configurationDigest: String,
    val timeoutMillis: Long,
    val maximumSnapshotAgeMillis: Long,
) {
    val probeId: String = requireSystemDoctorCode(probeId, "System Doctor probe id is invalid.")
    val contractVersion: String = requireSystemDoctorCode(
        contractVersion,
        "System Doctor probe contract version is invalid.",
    )
    val configurationDigest: String = requireSystemDoctorDigest(
        configurationDigest,
        "System Doctor probe configuration digest is invalid.",
    )

    init {
        require(timeoutMillis in 1L..SystemDoctorLimits.MAX_PROBE_TIMEOUT_MILLIS) {
            "System Doctor probe timeout is invalid."
        }
        require(maximumSnapshotAgeMillis in 1L..SystemDoctorLimits.MAX_SNAPSHOT_AGE_MILLIS) {
            "System Doctor probe snapshot age is invalid."
        }
    }

    override fun toString(): String =
        "SystemDoctorProbeRequirement(capability=$capability, required=$required, <redacted>)"
}

/**
 * Complete topology: every production capability is declared exactly once.
 * Optional capability absence is still observable as UNSUPPORTED.
 */
class SystemDoctorTopology(requirements: Collection<SystemDoctorProbeRequirement>) {
    val requirements: List<SystemDoctorProbeRequirement>
    val requiredProbeCount: Int

    init {
        val snapshot = ArrayList(requirements)
        require(snapshot.size <= SystemDoctorLimits.MAX_REQUIREMENTS) {
            "System Doctor topology is too large."
        }
        require(snapshot.map { requirement -> requirement.capability }.distinct().size == snapshot.size) {
            "System Doctor topology contains duplicate capabilities."
        }
        require(snapshot.map { requirement -> requirement.probeId }.distinct().size == snapshot.size) {
            "System Doctor topology contains duplicate probe ids."
        }
        require(snapshot.map { requirement -> requirement.capability }.toSet() ==
            SystemDoctorCapability.values().toSet()
        ) { "System Doctor topology must classify every production capability." }
        require(snapshot.any { requirement -> requirement.required }) {
            "System Doctor topology requires at least one readiness capability."
        }
        this.requirements = Collections.unmodifiableList(snapshot)
        requiredProbeCount = snapshot.count { requirement -> requirement.required }
    }

    override fun toString(): String =
        "SystemDoctorTopology(requirementCount=${requirements.size}, requiredProbeCount=$requiredProbeCount)"
}

fun interface SystemDoctorProbeRegistry {
    fun find(capability: SystemDoctorCapability, probeId: String): SystemDoctorProbe?
}

fun interface SystemDoctorProbe {
    /** Implementations must honor the tenant/system scope and [SystemDoctorProbeRequest.deadlineAt]. */
    fun inspect(request: SystemDoctorProbeRequest): SystemDoctorProbeResult
}

/** Probe context is created only after fresh authorization succeeds. */
class SystemDoctorProbeRequest internal constructor(
    val requestId: Identifier,
    val scope: SystemDoctorScope,
    val tenantId: Identifier?,
    val authorizationRevision: String,
    val capability: SystemDoctorCapability,
    val probeId: String,
    val required: Boolean,
    val contractVersion: String,
    val configurationDigest: String,
    val issuedAt: Long,
    val deadlineAt: Long,
    val requestBindingDigest: String,
) {
    val probeBindingDigest: String

    init {
        require(deadlineAt > issuedAt) { "System Doctor probe deadline is invalid." }
        probeBindingDigest = SystemDoctorDigest("flowweft.system-doctor.probe-request.v1")
            .add(requestBindingDigest)
            .add(capability.name)
            .add(probeId)
            .add(required)
            .add(contractVersion)
            .add(configurationDigest)
            .add(issuedAt)
            .add(deadlineAt)
            .finish()
    }

    override fun toString(): String =
        "SystemDoctorProbeRequest(capability=$capability, required=$required, <redacted>)"

    companion object {
        internal fun from(
            request: SystemDoctorRequest,
            requirement: SystemDoctorProbeRequirement,
            issuedAt: Long,
            deadlineAt: Long,
        ): SystemDoctorProbeRequest = SystemDoctorProbeRequest(
            request.requestId,
            request.scope,
            request.tenantId,
            request.authorizationRevision,
            requirement.capability,
            requirement.probeId,
            requirement.required,
            requirement.contractVersion,
            requirement.configurationDigest,
            issuedAt,
            deadlineAt,
            request.bindingDigest,
        )
    }
}

enum class SystemDoctorProbeState {
    HEALTHY,
    DEGRADED,
    UNAVAILABLE,
    UNSUPPORTED,
}

/** A probe can report only this closed, aggregate signal shape. */
class SystemDoctorProbeSignal(
    val severity: SystemDoctorSeverity,
    val code: SystemDoctorCode,
    val count: Long,
    val bucket: SystemDoctorBucket,
    val repairAction: SystemDoctorRepairAction,
) {
    init {
        require(count in 0L..SystemDoctorLimits.MAX_COUNT) {
            "System Doctor probe signal count is invalid."
        }
        require((severity == SystemDoctorSeverity.HEALTHY) == code.isHealthyProbeSignal()) {
            "System Doctor probe signal severity contradicts its stable code."
        }
    }

    override fun toString(): String =
        "SystemDoctorProbeSignal(severity=$severity, code=$code, count=$count, bucket=$bucket)"
}

private fun SystemDoctorCode.isHealthyProbeSignal(): Boolean = when (this) {
    SystemDoctorCode.DATABASE_AVAILABLE,
    SystemDoctorCode.HISTORY_COMPLETE,
    SystemDoctorCode.WORKER_LEASE_CURRENT,
    SystemDoctorCode.QUEUE_BACKLOG_WITHIN_LIMIT,
    SystemDoctorCode.QUEUE_OLDEST_AGE_WITHIN_LIMIT,
    SystemDoctorCode.INDEX_LAG_WITHIN_LIMIT,
    SystemDoctorCode.TOMBSTONE_LAG_WITHIN_LIMIT,
    SystemDoctorCode.CAPACITY_WITHIN_LIMIT,
    SystemDoctorCode.DISK_WITHIN_LIMIT,
    SystemDoctorCode.RUNTIME_READY -> true
    else -> false
}

/** Aggregate-only response. Raw diagnostic data has no representable field. */
class SystemDoctorProbeResult @JvmOverloads constructor(
    probeBindingDigest: String,
    val capability: SystemDoctorCapability,
    val state: SystemDoctorProbeState,
    contractVersion: String,
    configurationDigest: String,
    val observedAt: Long,
    signals: Collection<SystemDoctorProbeSignal> = emptyList(),
    val truncated: Boolean = false,
) {
    val probeBindingDigest: String = requireSystemDoctorDigest(
        probeBindingDigest,
        "System Doctor result binding is invalid.",
    )
    val contractVersion: String = requireSystemDoctorCode(
        contractVersion,
        "System Doctor result contract version is invalid.",
    )
    val configurationDigest: String = requireSystemDoctorDigest(
        configurationDigest,
        "System Doctor result configuration digest is invalid.",
    )
    val signals: List<SystemDoctorProbeSignal>

    init {
        val snapshot = ArrayList(signals)
        require(observedAt >= 0L) { "System Doctor result observation time is invalid." }
        require(snapshot.size <= SystemDoctorLimits.MAX_SIGNALS_PER_PROBE) {
            "System Doctor result contains too many signals."
        }
        require(snapshot.all { signal -> signal.code.isAllowedFor(capability) }) {
            "System Doctor result contains a signal outside its capability vocabulary."
        }
        require(state != SystemDoctorProbeState.HEALTHY ||
            !truncated && snapshot.all { signal -> signal.severity == SystemDoctorSeverity.HEALTHY }
        ) { "A healthy System Doctor result cannot contain degraded evidence." }
        require(state != SystemDoctorProbeState.DEGRADED ||
            truncated || snapshot.any { signal -> signal.severity != SystemDoctorSeverity.HEALTHY }
        ) { "A degraded System Doctor result requires degraded evidence." }
        this.signals = Collections.unmodifiableList(snapshot)
    }

    override fun toString(): String =
        "SystemDoctorProbeResult(capability=$capability, state=$state, signalCount=${signals.size}, truncated=$truncated)"
}

private fun SystemDoctorCode.isAllowedFor(capability: SystemDoctorCapability): Boolean = when (capability) {
    SystemDoctorCapability.DATABASE -> this == SystemDoctorCode.DATABASE_AVAILABLE ||
        this == SystemDoctorCode.DATABASE_HISTORY_GAP
    SystemDoctorCapability.HISTORY -> this == SystemDoctorCode.HISTORY_COMPLETE ||
        this == SystemDoctorCode.HISTORY_INCOMPLETE || this == SystemDoctorCode.DATABASE_HISTORY_GAP
    SystemDoctorCapability.WORKER_LEASE -> this == SystemDoctorCode.WORKER_LEASE_CURRENT ||
        this == SystemDoctorCode.WORKER_LEASE_EXPIRED
    SystemDoctorCapability.OUTBOX_QUEUE,
    SystemDoctorCapability.NOTIFICATION_QUEUE,
    SystemDoctorCapability.EFFECT_QUEUE,
    SystemDoctorCapability.AGENT_QUEUE,
    SystemDoctorCapability.RETRIEVAL_QUEUE -> this == SystemDoctorCode.QUEUE_BACKLOG_WITHIN_LIMIT ||
        this == SystemDoctorCode.QUEUE_BACKLOG_HIGH ||
        this == SystemDoctorCode.QUEUE_OLDEST_AGE_WITHIN_LIMIT ||
        this == SystemDoctorCode.QUEUE_OLDEST_AGE_HIGH ||
        this == SystemDoctorCode.QUEUE_FAILED_ITEMS ||
        this == SystemDoctorCode.QUEUE_OUTCOME_UNKNOWN ||
        this == SystemDoctorCode.QUEUE_RECONCILIATION_PENDING
    SystemDoctorCapability.INDEX_CONVERGENCE -> this == SystemDoctorCode.INDEX_LAG_WITHIN_LIMIT ||
        this == SystemDoctorCode.INDEX_LAG_HIGH
    SystemDoctorCapability.TOMBSTONE_CONVERGENCE -> this == SystemDoctorCode.TOMBSTONE_LAG_WITHIN_LIMIT ||
        this == SystemDoctorCode.TOMBSTONE_LAG_HIGH
    SystemDoctorCapability.CAPACITY -> this == SystemDoctorCode.CAPACITY_WITHIN_LIMIT ||
        this == SystemDoctorCode.CAPACITY_LOW
    SystemDoctorCapability.DISK -> this == SystemDoctorCode.DISK_WITHIN_LIMIT || this == SystemDoctorCode.DISK_LOW
    SystemDoctorCapability.RUNTIME_READINESS -> this == SystemDoctorCode.RUNTIME_READY ||
        this == SystemDoctorCode.RUNTIME_NOT_READY
}

enum class SystemDoctorProbeExecutionState {
    COMPLETED,
    TIMED_OUT,
    FAILED,
}

class SystemDoctorProbeExecution private constructor(
    val state: SystemDoctorProbeExecutionState,
    val result: SystemDoctorProbeResult?,
) {
    init {
        require((state == SystemDoctorProbeExecutionState.COMPLETED) == (result != null)) {
            "System Doctor probe execution result is invalid."
        }
    }

    override fun toString(): String = "SystemDoctorProbeExecution(state=$state)"

    companion object {
        @JvmStatic
        fun completed(result: SystemDoctorProbeResult): SystemDoctorProbeExecution =
            SystemDoctorProbeExecution(SystemDoctorProbeExecutionState.COMPLETED, result)

        @JvmStatic
        fun timedOut(): SystemDoctorProbeExecution =
            SystemDoctorProbeExecution(SystemDoctorProbeExecutionState.TIMED_OUT, null)

        @JvmStatic
        fun failed(): SystemDoctorProbeExecution =
            SystemDoctorProbeExecution(SystemDoctorProbeExecutionState.FAILED, null)
    }
}

/** Execution boundary owns hard deadline enforcement and exception containment. */
fun interface SystemDoctorProbeExecutionPort {
    fun execute(
        probe: SystemDoctorProbe,
        request: SystemDoctorProbeRequest,
        timeoutMillis: Long,
    ): SystemDoctorProbeExecution

    companion object {
        /** Only for already-bounded in-process probes and deterministic tests. */
        @JvmField
        val DIRECT: SystemDoctorProbeExecutionPort = SystemDoctorProbeExecutionPort { probe, request, _ ->
            try {
                SystemDoctorProbeExecution.completed(probe.inspect(request))
            } catch (_: Exception) {
                SystemDoctorProbeExecution.failed()
            }
        }
    }
}

fun interface SystemDoctorObservationSink {
    fun observe(finding: SystemDoctorFinding)

    companion object {
        @JvmField
        val NOOP: SystemDoctorObservationSink = SystemDoctorObservationSink { }
    }
}

fun interface SystemDoctorClock {
    fun currentTimeMillis(): Long

    companion object {
        @JvmField
        val SYSTEM: SystemDoctorClock = SystemDoctorClock(System::currentTimeMillis)
    }
}
