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
import ai.icen.fw.agent.api.AgentToolObserver
import ai.icen.fw.agent.api.AgentToolResultStatus
import ai.icen.fw.agent.api.AgentUsage
import ai.icen.fw.agent.api.AuthorizedToolInvocation
import ai.icen.fw.agent.api.ProviderId
import ai.icen.fw.core.id.Identifier
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WorkflowAgentToolExecutorSecurityTest {
    @Test
    fun `confirmed current principal reaches only named publish use case`() {
        val executable = executable()
        val captured = AtomicReference<WorkflowAgentAuthorizedCommand>()
        val calls = AtomicInteger()
        val suite = suite { command ->
            calls.incrementAndGet()
            captured.set(command)
            val json = "{\"definitionStateVersion\":8,\"status\":\"published\"}"
                .toByteArray(StandardCharsets.UTF_8)
            CompletableFuture.completedFuture(
                WorkflowAgentUseCaseResult.succeeded("WORKFLOW_COMMITTED", json, digest(json)),
            )
        }

        val result = assertNotNull(suite.executor(WorkflowAgentOperation.PUBLISH_DEFINITION.toolId))
            .start(executable, AgentToolObserver.NOOP)
            .completion()
            .toCompletableFuture()
            .join()

        assertEquals(AgentToolResultStatus.SUCCEEDED, result.status)
        assertEquals(1, calls.get())
        assertEquals("principal-1", captured.get().context.principalId.value)
        assertEquals("USER", captured.get().context.principalType)
        assertEquals("definition-1", captured.get().command.resourceId)
        assertEquals(7L, captured.get().command.expectedDefinitionStateVersion)
        assertEquals("auth-r1", captured.get().context.authorizationRevision)
        assertTrue(captured.get().authorizationEvidenceDigest.length == 64)
        assertFalse(result.toString().contains("ticket-secret"))
    }

    @Test
    fun `high risk direct allow and another principal confirmation fail before application`() {
        val calls = AtomicInteger()
        val suite = suite { command ->
            calls.incrementAndGet()
            unsupported(command)
        }

        val directAllow = suite.executor(WorkflowAgentOperation.PUBLISH_DEFINITION.toolId)!!
            .start(executable(confirmation = false), AgentToolObserver.NOOP)
            .completion().toCompletableFuture().join()
        val otherPrincipal = suite.executor(WorkflowAgentOperation.PUBLISH_DEFINITION.toolId)!!
            .start(executable(approvalOperator = "security-admin"), AgentToolObserver.NOOP)
            .completion().toCompletableFuture().join()

        assertEquals(AgentToolResultStatus.FAILED, directAllow.status)
        assertEquals("WORKFLOW_BINDING_REJECTED", directAllow.safeErrorCode)
        assertEquals(AgentToolResultStatus.FAILED, otherPrincipal.status)
        assertEquals(0, calls.get())
    }

    @Test
    fun `resource revision drift and raw use case failures stay closed and redacted`() {
        val calls = AtomicInteger()
        val suite = suite { _ ->
            calls.incrementAndGet()
            CompletableFuture<WorkflowAgentUseCaseResult>().also { future ->
                future.completeExceptionally(IllegalStateException("jdbc password=ticket-secret"))
            }
        }
        val drifted = suite.executor(WorkflowAgentOperation.PUBLISH_DEFINITION.toolId)!!
            .start(executable(resourceRevision = "f".repeat(64)), AgentToolObserver.NOOP)
            .completion().toCompletableFuture().join()
        val failed = suite.executor(WorkflowAgentOperation.PUBLISH_DEFINITION.toolId)!!
            .start(executable(), AgentToolObserver.NOOP)
            .completion().toCompletableFuture().join()

        assertEquals(AgentToolResultStatus.FAILED, drifted.status)
        assertEquals("WORKFLOW_BINDING_REJECTED", drifted.safeErrorCode)
        assertEquals(AgentToolResultStatus.FAILED, failed.status)
        assertEquals("WORKFLOW_USE_CASE_FAILURE", failed.safeErrorCode)
        assertEquals(1, calls.get())
        assertFalse(failed.toString().contains("ticket-secret"))
        assertFalse(failed.blocks.joinToString().contains("ticket-secret"))
    }

    private fun suite(
        publish: (WorkflowAgentAuthorizedCommand) -> CompletionStage<WorkflowAgentUseCaseResult>,
    ): WorkflowAgentToolSuite {
        val authorization = WorkflowAgentExecutionAuthorizationPort { request ->
            WorkflowAgentExecutionAuthorizationDecision.authorize(
                "workflow-authorization-1",
                request,
                request.context.authorizationRevision,
                "b".repeat(64),
                request.requestedAt,
                650L,
            )
        }
        return WorkflowAgentToolSuite(
            WorkflowAgentToolCatalog(),
            authorization,
            applicationPorts(publish),
            WorkflowAgentClock { 410L },
        )
    }

    private fun applicationPorts(
        publish: (WorkflowAgentAuthorizedCommand) -> CompletionStage<WorkflowAgentUseCaseResult>,
    ): WorkflowAgentApplicationPorts = WorkflowAgentApplicationPorts(
        object : WorkflowAgentDefinitionUseCasePort {
            override fun saveDraft(command: WorkflowAgentAuthorizedCommand) = unsupported(command)
            override fun publish(command: WorkflowAgentAuthorizedCommand) = publish(command)
            override fun retire(command: WorkflowAgentAuthorizedCommand) = unsupported(command)
        },
        object : WorkflowAgentInstanceUseCasePort {
            override fun start(command: WorkflowAgentAuthorizedCommand) = unsupported(command)
            override fun suspend(command: WorkflowAgentAuthorizedCommand) = unsupported(command)
            override fun resume(command: WorkflowAgentAuthorizedCommand) = unsupported(command)
            override fun cancel(command: WorkflowAgentAuthorizedCommand) = unsupported(command)
            override fun terminate(command: WorkflowAgentAuthorizedCommand) = unsupported(command)
        },
        object : WorkflowAgentHumanTaskUseCasePort {
            override fun approve(command: WorkflowAgentAuthorizedCommand) = unsupported(command)
            override fun reject(command: WorkflowAgentAuthorizedCommand) = unsupported(command)
            override fun claim(command: WorkflowAgentAuthorizedCommand) = unsupported(command)
            override fun unclaim(command: WorkflowAgentAuthorizedCommand) = unsupported(command)
            override fun delegate(command: WorkflowAgentAuthorizedCommand) = unsupported(command)
            override fun transfer(command: WorkflowAgentAuthorizedCommand) = unsupported(command)
            override fun addSign(command: WorkflowAgentAuthorizedCommand) = unsupported(command)
            override fun returnTask(command: WorkflowAgentAuthorizedCommand) = unsupported(command)
        },
        WorkflowAgentIncidentUseCasePort { command -> unsupported(command) },
    )

    private fun unsupported(command: WorkflowAgentAuthorizedCommand): CompletionStage<WorkflowAgentUseCaseResult> {
        require(command.authorizationEvidenceDigest.length == 64)
        return CompletableFuture.completedFuture(WorkflowAgentUseCaseResult.rejected("WORKFLOW_UNSUPPORTED"))
    }

    private fun executable(
        confirmation: Boolean = true,
        approvalOperator: String = "principal-1",
        resourceRevision: String? = null,
    ): AgentExecutableToolInvocation {
        val descriptor = WorkflowAgentToolCatalog().descriptor(WorkflowAgentOperation.PUBLISH_DEFINITION.toolId)!!
        val arguments = publishArguments()
        val target = WorkflowAgentAuthorizationTarget.decode(descriptor.toolId, arguments)
        val idempotencyKey = target.idempotencyKey
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
            idempotencyKey,
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
                id("policy-decision-1"), ProviderId("policy.local"), proposal, "policy-r1", 200L, 900L,
            )
        } else {
            AgentPolicyDecision.allow(
                id("policy-decision-1"), ProviderId("policy.local"), proposal, "policy-r1", 200L, 900L,
            )
        }
        val approvalRequest = if (confirmation) AgentApprovalRequest.create(
            id("approval-request-1"),
            proposal,
            policy,
            id(approvalOperator),
            "USER",
            "confirmation-nonce-1",
            250L,
            800L,
        ) else null
        val approvalDecision = approvalRequest?.let { request ->
            AgentApprovalDecision.approve(
                id("approval-decision-1"), request, id(approvalOperator), "USER", 300L,
            )
        }
        val executionRequest = AgentAuthorizationRequest.executionRecheck(
            id("authorization-execution-request-1"), preflight, 325L, 850L,
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
            idempotencyKey,
            1,
            400L,
            700L,
            AgentCancellationToken.NONE,
        )
        val consumption = AgentExecutionContextConsumption.claimed(
            id("execution-consumption-1"), ProviderId("execution-store.local"), invocation, 401L, "store-r1",
        )
        val finalRequest = AgentAuthorizationRequest.finalExecutionRecheck(
            id("authorization-final-request-1"), executionRequest, 402L, 700L,
        )
        val finalDecision = AgentAuthorizationDecision.allow(
            id("authorization-final-1"),
            ProviderId("authorization.local"),
            finalRequest,
            "auth-r1",
            403L,
            700L,
        )
        val dispatchFence = AgentDispatchAuthorizationFenceRequest(
            id("dispatch-fence-1"),
            ProviderId("runtime.worker"),
            invocation,
            finalRequest,
            finalDecision,
            404L,
            700L,
        )
        val dispatchConsumption = AgentDispatchAuthorizationFenceConsumption.consumed(
            id("dispatch-consumption-1"), dispatchFence, 405L, "authorization-store-r1",
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
        "{\"definitionDigest\":\"${"a".repeat(64)}\",\"definitionId\":\"definition-1\"," +
            "\"definitionVersion\":\"2026.07.1\",\"executionNonce\":\"nonce-publish-1\"," +
            "\"expectedDefinitionStateVersion\":7,\"expectedIncidentVersion\":0," +
            "\"expectedInstanceVersion\":0,\"expectedWorkItemVersion\":0," +
            "\"idempotencyKey\":\"idem-publish-1\",\"incidentId\":\"-\",\"instanceId\":\"-\"," +
            "\"operation\":\"workflow.definition.publish\"," +
            "\"payload\":{\"changeTicket\":\"ticket-secret\"}," +
            "\"purpose\":\"publish-approved-draft\",\"resourceId\":\"definition-1\"," +
            "\"resourceType\":\"workflow-definition\",\"workItemId\":\"-\"}"
        ).toByteArray(StandardCharsets.UTF_8)

    private fun id(value: String): Identifier = Identifier(value)

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
