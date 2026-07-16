package ai.icen.fw.agent.api

import ai.icen.fw.core.id.Identifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AgentEvaluationRegressionContractTest {

    @Test
    fun `suite snapshots fixed cases provider contracts and safe expectations`() {
        val capabilities = mutableListOf(AgentCapabilityId("agent.answer"))
        val provider = AgentEvaluationProviderSnapshot(
            ProviderId("model.local"),
            "2026.07.1",
            capabilities,
            digest('a'),
            100,
            200,
        )
        val tags = mutableListOf("grounded")
        val cases = mutableListOf(regressionCase(tags))
        val suite = AgentEvaluationSuite(id("suite-1"), "安全回归", "1.0", cases, 90)

        capabilities += AgentCapabilityId("agent.changed")
        tags += "changed"
        cases.clear()

        assertEquals(setOf(AgentCapabilityId("agent.answer")), provider.capabilities)
        assertEquals(setOf("grounded"), suite.cases.single().tags)
        assertEquals(1, suite.cases.size)
        assertTrue(provider.supports(AgentCapabilityId("agent.answer")))
        assertTrue(provider.isCurrent(150))
        assertFalse(provider.isCurrent(200))
        assertFalse(provider.toString().contains(provider.descriptorDigest))
        assertFalse(suite.cases.single().toString().contains("fixture-1"))

        val changed = AgentEvaluationSuite(
            id("suite-1"),
            "安全回归",
            "1.0",
            listOf(regressionCase(mutableListOf("grounded"), maximumLatencyMillis = 501)),
            90,
        )
        assertNotEquals(suite.suiteDigest, changed.suiteDigest)
    }

    @Test
    fun `observations bind authorization scope and fail closed on unsafe evidence`() {
        val case = regressionCase(mutableListOf("grounded"))
        val suite = AgentEvaluationSuite(id("suite-1"), "Regression", "1.0", listOf(case), 90)
        val context = observationContext(suite, case, "tenant-1", "principal-1")
        context.requireMatches(suite, case)

        val retrieval = AgentEvaluationRetrievalObservation(context, digest('c'), 2, 0, 0, true)
        val citations = AgentEvaluationCitationObservation(context, digest('d'), 2, 2, 0, 0, 0)
        val tool = AgentEvaluationToolDecisionObservation(
            context,
            AgentEvaluationToolDecision.REQUIRE_APPROVAL,
            ProviderId("tool.local"),
            ToolId("document.publish"),
            digest('e'),
            authorizationFresh = true,
            approvalBindingValid = true,
        )
        val refusal = AgentEvaluationRefusalObservation(context, false)
        val cost = AgentEvaluationCostObservation(context, 100, 100)
        val latency = AgentEvaluationLatencyObservation(context, 100, 600, 500)
        val expected = case.expected

        assertTrue(retrieval.satisfies(requireNotNull(expected.retrieval)))
        assertTrue(citations.satisfies(requireNotNull(expected.citations)))
        assertTrue(tool.satisfies(requireNotNull(expected.tool)))
        assertTrue(refusal.satisfies(expected.refusal))
        assertFalse(cost.exceeded())
        assertFalse(latency.exceeded())
        assertEquals(AgentEvaluationObservationKind.RETRIEVAL, retrieval.kind())

        val unsafeRetrieval = AgentEvaluationRetrievalObservation(context, digest('f'), 2, 0, 1, true)
        val foreignCitation = AgentEvaluationCitationObservation(context, digest('0'), 2, 2, 0, 0, 1)
        assertFalse(unsafeRetrieval.satisfies(requireNotNull(expected.retrieval)))
        assertFalse(foreignCitation.satisfies(requireNotNull(expected.citations)))

        val otherTenant = observationContext(suite, case, "tenant-2", "principal-1")
        assertNotEquals(context.bindingDigest, otherTenant.bindingDigest)
        assertFailsWith<IllegalArgumentException> {
            context.requireMatches(
                AgentEvaluationSuite(id("suite-other"), "Other", "1.0", listOf(case), 90),
                case,
            )
        }
    }

    @Test
    fun `diagnostics expose bounded status and safe reason only`() {
        val ready = AgentEvaluationDiagnostic(
            AgentEvaluationDiagnosticStatus.READY,
            null,
            ProviderId("model.local"),
            AgentCapabilityId("agent.answer"),
            digest('a'),
            100,
        )
        val drifted = AgentEvaluationDiagnostic(
            AgentEvaluationDiagnosticStatus.DRIFTED,
            AgentEvaluationDiagnosticReason.SNAPSHOT_DRIFT,
            ProviderId("model.local"),
            AgentCapabilityId("agent.answer"),
            digest('b'),
            101,
        )

        assertEquals("snapshot.drift", drifted.reason?.value)
        assertFalse(drifted.toString().contains(digest('b')))
        assertFailsWith<IllegalArgumentException> {
            AgentEvaluationDiagnostic(
                AgentEvaluationDiagnosticStatus.UNAVAILABLE,
                null,
                null,
                null,
                null,
                100,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AgentEvaluationDiagnostic(
                AgentEvaluationDiagnosticStatus.READY,
                AgentEvaluationDiagnosticReason.EVALUATION_FAILED,
                null,
                null,
                null,
                100,
            )
        }
        assertEquals(AgentEvaluationDiagnosticStatus.READY, ready.status)
    }

    @Test
    fun `contradictory or unbounded expected outcomes are rejected`() {
        assertFailsWith<IllegalArgumentException> { AgentEvaluationExpectedOutcome() }
        assertFailsWith<IllegalArgumentException> {
            AgentEvaluationExpectedOutcome(maximumLatencyMillis = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            AgentEvaluationExpectedOutcome(
                tool = AgentEvaluationToolExpectation(
                    AgentEvaluationToolDecision.INVOKE,
                    ProviderId("tool.local"),
                    ToolId("document.publish"),
                ),
                refusal = AgentEvaluationRefusalExpectation.MUST_REFUSE,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AgentEvaluationToolDecisionObservation(
                observationContext(
                    AgentEvaluationSuite(
                        id("suite-1"),
                        "Regression",
                        "1.0",
                        listOf(regressionCase(mutableListOf("safe"))),
                        90,
                    ),
                    regressionCase(mutableListOf("safe")),
                    "tenant-1",
                    "principal-1",
                ),
                AgentEvaluationToolDecision.SKIP,
                ProviderId("tool.local"),
            )
        }
    }

    private fun regressionCase(
        tags: MutableList<String>,
        maximumLatencyMillis: Long = 500,
    ): AgentEvaluationCase {
        val evidence = listOf(id("evidence-1"), id("evidence-2"))
        return AgentEvaluationCase(
            id("case-1"),
            id("fixture-1"),
            AgentCapabilityId("agent.answer"),
            digest('b'),
            AgentEvaluationExpectedOutcome(
                retrieval = AgentEvaluationRetrievalExpectation(evidence, 2),
                citations = AgentEvaluationCitationExpectation(evidence, 2),
                tool = AgentEvaluationToolExpectation(
                    AgentEvaluationToolDecision.REQUIRE_APPROVAL,
                    ProviderId("tool.local"),
                    ToolId("document.publish"),
                    digest('e'),
                ),
                refusal = AgentEvaluationRefusalExpectation.MUST_ANSWER,
                maximumCostMicros = 100,
                maximumLatencyMillis = maximumLatencyMillis,
            ),
            tags,
        )
    }

    private fun observationContext(
        suite: AgentEvaluationSuite,
        case: AgentEvaluationCase,
        tenant: String,
        principal: String,
    ): AgentEvaluationObservationContext = AgentEvaluationObservationContext(
        suite.suiteId,
        suite.suiteDigest,
        case.caseId,
        case.bindingDigest,
        id(tenant),
        id(principal),
        "USER",
        "authorization-v1",
        digest('a'),
        100,
    )

    private fun id(value: String): Identifier = Identifier(value)
    private fun digest(character: Char): String = character.toString().repeat(64)
}
