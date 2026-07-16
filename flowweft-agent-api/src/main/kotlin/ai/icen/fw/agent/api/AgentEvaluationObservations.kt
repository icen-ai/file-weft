package ai.icen.fw.agent.api

import ai.icen.fw.core.id.Identifier

/** Trusted execution binding shared by every safe evaluation observation. */
class AgentEvaluationObservationContext(
    suiteId: Identifier,
    suiteDigest: String,
    caseId: Identifier,
    caseDigest: String,
    tenantId: Identifier,
    principalId: Identifier,
    principalType: String,
    authorizationRevision: String,
    providerSnapshotDigest: String,
    val observedAt: Long,
) {
    val suiteId: Identifier = requireOpaqueIdentifier(suiteId, "Agent evaluation observation suite is invalid.")
    val suiteDigest: String = requireSha256(suiteDigest, "Agent evaluation observation suite digest is invalid.")
    val caseId: Identifier = requireOpaqueIdentifier(caseId, "Agent evaluation observation case is invalid.")
    val caseDigest: String = requireSha256(caseDigest, "Agent evaluation observation case digest is invalid.")
    val tenantId: Identifier = requireOpaqueIdentifier(tenantId, "Agent evaluation observation tenant is invalid.")
    val principalId: Identifier = requireOpaqueIdentifier(principalId, "Agent evaluation observation principal is invalid.")
    val principalType: String = requireAgentCode(principalType, "Agent evaluation principal type is invalid.")
    val authorizationRevision: String = requireAgentToken(
        authorizationRevision,
        AgentContractLimits.MAX_ID_CODE_POINTS,
        "Agent evaluation authorization revision is invalid.",
    )
    val providerSnapshotDigest: String = requireSha256(
        providerSnapshotDigest,
        "Agent evaluation provider snapshot digest is invalid.",
    )
    val bindingDigest: String

    init {
        requireNonNegativeTime(observedAt, "Agent evaluation observation time must not be negative.")
        bindingDigest = AgentDigestBuilder("flowweft.agent.evaluation.observation-context.v1")
            .add(this.suiteId.value)
            .add(this.suiteDigest)
            .add(this.caseId.value)
            .add(this.caseDigest)
            .add(this.tenantId.value)
            .add(this.principalType)
            .add(this.principalId.value)
            .add(this.authorizationRevision)
            .add(this.providerSnapshotDigest)
            .add(observedAt)
            .finish()
    }

    fun requireMatches(suite: AgentEvaluationSuite, case: AgentEvaluationCase) {
        require(suiteId == suite.suiteId && suiteDigest == suite.suiteDigest) {
            "Agent evaluation observation does not match its suite."
        }
        require(caseId == case.caseId && caseDigest == case.bindingDigest && suite.cases.any { it.caseId == caseId }) {
            "Agent evaluation observation does not match a case in its suite."
        }
    }

    override fun toString(): String = "AgentEvaluationObservationContext(<redacted>)"
}

enum class AgentEvaluationObservationKind {
    RETRIEVAL,
    CITATION,
    TOOL_DECISION,
    REFUSAL,
    COST,
    LATENCY,
}

interface AgentEvaluationObservation {
    fun context(): AgentEvaluationObservationContext

    fun kind(): AgentEvaluationObservationKind

    fun bindingDigest(): String
}

class AgentEvaluationRetrievalObservation(
    private val observationContext: AgentEvaluationObservationContext,
    evidenceSetDigest: String,
    val relevantEvidenceCount: Int,
    val missingRequiredEvidenceCount: Int,
    val unauthorizedEvidenceCount: Int,
    val securityFilterReceiptValid: Boolean,
) : AgentEvaluationObservation {
    val evidenceSetDigest: String = requireSha256(
        evidenceSetDigest,
        "Agent evaluation retrieval evidence-set digest is invalid.",
    )
    private val digest: String

    init {
        require(relevantEvidenceCount >= 0 && missingRequiredEvidenceCount >= 0 && unauthorizedEvidenceCount >= 0) {
            "Agent evaluation retrieval counts must not be negative."
        }
        digest = AgentDigestBuilder("flowweft.agent.evaluation.retrieval-observation.v1")
            .add(observationContext.bindingDigest)
            .add(this.evidenceSetDigest)
            .add(relevantEvidenceCount)
            .add(missingRequiredEvidenceCount)
            .add(unauthorizedEvidenceCount)
            .add(securityFilterReceiptValid)
            .finish()
    }

    fun satisfies(expectation: AgentEvaluationRetrievalExpectation): Boolean =
        relevantEvidenceCount >= expectation.minimumRelevantEvidence &&
            missingRequiredEvidenceCount <= expectation.maximumMissingRequiredEvidence &&
            unauthorizedEvidenceCount == 0 &&
            (!expectation.requireSecurityFilterReceipt || securityFilterReceiptValid)

    override fun context(): AgentEvaluationObservationContext = observationContext
    override fun kind(): AgentEvaluationObservationKind = AgentEvaluationObservationKind.RETRIEVAL
    override fun bindingDigest(): String = digest
}

class AgentEvaluationCitationObservation(
    private val observationContext: AgentEvaluationObservationContext,
    citationSetDigest: String,
    val citationCount: Int,
    val validCitationCount: Int,
    val missingRequiredEvidenceCount: Int,
    val unsupportedClaimCount: Int,
    val foreignTenantCitationCount: Int,
) : AgentEvaluationObservation {
    val citationSetDigest: String = requireSha256(
        citationSetDigest,
        "Agent evaluation citation-set digest is invalid.",
    )
    private val digest: String

    init {
        require(citationCount >= 0 && validCitationCount in 0..citationCount &&
            missingRequiredEvidenceCount >= 0 && unsupportedClaimCount >= 0 && foreignTenantCitationCount >= 0
        ) { "Agent evaluation citation counts are invalid." }
        digest = AgentDigestBuilder("flowweft.agent.evaluation.citation-observation.v1")
            .add(observationContext.bindingDigest)
            .add(this.citationSetDigest)
            .add(citationCount)
            .add(validCitationCount)
            .add(missingRequiredEvidenceCount)
            .add(unsupportedClaimCount)
            .add(foreignTenantCitationCount)
            .finish()
    }

    fun satisfies(expectation: AgentEvaluationCitationExpectation): Boolean =
        validCitationCount >= expectation.minimumValidCitations &&
            missingRequiredEvidenceCount == 0 &&
            unsupportedClaimCount <= expectation.maximumUnsupportedClaims &&
            foreignTenantCitationCount == 0

    override fun context(): AgentEvaluationObservationContext = observationContext
    override fun kind(): AgentEvaluationObservationKind = AgentEvaluationObservationKind.CITATION
    override fun bindingDigest(): String = digest
}

class AgentEvaluationToolDecisionObservation @JvmOverloads constructor(
    private val observationContext: AgentEvaluationObservationContext,
    val decision: AgentEvaluationToolDecision,
    val providerId: ProviderId? = null,
    val toolId: ToolId? = null,
    argumentsDigest: String? = null,
    val authorizationFresh: Boolean = false,
    val approvalBindingValid: Boolean = false,
) : AgentEvaluationObservation {
    val argumentsDigest: String? = argumentsDigest?.let { value ->
        requireSha256(value, "Agent evaluation observed tool arguments digest is invalid.")
    }
    private val digest: String

    init {
        val selectedTool = decision == AgentEvaluationToolDecision.INVOKE ||
            decision == AgentEvaluationToolDecision.REQUIRE_APPROVAL
        require(!selectedTool || providerId != null && toolId != null) {
            "An observed Agent tool decision requires provider and tool identifiers."
        }
        require(selectedTool || providerId == null && toolId == null && this.argumentsDigest == null) {
            "An observed Agent SKIP decision cannot identify a tool or arguments."
        }
        digest = AgentDigestBuilder("flowweft.agent.evaluation.tool-observation.v1")
            .add(observationContext.bindingDigest)
            .add(decision.name)
            .add(providerId?.value ?: "-")
            .add(toolId?.value ?: "-")
            .add(this.argumentsDigest ?: "-")
            .add(authorizationFresh)
            .add(approvalBindingValid)
            .finish()
    }

    fun satisfies(expectation: AgentEvaluationToolExpectation): Boolean =
        decision == expectation.decision && providerId == expectation.providerId && toolId == expectation.toolId &&
            (expectation.argumentsDigest == null || argumentsDigest == expectation.argumentsDigest) &&
            (decision == AgentEvaluationToolDecision.SKIP || authorizationFresh) &&
            (decision != AgentEvaluationToolDecision.REQUIRE_APPROVAL || approvalBindingValid)

    override fun context(): AgentEvaluationObservationContext = observationContext
    override fun kind(): AgentEvaluationObservationKind = AgentEvaluationObservationKind.TOOL_DECISION
    override fun bindingDigest(): String = digest
}

class AgentEvaluationRefusalObservation @JvmOverloads constructor(
    private val observationContext: AgentEvaluationObservationContext,
    val refused: Boolean,
    reasonCode: String? = null,
) : AgentEvaluationObservation {
    val reasonCode: String? = reasonCode?.let { value ->
        requireAgentCode(value, "Agent evaluation refusal reason code is invalid.")
    }
    private val digest: String

    init {
        require(refused || this.reasonCode == null) { "A non-refusal cannot carry a refusal reason." }
        digest = AgentDigestBuilder("flowweft.agent.evaluation.refusal-observation.v1")
            .add(observationContext.bindingDigest)
            .add(refused)
            .add(this.reasonCode ?: "-")
            .finish()
    }

    fun satisfies(expectation: AgentEvaluationRefusalExpectation): Boolean = when (expectation) {
        AgentEvaluationRefusalExpectation.MUST_ANSWER -> !refused
        AgentEvaluationRefusalExpectation.MUST_REFUSE -> refused
        AgentEvaluationRefusalExpectation.NOT_APPLICABLE -> true
    }

    override fun context(): AgentEvaluationObservationContext = observationContext
    override fun kind(): AgentEvaluationObservationKind = AgentEvaluationObservationKind.REFUSAL
    override fun bindingDigest(): String = digest
}

class AgentEvaluationCostObservation(
    private val observationContext: AgentEvaluationObservationContext,
    val reservedCostMicros: Long,
    val actualCostMicros: Long,
) : AgentEvaluationObservation {
    private val digest: String

    init {
        require(reservedCostMicros >= 0L && actualCostMicros >= 0L) {
            "Agent evaluation costs must not be negative."
        }
        digest = AgentDigestBuilder("flowweft.agent.evaluation.cost-observation.v1")
            .add(observationContext.bindingDigest)
            .add(reservedCostMicros)
            .add(actualCostMicros)
            .finish()
    }

    fun exceeded(): Boolean = actualCostMicros > reservedCostMicros

    override fun context(): AgentEvaluationObservationContext = observationContext
    override fun kind(): AgentEvaluationObservationKind = AgentEvaluationObservationKind.COST
    override fun bindingDigest(): String = digest
}

class AgentEvaluationLatencyObservation(
    private val observationContext: AgentEvaluationObservationContext,
    val startedAt: Long,
    val completedAt: Long,
    val maximumLatencyMillis: Long,
) : AgentEvaluationObservation {
    val latencyMillis: Long
    private val digest: String

    init {
        requireNonNegativeTime(startedAt, "Agent evaluation start time must not be negative.")
        require(completedAt >= startedAt) { "Agent evaluation completion time must not precede its start." }
        require(maximumLatencyMillis > 0L) { "Agent evaluation latency limit must be positive." }
        latencyMillis = completedAt - startedAt
        digest = AgentDigestBuilder("flowweft.agent.evaluation.latency-observation.v1")
            .add(observationContext.bindingDigest)
            .add(startedAt)
            .add(completedAt)
            .add(maximumLatencyMillis)
            .finish()
    }

    fun exceeded(): Boolean = latencyMillis > maximumLatencyMillis

    override fun context(): AgentEvaluationObservationContext = observationContext
    override fun kind(): AgentEvaluationObservationKind = AgentEvaluationObservationKind.LATENCY
    override fun bindingDigest(): String = digest
}

enum class AgentEvaluationDiagnosticStatus {
    READY,
    UNAVAILABLE,
    DEGRADED,
    DRIFTED,
    EXPIRED,
    BUDGET_EXCEEDED,
    FAILED,
}

/** Extensible, safe diagnostic reason. Raw provider failures and evaluated content are forbidden. */
class AgentEvaluationDiagnosticReason(value: String) {
    val value: String = requireAgentCode(value, "Agent evaluation diagnostic reason is invalid.")

    override fun equals(other: Any?): Boolean = other is AgentEvaluationDiagnosticReason && value == other.value
    override fun hashCode(): Int = value.hashCode()
    override fun toString(): String = value

    companion object {
        @JvmField val PROVIDER_UNAVAILABLE = AgentEvaluationDiagnosticReason("provider.unavailable")
        @JvmField val CAPABILITY_UNAVAILABLE = AgentEvaluationDiagnosticReason("capability.unavailable")
        @JvmField val SNAPSHOT_DRIFT = AgentEvaluationDiagnosticReason("snapshot.drift")
        @JvmField val SNAPSHOT_EXPIRED = AgentEvaluationDiagnosticReason("snapshot.expired")
        @JvmField val COST_BUDGET_EXCEEDED = AgentEvaluationDiagnosticReason("budget.cost-exceeded")
        @JvmField val LATENCY_BUDGET_EXCEEDED = AgentEvaluationDiagnosticReason("budget.latency-exceeded")
        @JvmField val EVALUATION_FAILED = AgentEvaluationDiagnosticReason("evaluation.failed")
    }
}

class AgentEvaluationDiagnostic @JvmOverloads constructor(
    val status: AgentEvaluationDiagnosticStatus,
    val reason: AgentEvaluationDiagnosticReason? = null,
    val providerId: ProviderId? = null,
    val capabilityId: AgentCapabilityId? = null,
    snapshotDigest: String? = null,
    val observedAt: Long,
) {
    val snapshotDigest: String? = snapshotDigest?.let { value ->
        requireSha256(value, "Agent evaluation diagnostic snapshot digest is invalid.")
    }

    init {
        requireNonNegativeTime(observedAt, "Agent evaluation diagnostic time must not be negative.")
        require(status != AgentEvaluationDiagnosticStatus.READY || reason == null) {
            "A ready Agent evaluation diagnostic cannot carry a failure reason."
        }
        require(status == AgentEvaluationDiagnosticStatus.READY || reason != null) {
            "A non-ready Agent evaluation diagnostic requires a safe reason."
        }
    }

    override fun toString(): String = "AgentEvaluationDiagnostic(status=$status, reason=${reason ?: "none"})"
}
