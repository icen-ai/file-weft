package ai.icen.fw.observability

import ai.icen.fw.core.id.Identifier
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Collections

enum class SystemDoctorScope {
    TENANT,
    SYSTEM,
}

enum class SystemDoctorSeverity {
    HEALTHY,
    WARNING,
    UNSUPPORTED,
    ERROR,
}

enum class SystemDoctorReadiness {
    READY,
    NOT_READY,
}

enum class SystemDoctorFindingArea {
    AUTHORIZATION,
    CAPABILITY,
}

/**
 * Closed production capability inventory. A topology must declare exactly one
 * required or optional probe slot for every value, so an omitted integration
 * can never disappear from the report.
 */
enum class SystemDoctorCapability {
    DATABASE,
    HISTORY,
    WORKER_LEASE,
    OUTBOX_QUEUE,
    NOTIFICATION_QUEUE,
    EFFECT_QUEUE,
    AGENT_QUEUE,
    RETRIEVAL_QUEUE,
    INDEX_CONVERGENCE,
    TOMBSTONE_CONVERGENCE,
    CAPACITY,
    DISK,
    RUNTIME_READINESS,
}

/** Content-free buckets are safe for operator APIs, metrics and traces. */
enum class SystemDoctorBucket {
    AVAILABLE,
    UNAVAILABLE,
    MISSING,
    UNSUPPORTED,
    MATCHED,
    DRIFTED,
    WITHIN_LIMIT,
    ABOVE_LIMIT,
    CURRENT,
    STALE,
    COMPLETE,
    PARTIAL,
    TRUNCATED,
    UNKNOWN,
    LOW,
    EXHAUSTED,
    TIMED_OUT,
}

/** Stable actions are rendered by a host; probes cannot inject repair text. */
enum class SystemDoctorRepairAction {
    NONE,
    GRANT_DIAGNOSTIC_PERMISSION,
    REFRESH_AUTHORIZATION,
    CONFIGURE_PROBE,
    ALIGN_PROBE_VERSION,
    ALIGN_PROBE_CONFIGURATION,
    RESTORE_DEPENDENCY,
    RETRY_DIAGNOSTIC,
    REPAIR_HISTORY,
    RECOVER_EXPIRED_LEASES,
    SCALE_WORKERS,
    DRAIN_QUEUE,
    RECONCILE_UNKNOWN_OUTCOMES,
    REBUILD_INDEX,
    PROPAGATE_TOMBSTONES,
    INCREASE_CAPACITY,
    FREE_DISK_SPACE,
    RESTORE_READINESS,
}

/**
 * Closed code vocabulary. There is deliberately no message, SQL, endpoint,
 * credential, exception, resource identifier or tenant payload field.
 */
enum class SystemDoctorCode(val value: String) {
    AUTHORIZATION_DENIED("flowweft.doctor.authorization.denied"),
    AUTHORIZATION_UNAVAILABLE("flowweft.doctor.authorization.unavailable"),
    AUTHORIZATION_MISMATCH("flowweft.doctor.authorization.mismatch"),
    AUTHORIZATION_EXPIRED("flowweft.doctor.authorization.expired"),
    REQUEST_EXPIRED("flowweft.doctor.request.expired"),
    DIAGNOSTIC_DEADLINE_EXCEEDED("flowweft.doctor.deadline.exceeded"),
    PROBE_MISSING("flowweft.doctor.probe.missing"),
    PROBE_UNSUPPORTED("flowweft.doctor.probe.unsupported"),
    PROBE_UNAVAILABLE("flowweft.doctor.probe.unavailable"),
    PROBE_FAILED("flowweft.doctor.probe.failed"),
    PROBE_TIMED_OUT("flowweft.doctor.probe.timed-out"),
    PROBE_BINDING_MISMATCH("flowweft.doctor.probe.binding-mismatch"),
    PROBE_VERSION_DRIFT("flowweft.doctor.probe.version-drift"),
    PROBE_CONFIGURATION_DRIFT("flowweft.doctor.probe.configuration-drift"),
    PROBE_SNAPSHOT_STALE("flowweft.doctor.probe.snapshot-stale"),
    PROBE_RESULT_TRUNCATED("flowweft.doctor.probe.result-truncated"),
    PROBE_HEALTHY("flowweft.doctor.probe.healthy"),
    PROBE_DEGRADED("flowweft.doctor.probe.degraded"),
    DATABASE_AVAILABLE("flowweft.doctor.database.available"),
    DATABASE_HISTORY_GAP("flowweft.doctor.database.history-gap"),
    HISTORY_COMPLETE("flowweft.doctor.history.complete"),
    HISTORY_INCOMPLETE("flowweft.doctor.history.incomplete"),
    WORKER_LEASE_CURRENT("flowweft.doctor.worker.lease-current"),
    WORKER_LEASE_EXPIRED("flowweft.doctor.worker.lease-expired"),
    QUEUE_BACKLOG_WITHIN_LIMIT("flowweft.doctor.queue.backlog-within-limit"),
    QUEUE_BACKLOG_HIGH("flowweft.doctor.queue.backlog-high"),
    QUEUE_OLDEST_AGE_WITHIN_LIMIT("flowweft.doctor.queue.oldest-age-within-limit"),
    QUEUE_OLDEST_AGE_HIGH("flowweft.doctor.queue.oldest-age-high"),
    QUEUE_FAILED_ITEMS("flowweft.doctor.queue.failed-items"),
    QUEUE_OUTCOME_UNKNOWN("flowweft.doctor.queue.outcome-unknown"),
    QUEUE_RECONCILIATION_PENDING("flowweft.doctor.queue.reconciliation-pending"),
    INDEX_LAG_WITHIN_LIMIT("flowweft.doctor.index.lag-within-limit"),
    INDEX_LAG_HIGH("flowweft.doctor.index.lag-high"),
    TOMBSTONE_LAG_WITHIN_LIMIT("flowweft.doctor.tombstone.lag-within-limit"),
    TOMBSTONE_LAG_HIGH("flowweft.doctor.tombstone.lag-high"),
    CAPACITY_WITHIN_LIMIT("flowweft.doctor.capacity.within-limit"),
    CAPACITY_LOW("flowweft.doctor.capacity.low"),
    DISK_WITHIN_LIMIT("flowweft.doctor.disk.within-limit"),
    DISK_LOW("flowweft.doctor.disk.low"),
    RUNTIME_READY("flowweft.doctor.runtime.ready"),
    RUNTIME_NOT_READY("flowweft.doctor.runtime.not-ready"),
    ;

    override fun toString(): String = value
}

/** Public report atom: only stable vocabulary and a bounded aggregate count. */
class SystemDoctorFinding(
    val area: SystemDoctorFindingArea,
    val capability: SystemDoctorCapability?,
    val severity: SystemDoctorSeverity,
    val code: SystemDoctorCode,
    val count: Long,
    val bucket: SystemDoctorBucket,
    val repairAction: SystemDoctorRepairAction,
    val required: Boolean,
) {
    init {
        require((area == SystemDoctorFindingArea.CAPABILITY) == (capability != null)) {
            "System Doctor capability binding is invalid."
        }
        require(count in 0L..SystemDoctorLimits.MAX_COUNT) {
            "System Doctor finding count is invalid."
        }
        require(area == SystemDoctorFindingArea.CAPABILITY || !required) {
            "Authorization findings cannot be capability requirements."
        }
    }

    override fun toString(): String =
        "SystemDoctorFinding(area=$area, capability=$capability, severity=$severity, code=$code, count=$count)"
}

class SystemDoctorReport internal constructor(
    val scope: SystemDoctorScope,
    findings: Collection<SystemDoctorFinding>,
    val readiness: SystemDoctorReadiness,
    val requiredProbeCount: Int,
    val healthyRequiredProbeCount: Int,
    val inspectedAt: Long,
) {
    val findings: List<SystemDoctorFinding>
    val status: SystemDoctorSeverity

    init {
        val snapshot = ArrayList(findings)
        require(snapshot.isNotEmpty() && snapshot.size <= SystemDoctorLimits.MAX_FINDINGS) {
            "System Doctor report finding count is invalid."
        }
        require(requiredProbeCount > 0 && healthyRequiredProbeCount in 0..requiredProbeCount) {
            "System Doctor required-probe summary is invalid."
        }
        require((readiness == SystemDoctorReadiness.READY) ==
            (healthyRequiredProbeCount == requiredProbeCount)
        ) { "System Doctor readiness does not match required capability health." }
        require(inspectedAt >= 0L) { "System Doctor inspection time is invalid." }
        this.findings = Collections.unmodifiableList(snapshot)
        status = aggregateSystemDoctorSeverity(snapshot)
    }

    fun count(severity: SystemDoctorSeverity): Long =
        findings.count { finding -> finding.severity == severity }.toLong()

    override fun toString(): String =
        "SystemDoctorReport(scope=$scope, status=$status, readiness=$readiness, findingCount=${findings.size})"
}

/** Trusted caller context; none of its identity values are copied to a report. */
class SystemDoctorRequest constructor(
    requestId: Identifier,
    val scope: SystemDoctorScope,
    tenantId: Identifier?,
    principalId: Identifier,
    principalType: String,
    authorizationRevision: String,
    val requestedAt: Long,
    val deadlineAt: Long,
) {
    val requestId: Identifier = requireSystemDoctorIdentifier(requestId, "System Doctor request id is invalid.")
    val tenantId: Identifier? = tenantId?.let { value ->
        requireSystemDoctorIdentifier(value, "System Doctor tenant id is invalid.")
    }
    val principalId: Identifier = requireSystemDoctorIdentifier(
        principalId,
        "System Doctor principal id is invalid.",
    )
    val principalType: String = requireSystemDoctorCode(
        principalType,
        "System Doctor principal type is invalid.",
    )
    val authorizationRevision: String = requireSystemDoctorToken(
        authorizationRevision,
        "System Doctor authorization revision is invalid.",
    )
    val bindingDigest: String

    init {
        require((scope == SystemDoctorScope.TENANT) == (this.tenantId != null)) {
            "Tenant System Doctor requests require exactly one tenant binding."
        }
        require(requestedAt >= 0L && deadlineAt > requestedAt) {
            "System Doctor request lifetime is invalid."
        }
        require(deadlineAt - requestedAt <= SystemDoctorLimits.MAX_REQUEST_DURATION_MILLIS) {
            "System Doctor request duration exceeds the limit."
        }
        bindingDigest = SystemDoctorDigest("flowweft.system-doctor.request.v1")
            .add(this.requestId.value)
            .add(scope.name)
            .add(this.tenantId?.value ?: "-")
            .add(this.principalId.value)
            .add(this.principalType)
            .add(this.authorizationRevision)
            .add(requestedAt)
            .add(deadlineAt)
            .finish()
    }

    override fun toString(): String = "SystemDoctorRequest(<redacted>)"
}

/** Strongly bound decision supplied by the host's trusted authorization adapter. */
class SystemDoctorAuthorization(
    val allowed: Boolean,
    requestBindingDigest: String,
    val scope: SystemDoctorScope,
    tenantId: Identifier?,
    principalId: Identifier,
    principalType: String,
    authorizationRevision: String,
    val issuedAt: Long,
    val expiresAt: Long,
) {
    val requestBindingDigest: String = requireSystemDoctorDigest(
        requestBindingDigest,
        "System Doctor authorization request binding is invalid.",
    )
    val tenantId: Identifier? = tenantId?.let { value ->
        requireSystemDoctorIdentifier(value, "System Doctor authorization tenant is invalid.")
    }
    val principalId: Identifier = requireSystemDoctorIdentifier(
        principalId,
        "System Doctor authorization principal is invalid.",
    )
    val principalType: String = requireSystemDoctorCode(
        principalType,
        "System Doctor authorization principal type is invalid.",
    )
    val authorizationRevision: String = requireSystemDoctorToken(
        authorizationRevision,
        "System Doctor authorization revision is invalid.",
    )

    init {
        require((scope == SystemDoctorScope.TENANT) == (this.tenantId != null)) {
            "Tenant System Doctor authorization requires exactly one tenant binding."
        }
        require(issuedAt >= 0L && expiresAt > issuedAt) {
            "System Doctor authorization lifetime is invalid."
        }
    }

    fun matches(request: SystemDoctorRequest): Boolean =
        requestBindingDigest == request.bindingDigest && scope == request.scope && tenantId == request.tenantId &&
            principalId == request.principalId && principalType == request.principalType &&
            authorizationRevision == request.authorizationRevision

    fun isCurrent(atTime: Long): Boolean = atTime in issuedAt until expiresAt

    override fun toString(): String =
        "SystemDoctorAuthorization(allowed=$allowed, scope=$scope, <redacted>)"
}

internal object SystemDoctorLimits {
    const val MAX_CODE_POINTS: Int = 256
    const val MAX_REQUIREMENTS: Int = 64
    const val MAX_SIGNALS_PER_PROBE: Int = 64
    const val MAX_FINDINGS: Int = 1024
    const val MAX_COUNT: Long = 1_000_000_000_000L
    const val MAX_REQUEST_DURATION_MILLIS: Long = 60_000L
    const val MAX_PROBE_TIMEOUT_MILLIS: Long = 30_000L
    const val MAX_SNAPSHOT_AGE_MILLIS: Long = 86_400_000L
}

internal fun aggregateSystemDoctorSeverity(findings: Collection<SystemDoctorFinding>): SystemDoctorSeverity = when {
    findings.any { finding -> finding.severity == SystemDoctorSeverity.ERROR } -> SystemDoctorSeverity.ERROR
    findings.any { finding -> finding.severity == SystemDoctorSeverity.UNSUPPORTED } ->
        SystemDoctorSeverity.UNSUPPORTED
    findings.any { finding -> finding.severity == SystemDoctorSeverity.WARNING } -> SystemDoctorSeverity.WARNING
    else -> SystemDoctorSeverity.HEALTHY
}

internal fun requireSystemDoctorIdentifier(identifier: Identifier, message: String): Identifier = identifier.also {
    require(it.value.codePointCount(0, it.value.length) <= SystemDoctorLimits.MAX_CODE_POINTS) { message }
    require(it.value.none(Char::isISOControl)) { message }
}

internal fun requireSystemDoctorCode(value: String, message: String): String = value.also {
    require(it.isNotBlank() && it.codePointCount(0, it.length) <= SystemDoctorLimits.MAX_CODE_POINTS) { message }
    require(it.matches(Regex("[A-Za-z0-9]+(?:[._-][A-Za-z0-9]+)*"))) { message }
}

internal fun requireSystemDoctorToken(value: String, message: String): String = value.also {
    require(it.isNotBlank() && it.codePointCount(0, it.length) <= SystemDoctorLimits.MAX_CODE_POINTS) { message }
    require(it.none(Char::isISOControl)) { message }
}

internal fun requireSystemDoctorDigest(value: String, message: String): String = value.lowercase().also {
    require(it.matches(Regex("[0-9a-f]{64}"))) { message }
}

internal class SystemDoctorDigest(domain: String) {
    private val digest = MessageDigest.getInstance("SHA-256")

    init {
        add(domain)
    }

    fun add(value: String): SystemDoctorDigest {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        digest.update(bytes.size.toString().toByteArray(StandardCharsets.US_ASCII))
        digest.update(':'.code.toByte())
        digest.update(bytes)
        return this
    }

    fun add(value: Long): SystemDoctorDigest = add(value.toString())

    fun add(value: Boolean): SystemDoctorDigest = add(value.toString())

    fun finish(): String = digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
