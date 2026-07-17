package ai.icen.fw.agent.evaluation

import ai.icen.fw.agent.api.AgentCapabilityId
import ai.icen.fw.agent.api.AgentEvaluationCase
import ai.icen.fw.agent.api.AgentEvaluationCitationExpectation
import ai.icen.fw.agent.api.AgentEvaluationCitationObservation
import ai.icen.fw.agent.api.AgentEvaluationCostObservation
import ai.icen.fw.agent.api.AgentEvaluationExpectedOutcome
import ai.icen.fw.agent.api.AgentEvaluationLatencyObservation
import ai.icen.fw.agent.api.AgentEvaluationObservationContext
import ai.icen.fw.agent.api.AgentEvaluationProviderSnapshot
import ai.icen.fw.agent.api.AgentEvaluationRefusalExpectation
import ai.icen.fw.agent.api.AgentEvaluationRefusalObservation
import ai.icen.fw.agent.api.AgentEvaluationRetrievalExpectation
import ai.icen.fw.agent.api.AgentEvaluationRetrievalObservation
import ai.icen.fw.agent.api.AgentEvaluationSuite
import ai.icen.fw.agent.api.AgentEvaluationToolDecision
import ai.icen.fw.agent.api.AgentEvaluationToolDecisionObservation
import ai.icen.fw.agent.api.AgentEvaluationToolExpectation
import ai.icen.fw.agent.api.ProviderId
import ai.icen.fw.agent.api.ToolId
import ai.icen.fw.core.id.Identifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InMemoryAgentEvaluationRunnerTest {
    @Test
    fun `fixed evidence produces deterministic complete score without sensitive content`() {
        val fixture = fixture()
        val runner = runner(fixture.suite, fixture.provider)
        val request = runRequest(fixture, listOf(fixture.evidence))

        val first = runner.run(request)
        val second = runner.run(request)

        assertTrue(first.passed)
        assertEquals(10_000, first.scoreBasisPoints)
        assertEquals(6, first.totalCriteria)
        assertEquals(first.reportDigest, second.reportDigest)
        assertEquals(setOf(AgentEvaluationDoctorCode.READY), first.doctor.codes)
        val rendered = first.toString() + first.doctor.toString() + first.caseScores.joinToString()
        assertFalse(rendered.contains("private prompt"))
        assertFalse(rendered.contains("generated answer"))
        assertFalse(rendered.contains("authorization-v1"))
        assertFalse(rendered.contains(fixture.evidence.outputDigest))
    }

    @Test
    fun `missing evidence and unsafe refusal fail closed with stable diagnostics`() {
        val fixture = fixture()
        val runner = runner(fixture.suite, fixture.provider)

        val missing = runner.run(runRequest(fixture, emptyList()))

        assertFalse(missing.passed)
        assertEquals(0, missing.scoreBasisPoints)
        assertEquals(6, missing.caseScores.single().criterionResults.count { result ->
            result.outcome == AgentEvaluationCriterionOutcome.MISSING
        })
        assertTrue(AgentEvaluationDoctorCode.EVIDENCE_MISSING in missing.doctor.codes)

        val unsafeRefusal = AgentEvaluationRefusalObservation(fixture.context, refused = false)
        val unsafeEvidence = AgentEvaluationEvidenceBatch(
            fixture.context,
            fixture.case.fixtureId,
            fixture.case.inputDigest,
            fixture.evidence.outputDigest,
            fixture.evidence.observations.map { observation ->
                if (observation is AgentEvaluationRefusalObservation) unsafeRefusal else observation
            },
            fixture.evidence.completedAt,
        )
        val unsafe = runner.run(runRequest(fixture, listOf(unsafeEvidence)))

        assertFalse(unsafe.passed)
        assertTrue(AgentEvaluationDoctorCode.SECURITY_REFUSAL_FAILED in unsafe.doctor.codes)
    }

    @Test
    fun `provider absence drift and evaluator absence are diagnosed without probing`() {
        val fixture = fixture()
        val datasets = InMemoryAgentEvaluationDatasetRegistry(listOf(fixture.suite))
        val evaluators = InMemoryAgentEvaluationEvaluatorRegistry(listOf(DeterministicAgentEvaluationEvaluator()))
        val request = runRequest(fixture, listOf(fixture.evidence))

        val unavailable = InMemoryAgentEvaluationRunner(
            datasets,
            InMemoryAgentEvaluationProviderInventory(),
            evaluators,
        ).run(request)
        assertEquals(AgentEvaluationDoctorStatus.UNAVAILABLE, unavailable.doctor.status)
        assertEquals(setOf(AgentEvaluationDoctorCode.PROVIDER_UNAVAILABLE), unavailable.doctor.codes)

        val changed = AgentEvaluationProviderSnapshot(
            fixture.provider.providerId,
            "2.0",
            fixture.provider.capabilities,
            digest('9'),
            90,
            1_000,
        )
        val drifted = InMemoryAgentEvaluationRunner(
            datasets,
            InMemoryAgentEvaluationProviderInventory(listOf(changed)),
            evaluators,
        ).run(request)
        assertEquals(AgentEvaluationDoctorStatus.DRIFTED, drifted.doctor.status)
        assertEquals(setOf(AgentEvaluationDoctorCode.CONFIGURATION_DRIFT), drifted.doctor.codes)

        val unsupported = InMemoryAgentEvaluationRunner(
            datasets,
            InMemoryAgentEvaluationProviderInventory(listOf(fixture.provider)),
            InMemoryAgentEvaluationEvaluatorRegistry(),
        ).run(request)
        assertEquals(AgentEvaluationDoctorStatus.UNSUPPORTED, unsupported.doctor.status)
        assertEquals(setOf(AgentEvaluationDoctorCode.EVALUATOR_UNSUPPORTED), unsupported.doctor.codes)
    }

    @Test
    fun `dataset version cannot be rebound to a different digest`() {
        val fixture = fixture()
        val registry = InMemoryAgentEvaluationDatasetRegistry(listOf(fixture.suite))
        val changedCase = AgentEvaluationCase(
            fixture.case.caseId,
            fixture.case.fixtureId,
            fixture.case.capabilityId,
            digest('8'),
            fixture.case.expected,
            fixture.case.tags,
        )
        val changed = AgentEvaluationSuite(
            fixture.suite.suiteId,
            fixture.suite.name,
            fixture.suite.version,
            listOf(changedCase),
            fixture.suite.createdAt,
        )

        assertFailsWith<IllegalArgumentException> { registry.register(changed) }
    }

    private fun runner(
        suite: AgentEvaluationSuite,
        provider: AgentEvaluationProviderSnapshot,
    ): InMemoryAgentEvaluationRunner = InMemoryAgentEvaluationRunner(
        InMemoryAgentEvaluationDatasetRegistry(listOf(suite)),
        InMemoryAgentEvaluationProviderInventory(listOf(provider)),
        InMemoryAgentEvaluationEvaluatorRegistry(listOf(DeterministicAgentEvaluationEvaluator())),
    )

    private fun runRequest(
        fixture: Fixture,
        evidence: Collection<AgentEvaluationEvidenceBatch>,
    ): AgentEvaluationRegressionRun = AgentEvaluationRegressionRun(
        AgentEvaluationDatasetReference.from(fixture.suite),
        fixture.provider,
        AgentEvaluationEvaluatorReference.from(DeterministicAgentEvaluationEvaluator.DESCRIPTOR),
        fixture.subject,
        evidence,
        500,
    )

    private fun fixture(): Fixture {
        val capability = AgentCapabilityId("agent.answer")
        val provider = AgentEvaluationProviderSnapshot(
            ProviderId("model.local"),
            "1.0",
            listOf(capability),
            digest('a'),
            90,
            1_000,
        )
        val evidenceIds = listOf(id("evidence-1"), id("evidence-2"))
        val case = AgentEvaluationCase(
            id("case-1"),
            id("fixture-1"),
            capability,
            digest('b'),
            AgentEvaluationExpectedOutcome(
                AgentEvaluationRetrievalExpectation(evidenceIds, 2),
                AgentEvaluationCitationExpectation(evidenceIds, 2),
                AgentEvaluationToolExpectation(
                    AgentEvaluationToolDecision.REQUIRE_APPROVAL,
                    ProviderId("tool.local"),
                    ToolId("document.publish"),
                    digest('c'),
                ),
                AgentEvaluationRefusalExpectation.MUST_REFUSE,
                1_000,
                500,
            ),
            listOf("grounded", "security-refusal"),
        )
        val suite = AgentEvaluationSuite(id("suite-1"), "安全回归", "1.0", listOf(case), 100)
        val context = AgentEvaluationObservationContext(
            suite.suiteId,
            suite.suiteDigest,
            case.caseId,
            case.bindingDigest,
            id("tenant-1"),
            id("principal-1"),
            "USER",
            "authorization-v1",
            provider.snapshotDigest,
            150,
        )
        val subject = AgentEvaluationSubjectBinding.from(context)
        val observations = listOf(
            AgentEvaluationRetrievalObservation(context, digest('d'), 2, 0, 0, true),
            AgentEvaluationCitationObservation(context, digest('e'), 2, 2, 0, 0, 0),
            AgentEvaluationToolDecisionObservation(
                context,
                AgentEvaluationToolDecision.REQUIRE_APPROVAL,
                ProviderId("tool.local"),
                ToolId("document.publish"),
                digest('c'),
                authorizationFresh = true,
                approvalBindingValid = true,
            ),
            AgentEvaluationRefusalObservation(context, refused = true, reasonCode = "policy.security"),
            AgentEvaluationCostObservation(context, 1_000, 900),
            AgentEvaluationLatencyObservation(context, 150, 450, 500),
        )
        val evidence = AgentEvaluationEvidenceBatch(
            context,
            case.fixtureId,
            case.inputDigest,
            digest('f'),
            observations,
            450,
        )
        return Fixture(suite, case, provider, context, subject, evidence)
    }

    private class Fixture(
        val suite: AgentEvaluationSuite,
        val case: AgentEvaluationCase,
        val provider: AgentEvaluationProviderSnapshot,
        val context: AgentEvaluationObservationContext,
        val subject: AgentEvaluationSubjectBinding,
        val evidence: AgentEvaluationEvidenceBatch,
    )

    private fun id(value: String): Identifier = Identifier(value)
    private fun digest(value: Char): String = value.toString().repeat(64)
}
