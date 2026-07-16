package ai.icen.fw.agent.persistence.jdbc

import ai.icen.fw.agent.api.AgentCapabilityId
import ai.icen.fw.agent.api.AgentEvaluationCase
import ai.icen.fw.agent.api.AgentEvaluationCitationExpectation
import ai.icen.fw.agent.api.AgentEvaluationDiagnostic
import ai.icen.fw.agent.api.AgentEvaluationDiagnosticStatus
import ai.icen.fw.agent.api.AgentEvaluationExpectedOutcome
import ai.icen.fw.agent.api.AgentEvaluationProviderSnapshot
import ai.icen.fw.agent.api.AgentEvaluationRefusalExpectation
import ai.icen.fw.agent.api.AgentEvaluationRetrievalExpectation
import ai.icen.fw.agent.api.AgentEvaluationSuite
import ai.icen.fw.agent.api.AgentEvaluationToolDecision
import ai.icen.fw.agent.api.AgentEvaluationToolExpectation
import ai.icen.fw.agent.api.ProviderId
import ai.icen.fw.agent.api.ToolId
import ai.icen.fw.agent.runtime.AgentEvaluationLease
import ai.icen.fw.agent.runtime.AgentEvaluationCaseEvidence
import ai.icen.fw.agent.runtime.AgentEvaluationRunRequest
import ai.icen.fw.agent.runtime.AgentEvaluationRunState
import ai.icen.fw.core.id.Identifier
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

object AgentEvaluationJdbcTestFixture {
    private val inputDigest = sha256("fixture bytes must remain ephemeral")
    private val descriptorDigest = sha256("provider descriptor")
    private val argumentsDigest = sha256("tool arguments")
    private val observationDigest = sha256("safe observation")

    @JvmStatic
    @JvmOverloads
    fun initial(
        evaluationId: String = "evaluation-1",
        tenantId: String = "tenant-1",
        idempotencyKey: String = "idempotency-1",
        deadlineAt: Long = 1_000L,
    ): AgentEvaluationRunState {
        val suite = suite()
        val snapshot = snapshot()
        val request = AgentEvaluationRunRequest(
            Identifier("request-1"),
            Identifier(tenantId),
            Identifier("principal-1"),
            "USER",
            "authorization-v1",
            suite,
            snapshot,
            idempotencyKey,
            100L,
            deadlineAt,
            3,
        )
        return AgentEvaluationRunState.initial(Identifier(evaluationId), request)
    }

    @JvmStatic
    fun claimed(state: AgentEvaluationRunState, fencingToken: Long = 1L, atTime: Long = 110L): AgentEvaluationRunState {
        val lease = AgentEvaluationLease(
            Identifier("lease-$fencingToken"),
            ProviderId("worker-$fencingToken"),
            fencingToken,
            atTime,
            atTime + 100L,
        )
        return state.claimed(lease, atTime)
    }

    @JvmStatic
    fun progressed(state: AgentEvaluationRunState, atTime: Long = 120L): AgentEvaluationRunState {
        val lease = requireNotNull(state.lease)
        val renewed = AgentEvaluationLease(
            lease.leaseId,
            lease.ownerId,
            lease.fencingToken,
            lease.acquiredAt,
            lease.expiresAt + 100L,
        )
        val case = state.suite.cases.single()
        val evidence = AgentEvaluationCaseEvidence(
            case.caseId,
            case.bindingDigest,
            true,
            listOf(observationDigest),
            AgentEvaluationDiagnostic(
                AgentEvaluationDiagnosticStatus.READY,
                null,
                state.providerSnapshot.providerId,
                case.capabilityId,
                state.providerSnapshot.snapshotDigest,
                atTime,
            ),
            atTime,
        )
        return state.progressed(lease, renewed, evidence, atTime)
    }

    @JvmStatic
    fun completed(state: AgentEvaluationRunState, atTime: Long = 130L): AgentEvaluationRunState =
        state.completed(requireNotNull(state.lease), atTime)

    private fun suite(): AgentEvaluationSuite = AgentEvaluationSuite(
        Identifier("suite-1"),
        "Regression Suite",
        "1.0.0",
        listOf(
            AgentEvaluationCase(
                Identifier("case-1"),
                Identifier("fixture-1"),
                AgentCapabilityId("agent.answer"),
                inputDigest,
                AgentEvaluationExpectedOutcome(
                    AgentEvaluationRetrievalExpectation(listOf(Identifier("evidence-1")), 1),
                    AgentEvaluationCitationExpectation(listOf(Identifier("evidence-1")), 1),
                    AgentEvaluationToolExpectation(
                        AgentEvaluationToolDecision.REQUIRE_APPROVAL,
                        ProviderId("tool-provider"),
                        ToolId("document.read"),
                        argumentsDigest,
                    ),
                    AgentEvaluationRefusalExpectation.MUST_ANSWER,
                    5_000L,
                    500L,
                ),
                listOf("retrieval", "security"),
            ),
        ),
        90L,
    )

    private fun snapshot(): AgentEvaluationProviderSnapshot = AgentEvaluationProviderSnapshot(
        ProviderId("evaluator.local"),
        "1.0.0",
        listOf(AgentCapabilityId("agent.answer")),
        descriptorDigest,
        90L,
        1_000L,
    )

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
