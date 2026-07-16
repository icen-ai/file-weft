package ai.icen.fw.agent.web.api

import ai.icen.fw.agent.api.AgentApprovalRequest
import ai.icen.fw.agent.api.AgentAuthorizationDecision
import ai.icen.fw.agent.api.AgentAuthorizationRequest
import ai.icen.fw.agent.api.AgentBudget
import ai.icen.fw.agent.api.AgentCapabilityId
import ai.icen.fw.agent.api.AgentCitation
import ai.icen.fw.agent.api.AgentEvaluationCase
import ai.icen.fw.agent.api.AgentEvaluationExpectedOutcome
import ai.icen.fw.agent.api.AgentEvaluationProviderSnapshot
import ai.icen.fw.agent.api.AgentEvaluationRefusalExpectation
import ai.icen.fw.agent.api.AgentEvaluationSuite
import ai.icen.fw.agent.api.AgentMessageRole
import ai.icen.fw.agent.api.AgentPolicyDecision
import ai.icen.fw.agent.api.AgentPolicyProposal
import ai.icen.fw.agent.api.AgentRunContext
import ai.icen.fw.agent.api.AgentToolDescriptor
import ai.icen.fw.agent.api.AgentToolRisk
import ai.icen.fw.agent.api.AgentUsage
import ai.icen.fw.agent.api.ProviderId
import ai.icen.fw.agent.api.ToolId
import ai.icen.fw.agent.evaluation.AgentEvaluationDatasetReference
import ai.icen.fw.agent.evaluation.AgentEvaluationEvaluatorReference
import ai.icen.fw.core.id.Identifier
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AgentWebContractTest {
    @Test
    fun `route catalog is complete versioned and mutation preconditions are uniform`() {
        val routes = AgentWebRoute.all()

        assertEquals("flowweft.agent.web.v1", AgentWebRoute.CONTRACT_VERSION)
        assertEquals(25, routes.size)
        assertEquals(25, routes.map { route -> route.operationId }.toSet().size)
        assertTrue(routes.all { route -> route.pathTemplate.startsWith(AgentWebRoute.BASE_PATH + "/") })
        assertTrue(routes.filter { route -> route.method == "GET" }.all { route ->
            !route.idempotencyRequired && !route.ifMatchRequired
        })
        assertTrue(routes.filter { route -> route.method != "GET" }.all { route ->
            route.idempotencyRequired && route.ifMatchRequired
        })
        assertTrue(routes.any { route -> route.operationId == "listAgentRunEvents" })
        assertTrue(routes.any { route -> route.operationId == "approveAgentToolConfirmation" })
        assertTrue(routes.any { route -> route.operationId == "triggerAgentEvaluationRun" })
    }

    @Test
    fun `trusted context etag pages and visible messages fail closed`() {
        val context = trustedContext()
        context.requireFresh(300L)
        assertEquals(64, context.trustedContextDigest.length)
        assertEquals(context.trustedContextDigest, trustedContext().trustedContextDigest)
        assertNotEquals(context.trustedContextDigest, trustedContext(principalId = "principal-2").trustedContextDigest)
        assertNotEquals(
            context.trustedContextDigest,
            trustedContext(authorizationRevision = "auth-r2").trustedContextDigest,
        )
        assertFailsWith<IllegalArgumentException> { context.requireFresh(900L) }

        val preconditions = AgentWebWritePreconditions.parse("idem-1", "\"fw-agent-7\"")
        assertEquals(7L, preconditions.versionTag.expectedVersion)
        assertEquals("\"fw-agent-7\"", preconditions.versionTag.toHeaderValue())
        assertFailsWith<IllegalArgumentException> {
            AgentWebWritePreconditions.parse("contains space", "\"fw-agent-7\"")
        }

        val source = arrayListOf("one")
        val page = AgentWebPage(source, AgentWebCursor.of("cursor-1"))
        source += "two"
        assertEquals(listOf("one"), page.items)
        assertFailsWith<UnsupportedOperationException> {
            (page.items as MutableList<String>).add("three")
        }

        val visible = AgentWebVisibleMessageDto(
            id("message-1"),
            id("run-1"),
            1L,
            AgentMessageRole.ASSISTANT,
            "Authorized answer",
            emptyList(),
            300L,
        )
        assertFalse(visible.toString().contains("Authorized answer"))
        assertFailsWith<IllegalArgumentException> {
            AgentWebVisibleMessageDto(
                id("message-2"),
                id("run-1"),
                2L,
                AgentMessageRole.SYSTEM,
                "hidden system prompt",
                emptyList(),
                301L,
            )
        }

        val unknown = AgentWebErrorCode("PLUGIN_LATER_FAILURE")
        assertEquals(500, AgentWebHttpStatusPolicy.statusFor(unknown))
        assertNotEquals(AgentWebErrorCode.INTERNAL_ERROR, unknown)

        val citation = citation("tenant-1")
        val evidence = AgentWebCitationEvidenceDto.authorized(
            citation,
            context,
            "e".repeat(64),
            id("citation-authorization-1"),
            300L,
        )
        assertEquals(64, evidence.evidenceDigest.length)
        assertEquals(context.authorizationRevision, evidence.authorizationRevision)
        assertFailsWith<IllegalArgumentException> {
            AgentWebCitationEvidenceDto.authorized(
                citation("tenant-2"),
                context,
                "e".repeat(64),
                id("citation-authorization-2"),
                300L,
            )
        }
    }

    @Test
    fun `confirmation binds current principal fresh authorization exact evidence and state version`() {
        val request = approvalRequest()
        val command = AgentWebToolConfirmationDecisionCommand.approve(
            request.requestId,
            request.proposalId,
            request.argumentsDigest,
            request.evidenceDigest,
            request.nonce,
        )
        val preconditions = AgentWebWritePreconditions.parse("confirm-idem-1", "\"fw-agent-3\"")

        command.requireCurrentFor(request, trustedContext(), preconditions, 3L, 300L)
        assertFailsWith<IllegalArgumentException> {
            command.requireCurrentFor(request, trustedContext(authorizationRevision = "auth-r2"), preconditions, 3L, 300L)
        }
        assertFailsWith<IllegalArgumentException> {
            command.requireCurrentFor(request, trustedContext(principalId = "principal-2"), preconditions, 3L, 300L)
        }
        assertFailsWith<IllegalArgumentException> {
            command.requireCurrentFor(request, trustedContext(), preconditions, 4L, 300L)
        }
        val changedArguments = AgentWebToolConfirmationDecisionCommand.approve(
            request.requestId,
            request.proposalId,
            "f".repeat(64),
            request.evidenceDigest,
            request.nonce,
        )
        assertFailsWith<IllegalArgumentException> {
            changedArguments.requireCurrentFor(request, trustedContext(), preconditions, 3L, 300L)
        }
        val changedNonce = AgentWebToolConfirmationDecisionCommand.approve(
            request.requestId,
            request.proposalId,
            request.argumentsDigest,
            request.evidenceDigest,
            "wrong-approval-nonce",
        )
        assertFailsWith<IllegalArgumentException> {
            changedNonce.requireCurrentFor(request, trustedContext(), preconditions, 3L, 300L)
        }
    }

    @Test
    fun `evaluation web contracts reuse content free versioned evidence`() {
        val capability = AgentCapabilityId("agent.answer")
        val expected = AgentEvaluationExpectedOutcome(
            refusal = AgentEvaluationRefusalExpectation.MUST_REFUSE,
        )
        val case = AgentEvaluationCase(
            id("case-1"),
            id("fixture-1"),
            capability,
            "a".repeat(64),
            expected,
            setOf("security"),
        )
        val suite = AgentEvaluationSuite(id("suite-1"), "Security suite", "1", listOf(case), 100L)
        val dataset = AgentEvaluationDatasetReference.from(suite)
        val provider = AgentEvaluationProviderSnapshot(
            ProviderId("provider.local"),
            "1",
            setOf(capability),
            "b".repeat(64),
            100L,
            1_000L,
        )
        val evaluator = AgentEvaluationEvaluatorReference(
            ProviderId("evaluator.local"),
            "1",
            "c".repeat(64),
        )
        val command = AgentWebEvaluationTriggerCommand(dataset, provider, evaluator, 900L, 3)

        assertEquals(dataset.bindingDigest, AgentWebEvaluationDatasetSummaryDto.from(suite).dataset.bindingDigest)
        assertEquals(provider.snapshotDigest, command.providerSnapshot.snapshotDigest)
        assertFalse(command.toString().contains("fixture-1"))
    }

    private fun approvalRequest(): AgentApprovalRequest {
        val schema = "{}".toByteArray(StandardCharsets.UTF_8)
        val descriptor = AgentToolDescriptor(
            ProviderId("tool.local"),
            ToolId("tool.publish"),
            "Publish",
            "Publish an authorized resource",
            AgentToolRisk.IRREVERSIBLE_OR_EXTERNAL_SIDE_EFFECT,
            schema,
            digest(schema),
            setOf(AgentCapabilityId("tool.publish")),
            true,
            1_024,
            0L,
            1_000L,
        )
        val arguments = "{}".toByteArray(StandardCharsets.UTF_8)
        val authorization = AgentAuthorizationRequest.preflight(
            id("authorization-request-1"),
            id("execution-context-1"),
            id("tenant-1"),
            id("principal-1"),
            "USER",
            id("run-1"),
            id("step-1"),
            ProviderId("authorization.local"),
            descriptor,
            arguments,
            "idem-approval-1",
            "resource.publish",
            "resource",
            id("resource-1"),
            "resource-r1",
            "publish-approved-resource",
            50L,
            1_000L,
        )
        val initial = AgentAuthorizationDecision.allow(
            id("authorization-decision-1"),
            ProviderId("authorization.local"),
            authorization,
            "auth-r1",
            75L,
            950L,
        )
        val proposal = AgentPolicyProposal.create(
            id("proposal-1"),
            ProviderId("policy.local"),
            authorization,
            initial,
            descriptor.risk,
            AgentBudget(10_000L, 2_000L, 10, 5, 10_000L, 0L),
            AgentUsage(),
            100L,
            900L,
        )
        val policy = AgentPolicyDecision.requireApproval(
            id("policy-decision-1"),
            ProviderId("policy.local"),
            proposal,
            "policy-r1",
            200L,
            900L,
        )
        return AgentApprovalRequest.create(
            id("approval-request-1"),
            proposal,
            policy,
            id("principal-1"),
            "USER",
            "approval-nonce-1",
            250L,
            800L,
        )
    }

    private fun trustedContext(
        principalId: String = "principal-1",
        authorizationRevision: String = "auth-r1",
    ): AgentWebTrustedContext = AgentWebTrustedContext.authenticated(
        AgentRunContext(
            id("tenant-1"),
            id(principalId),
            "USER",
            id("request-1"),
            240L,
        ),
        id("authentication-1"),
        authorizationRevision,
        850L,
        "d".repeat(64),
    )

    private fun citation(tenantId: String): AgentCitation = AgentCitation(
        id("citation-1"),
        id(tenantId),
        id("document-1"),
        id("document-version-1"),
        id("evidence-1"),
        "9".repeat(64),
        0L,
        10L,
        1,
    )

    private fun id(value: String): Identifier = Identifier(value)

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
