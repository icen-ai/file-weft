package ai.icen.fw.reliability.api

enum class ReliabilityDoctorMode { CONFIGURATION, CONNECTIVITY, CONSISTENCY, RECOVERABILITY }

class ReliabilityDoctorRequest private constructor(
    val context: ReliabilityCallContext,
    val mode: ReliabilityDoctorMode,
) {
    val requestDigest: String

    init {
        require(context.purpose == ReliabilityPurpose.INSPECT_DOCTOR &&
            context.action == ReliabilityAction.INSPECT_DOCTOR
        ) { "Reliability Doctor requires its exact purpose and action." }
        requestDigest = ReliabilityContractSupport.digest("flowweft-reliability-api-doctor-request-v1")
            .text(context.contextDigest)
            .text(mode.name)
            .finish()
    }

    override fun toString(): String = "ReliabilityDoctorRequest(<redacted>)"

    companion object {
        @JvmStatic
        fun of(context: ReliabilityCallContext, mode: ReliabilityDoctorMode): ReliabilityDoctorRequest =
            ReliabilityDoctorRequest(context, mode)
    }
}

enum class ReliabilityDoctorSeverity { INFO, WARNING, ERROR }
enum class ReliabilityDoctorStatus { READY, DEGRADED, NOT_READY, UNSUPPORTED }

/** Open but bounded machine code; implementations must use a finite documented vocabulary. */
class ReliabilityDoctorFindingCode private constructor(code: String) {
    val code: String = ReliabilityContractSupport.code(code, "Reliability Doctor finding code is invalid.")
    override fun equals(other: Any?): Boolean =
        this === other || other is ReliabilityDoctorFindingCode && code == other.code
    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = code

    companion object {
        @JvmField val CONFIGURATION_READY = ReliabilityDoctorFindingCode("configuration-ready")
        @JvmField val PROVIDER_UNREACHABLE = ReliabilityDoctorFindingCode("provider-unreachable")
        @JvmField val CAPABILITY_MISSING = ReliabilityDoctorFindingCode("capability-missing")
        @JvmField val MANIFEST_INTEGRITY_FAILED = ReliabilityDoctorFindingCode("manifest-integrity-failed")
        @JvmField val RECOVERY_OBJECTIVE_AT_RISK = ReliabilityDoctorFindingCode("recovery-objective-at-risk")
        @JvmField val DRILL_OVERDUE = ReliabilityDoctorFindingCode("drill-overdue")

        @JvmStatic fun of(code: String): ReliabilityDoctorFindingCode = ReliabilityDoctorFindingCode(code)
    }
}

/** Categorical, value-free evidence: no counts, endpoint, path, tenant, resource, or raw message. */
class ReliabilityDoctorFinding private constructor(
    val code: ReliabilityDoctorFindingCode,
    val severity: ReliabilityDoctorSeverity,
    val componentClass: ReliabilityMetricComponentClass,
) {
    val findingDigest: String = ReliabilityContractSupport.digest("flowweft-reliability-api-doctor-finding-v1")
        .text(code.code)
        .text(severity.name)
        .text(componentClass.name)
        .finish()

    override fun toString(): String = "ReliabilityDoctorFinding(code=$code, severity=$severity)"

    companion object {
        @JvmStatic
        fun of(
            code: ReliabilityDoctorFindingCode,
            severity: ReliabilityDoctorSeverity,
            componentClass: ReliabilityMetricComponentClass,
        ): ReliabilityDoctorFinding = ReliabilityDoctorFinding(code, severity, componentClass)
    }
}

class ReliabilityDoctorReport private constructor(
    request: ReliabilityDoctorRequest,
    providerId: String,
    providerRevision: String,
    val status: ReliabilityDoctorStatus,
    findings: Collection<ReliabilityDoctorFinding>,
    val observedAtEpochMilli: Long,
    val expiresAtEpochMilli: Long,
) {
    val requestDigest: String = request.requestDigest
    val providerId: String = ReliabilityContractSupport.code(providerId, "Reliability Doctor provider is invalid.")
    val providerRevision: String = ReliabilityContractSupport.text(
        providerRevision, ReliabilityContractSupport.MAX_REVISION_BYTES, "Reliability provider revision is invalid.",
    )
    val findings: List<ReliabilityDoctorFinding> = ReliabilityContractSupport.immutable(
        findings,
        MAX_FINDINGS,
        "Reliability Doctor findings are invalid.",
    )
    val reportDigest: String

    init {
        require(this.findings.isNotEmpty()) { "Reliability Doctor report requires categorical evidence." }
        require(observedAtEpochMilli >= request.context.requestedAtEpochMilli &&
            observedAtEpochMilli < request.context.deadlineEpochMilli &&
            expiresAtEpochMilli > observedAtEpochMilli
        ) { "Reliability Doctor evidence lifetime is invalid." }
        when (status) {
            ReliabilityDoctorStatus.READY -> require(
                this.findings.all { it.severity == ReliabilityDoctorSeverity.INFO },
            ) { "Ready Reliability Doctor report may contain informational findings only." }
            ReliabilityDoctorStatus.DEGRADED -> require(
                this.findings.any { it.severity == ReliabilityDoctorSeverity.WARNING } &&
                    this.findings.none { it.severity == ReliabilityDoctorSeverity.ERROR },
            ) { "Degraded Reliability Doctor report requires warnings without errors." }
            ReliabilityDoctorStatus.NOT_READY -> require(
                this.findings.any { it.severity == ReliabilityDoctorSeverity.ERROR },
            ) { "Not-ready Reliability Doctor report requires an error finding." }
            ReliabilityDoctorStatus.UNSUPPORTED -> require(
                this.findings.any { it.code == ReliabilityDoctorFindingCode.CAPABILITY_MISSING },
            ) { "Unsupported Reliability Doctor report requires a capability finding." }
        }
        val writer = ReliabilityContractSupport.digest("flowweft-reliability-api-doctor-report-v1")
            .text(request.requestDigest)
            .text(this.providerId)
            .text(this.providerRevision)
            .text(status.name)
            .longValue(observedAtEpochMilli)
            .longValue(expiresAtEpochMilli)
            .integer(this.findings.size)
        this.findings.forEach { writer.text(it.findingDigest) }
        reportDigest = writer.finish()
    }

    override fun toString(): String = "ReliabilityDoctorReport(status=$status, findings=${findings.size})"

    companion object {
        const val MAX_FINDINGS: Int = 64

        @JvmStatic
        fun of(
            request: ReliabilityDoctorRequest,
            providerId: String,
            providerRevision: String,
            status: ReliabilityDoctorStatus,
            findings: Collection<ReliabilityDoctorFinding>,
            observedAtEpochMilli: Long,
            expiresAtEpochMilli: Long,
        ): ReliabilityDoctorReport = ReliabilityDoctorReport(
            request,
            providerId,
            providerRevision,
            status,
            findings,
            observedAtEpochMilli,
            expiresAtEpochMilli,
        )
    }
}

/** Closed, deliberately coarse dimensions are the only dimensions a metric sink may label. */
enum class ReliabilityMetricComponentClass { DATABASE, OBJECT_STORAGE, SEARCH_INDEX, OTHER }
enum class ReliabilityMetricCode {
    OPERATION_RESULT,
    SLO_EVALUATION_RESULT,
    BURN_RATE_ALERT,
    DOCTOR_RESULT,
}
enum class ReliabilityMetricOutcome {
    SUCCESS,
    FAILURE,
    OUTCOME_UNKNOWN,
    SLO_SATISFIED,
    SLO_BREACHED,
    DATA_UNAVAILABLE,
    ALERT_NONE,
    ALERT_WARNING,
    ALERT_CRITICAL,
    READY,
    DEGRADED,
    NOT_READY,
    UNSUPPORTED,
}

/**
 * Value-free metric event. It intentionally cannot carry a tenant/resource/provider identifier,
 * digest, numeric measurement, error text, URL, or payload. Sinks aggregate only these finite enums.
 */
class ReliabilityMetricEvidence private constructor(
    val metric: ReliabilityMetricCode,
    val outcome: ReliabilityMetricOutcome,
    val operation: ReliabilityOperationKind?,
    val componentClass: ReliabilityMetricComponentClass?,
    val observedAtEpochMilli: Long,
) {
    init {
        require(observedAtEpochMilli >= 0L) { "Reliability metric observation time is invalid." }
        require((metric == ReliabilityMetricCode.OPERATION_RESULT) == (operation != null)) {
            "Reliability operation metric must carry exactly one coarse operation kind."
        }
        require(metric != ReliabilityMetricCode.OPERATION_RESULT || outcome in OPERATION_OUTCOMES) {
            "Reliability operation metric outcome is invalid."
        }
        require(metric != ReliabilityMetricCode.SLO_EVALUATION_RESULT || outcome in SLO_OUTCOMES) {
            "Reliability SLO metric outcome is invalid."
        }
        require(metric != ReliabilityMetricCode.BURN_RATE_ALERT || outcome in ALERT_OUTCOMES) {
            "Reliability alert metric outcome is invalid."
        }
        require(metric != ReliabilityMetricCode.DOCTOR_RESULT || outcome in DOCTOR_OUTCOMES) {
            "Reliability Doctor metric outcome is invalid."
        }
    }

    override fun toString(): String =
        "ReliabilityMetricEvidence(metric=$metric, outcome=$outcome, operation=$operation)"

    companion object {
        private val OPERATION_OUTCOMES = setOf(
            ReliabilityMetricOutcome.SUCCESS,
            ReliabilityMetricOutcome.FAILURE,
            ReliabilityMetricOutcome.OUTCOME_UNKNOWN,
        )
        private val SLO_OUTCOMES = setOf(
            ReliabilityMetricOutcome.SLO_SATISFIED,
            ReliabilityMetricOutcome.SLO_BREACHED,
            ReliabilityMetricOutcome.DATA_UNAVAILABLE,
        )
        private val ALERT_OUTCOMES = setOf(
            ReliabilityMetricOutcome.ALERT_NONE,
            ReliabilityMetricOutcome.ALERT_WARNING,
            ReliabilityMetricOutcome.ALERT_CRITICAL,
        )
        private val DOCTOR_OUTCOMES = setOf(
            ReliabilityMetricOutcome.READY,
            ReliabilityMetricOutcome.DEGRADED,
            ReliabilityMetricOutcome.NOT_READY,
            ReliabilityMetricOutcome.UNSUPPORTED,
        )

        @JvmStatic
        fun of(
            metric: ReliabilityMetricCode,
            outcome: ReliabilityMetricOutcome,
            operation: ReliabilityOperationKind?,
            componentClass: ReliabilityMetricComponentClass?,
            observedAtEpochMilli: Long,
        ): ReliabilityMetricEvidence = ReliabilityMetricEvidence(
            metric, outcome, operation, componentClass, observedAtEpochMilli,
        )
    }
}

fun interface ReliabilityMetricSink {
    /** Implementations must never add ambient tenant/resource/provider/request identifiers as labels. */
    fun observe(evidence: ReliabilityMetricEvidence)

    companion object {
        @JvmField val NOOP: ReliabilityMetricSink = ReliabilityMetricSink { }
    }
}
