package ai.icen.fw.agent.evaluation

import ai.icen.fw.agent.api.AgentEvaluationProviderSnapshot
import java.util.Collections

/** Exact, tenant-bound request for one process-local regression run. */
class AgentEvaluationRegressionRun(
    val dataset: AgentEvaluationDatasetReference,
    val providerSnapshot: AgentEvaluationProviderSnapshot,
    val evaluator: AgentEvaluationEvaluatorReference,
    val subject: AgentEvaluationSubjectBinding,
    evidence: Collection<AgentEvaluationEvidenceBatch>,
    val evaluatedAt: Long,
) {
    val evidence: List<AgentEvaluationEvidenceBatch> = immutableEvaluationList(
        evidence,
        AgentEvaluationLimits.MAX_CASES,
        "Agent evaluation run contains too many evidence batches.",
    )
    val requestDigest: String

    init {
        require(this.evidence.map { batch -> batch.context.caseId }.toSet().size == this.evidence.size) {
            "Agent evaluation run contains duplicate case evidence."
        }
        require(evaluatedAt >= 0L) { "Agent evaluation run time is invalid." }
        val digest = AgentEvaluationDigest("flowweft.agent.evaluation.regression-run.v1")
            .add(dataset.bindingDigest)
            .add(providerSnapshot.snapshotDigest)
            .add(evaluator.bindingDigest)
            .add(subject.bindingDigest)
            .add(evaluatedAt)
            .add(this.evidence.size)
        this.evidence.sortedBy { batch -> batch.context.caseId.value }.forEach { batch -> digest.add(batch.evidenceDigest) }
        requestDigest = digest.finish()
    }

    override fun toString(): String = "AgentEvaluationRegressionRun(<redacted>)"
}

class AgentEvaluationRegressionReport internal constructor(
    val dataset: AgentEvaluationDatasetReference,
    providerSnapshotDigest: String,
    val evaluator: AgentEvaluationEvaluatorReference,
    runBindingDigest: String,
    caseScores: Collection<AgentEvaluationCaseScore>,
    val doctor: AgentEvaluationDoctorSnapshot,
    val completedAt: Long,
) {
    val providerSnapshotDigest: String = requireEvaluationDigest(
        providerSnapshotDigest,
        "Agent evaluation report provider digest is invalid.",
    )
    val runBindingDigest: String = requireEvaluationDigest(
        runBindingDigest,
        "Agent evaluation report run digest is invalid.",
    )
    val caseScores: List<AgentEvaluationCaseScore> = Collections.unmodifiableList(
        immutableEvaluationList(
            caseScores,
            AgentEvaluationLimits.MAX_CASES,
            "Agent evaluation report contains too many case scores.",
        ).sortedBy { score -> score.caseId.value },
    )
    val passedCriteria: Int
    val totalCriteria: Int
    val scoreBasisPoints: Int
    val passed: Boolean
    val reportDigest: String

    init {
        require(this.caseScores.map { score -> score.caseId }.toSet().size == this.caseScores.size) {
            "Agent evaluation report case scores must be unique."
        }
        require(completedAt >= 0L && doctor.observedAt == completedAt) {
            "Agent evaluation report time does not match its diagnostic."
        }
        var passedCount = 0
        var totalCount = 0
        this.caseScores.forEach { score ->
            passedCount += score.passedCriteria
            totalCount += score.totalCriteria
        }
        passedCriteria = passedCount
        totalCriteria = totalCount
        scoreBasisPoints = if (totalCriteria == 0) 0 else {
            ((passedCriteria.toLong() * AgentEvaluationLimits.SCORE_SCALE) / totalCriteria).toInt()
        }
        passed = doctor.status == AgentEvaluationDoctorStatus.READY && this.caseScores.isNotEmpty() &&
            this.caseScores.all { score -> score.passed }
        val digest = AgentEvaluationDigest("flowweft.agent.evaluation.regression-report.v1")
            .add(dataset.bindingDigest)
            .add(this.providerSnapshotDigest)
            .add(evaluator.bindingDigest)
            .add(this.runBindingDigest)
            .add(doctor.snapshotDigest)
            .add(completedAt)
            .add(scoreBasisPoints)
            .add(this.caseScores.size)
        this.caseScores.forEach { score -> digest.add(score.scoreDigest) }
        reportDigest = digest.finish()
    }

    override fun toString(): String =
        "AgentEvaluationRegressionReport(passed=$passed, scoreBasisPoints=$scoreBasisPoints, " +
            "caseCount=${caseScores.size}, status=${doctor.status})"
}

/**
 * Minimal provider-neutral runner. It performs no network I/O and grants no authority; a trusted
 * Agent runtime supplies already-bound observations, while this runner only validates and scores them.
 */
class InMemoryAgentEvaluationRunner(
    private val datasets: AgentEvaluationDatasetRegistry,
    private val providers: AgentEvaluationProviderInventory,
    private val evaluators: AgentEvaluationEvaluatorRegistry,
) {
    fun run(request: AgentEvaluationRegressionRun): AgentEvaluationRegressionReport {
        val suite = try {
            datasets.find(request.dataset.suiteId, request.dataset.version)
        } catch (_: Exception) {
            null
        } ?: return failure(
            request,
            AgentEvaluationDoctorStatus.UNAVAILABLE,
            setOf(AgentEvaluationDoctorCode.DATASET_UNAVAILABLE),
        )
        if (!request.dataset.matches(suite) || suite.createdAt > request.evaluatedAt) {
            return failure(
                request,
                AgentEvaluationDoctorStatus.DRIFTED,
                setOf(AgentEvaluationDoctorCode.CONFIGURATION_DRIFT),
            )
        }

        val currentProvider = try {
            providers.current(request.providerSnapshot.providerId)
        } catch (_: Exception) {
            null
        } ?: return failure(
            request,
            AgentEvaluationDoctorStatus.UNAVAILABLE,
            setOf(AgentEvaluationDoctorCode.PROVIDER_UNAVAILABLE),
        )
        if (currentProvider.snapshotDigest != request.providerSnapshot.snapshotDigest ||
            suite.cases.any { case -> !currentProvider.supports(case.capabilityId) }
        ) {
            return failure(
                request,
                AgentEvaluationDoctorStatus.DRIFTED,
                setOf(AgentEvaluationDoctorCode.CONFIGURATION_DRIFT),
            )
        }
        if (!currentProvider.isCurrent(request.evaluatedAt)) {
            return failure(
                request,
                AgentEvaluationDoctorStatus.UNAVAILABLE,
                setOf(AgentEvaluationDoctorCode.PROVIDER_SNAPSHOT_EXPIRED),
            )
        }

        val selectedEvaluator = try {
            evaluators.find(
                request.evaluator.evaluatorId,
                request.evaluator.implementationVersion,
            )
        } catch (_: Exception) {
            null
        } ?: return failure(
            request,
            AgentEvaluationDoctorStatus.UNSUPPORTED,
            setOf(AgentEvaluationDoctorCode.EVALUATOR_UNSUPPORTED),
        )
        val descriptor = try {
            selectedEvaluator.descriptor()
        } catch (_: Exception) {
            return failure(
                request,
                AgentEvaluationDoctorStatus.UNSUPPORTED,
                setOf(AgentEvaluationDoctorCode.EVALUATOR_UNSUPPORTED),
            )
        }
        if (!request.evaluator.matches(descriptor)) {
            return failure(
                request,
                AgentEvaluationDoctorStatus.DRIFTED,
                setOf(AgentEvaluationDoctorCode.CONFIGURATION_DRIFT),
            )
        }
        val requiredCriteria = suite.cases.flatMap(::expectedEvaluationCriteria).toSet()
        if (!descriptor.supports(requiredCriteria)) {
            return failure(
                request,
                AgentEvaluationDoctorStatus.UNSUPPORTED,
                setOf(AgentEvaluationDoctorCode.EVALUATOR_UNSUPPORTED),
            )
        }

        val casesById = suite.cases.associateBy { case -> case.caseId }
        val evidenceByCase = request.evidence.associateBy { batch -> batch.context.caseId }
        try {
            request.evidence.forEach { batch ->
                val case = casesById[batch.context.caseId]
                    ?: throw IllegalArgumentException("Unknown Agent evaluation case evidence.")
                batch.requireMatches(suite, case, request.subject, currentProvider)
            }
        } catch (_: IllegalArgumentException) {
            return failure(
                request,
                AgentEvaluationDoctorStatus.DRIFTED,
                setOf(AgentEvaluationDoctorCode.CONFIGURATION_DRIFT),
            )
        }

        val scores = ArrayList<AgentEvaluationCaseScore>()
        for (case in suite.cases) {
            val scoringRequest = try {
                AgentEvaluationScoringRequest(
                    suite,
                    case,
                    currentProvider,
                    request.subject,
                    evidenceByCase[case.caseId],
                    request.evaluatedAt,
                )
            } catch (_: IllegalArgumentException) {
                return failure(
                    request,
                    AgentEvaluationDoctorStatus.DRIFTED,
                    setOf(AgentEvaluationDoctorCode.CONFIGURATION_DRIFT),
                    scores,
                )
            }
            val score = try {
                selectedEvaluator.evaluate(scoringRequest)
            } catch (_: Exception) {
                return failure(
                    request,
                    AgentEvaluationDoctorStatus.FAILED,
                    setOf(AgentEvaluationDoctorCode.REGRESSION_FAILED),
                    scores,
                )
            }
            try {
                score.requireValidFor(scoringRequest, descriptor)
            } catch (_: Exception) {
                return failure(
                    request,
                    AgentEvaluationDoctorStatus.UNSUPPORTED,
                    setOf(AgentEvaluationDoctorCode.EVALUATOR_UNSUPPORTED),
                    scores,
                )
            }
            scores += score
        }

        val codes = failureCodes(scores)
        val failedCases = scores.count { score -> !score.passed }
        val missingCases = scores.count { score ->
            score.criterionResults.any { result -> result.outcome == AgentEvaluationCriterionOutcome.MISSING }
        }
        val doctor = if (failedCases == 0) {
            AgentEvaluationDoctorSnapshot(
                AgentEvaluationDoctorStatus.READY,
                setOf(AgentEvaluationDoctorCode.READY),
                scores.size,
                0,
                0,
                request.evaluatedAt,
            )
        } else {
            AgentEvaluationDoctorSnapshot(
                AgentEvaluationDoctorStatus.FAILED,
                codes,
                scores.size,
                failedCases,
                missingCases,
                request.evaluatedAt,
            )
        }
        return report(request, scores, doctor)
    }

    private fun failure(
        request: AgentEvaluationRegressionRun,
        status: AgentEvaluationDoctorStatus,
        codes: Set<AgentEvaluationDoctorCode>,
        scores: Collection<AgentEvaluationCaseScore> = emptyList(),
    ): AgentEvaluationRegressionReport {
        val failedCases = scores.count { score -> !score.passed }
        val missingCases = scores.count { score ->
            score.criterionResults.any { result -> result.outcome == AgentEvaluationCriterionOutcome.MISSING }
        }
        return report(
            request,
            scores,
            AgentEvaluationDoctorSnapshot(
                status,
                codes,
                scores.size,
                failedCases,
                missingCases,
                request.evaluatedAt,
            ),
        )
    }

    private fun report(
        request: AgentEvaluationRegressionRun,
        scores: Collection<AgentEvaluationCaseScore>,
        doctor: AgentEvaluationDoctorSnapshot,
    ): AgentEvaluationRegressionReport = AgentEvaluationRegressionReport(
        request.dataset,
        request.providerSnapshot.snapshotDigest,
        request.evaluator,
        request.requestDigest,
        scores,
        doctor,
        request.evaluatedAt,
    )

    private fun failureCodes(scores: Collection<AgentEvaluationCaseScore>): Set<AgentEvaluationDoctorCode> {
        val codes = linkedSetOf(AgentEvaluationDoctorCode.REGRESSION_FAILED)
        scores.flatMap { score -> score.criterionResults }.forEach { result ->
            when (result.code) {
                AgentEvaluationCriterionCode.EVIDENCE_MISSING -> codes += AgentEvaluationDoctorCode.EVIDENCE_MISSING
                AgentEvaluationCriterionCode.RETRIEVAL_MISMATCH -> codes += AgentEvaluationDoctorCode.RETRIEVAL_FAILED
                AgentEvaluationCriterionCode.CITATION_MISMATCH -> codes += AgentEvaluationDoctorCode.CITATION_FAILED
                AgentEvaluationCriterionCode.TOOL_DECISION_MISMATCH -> codes += AgentEvaluationDoctorCode.TOOL_DECISION_FAILED
                AgentEvaluationCriterionCode.SECURITY_REFUSAL_MISMATCH -> {
                    codes += AgentEvaluationDoctorCode.SECURITY_REFUSAL_FAILED
                }
                AgentEvaluationCriterionCode.COST_LIMIT_EXCEEDED -> codes += AgentEvaluationDoctorCode.COST_LIMIT_EXCEEDED
                AgentEvaluationCriterionCode.LATENCY_LIMIT_EXCEEDED -> {
                    codes += AgentEvaluationDoctorCode.LATENCY_LIMIT_EXCEEDED
                }
                AgentEvaluationCriterionCode.PASSED -> Unit
            }
        }
        return Collections.unmodifiableSet(codes)
    }
}
