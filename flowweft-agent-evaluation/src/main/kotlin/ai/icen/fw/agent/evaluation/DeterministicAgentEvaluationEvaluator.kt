package ai.icen.fw.agent.evaluation

import ai.icen.fw.agent.api.ProviderId

/** Built-in reference scorer for the fixed regression contract. It performs no I/O. */
class DeterministicAgentEvaluationEvaluator : AgentEvaluationCaseEvaluator {
    override fun descriptor(): AgentEvaluationEvaluatorDescriptor = DESCRIPTOR

    override fun evaluate(request: AgentEvaluationScoringRequest): AgentEvaluationCaseScore {
        val criteria = expectedEvaluationCriteria(request.case)
        require(DESCRIPTOR.supports(criteria)) { "The deterministic evaluator does not support this case." }
        return AgentEvaluationCaseScore(
            request.case.caseId,
            request.case.bindingDigest,
            DESCRIPTOR.bindingDigest,
            request.requestDigest,
            criteria.map { criterion -> canonicalCriterionResult(request, criterion) },
            request.evaluatedAt,
        )
    }

    companion object {
        @JvmField
        val DESCRIPTOR: AgentEvaluationEvaluatorDescriptor = AgentEvaluationEvaluatorDescriptor(
            ProviderId("flowweft.evaluation.deterministic"),
            "1.0",
            AgentEvaluationCriterion.values().toList(),
            evaluationDigestOf(
                "flowweft.agent.evaluation.deterministic-configuration.v1",
                "binary-exact-uniform-basis-points",
            ),
        )
    }
}
