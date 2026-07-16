package ai.icen.fw.agent.evaluation

import ai.icen.fw.agent.api.AgentEvaluationCase
import ai.icen.fw.agent.api.AgentEvaluationCitationObservation
import ai.icen.fw.agent.api.AgentEvaluationCostObservation
import ai.icen.fw.agent.api.AgentEvaluationLatencyObservation
import ai.icen.fw.agent.api.AgentEvaluationProviderSnapshot
import ai.icen.fw.agent.api.AgentEvaluationRefusalExpectation
import ai.icen.fw.agent.api.AgentEvaluationRefusalObservation
import ai.icen.fw.agent.api.AgentEvaluationRetrievalObservation
import ai.icen.fw.agent.api.AgentEvaluationSuite
import ai.icen.fw.agent.api.AgentEvaluationToolDecisionObservation
import ai.icen.fw.agent.api.ProviderId
import ai.icen.fw.core.id.Identifier

enum class AgentEvaluationCriterion {
    RETRIEVAL,
    CITATION,
    TOOL_DECISION,
    SECURITY_REFUSAL,
    COST,
    LATENCY,
}

enum class AgentEvaluationCriterionOutcome {
    PASS,
    FAIL,
    MISSING,
}

/** Closed, content-free result vocabulary suitable for storage, metrics and operator APIs. */
enum class AgentEvaluationCriterionCode(val value: String) {
    PASSED("evaluation.criterion.passed"),
    EVIDENCE_MISSING("evaluation.evidence.missing"),
    RETRIEVAL_MISMATCH("evaluation.retrieval.mismatch"),
    CITATION_MISMATCH("evaluation.citation.mismatch"),
    TOOL_DECISION_MISMATCH("evaluation.tool-decision.mismatch"),
    SECURITY_REFUSAL_MISMATCH("evaluation.security-refusal.mismatch"),
    COST_LIMIT_EXCEEDED("evaluation.cost.limit-exceeded"),
    LATENCY_LIMIT_EXCEEDED("evaluation.latency.limit-exceeded"),
    ;

    override fun toString(): String = value
}

/** Immutable identity and supported assertion set for one deterministic scoring implementation. */
class AgentEvaluationEvaluatorDescriptor(
    val evaluatorId: ProviderId,
    implementationVersion: String,
    supportedCriteria: Collection<AgentEvaluationCriterion>,
    configurationDigest: String,
) {
    val implementationVersion: String = requireEvaluationToken(
        implementationVersion,
        "Agent evaluation evaluator version is invalid.",
    )
    val supportedCriteria: Set<AgentEvaluationCriterion> = immutableEvaluationSet(
        supportedCriteria,
        AgentEvaluationCriterion.values().size,
        "Agent evaluation evaluator supports too many criteria.",
    )
    val configurationDigest: String = requireEvaluationDigest(
        configurationDigest,
        "Agent evaluation evaluator configuration digest is invalid.",
    )
    val bindingDigest: String

    init {
        require(this.supportedCriteria.isNotEmpty()) { "Agent evaluation evaluator requires a supported criterion." }
        val digest = AgentEvaluationDigest("flowweft.agent.evaluation.evaluator-descriptor.v1")
            .add(evaluatorId.value)
            .add(this.implementationVersion)
            .add(this.configurationDigest)
            .add(this.supportedCriteria.size)
        this.supportedCriteria.sortedBy { criterion -> criterion.name }.forEach { criterion -> digest.add(criterion.name) }
        bindingDigest = digest.finish()
    }

    fun supports(criteria: Collection<AgentEvaluationCriterion>): Boolean = supportedCriteria.containsAll(criteria)

    override fun toString(): String =
        "AgentEvaluationEvaluatorDescriptor(evaluatorId=$evaluatorId, version=$implementationVersion)"
}

/** Exact evaluator revision selected by an evaluation run. */
class AgentEvaluationEvaluatorReference(
    val evaluatorId: ProviderId,
    implementationVersion: String,
    descriptorBindingDigest: String,
) {
    val implementationVersion: String = requireEvaluationToken(
        implementationVersion,
        "Agent evaluation evaluator reference version is invalid.",
    )
    val descriptorBindingDigest: String = requireEvaluationDigest(
        descriptorBindingDigest,
        "Agent evaluation evaluator reference digest is invalid.",
    )
    val bindingDigest: String = AgentEvaluationDigest("flowweft.agent.evaluation.evaluator-reference.v1")
        .add(evaluatorId.value)
        .add(this.implementationVersion)
        .add(this.descriptorBindingDigest)
        .finish()

    fun matches(descriptor: AgentEvaluationEvaluatorDescriptor): Boolean =
        evaluatorId == descriptor.evaluatorId && implementationVersion == descriptor.implementationVersion &&
            descriptorBindingDigest == descriptor.bindingDigest

    override fun equals(other: Any?): Boolean = other is AgentEvaluationEvaluatorReference && bindingDigest == other.bindingDigest
    override fun hashCode(): Int = bindingDigest.hashCode()
    override fun toString(): String = "AgentEvaluationEvaluatorReference(evaluatorId=$evaluatorId, version=$implementationVersion)"

    companion object {
        @JvmStatic
        fun from(descriptor: AgentEvaluationEvaluatorDescriptor): AgentEvaluationEvaluatorReference =
            AgentEvaluationEvaluatorReference(
                descriptor.evaluatorId,
                descriptor.implementationVersion,
                descriptor.bindingDigest,
            )
    }
}

/** Safe scoring request. No prompt, output text, provider credential or tool argument crosses this boundary. */
class AgentEvaluationScoringRequest(
    val suite: AgentEvaluationSuite,
    val case: AgentEvaluationCase,
    val providerSnapshot: AgentEvaluationProviderSnapshot,
    val subject: AgentEvaluationSubjectBinding,
    val evidence: AgentEvaluationEvidenceBatch?,
    val evaluatedAt: Long,
) {
    val requestDigest: String

    init {
        require(suite.cases.any { candidate ->
            candidate.caseId == case.caseId && candidate.bindingDigest == case.bindingDigest
        }) { "Agent evaluation scoring case does not belong to its fixed dataset." }
        require(providerSnapshot.supports(case.capabilityId)) {
            "Agent evaluation provider snapshot does not support the case capability."
        }
        require(providerSnapshot.isCurrent(evaluatedAt)) {
            "Agent evaluation provider snapshot is not current at scoring time."
        }
        require(evaluatedAt >= (evidence?.completedAt ?: 0L)) {
            "Agent evaluation scoring time precedes its evidence."
        }
        evidence?.requireMatches(suite, case, subject, providerSnapshot)
        requestDigest = AgentEvaluationDigest("flowweft.agent.evaluation.scoring-request.v1")
            .add(suite.suiteDigest)
            .add(case.bindingDigest)
            .add(providerSnapshot.snapshotDigest)
            .add(subject.bindingDigest)
            .add(evidence?.evidenceDigest ?: "-")
            .add(evaluatedAt)
            .finish()
    }

    override fun toString(): String = "AgentEvaluationScoringRequest(<redacted>)"
}

class AgentEvaluationCriterionResult(
    val criterion: AgentEvaluationCriterion,
    val outcome: AgentEvaluationCriterionOutcome,
    val code: AgentEvaluationCriterionCode,
    observationDigest: String?,
) {
    val observationDigest: String? = observationDigest?.let { digest ->
        requireEvaluationDigest(digest, "Agent evaluation criterion observation digest is invalid.")
    }
    val resultDigest: String

    init {
        require(outcome != AgentEvaluationCriterionOutcome.PASS ||
            code == AgentEvaluationCriterionCode.PASSED && this.observationDigest != null
        ) { "A passing Agent evaluation criterion requires observed evidence." }
        require(outcome != AgentEvaluationCriterionOutcome.MISSING ||
            code == AgentEvaluationCriterionCode.EVIDENCE_MISSING && this.observationDigest == null
        ) { "A missing Agent evaluation criterion requires the missing-evidence code." }
        require(outcome != AgentEvaluationCriterionOutcome.FAIL ||
            code != AgentEvaluationCriterionCode.PASSED && code != AgentEvaluationCriterionCode.EVIDENCE_MISSING &&
            this.observationDigest != null
        ) { "A failed Agent evaluation criterion requires observed failure evidence." }
        resultDigest = AgentEvaluationDigest("flowweft.agent.evaluation.criterion-result.v1")
            .add(criterion.name)
            .add(outcome.name)
            .add(code.value)
            .add(this.observationDigest ?: "-")
            .finish()
    }

    override fun toString(): String =
        "AgentEvaluationCriterionResult(criterion=$criterion, outcome=$outcome, code=$code)"
}

/** Integer scoring avoids floating-point and locale-dependent result drift. */
class AgentEvaluationCaseScore(
    caseId: Identifier,
    caseDigest: String,
    evaluatorBindingDigest: String,
    scoringRequestDigest: String,
    criterionResults: Collection<AgentEvaluationCriterionResult>,
    val completedAt: Long,
) {
    val caseId: Identifier = requireEvaluationIdentifier(caseId, "Agent evaluation score case is invalid.")
    val caseDigest: String = requireEvaluationDigest(caseDigest, "Agent evaluation score case digest is invalid.")
    val evaluatorBindingDigest: String = requireEvaluationDigest(
        evaluatorBindingDigest,
        "Agent evaluation score evaluator digest is invalid.",
    )
    val scoringRequestDigest: String = requireEvaluationDigest(
        scoringRequestDigest,
        "Agent evaluation score request digest is invalid.",
    )
    val criterionResults: List<AgentEvaluationCriterionResult>
    val passedCriteria: Int
    val totalCriteria: Int
    val scoreBasisPoints: Int
    val passed: Boolean
    val scoreDigest: String

    init {
        val snapshot = immutableEvaluationList(
            criterionResults,
            AgentEvaluationCriterion.values().size,
            "Agent evaluation score contains too many criteria.",
        )
        require(snapshot.isNotEmpty()) { "Agent evaluation score requires a criterion result." }
        require(snapshot.map { result -> result.criterion }.toSet().size == snapshot.size) {
            "Agent evaluation score criterion results must be unique."
        }
        require(completedAt >= 0L) { "Agent evaluation score completion time is invalid." }
        this.criterionResults = java.util.Collections.unmodifiableList(snapshot.sortedBy { result -> result.criterion.ordinal })
        passedCriteria = this.criterionResults.count { result -> result.outcome == AgentEvaluationCriterionOutcome.PASS }
        totalCriteria = this.criterionResults.size
        scoreBasisPoints = ((passedCriteria.toLong() * AgentEvaluationLimits.SCORE_SCALE) / totalCriteria).toInt()
        passed = passedCriteria == totalCriteria
        val digest = AgentEvaluationDigest("flowweft.agent.evaluation.case-score.v1")
            .add(this.caseId.value)
            .add(this.caseDigest)
            .add(this.evaluatorBindingDigest)
            .add(this.scoringRequestDigest)
            .add(completedAt)
            .add(scoreBasisPoints)
            .add(this.criterionResults.size)
        this.criterionResults.forEach { result -> digest.add(result.resultDigest) }
        scoreDigest = digest.finish()
    }

    fun requireValidFor(request: AgentEvaluationScoringRequest, descriptor: AgentEvaluationEvaluatorDescriptor) {
        require(caseId == request.case.caseId && caseDigest == request.case.bindingDigest) {
            "Agent evaluation score does not match its fixed case."
        }
        require(evaluatorBindingDigest == descriptor.bindingDigest && scoringRequestDigest == request.requestDigest &&
            descriptor.supports(expectedEvaluationCriteria(request.case))
        ) { "Agent evaluation score does not match a supported evaluator revision." }
        require(completedAt == request.evaluatedAt) { "Agent evaluation score is not deterministic for its request time." }
        val expected = expectedEvaluationCriteria(request.case).map { criterion ->
            canonicalCriterionResult(request, criterion)
        }
        require(criterionResults.size == expected.size && criterionResults.zip(expected).all { pair ->
            pair.first.criterion == pair.second.criterion && pair.first.outcome == pair.second.outcome &&
                pair.first.code == pair.second.code && pair.first.observationDigest == pair.second.observationDigest
        }) { "Agent evaluation score does not match its fail-closed evidence semantics." }
    }

    override fun toString(): String =
        "AgentEvaluationCaseScore(passed=$passed, scoreBasisPoints=$scoreBasisPoints, <redacted>)"
}

interface AgentEvaluationCaseEvaluator {
    /** Local immutable metadata only; descriptor access must not perform I/O. */
    fun descriptor(): AgentEvaluationEvaluatorDescriptor

    /** Implementations score only the bounded digest/reference evidence in [request]. */
    fun evaluate(request: AgentEvaluationScoringRequest): AgentEvaluationCaseScore
}

internal fun expectedEvaluationCriteria(case: AgentEvaluationCase): List<AgentEvaluationCriterion> {
    val result = ArrayList<AgentEvaluationCriterion>()
    if (case.expected.retrieval != null) result += AgentEvaluationCriterion.RETRIEVAL
    if (case.expected.citations != null) result += AgentEvaluationCriterion.CITATION
    if (case.expected.tool != null) result += AgentEvaluationCriterion.TOOL_DECISION
    if (case.expected.refusal != AgentEvaluationRefusalExpectation.NOT_APPLICABLE) {
        result += AgentEvaluationCriterion.SECURITY_REFUSAL
    }
    if (case.expected.maximumCostMicros != null) result += AgentEvaluationCriterion.COST
    if (case.expected.maximumLatencyMillis != null) result += AgentEvaluationCriterion.LATENCY
    return result
}

internal fun canonicalCriterionResult(
    request: AgentEvaluationScoringRequest,
    criterion: AgentEvaluationCriterion,
): AgentEvaluationCriterionResult {
    val observations = request.evidence?.observations.orEmpty()
    return when (criterion) {
        AgentEvaluationCriterion.RETRIEVAL -> criterionResult(
            criterion,
            observations.filterIsInstance<AgentEvaluationRetrievalObservation>().singleOrNull(),
            AgentEvaluationCriterionCode.RETRIEVAL_MISMATCH,
        ) { observation -> observation.satisfies(requireNotNull(request.case.expected.retrieval)) }
        AgentEvaluationCriterion.CITATION -> criterionResult(
            criterion,
            observations.filterIsInstance<AgentEvaluationCitationObservation>().singleOrNull(),
            AgentEvaluationCriterionCode.CITATION_MISMATCH,
        ) { observation -> observation.satisfies(requireNotNull(request.case.expected.citations)) }
        AgentEvaluationCriterion.TOOL_DECISION -> criterionResult(
            criterion,
            observations.filterIsInstance<AgentEvaluationToolDecisionObservation>().singleOrNull(),
            AgentEvaluationCriterionCode.TOOL_DECISION_MISMATCH,
        ) { observation -> observation.satisfies(requireNotNull(request.case.expected.tool)) }
        AgentEvaluationCriterion.SECURITY_REFUSAL -> criterionResult(
            criterion,
            observations.filterIsInstance<AgentEvaluationRefusalObservation>().singleOrNull(),
            AgentEvaluationCriterionCode.SECURITY_REFUSAL_MISMATCH,
        ) { observation -> observation.satisfies(request.case.expected.refusal) }
        AgentEvaluationCriterion.COST -> criterionResult(
            criterion,
            observations.filterIsInstance<AgentEvaluationCostObservation>().singleOrNull(),
            AgentEvaluationCriterionCode.COST_LIMIT_EXCEEDED,
        ) { observation -> observation.actualCostMicros <= requireNotNull(request.case.expected.maximumCostMicros) }
        AgentEvaluationCriterion.LATENCY -> criterionResult(
            criterion,
            observations.filterIsInstance<AgentEvaluationLatencyObservation>().singleOrNull(),
            AgentEvaluationCriterionCode.LATENCY_LIMIT_EXCEEDED,
        ) { observation -> observation.latencyMillis <= requireNotNull(request.case.expected.maximumLatencyMillis) }
    }
}

private fun <T : ai.icen.fw.agent.api.AgentEvaluationObservation> criterionResult(
    criterion: AgentEvaluationCriterion,
    observation: T?,
    failureCode: AgentEvaluationCriterionCode,
    predicate: (T) -> Boolean,
): AgentEvaluationCriterionResult {
    if (observation == null) {
        return AgentEvaluationCriterionResult(
            criterion,
            AgentEvaluationCriterionOutcome.MISSING,
            AgentEvaluationCriterionCode.EVIDENCE_MISSING,
            null,
        )
    }
    val passed = predicate(observation)
    return AgentEvaluationCriterionResult(
        criterion,
        if (passed) AgentEvaluationCriterionOutcome.PASS else AgentEvaluationCriterionOutcome.FAIL,
        if (passed) AgentEvaluationCriterionCode.PASSED else failureCode,
        observation.bindingDigest(),
    )
}
