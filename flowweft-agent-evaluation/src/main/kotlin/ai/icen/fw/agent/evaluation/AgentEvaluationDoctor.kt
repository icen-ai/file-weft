package ai.icen.fw.agent.evaluation

enum class AgentEvaluationDoctorStatus {
    READY,
    UNAVAILABLE,
    DRIFTED,
    UNSUPPORTED,
    FAILED,
}

/** Stable, content-free codes. Raw provider/evaluator failures must never become doctor output. */
enum class AgentEvaluationDoctorCode(val value: String) {
    READY("agent.evaluation.ready"),
    DATASET_UNAVAILABLE("agent.evaluation.dataset.unavailable"),
    PROVIDER_UNAVAILABLE("agent.evaluation.provider.unavailable"),
    PROVIDER_SNAPSHOT_EXPIRED("agent.evaluation.provider.snapshot-expired"),
    CONFIGURATION_DRIFT("agent.evaluation.configuration.drift"),
    EVALUATOR_UNSUPPORTED("agent.evaluation.evaluator.unsupported"),
    EVIDENCE_MISSING("agent.evaluation.evidence.missing"),
    REGRESSION_FAILED("agent.evaluation.regression.failed"),
    RETRIEVAL_FAILED("agent.evaluation.retrieval.failed"),
    CITATION_FAILED("agent.evaluation.citation.failed"),
    TOOL_DECISION_FAILED("agent.evaluation.tool-decision.failed"),
    SECURITY_REFUSAL_FAILED("agent.evaluation.security-refusal.failed"),
    COST_LIMIT_EXCEEDED("agent.evaluation.cost.limit-exceeded"),
    LATENCY_LIMIT_EXCEEDED("agent.evaluation.latency.limit-exceeded"),
    ;

    override fun toString(): String = value
}

/** Aggregate-only runtime diagnostic; it contains no prompt, output, evidence ID or failure text. */
class AgentEvaluationDoctorSnapshot(
    val status: AgentEvaluationDoctorStatus,
    codes: Collection<AgentEvaluationDoctorCode>,
    val evaluatedCaseCount: Int,
    val failedCaseCount: Int,
    val missingEvidenceCaseCount: Int,
    val observedAt: Long,
) {
    val codes: Set<AgentEvaluationDoctorCode> = immutableEvaluationSet(
        codes,
        AgentEvaluationDoctorCode.values().size,
        "Agent evaluation doctor contains too many codes.",
    )
    val snapshotDigest: String

    init {
        require(this.codes.isNotEmpty()) { "Agent evaluation doctor requires a stable code." }
        require(evaluatedCaseCount in 0..AgentEvaluationLimits.MAX_CASES &&
            failedCaseCount in 0..evaluatedCaseCount && missingEvidenceCaseCount in 0..failedCaseCount
        ) { "Agent evaluation doctor counts are invalid." }
        require(observedAt >= 0L) { "Agent evaluation doctor time is invalid." }
        require(status != AgentEvaluationDoctorStatus.READY ||
            this.codes == setOf(AgentEvaluationDoctorCode.READY) && failedCaseCount == 0
        ) { "A ready Agent evaluation doctor cannot contain a failure." }
        require(status == AgentEvaluationDoctorStatus.READY || AgentEvaluationDoctorCode.READY !in this.codes) {
            "A non-ready Agent evaluation doctor cannot carry the ready code."
        }
        val digest = AgentEvaluationDigest("flowweft.agent.evaluation.doctor-snapshot.v1")
            .add(status.name)
            .add(evaluatedCaseCount)
            .add(failedCaseCount)
            .add(missingEvidenceCaseCount)
            .add(observedAt)
            .add(this.codes.size)
        this.codes.sortedBy { code -> code.value }.forEach { code -> digest.add(code.value) }
        snapshotDigest = digest.finish()
    }

    override fun toString(): String =
        "AgentEvaluationDoctorSnapshot(status=$status, evaluatedCaseCount=$evaluatedCaseCount, " +
            "failedCaseCount=$failedCaseCount, missingEvidenceCaseCount=$missingEvidenceCaseCount)"
}
