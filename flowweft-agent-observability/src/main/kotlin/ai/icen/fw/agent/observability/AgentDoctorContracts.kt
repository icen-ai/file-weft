package ai.icen.fw.agent.observability

import ai.icen.fw.core.id.Identifier
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.Collections

enum class AgentDoctorScope {
    TENANT,
    SYSTEM,
}

enum class AgentDoctorStatus {
    HEALTHY,
    WARNING,
    UNSUPPORTED,
    ERROR,
}

enum class AgentDoctorCategory {
    AUTHORIZATION,
    PROVIDER,
    DURABLE_RUN,
    EVALUATION_RUN,
}

enum class AgentProviderKind {
    MODEL,
    RETRIEVAL,
    TOOL,
    REMOTE_PROTOCOL,
}

/** A deliberately small, content-free vocabulary suitable for metrics and operator APIs. */
enum class AgentDoctorBucket {
    AVAILABLE,
    UNAVAILABLE,
    NOT_CONFIGURED,
    MISSING,
    MATCHED,
    DRIFTED,
    WITHIN_LIMIT,
    ABOVE_LIMIT,
    CURRENT,
    STALE,
    COMPLETE,
    TRUNCATED,
    UNKNOWN,
}

/** Stable actions are rendered by the host; no free-form repair text crosses this boundary. */
enum class AgentDoctorRepairAction {
    NONE,
    GRANT_DIAGNOSTIC_PERMISSION,
    REFRESH_AUTHORIZATION,
    CONFIGURE_PROVIDER_INVENTORY,
    CONFIGURE_PROVIDER_PROBE,
    RESTORE_PROVIDER,
    ALIGN_PROVIDER_DESCRIPTOR,
    ALIGN_PROVIDER_CAPABILITIES,
    ALIGN_PROVIDER_CONFIGURATION,
    CONFIGURE_DURABLE_PROBE,
    SCALE_DURABLE_WORKERS,
    RECOVER_EXPIRED_LEASES,
    RECONCILE_UNKNOWN_OUTCOMES,
    INSPECT_FAILED_RUNS,
    INSPECT_CANCELLED_RUNS,
    INSPECT_EVALUATION_REGRESSIONS,
    REVIEW_RETRIEVAL_PROVIDER,
    REVIEW_CITATION_POLICY,
    REVIEW_TOOL_POLICY,
    REVIEW_REFUSAL_POLICY,
    CONFIGURE_COST_OBSERVATION,
    CONFIGURE_LATENCY_OBSERVATION,
    REDUCE_COST,
    REDUCE_LATENCY,
    RETRY_DIAGNOSTIC,
}

/** Closed stable code vocabulary. Probe implementations can never inject report text. */
enum class AgentDoctorCode(val value: String) {
    AUTHORIZATION_DENIED("agent.doctor.authorization.denied"),
    AUTHORIZATION_UNAVAILABLE("agent.doctor.authorization.unavailable"),
    AUTHORIZATION_MISMATCH("agent.doctor.authorization.mismatch"),
    AUTHORIZATION_EXPIRED("agent.doctor.authorization.expired"),
    REQUEST_EXPIRED("agent.doctor.request.expired"),
    PROVIDER_INVENTORY_CHECKED("agent.doctor.provider.inventory.checked"),
    PROVIDER_INVENTORY_UNSUPPORTED("agent.doctor.provider.inventory.unsupported"),
    PROVIDER_INVENTORY_UNAVAILABLE("agent.doctor.provider.inventory.unavailable"),
    PROVIDER_INVENTORY_TRUNCATED("agent.doctor.provider.inventory.truncated"),
    PROVIDER_PROBE_MISSING("agent.doctor.provider.probe.missing"),
    PROVIDER_PROBE_UNSUPPORTED("agent.doctor.provider.probe.unsupported"),
    PROVIDER_PROBE_UNAVAILABLE("agent.doctor.provider.probe.unavailable"),
    PROVIDER_PROBE_FAILED("agent.doctor.provider.probe.failed"),
    PROVIDER_PROBE_MISMATCH("agent.doctor.provider.probe.mismatch"),
    PROVIDER_AVAILABLE("agent.doctor.provider.available"),
    PROVIDER_DESCRIPTOR_DRIFT("agent.doctor.provider.descriptor.drift"),
    PROVIDER_CAPABILITY_DRIFT("agent.doctor.provider.capability.drift"),
    PROVIDER_CONFIGURATION_DRIFT("agent.doctor.provider.configuration.drift"),
    DIAGNOSTIC_DEADLINE_EXCEEDED("agent.doctor.deadline.exceeded"),
    DURABLE_PROBE_MISSING("agent.doctor.durable.probe.missing"),
    DURABLE_PROBE_FAILED("agent.doctor.durable.probe.failed"),
    DURABLE_PROBE_MISMATCH("agent.doctor.durable.probe.mismatch"),
    DURABLE_SNAPSHOT_STALE("agent.doctor.durable.snapshot.stale"),
    DURABLE_SNAPSHOT_TRUNCATED("agent.doctor.durable.snapshot.truncated"),
    DURABLE_BACKLOG_WITHIN_LIMIT("agent.doctor.durable.backlog.within-limit"),
    DURABLE_BACKLOG_HIGH("agent.doctor.durable.backlog.high"),
    DURABLE_RUNNING("agent.doctor.durable.running"),
    DURABLE_EXPIRED_LEASE("agent.doctor.durable.lease.expired"),
    DURABLE_OUTCOME_UNKNOWN("agent.doctor.durable.outcome.unknown"),
    DURABLE_RECONCILIATION_PENDING("agent.doctor.durable.reconciliation.pending"),
    DURABLE_FAILED("agent.doctor.durable.failed"),
    DURABLE_CANCELLED("agent.doctor.durable.cancelled"),
    DURABLE_EXPIRED("agent.doctor.durable.expired"),
    EVALUATION_REGRESSION_WITHIN_LIMIT("agent.doctor.evaluation.regression.within-limit"),
    EVALUATION_REGRESSION_FAILED("agent.doctor.evaluation.regression.failed"),
    EVALUATION_RETRIEVAL_FAILED("agent.doctor.evaluation.retrieval.failed"),
    EVALUATION_CITATION_FAILED("agent.doctor.evaluation.citation.failed"),
    EVALUATION_TOOL_FAILED("agent.doctor.evaluation.tool.failed"),
    EVALUATION_REFUSAL_FAILED("agent.doctor.evaluation.refusal.failed"),
    EVALUATION_OBSERVATION_UNKNOWN("agent.doctor.evaluation.observation.unknown"),
    COST_WITHIN_LIMIT("agent.doctor.cost.within-limit"),
    COST_LIMIT_EXCEEDED("agent.doctor.cost.limit-exceeded"),
    COST_OBSERVATION_UNKNOWN("agent.doctor.cost.observation.unknown"),
    LATENCY_WITHIN_LIMIT("agent.doctor.latency.within-limit"),
    LATENCY_LIMIT_EXCEEDED("agent.doctor.latency.limit-exceeded"),
    LATENCY_OBSERVATION_UNKNOWN("agent.doctor.latency.observation.unknown"),
    ;

    override fun toString(): String = value
}

/** Public output contains only stable vocabulary, a bounded count and a stable repair action. */
class AgentDoctorFinding(
    val category: AgentDoctorCategory,
    val status: AgentDoctorStatus,
    val code: AgentDoctorCode,
    val count: Long,
    val bucket: AgentDoctorBucket,
    val repairAction: AgentDoctorRepairAction,
    val providerKind: AgentProviderKind? = null,
) {
    init {
        require(count in 0L..AgentDoctorLimits.MAX_COUNT) { "Agent Doctor finding count is invalid." }
        require(category == AgentDoctorCategory.PROVIDER || providerKind == null) {
            "Only provider findings may identify a provider kind."
        }
    }

    override fun toString(): String =
        "AgentDoctorFinding(category=$category, status=$status, code=$code, count=$count, bucket=$bucket)"
}

class AgentDoctorReport private constructor(findings: Collection<AgentDoctorFinding>) {
    val findings: List<AgentDoctorFinding>
    val status: AgentDoctorStatus

    init {
        val snapshot = ArrayList(findings)
        require(snapshot.isNotEmpty()) { "Agent Doctor report requires at least one finding." }
        require(snapshot.size <= AgentDoctorLimits.MAX_FINDINGS) { "Agent Doctor report contains too many findings." }
        this.findings = Collections.unmodifiableList(snapshot)
        status = aggregateDoctorStatus(snapshot)
    }

    fun count(status: AgentDoctorStatus): Long = findings.count { finding -> finding.status == status }.toLong()

    override fun toString(): String = "AgentDoctorReport(status=$status, findingCount=${findings.size})"

    companion object {
        @JvmStatic
        fun of(findings: Collection<AgentDoctorFinding>): AgentDoctorReport = AgentDoctorReport(findings)
    }
}

/** Trusted caller context. Its identities are never copied into [AgentDoctorReport]. */
class AgentDoctorRequest constructor(
    requestId: Identifier,
    val scope: AgentDoctorScope,
    tenantId: Identifier?,
    principalId: Identifier,
    principalType: String,
    authorizationRevision: String,
    val requestedAt: Long,
    val deadlineAt: Long,
) {
    val requestId: Identifier = requireDoctorIdentifier(requestId, "Agent Doctor request identifier is invalid.")
    val tenantId: Identifier? = tenantId?.let {
        requireDoctorIdentifier(it, "Agent Doctor tenant identifier is invalid.")
    }
    val principalId: Identifier = requireDoctorIdentifier(principalId, "Agent Doctor principal identifier is invalid.")
    val principalType: String = requireDoctorCode(principalType, "Agent Doctor principal type is invalid.")
    val authorizationRevision: String = requireDoctorToken(
        authorizationRevision,
        "Agent Doctor authorization revision is invalid.",
    )
    val bindingDigest: String

    init {
        require((scope == AgentDoctorScope.TENANT) == (this.tenantId != null)) {
            "Tenant Agent Doctor requests require exactly one tenant binding."
        }
        require(requestedAt >= 0L && deadlineAt > requestedAt) { "Agent Doctor request lifetime is invalid." }
        require(deadlineAt - requestedAt <= AgentDoctorLimits.MAX_REQUEST_DURATION_MILLIS) {
            "Agent Doctor request duration exceeds the contract limit."
        }
        bindingDigest = AgentDoctorDigest("flowweft.agent.doctor.request.v1")
            .add(this.requestId.value)
            .add(scope.name)
            .add(this.tenantId?.value ?: "-")
            .add(this.principalType)
            .add(this.principalId.value)
            .add(this.authorizationRevision)
            .add(requestedAt)
            .add(deadlineAt)
            .finish()
    }

    override fun toString(): String = "AgentDoctorRequest(<redacted>)"
}

/** A strongly bound authorization decision returned by a trusted host adapter. */
class AgentDoctorAuthorization @JvmOverloads constructor(
    val allowed: Boolean,
    requestBindingDigest: String,
    val scope: AgentDoctorScope,
    tenantId: Identifier?,
    principalId: Identifier,
    principalType: String,
    authorizationRevision: String,
    val issuedAt: Long,
    val expiresAt: Long,
    val maximumProviderProbes: Int = AgentDoctorLimits.MAX_PROVIDER_PROBES,
) {
    val requestBindingDigest: String = requireDoctorDigest(
        requestBindingDigest,
        "Agent Doctor authorization request binding is invalid.",
    )
    val tenantId: Identifier? = tenantId?.let {
        requireDoctorIdentifier(it, "Agent Doctor authorization tenant is invalid.")
    }
    val principalId: Identifier = requireDoctorIdentifier(principalId, "Agent Doctor authorization principal is invalid.")
    val principalType: String = requireDoctorCode(principalType, "Agent Doctor authorization principal type is invalid.")
    val authorizationRevision: String = requireDoctorToken(
        authorizationRevision,
        "Agent Doctor authorization revision is invalid.",
    )

    init {
        require((scope == AgentDoctorScope.TENANT) == (this.tenantId != null)) {
            "Tenant Agent Doctor authorization requires exactly one tenant binding."
        }
        require(issuedAt >= 0L && expiresAt > issuedAt) { "Agent Doctor authorization lifetime is invalid." }
        require(maximumProviderProbes in 1..AgentDoctorLimits.MAX_PROVIDER_PROBES) {
            "Agent Doctor provider-probe authorization limit is invalid."
        }
    }

    fun matches(request: AgentDoctorRequest): Boolean =
        requestBindingDigest == request.bindingDigest && scope == request.scope && tenantId == request.tenantId &&
            principalId == request.principalId &&
            principalType == request.principalType && authorizationRevision == request.authorizationRevision

    fun isCurrent(atTime: Long): Boolean = atTime in issuedAt until expiresAt

    override fun toString(): String = "AgentDoctorAuthorization(allowed=$allowed, scope=$scope, <redacted>)"
}

/** Probe context is emitted only after authorization has succeeded. */
class AgentDoctorProbeRequest internal constructor(
    val requestId: Identifier,
    val scope: AgentDoctorScope,
    val tenantId: Identifier?,
    val principalId: Identifier,
    val principalType: String,
    val authorizationRevision: String,
    val requestedAt: Long,
    val deadlineAt: Long,
    val requestBindingDigest: String,
) {
    override fun toString(): String = "AgentDoctorProbeRequest(<redacted>)"

    companion object {
        internal fun from(request: AgentDoctorRequest): AgentDoctorProbeRequest = AgentDoctorProbeRequest(
            request.requestId,
            request.scope,
            request.tenantId,
            request.principalId,
            request.principalType,
            request.authorizationRevision,
            request.requestedAt,
            request.deadlineAt,
            request.bindingDigest,
        )
    }
}

internal object AgentDoctorLimits {
    const val MAX_PROVIDER_PROBES: Int = 128
    const val MAX_FINDINGS: Int = 256
    const val MAX_CODE_POINTS: Int = 256
    const val MAX_REQUEST_DURATION_MILLIS: Long = 60_000L
    const val MAX_COUNT: Long = 1_000_000_000_000L
}

internal fun aggregateDoctorStatus(findings: Collection<AgentDoctorFinding>): AgentDoctorStatus = when {
    findings.any { finding -> finding.status == AgentDoctorStatus.ERROR } -> AgentDoctorStatus.ERROR
    findings.any { finding -> finding.status == AgentDoctorStatus.UNSUPPORTED } -> AgentDoctorStatus.UNSUPPORTED
    findings.any { finding -> finding.status == AgentDoctorStatus.WARNING } -> AgentDoctorStatus.WARNING
    else -> AgentDoctorStatus.HEALTHY
}

internal fun requireDoctorIdentifier(identifier: Identifier, message: String): Identifier = identifier.also {
    require(it.value.codePointCount(0, it.value.length) <= AgentDoctorLimits.MAX_CODE_POINTS) { message }
    require(it.value.none(Char::isISOControl)) { message }
}

internal fun requireDoctorCode(value: String, message: String): String = value.also {
    require(it.isNotBlank() && it.codePointCount(0, it.length) <= AgentDoctorLimits.MAX_CODE_POINTS) { message }
    require(it.matches(Regex("[A-Za-z0-9]+(?:[._-][A-Za-z0-9]+)*"))) { message }
}

internal fun requireDoctorToken(value: String, message: String): String = value.also {
    require(it.isNotBlank() && it.codePointCount(0, it.length) <= AgentDoctorLimits.MAX_CODE_POINTS) { message }
    require(it.none(Char::isISOControl)) { message }
}

internal fun requireDoctorDigest(value: String, message: String): String = value.lowercase().also {
    require(it.matches(Regex("[0-9a-f]{64}"))) { message }
}

internal class AgentDoctorDigest(domain: String) {
    private val digest = MessageDigest.getInstance("SHA-256")

    init {
        add(domain)
    }

    fun add(value: String): AgentDoctorDigest {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        digest.update(bytes.size.toString().toByteArray(StandardCharsets.US_ASCII))
        digest.update(':'.code.toByte())
        digest.update(bytes)
        return this
    }

    fun add(value: Long): AgentDoctorDigest = add(value.toString())

    fun finish(): String = digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
