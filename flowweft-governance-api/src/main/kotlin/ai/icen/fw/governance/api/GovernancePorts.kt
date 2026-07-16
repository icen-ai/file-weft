package ai.icen.fw.governance.api

import java.util.concurrent.CompletionStage

/** Exact request to resolve every applicable hold. Resolver incompleteness must return UNKNOWN. */
class GovernanceLegalHoldResolutionRequest private constructor(
    val context: GovernanceCallContext,
    val resource: GovernanceResourceRef,
    val clock: GovernanceEffectiveClock,
) {
    val requestDigest: String

    init {
        require(context.purpose == GovernancePurpose.RESOLVE_LEGAL_HOLD) {
            "Governance legal-hold resolution requires its exact purpose."
        }
        require(context.authorization.resource == resource) {
            "Governance legal-hold resolution does not match the authorized resource."
        }
        require(clock.observedAtEpochMilli in context.requestedAtEpochMilli..context.deadlineEpochMilli &&
            clock.expiresAtEpochMilli >= context.deadlineEpochMilli) {
            "Governance legal-hold resolution clock is not fresh for the call."
        }
        requestDigest = GovernanceContractSupport.digest("flowweft-governance-api-hold-resolution-request-v1")
            .text(context.contextDigest)
            .text(resource.referenceDigest)
            .text(clock.clockDigest)
            .finish()
    }

    override fun toString(): String = "GovernanceLegalHoldResolutionRequest(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            context: GovernanceCallContext,
            resource: GovernanceResourceRef,
            clock: GovernanceEffectiveClock,
        ): GovernanceLegalHoldResolutionRequest = GovernanceLegalHoldResolutionRequest(context, resource, clock)
    }
}

enum class GovernanceCapability {
    RETENTION_EVALUATION,
    LEGAL_HOLD_RESOLUTION,
    SECURE_DELETION,
    DRY_RUN,
    RECONCILIATION,
    METADATA_TOMBSTONE,
    OUTBOX_TOMBSTONE,
    INDEX_PURGE,
    OBJECT_PURGE,
}

class GovernanceCapabilityRequest private constructor(
    val context: GovernanceCallContext,
    required: Collection<GovernanceCapability>,
) {
    val required: List<GovernanceCapability> = GovernanceContractSupport.immutableList(
        required.toSet().sortedBy { it.name }, GovernanceCapability.values().size,
        "Governance required capabilities are invalid.",
    )
    val requestDigest: String

    init {
        require(context.purpose == GovernancePurpose.DISCOVER_CAPABILITIES) {
            "Governance capability discovery requires its exact purpose."
        }
        require(this.required.size == required.size) { "Governance required capabilities must be unique." }
        val writer = GovernanceContractSupport.digest("flowweft-governance-api-capability-request-v1")
            .text(context.contextDigest)
            .integer(this.required.size)
        this.required.forEach { capability -> writer.text(capability.name) }
        requestDigest = writer.finish()
    }

    override fun toString(): String = "GovernanceCapabilityRequest(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            context: GovernanceCallContext,
            required: Collection<GovernanceCapability>,
        ): GovernanceCapabilityRequest = GovernanceCapabilityRequest(context, required)
    }
}

/** Versioned implementation capability snapshot; absent capabilities are unsupported. */
class GovernanceCapabilitySnapshot private constructor(
    implementationId: String,
    implementationRevision: String,
    supported: Collection<GovernanceCapability>,
    val maximumHoldsPerResolution: Int,
    val maximumDeletionSteps: Int,
    val observedAtEpochMilli: Long,
    val expiresAtEpochMilli: Long,
) {
    val implementationId: String = GovernanceContractSupport.requireMachineCode(
        implementationId, "Governance capability implementation is invalid.",
    )
    val implementationRevision: String = GovernanceContractSupport.requireText(
        implementationRevision, GovernanceContractSupport.MAX_REVISION_UTF8_BYTES,
        "Governance capability implementation revision is invalid.",
    )
    val supported: List<GovernanceCapability> = GovernanceContractSupport.immutableList(
        supported.toSet().sortedBy { it.name }, GovernanceCapability.values().size,
        "Governance supported capabilities are invalid.",
    )
    val capabilityDigest: String

    init {
        require(this.supported.size == supported.size) { "Governance supported capabilities must be unique." }
        require(maximumHoldsPerResolution in 1..GovernanceContractSupport.MAX_HOLDS &&
            maximumDeletionSteps in GovernanceDeletionPlan.REQUIRED_STAGE_ORDER.size..GovernanceContractSupport.MAX_STEPS) {
            "Governance capability limits are invalid."
        }
        require(observedAtEpochMilli >= 0L && expiresAtEpochMilli > observedAtEpochMilli) {
            "Governance capability validity window is invalid."
        }
        val writer = GovernanceContractSupport.digest("flowweft-governance-api-capability-snapshot-v1")
            .text(this.implementationId)
            .text(this.implementationRevision)
            .integer(maximumHoldsPerResolution)
            .integer(maximumDeletionSteps)
            .longValue(observedAtEpochMilli)
            .longValue(expiresAtEpochMilli)
            .integer(this.supported.size)
        this.supported.forEach { capability -> writer.text(capability.name) }
        capabilityDigest = writer.finish()
    }

    fun supports(capability: GovernanceCapability): Boolean = supported.contains(capability)

    override fun toString(): String = "GovernanceCapabilitySnapshot(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            implementationId: String,
            implementationRevision: String,
            supported: Collection<GovernanceCapability>,
            maximumHoldsPerResolution: Int,
            maximumDeletionSteps: Int,
            observedAtEpochMilli: Long,
            expiresAtEpochMilli: Long,
        ): GovernanceCapabilitySnapshot = GovernanceCapabilitySnapshot(
            implementationId,
            implementationRevision,
            supported,
            maximumHoldsPerResolution,
            maximumDeletionSteps,
            observedAtEpochMilli,
            expiresAtEpochMilli,
        )
    }
}

class GovernanceCapabilityStatus private constructor(code: String) {
    val code: String = GovernanceContractSupport.requireMachineCode(
        code, "Governance capability status is invalid.",
    )

    override fun equals(other: Any?): Boolean =
        this === other || other is GovernanceCapabilityStatus && code == other.code

    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "GovernanceCapabilityStatus(<redacted>)"

    companion object {
        @JvmField val AVAILABLE = GovernanceCapabilityStatus("available")
        @JvmField val UNSUPPORTED = GovernanceCapabilityStatus("unsupported")
        @JvmField val UNAVAILABLE = GovernanceCapabilityStatus("unavailable")
    }
}

class GovernanceCapabilityResult private constructor(
    request: GovernanceCapabilityRequest,
    val status: GovernanceCapabilityStatus,
    val snapshot: GovernanceCapabilitySnapshot?,
    val failure: GovernanceFailure?,
    val observedAtEpochMilli: Long,
) {
    val requestDigest: String = request.requestDigest
    val resultDigest: String

    init {
        require(observedAtEpochMilli in request.context.requestedAtEpochMilli..request.context.deadlineEpochMilli) {
            "Governance capability observation is outside its call window."
        }
        when (status) {
            GovernanceCapabilityStatus.AVAILABLE -> require(snapshot != null && failure == null &&
                request.required.all { snapshot.supports(it) }) {
                "Available governance capabilities must satisfy the exact required set."
            }
            GovernanceCapabilityStatus.UNSUPPORTED -> require(
                snapshot == null && failure?.classification == GovernanceFailureClass.UNSUPPORTED,
            ) { "Unsupported governance capability requires explicit unsupported failure." }
            else -> require(snapshot == null && failure != null) {
                "Unavailable governance capability requires failure metadata."
            }
        }
        resultDigest = GovernanceContractSupport.digest("flowweft-governance-api-capability-result-v1")
            .text(request.context.contextDigest)
            .text(request.requestDigest)
            .text(status.code)
            .optionalText(snapshot?.capabilityDigest)
            .optionalText(failure?.failureDigest)
            .longValue(observedAtEpochMilli)
            .finish()
    }

    override fun toString(): String = "GovernanceCapabilityResult(<redacted>)"

    companion object {
        @JvmStatic
        fun available(
            request: GovernanceCapabilityRequest,
            snapshot: GovernanceCapabilitySnapshot,
            observedAtEpochMilli: Long,
        ): GovernanceCapabilityResult = GovernanceCapabilityResult(
            request, GovernanceCapabilityStatus.AVAILABLE, snapshot, null, observedAtEpochMilli,
        )

        @JvmStatic
        fun failure(
            request: GovernanceCapabilityRequest,
            status: GovernanceCapabilityStatus,
            failure: GovernanceFailure,
            observedAtEpochMilli: Long,
        ): GovernanceCapabilityResult {
            require(status != GovernanceCapabilityStatus.AVAILABLE) {
                "Governance capability failure cannot use available status."
            }
            return GovernanceCapabilityResult(request, status, null, failure, observedAtEpochMilli)
        }
    }
}

enum class GovernanceDoctorMode {
    CONFIGURATION,
    CONNECTIVITY,
    CONSISTENCY,
}

class GovernanceDoctorRequest private constructor(
    val context: GovernanceCallContext,
    val mode: GovernanceDoctorMode,
) {
    val requestDigest: String

    init {
        require(context.purpose == GovernancePurpose.INSPECT_DOCTOR) {
            "Governance Doctor requires its exact purpose."
        }
        requestDigest = GovernanceContractSupport.digest("flowweft-governance-api-doctor-request-v1")
            .text(context.contextDigest)
            .text(mode.name)
            .finish()
    }

    override fun toString(): String = "GovernanceDoctorRequest(<redacted>)"

    companion object {
        @JvmStatic
        fun of(context: GovernanceCallContext, mode: GovernanceDoctorMode): GovernanceDoctorRequest =
            GovernanceDoctorRequest(context, mode)
    }
}

enum class GovernanceDoctorSeverity {
    INFO,
    WARNING,
    ERROR,
}

class GovernanceDoctorFinding private constructor(
    code: String,
    val severity: GovernanceDoctorSeverity,
    val count: Long,
) {
    val code: String = GovernanceContractSupport.requireMachineCode(
        code, "Governance Doctor finding code is invalid.",
    )
    val findingDigest: String

    init {
        require(count >= 0L) { "Governance Doctor finding count is invalid." }
        findingDigest = GovernanceContractSupport.digest("flowweft-governance-api-doctor-finding-v1")
            .text(this.code)
            .text(severity.name)
            .longValue(count)
            .finish()
    }

    override fun toString(): String = "GovernanceDoctorFinding(<redacted>)"

    companion object {
        @JvmStatic
        fun of(code: String, severity: GovernanceDoctorSeverity, count: Long): GovernanceDoctorFinding =
            GovernanceDoctorFinding(code, severity, count)
    }
}

class GovernanceDoctorStatus private constructor(code: String) {
    val code: String = GovernanceContractSupport.requireMachineCode(
        code, "Governance Doctor status is invalid.",
    )

    override fun equals(other: Any?): Boolean =
        this === other || other is GovernanceDoctorStatus && code == other.code

    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "GovernanceDoctorStatus(<redacted>)"

    companion object {
        @JvmField val READY = GovernanceDoctorStatus("ready")
        @JvmField val DEGRADED = GovernanceDoctorStatus("degraded")
        @JvmField val NOT_READY = GovernanceDoctorStatus("not-ready")
        @JvmField val UNSUPPORTED = GovernanceDoctorStatus("unsupported")
    }
}

class GovernanceDoctorResult private constructor(
    request: GovernanceDoctorRequest,
    val status: GovernanceDoctorStatus,
    findings: Collection<GovernanceDoctorFinding>,
    val observedAtEpochMilli: Long,
    val expiresAtEpochMilli: Long,
) {
    val findings: List<GovernanceDoctorFinding> = GovernanceContractSupport.immutableList(
        findings, GovernanceContractSupport.MAX_FINDINGS, "Governance Doctor findings are invalid.",
    )
    val requestDigest: String = request.requestDigest
    val resultDigest: String

    init {
        require(this.findings.isNotEmpty()) { "Governance Doctor requires at least one finding." }
        require(observedAtEpochMilli in request.context.requestedAtEpochMilli..request.context.deadlineEpochMilli &&
            expiresAtEpochMilli > observedAtEpochMilli) {
            "Governance Doctor observation window is invalid."
        }
        when (status) {
            GovernanceDoctorStatus.READY -> require(
                this.findings.none { it.severity == GovernanceDoctorSeverity.ERROR },
            ) { "Ready governance Doctor result cannot contain error findings." }
            GovernanceDoctorStatus.DEGRADED -> require(
                this.findings.any { it.severity == GovernanceDoctorSeverity.WARNING } &&
                    this.findings.none { it.severity == GovernanceDoctorSeverity.ERROR },
            ) { "Degraded governance Doctor result requires warnings without errors." }
            GovernanceDoctorStatus.NOT_READY -> require(
                this.findings.any { it.severity == GovernanceDoctorSeverity.ERROR },
            ) { "Not-ready governance Doctor result requires an error finding." }
        }
        val writer = GovernanceContractSupport.digest("flowweft-governance-api-doctor-result-v1")
            .text(request.context.contextDigest)
            .text(request.requestDigest)
            .text(status.code)
            .longValue(observedAtEpochMilli)
            .longValue(expiresAtEpochMilli)
            .integer(this.findings.size)
        this.findings.forEach { finding -> writer.text(finding.findingDigest) }
        resultDigest = writer.finish()
    }

    override fun toString(): String = "GovernanceDoctorResult(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            request: GovernanceDoctorRequest,
            status: GovernanceDoctorStatus,
            findings: Collection<GovernanceDoctorFinding>,
            observedAtEpochMilli: Long,
            expiresAtEpochMilli: Long,
        ): GovernanceDoctorResult = GovernanceDoctorResult(
            request, status, findings, observedAtEpochMilli, expiresAtEpochMilli,
        )
    }
}

fun interface GovernanceLegalHoldResolver {
    fun resolve(request: GovernanceLegalHoldResolutionRequest): CompletionStage<GovernanceLegalHoldResolution>
}

/** Pure deterministic evaluator; implementations must not read ambient time or perform I/O. */
fun interface GovernanceRetentionEvaluator {
    fun evaluate(request: GovernanceRetentionEvaluationRequest): GovernanceRetentionAssessment
}

/** Executes exactly one next step outside unrelated business transactions. */
fun interface GovernanceDeletionStepExecutor {
    fun execute(request: GovernanceDeletionExecutionRequest): CompletionStage<GovernanceDeletionStepReceipt>
}

/** Read-only recovery path for an exact outcome-unknown receipt. */
fun interface GovernanceDeletionReconciler {
    fun reconcile(request: GovernanceDeletionReconciliationRequest): CompletionStage<GovernanceDeletionStepReceipt>
}

fun interface GovernanceCapabilityProvider {
    fun capabilities(request: GovernanceCapabilityRequest): CompletionStage<GovernanceCapabilityResult>
}

/** Diagnostics must not create holds, release holds, mutate catalog state, or execute deletion. */
fun interface GovernanceDoctor {
    fun inspect(request: GovernanceDoctorRequest): CompletionStage<GovernanceDoctorResult>
}
