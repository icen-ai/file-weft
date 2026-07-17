package ai.icen.fw.agent.web.api

import ai.icen.fw.agent.api.AgentEvaluationProviderSnapshot
import ai.icen.fw.agent.api.AgentEvaluationSuite
import ai.icen.fw.agent.evaluation.AgentEvaluationDatasetReference
import ai.icen.fw.agent.evaluation.AgentEvaluationEvaluatorReference
import ai.icen.fw.agent.evaluation.AgentEvaluationRegressionReport
import ai.icen.fw.core.id.Identifier

/** Open, presentation-only projection of durable evaluation state; it defines no transition rules. */
class AgentWebEvaluationStatusCode(value: String) {
    val value: String = agentWebCode(value, "Agent Web evaluation status")

    override fun equals(other: Any?): Boolean = other is AgentWebEvaluationStatusCode && value == other.value
    override fun hashCode(): Int = value.hashCode()
    override fun toString(): String = value

    companion object {
        @JvmField val QUEUED = AgentWebEvaluationStatusCode("QUEUED")
        @JvmField val RUNNING = AgentWebEvaluationStatusCode("RUNNING")
        @JvmField val COMPLETED = AgentWebEvaluationStatusCode("COMPLETED")
        @JvmField val FAILED = AgentWebEvaluationStatusCode("FAILED")
        @JvmField val CANCELLED = AgentWebEvaluationStatusCode("CANCELLED")
        @JvmField val EXPIRED = AgentWebEvaluationStatusCode("EXPIRED")
    }
}

/** Content-free dataset summary; fixed fixtures remain behind the trusted evaluation store. */
class AgentWebEvaluationDatasetSummaryDto private constructor(
    val dataset: AgentEvaluationDatasetReference,
    name: String,
    val caseCount: Int,
    val createdAt: Long,
) {
    val name: String = agentWebText(name, AGENT_WEB_MAX_NAME_BYTES, "Agent Web evaluation dataset name")

    init {
        require(caseCount > 0 && createdAt >= 0L) { "Agent Web evaluation dataset summary is invalid." }
    }

    companion object {
        @JvmStatic
        fun from(suite: AgentEvaluationSuite): AgentWebEvaluationDatasetSummaryDto =
            AgentWebEvaluationDatasetSummaryDto(
                AgentEvaluationDatasetReference.from(suite),
                suite.name,
                suite.cases.size,
                suite.createdAt,
            )
    }
}

/** The existing suite ABI contains fixture references/digests only, never prompt or output text. */
class AgentWebEvaluationDatasetDto(val suite: AgentEvaluationSuite) {
    val dataset: AgentEvaluationDatasetReference = AgentEvaluationDatasetReference.from(suite)

    override fun toString(): String =
        "AgentWebEvaluationDatasetDto(version=${suite.version}, cases=${suite.cases.size}, <redacted>)"
}

/**
 * Trigger references exact immutable dataset, provider and evaluator revisions. Tenant/principal
 * and evaluation subject binding are derived only from [AgentWebTrustedContext] by the application.
 */
class AgentWebEvaluationTriggerCommand(
    val dataset: AgentEvaluationDatasetReference,
    val providerSnapshot: AgentEvaluationProviderSnapshot,
    val evaluator: AgentEvaluationEvaluatorReference,
    val deadlineAt: Long,
    val maximumAttempts: Int,
) {
    init {
        require(deadlineAt > 0L && maximumAttempts in 1..100) {
            "Agent Web evaluation deadline or attempt limit is invalid."
        }
    }

    override fun toString(): String = "AgentWebEvaluationTriggerCommand(<redacted>)"
}

class AgentWebEvaluationRunDto @JvmOverloads constructor(
    evaluationId: Identifier,
    val dataset: AgentEvaluationDatasetReference,
    val providerSnapshot: AgentEvaluationProviderSnapshot,
    val evaluator: AgentEvaluationEvaluatorReference,
    val status: AgentWebEvaluationStatusCode,
    val completedCases: Int,
    val totalCases: Int,
    val stateVersion: Long,
    val createdAt: Long,
    val updatedAt: Long,
    val deadlineAt: Long,
    diagnosticCode: String? = null,
) {
    val evaluationId: Identifier = agentWebIdentifier(evaluationId, "Agent Web evaluation identifier")
    val diagnosticCode: String? = diagnosticCode?.let { code ->
        agentWebCode(code, "Agent Web evaluation diagnostic code")
    }

    init {
        require(totalCases > 0 && completedCases in 0..totalCases && stateVersion >= 0L &&
            createdAt >= 0L && updatedAt >= createdAt && deadlineAt > createdAt
        ) { "Agent Web evaluation run counts, version or timestamps are invalid." }
        require(providerSnapshot.isCurrent(createdAt)) {
            "Agent Web evaluation run did not start under a current provider snapshot."
        }
        require(status != AgentWebEvaluationStatusCode.COMPLETED || completedCases == totalCases) {
            "Completed Agent Web evaluations require every case."
        }
        require(status != AgentWebEvaluationStatusCode.FAILED && status != AgentWebEvaluationStatusCode.EXPIRED ||
            this.diagnosticCode != null
        ) { "Failed or expired Agent Web evaluations require a safe diagnostic code." }
    }

    override fun toString(): String = "AgentWebEvaluationRunDto(status=$status, <redacted>)"
}

/** Existing evaluation report is digest/reference-only and contains no prompt or generated body. */
class AgentWebEvaluationResultDto(
    evaluationId: Identifier,
    val report: AgentEvaluationRegressionReport,
    val stateVersion: Long,
) {
    val evaluationId: Identifier = agentWebIdentifier(evaluationId, "Agent Web evaluation identifier")

    init {
        require(stateVersion >= 0L) { "Agent Web evaluation result version is invalid." }
    }

    override fun toString(): String =
        "AgentWebEvaluationResultDto(passed=${report.passed}, scoreBasisPoints=${report.scoreBasisPoints}, <redacted>)"
}
