package ai.icen.fw.agent.workflow

import ai.icen.fw.agent.api.AgentApprovalDecision
import ai.icen.fw.agent.api.AgentApprovalRequest
import ai.icen.fw.agent.api.AgentAuthorizationDecision
import ai.icen.fw.agent.api.AgentAuthorizationRequest
import ai.icen.fw.agent.api.AgentBudget
import ai.icen.fw.agent.api.AgentCancellationToken
import ai.icen.fw.agent.api.AgentDispatchAuthorizationFenceConsumption
import ai.icen.fw.agent.api.AgentDispatchAuthorizationFenceRequest
import ai.icen.fw.agent.api.AgentExecutableToolInvocation
import ai.icen.fw.agent.api.AgentExecutionContextConsumption
import ai.icen.fw.agent.api.AgentPolicyDecision
import ai.icen.fw.agent.api.AgentPolicyProposal
import ai.icen.fw.agent.api.AgentUsage
import ai.icen.fw.agent.api.AuthorizedToolInvocation
import ai.icen.fw.agent.api.ProviderId
import ai.icen.fw.core.id.Identifier
import java.nio.charset.StandardCharsets
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull

class WorkflowAgentPublicInvocationBindingSecurityTest {
    private val directory = WorkflowAgentPublicToolDirectory()

    @Test
    fun `binding carries only current principal fresh authority exact command and remaining budget`() {
        val binding = WorkflowAgentPublicInvocationBinding.bind(directory, executable(), 410L)

        assertEquals("publishWorkflowDefinition", binding.useCase.operationId)
        assertEquals("tenant-1", binding.tenantId.value)
        assertEquals("principal-1", binding.principalId.value)
        assertEquals("USER", binding.principalType)
        assertEquals("auth-r1", binding.authorizationRevision)
        assertEquals(700L, binding.authorizationExpiresAt)
        assertEquals(0L, binding.maximumCostMicros)
        assertEquals(294L, binding.maximumDurationMillis)
        assertEquals(290L, binding.remainingDurationMillis)
        assertEquals("tenant-1", binding.trustedContext.tenantId)
        assertEquals("principal-1", binding.trustedContext.principalId)
        assertEquals("authorization-final-1", binding.trustedContext.authenticationId)
        assertEquals(binding.bindingDigest, binding.trustedContext.authorizationContextDigest)
        assertEquals("definition-1", binding.resourceId.value)
        assertEquals(7L, assertNotNull(binding.writePreconditions).versionTag.expectedVersion)
        assertEquals("idem-publish-1", binding.writePreconditions!!.idempotencyKey)
        assertEquals("{\"changeTicket\":\"ticket-secret\"}",
            binding.payload.toString(StandardCharsets.UTF_8))
        assertFalse(binding.toString().contains("ticket-secret"))
    }

    @Test
    fun `high risk direct allow or another principal confirmation never gains authority`() {
        assertFailsWith<IllegalArgumentException> {
            WorkflowAgentPublicInvocationBinding.bind(directory, executable(confirmation = false), 410L)
        }
        assertFailsWith<IllegalArgumentException> {
            WorkflowAgentPublicInvocationBinding.bind(
                directory,
                executable(approvalOperator = "security-admin"),
                410L,
            )
        }
    }

    @Test
    fun `resource revision drift expiry and spent reservation fail closed`() {
        assertFailsWith<IllegalArgumentException> {
            WorkflowAgentPublicInvocationBinding.bind(
                directory,
                executable(resourceRevision = "f".repeat(64)),
                410L,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            WorkflowAgentPublicInvocationBinding.bind(directory, executable(), 700L)
        }
        assertFailsWith<IllegalArgumentException> {
            WorkflowAgentPublicInvocationBinding.bind(directory, executable(deadlineAt = 800L), 701L)
        }
    }

    private fun executable(
        confirmation: Boolean = true,
        approvalOperator: String = "principal-1",
        resourceRevision: String? = null,
        deadlineAt: Long = 700L,
    ): AgentExecutableToolInvocation {
        val descriptor = directory.entry("publishWorkflowDefinition")!!.toolDescriptor
        val arguments = publishArguments()
        val target = WorkflowAgentPublicAuthorizationTarget.decode(directory, descriptor.toolId, arguments)
        val preflight = AgentAuthorizationRequest.preflight(
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
            target.idempotencyKey,
            target.action,
            target.resourceType,
            id(target.resourceId),
            resourceRevision ?: target.resourceRevision,
            target.purpose,
            50L,
            1_000L,
        )
        val initial = AgentAuthorizationDecision.allow(
            id("authorization-initial-1"),
            ProviderId("authorization.local"),
            preflight,
            "auth-r1",
            75L,
            950L,
        )
        val proposal = AgentPolicyProposal.create(
            id("proposal-1"),
            ProviderId("policy.local"),
            preflight,
            initial,
            descriptor.risk,
            AgentBudget(10_000L, 2_000L, 10, 5, 60_000L, 0L),
            AgentUsage(),
            100L,
            900L,
        )
        val policy = if (confirmation) {
            AgentPolicyDecision.requireApproval(
                id("policy-decision-1"),
                ProviderId("policy.local"),
                proposal,
                "policy-r1",
                200L,
                900L,
            )
        } else {
            AgentPolicyDecision.allow(
                id("policy-decision-1"),
                ProviderId("policy.local"),
                proposal,
                "policy-r1",
                200L,
                900L,
            )
        }
        val approvalRequest = if (confirmation) {
            AgentApprovalRequest.create(
                id("approval-request-1"),
                proposal,
                policy,
                id(approvalOperator),
                "USER",
                "confirmation-nonce-1",
                250L,
                800L,
            )
        } else {
            null
        }
        val approvalDecision = approvalRequest?.let { request ->
            AgentApprovalDecision.approve(
                id("approval-decision-1"),
                request,
                id(approvalOperator),
                "USER",
                300L,
            )
        }
        val executionRequest = AgentAuthorizationRequest.executionRecheck(
            id("authorization-execution-request-1"),
            preflight,
            325L,
            850L,
        )
        val executionDecision = AgentAuthorizationDecision.allow(
            id("authorization-execution-1"),
            ProviderId("authorization.local"),
            executionRequest,
            "auth-r1",
            350L,
            850L,
        )
        val invocation = AuthorizedToolInvocation.authorize(
            id("invocation-1"),
            proposal,
            descriptor,
            policy,
            executionRequest,
            executionDecision,
            approvalRequest,
            approvalDecision,
            arguments,
            target.idempotencyKey,
            1,
            400L,
            deadlineAt,
            AgentCancellationToken.NONE,
        )
        val consumption = AgentExecutionContextConsumption.claimed(
            id("execution-consumption-1"),
            ProviderId("execution-store.local"),
            invocation,
            401L,
            "store-r1",
        )
        val finalRequest = AgentAuthorizationRequest.finalExecutionRecheck(
            id("authorization-final-request-1"),
            executionRequest,
            402L,
            deadlineAt,
        )
        val finalDecision = AgentAuthorizationDecision.allow(
            id("authorization-final-1"),
            ProviderId("authorization.local"),
            finalRequest,
            "auth-r1",
            403L,
            deadlineAt,
        )
        val dispatchFence = AgentDispatchAuthorizationFenceRequest(
            id("dispatch-fence-1"),
            ProviderId("runtime.worker"),
            invocation,
            finalRequest,
            finalDecision,
            404L,
            deadlineAt,
        )
        val dispatchConsumption = AgentDispatchAuthorizationFenceConsumption.consumed(
            id("dispatch-consumption-1"),
            dispatchFence,
            405L,
            "authorization-store-r1",
        )
        return AgentExecutableToolInvocation.create(
            invocation,
            consumption,
            finalRequest,
            finalDecision,
            dispatchFence,
            dispatchConsumption,
            ProviderId("execution-store.local"),
            ProviderId("runtime.worker"),
            0L,
            294L,
            406L,
        )
    }

    private fun publishArguments(): ByteArray = (
        "{\"applicationContractVersion\":\"flowweft.workflow.web.application.v1\"," +
            "\"executionNonce\":\"nonce-publish-1\",\"expectedResourceVersion\":7," +
            "\"idempotencyKey\":\"idem-publish-1\"," +
            "\"operationId\":\"publishWorkflowDefinition\"," +
            "\"payload\":{\"changeTicket\":\"ticket-secret\"}," +
            "\"purpose\":\"publish-approved-draft\",\"resourceId\":\"definition-1\"," +
            "\"resourceType\":\"workflow-definition\"}"
        ).toByteArray(StandardCharsets.UTF_8)

    private fun id(value: String): Identifier = Identifier(value)
}
