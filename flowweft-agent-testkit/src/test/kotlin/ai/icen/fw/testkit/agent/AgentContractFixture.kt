package ai.icen.fw.testkit.agent

import ai.icen.fw.agent.api.AgentApprovalDecision
import ai.icen.fw.agent.api.AgentApprovalRequest
import ai.icen.fw.agent.api.AgentAtomicDispatchAuthorizationProvider
import ai.icen.fw.agent.api.AgentAuthorizationCall
import ai.icen.fw.agent.api.AgentAuthorizationDecision
import ai.icen.fw.agent.api.AgentAuthorizationProvider
import ai.icen.fw.agent.api.AgentAuthorizationRequest
import ai.icen.fw.agent.api.AgentBudget
import ai.icen.fw.agent.api.AgentCancellation
import ai.icen.fw.agent.api.AgentCancellationToken
import ai.icen.fw.agent.api.AgentCapabilityId
import ai.icen.fw.agent.api.AgentContentOrigin
import ai.icen.fw.agent.api.AgentDescriptorBoundToolExecutor
import ai.icen.fw.agent.api.AgentDispatchAuthorizationFenceConsumption
import ai.icen.fw.agent.api.AgentDispatchAuthorizationFenceRequest
import ai.icen.fw.agent.api.AgentEvaluationCall
import ai.icen.fw.agent.api.AgentEvaluationFinding
import ai.icen.fw.agent.api.AgentEvaluationObserver
import ai.icen.fw.agent.api.AgentEvaluationOutcome
import ai.icen.fw.agent.api.AgentEvaluationRequest
import ai.icen.fw.agent.api.AgentEvaluationResult
import ai.icen.fw.agent.api.AgentEvaluator
import ai.icen.fw.agent.api.AgentEvaluatorDescriptor
import ai.icen.fw.agent.api.AgentExecutableToolInvocation
import ai.icen.fw.agent.api.AgentExecutionContextConsumer
import ai.icen.fw.agent.api.AgentExecutionContextConsumption
import ai.icen.fw.agent.api.AgentFailureCategory
import ai.icen.fw.agent.api.AgentMessage
import ai.icen.fw.agent.api.AgentMessageRole
import ai.icen.fw.agent.api.AgentPolicyCall
import ai.icen.fw.agent.api.AgentPolicyDecision
import ai.icen.fw.agent.api.AgentPolicyProposal
import ai.icen.fw.agent.api.AgentPolicyProvider
import ai.icen.fw.agent.api.AgentProviderException
import ai.icen.fw.agent.api.AgentProviderFailureMapper
import ai.icen.fw.agent.api.AgentProviderOperationId
import ai.icen.fw.agent.api.AgentRunContext
import ai.icen.fw.agent.api.AgentTextContentBlock
import ai.icen.fw.agent.api.AgentToolCall
import ai.icen.fw.agent.api.AgentToolCatalog
import ai.icen.fw.agent.api.AgentToolDescriptor
import ai.icen.fw.agent.api.AgentToolDescriptorProvider
import ai.icen.fw.agent.api.AgentToolObserver
import ai.icen.fw.agent.api.AgentToolResult
import ai.icen.fw.agent.api.AgentToolResultStatus
import ai.icen.fw.agent.api.AgentToolRisk
import ai.icen.fw.agent.api.AgentUsage
import ai.icen.fw.agent.api.AuthorizedToolInvocation
import ai.icen.fw.agent.api.ProviderId
import ai.icen.fw.agent.api.ToolId
import ai.icen.fw.core.id.Identifier
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.atomic.AtomicLong

/** In-memory proof fixture. Published contracts are intended to run unchanged against real providers. */
internal class AgentContractFixture {
    private val sequence = AtomicLong()
    val authorizationProvider: AgentAtomicDispatchAuthorizationProvider =
        LinearizableAuthorizationProvider(::nextId)
    val policyProvider: AgentPolicyProvider = ApprovalPolicyProvider(::nextId)
    val executionContextConsumer: AgentExecutionContextConsumer =
        LinearizableExecutionContextConsumer(::nextId)
    val capability = AgentCapabilityId("document-update")
    private val schema = """{"type":"object","additionalProperties":false}"""
        .toByteArray(StandardCharsets.UTF_8)
    val descriptor = AgentToolDescriptor(
        TOOL_PROVIDER_ID,
        TOOL_ID,
        "Update document",
        "Updates one authorized document through an application use case.",
        AgentToolRisk.REVERSIBLE_WRITE,
        schema,
        sha256(schema),
        setOf(capability),
        true,
        1_024,
        10,
        100,
    )
    val descriptorProvider: AgentToolDescriptorProvider = object : AgentToolDescriptorProvider {
        override fun providerId(): ProviderId = TOOL_PROVIDER_ID

        override fun descriptors(context: AgentRunContext): AgentToolCatalog =
            AgentToolCatalog(TOOL_PROVIDER_ID, listOf(descriptor))
    }
    val toolExecutor: AgentDescriptorBoundToolExecutor = FixtureToolExecutor(descriptor)
    val evaluator: AgentEvaluator = FixtureEvaluator(EVALUATION_PROVIDER_ID, capability)
    val failureMapper: AgentProviderFailureMapper = object : AgentProviderFailureMapper {
        override fun map(
            providerId: ProviderId,
            operationId: AgentProviderOperationId,
            failure: Throwable,
        ): AgentProviderException = AgentProviderException(
            providerId,
            AgentFailureCategory.PROTOCOL,
            "fixture.provider-failed",
        )
    }

    fun newAuthorizationRequest(): AgentAuthorizationRequest = AgentAuthorizationRequest.preflight(
        nextId("authorization-request"),
        nextId("execution-context"),
        TENANT_ID,
        PRINCIPAL_ID,
        "USER",
        nextId("run"),
        nextId("step"),
        AUTHORIZATION_PROVIDER_ID,
        descriptor,
        ARGUMENTS,
        IDEMPOTENCY_KEY,
        "document.update",
        "document",
        DOCUMENT_ID,
        "revision-1",
        "testkit-contract",
        BASE_TIME,
        BASE_TIME + 900,
    )

    fun newPolicyProposal(): AgentPolicyProposal {
        val preflight = newAuthorizationRequest()
        val preflightDecision = authorize(preflight)
        return AgentPolicyProposal.create(
            nextId("policy-proposal"),
            POLICY_PROVIDER_ID,
            preflight,
            preflightDecision,
            descriptor.risk,
            AgentBudget(100, 100, 2, 2, 1_000, 100),
            AgentUsage(),
            preflightDecision.decidedAt + 1,
            preflightDecision.expiresAt - 1,
        )
    }

    fun newAuthorizedInvocation(): AuthorizedToolInvocation {
        val proposal = newPolicyProposal()
        val policyDecision = AgentContractAssertions.awaitStage(
            policyProvider.start(proposal).completion(),
            TIMEOUT,
            "Fixture policy decision",
        )
        val operatorId = nextId("operator")
        val approvalRequest = AgentApprovalRequest.create(
            nextId("approval-request"),
            proposal,
            policyDecision,
            operatorId,
            "USER",
            "fixture-nonce-${sequence.incrementAndGet()}",
            policyDecision.decidedAt + 1,
            policyDecision.expiresAt - 1,
        )
        val approvalDecision = AgentApprovalDecision.approve(
            nextId("approval-decision"),
            approvalRequest,
            operatorId,
            "USER",
            approvalRequest.requestedAt + 1,
        )
        val executionRequest = AgentAuthorizationRequest.executionRecheck(
            nextId("execution-authorization-request"),
            proposal.authorizationRequest,
            approvalDecision.decidedAt + 1,
            approvalRequest.expiresAt - 1,
        )
        val executionDecision = authorize(executionRequest)
        return AuthorizedToolInvocation.authorize(
            nextId("tool-invocation"),
            proposal,
            descriptor,
            policyDecision,
            executionRequest,
            executionDecision,
            approvalRequest,
            approvalDecision,
            ARGUMENTS,
            IDEMPOTENCY_KEY,
            1,
            executionDecision.decidedAt + 1,
            executionDecision.expiresAt - 90,
            AgentCancellationToken.NONE,
        )
    }

    fun newDispatchFenceRequest(): AgentDispatchAuthorizationFenceRequest = newPendingDispatch().fenceRequest

    fun newExecutableInvocation(): AgentExecutableToolInvocation {
        val pending = newPendingDispatch()
        val fenceConsumption = AgentContractAssertions.awaitStage(
            authorizationProvider.consumeDispatchFence(pending.fenceRequest),
            TIMEOUT,
            "Fixture dispatch-fence consumption",
        )
        return AgentExecutableToolInvocation.create(
            pending.invocation,
            pending.executionConsumption,
            pending.finalAuthorizationRequest,
            pending.finalAuthorizationDecision,
            pending.fenceRequest,
            fenceConsumption,
            EXECUTION_CONSUMER_ID,
            DISPATCH_CONSUMER_ID,
            descriptor.maximumCostMicros,
            descriptor.maximumDurationMillis,
            fenceConsumption.consumedAt,
        )
    }

    fun runContext(): AgentRunContext = AgentRunContext(
        TENANT_ID,
        PRINCIPAL_ID,
        "USER",
        nextId("request"),
        BASE_TIME,
        "zh-CN",
    )

    fun evaluationRequest(descriptor: AgentEvaluatorDescriptor): AgentEvaluationRequest = AgentEvaluationRequest(
        nextId("evaluation-request"),
        TENANT_ID,
        nextId("evaluation-run"),
        AgentMessage(
            nextId("evaluation-message"),
            AgentMessageRole.ASSISTANT,
            listOf(AgentTextContentBlock(AgentContentOrigin.MODEL, "fixture answer")),
            BASE_TIME,
        ),
        emptyList(),
        descriptor.criteria,
        BASE_TIME,
        BASE_TIME + 100,
        AgentCancellationToken.NONE,
    )

    private fun newPendingDispatch(): PendingDispatch {
        val invocation = newAuthorizedInvocation()
        val executionConsumption = AgentContractAssertions.awaitStage(
            executionContextConsumer.consume(invocation, invocation.startedAt + 1),
            TIMEOUT,
            "Fixture execution-context claim",
        )
        val finalAuthorizationRequest = AgentAuthorizationRequest.finalExecutionRecheck(
            nextId("final-authorization-request"),
            invocation.executionAuthorizationRequest,
            executionConsumption.consumedAt + 1,
            invocation.deadlineAt,
        )
        val finalAuthorizationDecision = authorize(finalAuthorizationRequest)
        val fenceRequest = AgentDispatchAuthorizationFenceRequest(
            nextId("dispatch-fence"),
            DISPATCH_CONSUMER_ID,
            invocation,
            finalAuthorizationRequest,
            finalAuthorizationDecision,
            finalAuthorizationDecision.decidedAt + 1,
            finalAuthorizationDecision.expiresAt - 1,
        )
        return PendingDispatch(
            invocation,
            executionConsumption,
            finalAuthorizationRequest,
            finalAuthorizationDecision,
            fenceRequest,
        )
    }

    private fun authorize(request: AgentAuthorizationRequest): AgentAuthorizationDecision =
        AgentContractAssertions.awaitStage(
            authorizationProvider.start(request).completion(),
            TIMEOUT,
            "Fixture authorization decision",
        )

    private fun nextId(label: String): Identifier = Identifier("fixture-$label-${sequence.incrementAndGet()}")

    private class PendingDispatch(
        val invocation: AuthorizedToolInvocation,
        val executionConsumption: AgentExecutionContextConsumption,
        val finalAuthorizationRequest: AgentAuthorizationRequest,
        val finalAuthorizationDecision: AgentAuthorizationDecision,
        val fenceRequest: AgentDispatchAuthorizationFenceRequest,
    )

    companion object {
        val AUTHORIZATION_PROVIDER_ID = ProviderId("fixture-authorization-provider")
        val POLICY_PROVIDER_ID = ProviderId("fixture-policy-provider")
        val TOOL_PROVIDER_ID = ProviderId("fixture-tool-provider")
        val EXECUTION_CONSUMER_ID = ProviderId("fixture-execution-consumer")
        val DISPATCH_CONSUMER_ID = ProviderId("fixture-dispatch-consumer")
        val EVALUATION_PROVIDER_ID = ProviderId("fixture-evaluation-provider")
        val TOOL_ID = ToolId("document-update")
        val TENANT_ID = Identifier("fixture-tenant")
        val PRINCIPAL_ID = Identifier("fixture-principal")
        val DOCUMENT_ID = Identifier("fixture-document")
        val ARGUMENTS: ByteArray = """{"documentId":"fixture-document"}"""
            .toByteArray(StandardCharsets.UTF_8)
        const val IDEMPOTENCY_KEY: String = "fixture-idempotency-key"
        const val BASE_TIME: Long = 1_000
        val TIMEOUT: Duration = Duration.ofSeconds(5)

        private fun sha256(value: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(value)
            .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }
}

private class LinearizableAuthorizationProvider(
    private val nextId: (String) -> Identifier,
) : AgentAtomicDispatchAuthorizationProvider {
    private val lock = Any()
    private val fences = LinkedHashMap<String, FenceRecord>()

    override fun providerId(): ProviderId = AgentContractFixture.AUTHORIZATION_PROVIDER_ID

    override fun start(request: AgentAuthorizationRequest): AgentAuthorizationCall {
        val decision = AgentAuthorizationDecision.allow(
            nextId("authorization-decision"),
            providerId(),
            request,
            "authorization-revision-1",
            request.requestedAt + 1,
            request.expiresAt - 1,
        )
        return object : AgentAuthorizationCall {
            override fun completion(): CompletionStage<AgentAuthorizationDecision> = completed(decision)

            override fun cancel(cancellation: AgentCancellation): CompletionStage<Boolean> = completed(false)
        }
    }

    override fun consumeDispatchFence(
        request: AgentDispatchAuthorizationFenceRequest,
    ): CompletionStage<AgentDispatchAuthorizationFenceConsumption> = completed(
        synchronized(lock) {
            val key = request.fenceId.value
            val existing = fences[key]
            if (existing == null) {
                val created = FenceRecord(
                    nextId("dispatch-receipt"),
                    request.bindingDigest,
                    request.requestedAt + 1,
                    "dispatch-revision-1",
                )
                fences[key] = created
                AgentDispatchAuthorizationFenceConsumption.consumed(
                    created.receiptId,
                    request,
                    created.consumedAt,
                    created.providerRevision,
                )
            } else {
                require(existing.bindingDigest == request.bindingDigest) {
                    "Fixture dispatch fence key was replayed with different evidence."
                }
                AgentDispatchAuthorizationFenceConsumption.replayed(
                    existing.receiptId,
                    request,
                    existing.consumedAt,
                    existing.providerRevision,
                )
            }
        },
    )

    private class FenceRecord(
        val receiptId: Identifier,
        val bindingDigest: String,
        val consumedAt: Long,
        val providerRevision: String,
    )
}

private class ApprovalPolicyProvider(
    private val nextId: (String) -> Identifier,
) : AgentPolicyProvider {
    override fun providerId(): ProviderId = AgentContractFixture.POLICY_PROVIDER_ID

    override fun start(proposal: AgentPolicyProposal): AgentPolicyCall {
        val decision = AgentPolicyDecision.requireApproval(
            nextId("policy-decision"),
            providerId(),
            proposal,
            "policy-revision-1",
            proposal.requestedAt + 1,
            proposal.expiresAt - 1,
        )
        return object : AgentPolicyCall {
            override fun completion(): CompletionStage<AgentPolicyDecision> = completed(decision)

            override fun cancel(cancellation: AgentCancellation): CompletionStage<Boolean> = completed(false)
        }
    }
}

private class LinearizableExecutionContextConsumer(
    private val nextId: (String) -> Identifier,
) : AgentExecutionContextConsumer {
    private val lock = Any()
    private val claims = LinkedHashMap<String, ClaimRecord>()

    override fun consumerId(): ProviderId = AgentContractFixture.EXECUTION_CONSUMER_ID

    override fun consume(
        invocation: AuthorizedToolInvocation,
        consumedAt: Long,
    ): CompletionStage<AgentExecutionContextConsumption> = completed(
        synchronized(lock) {
            val key = "${invocation.tenantId.value}:${invocation.executionContextId.value}"
            val existing = claims[key]
            if (existing == null) {
                val created = ClaimRecord(
                    nextId("execution-receipt"),
                    invocation.logicalInvocationDigest,
                    consumedAt,
                    "execution-consumer-revision-1",
                )
                claims[key] = created
                AgentExecutionContextConsumption.claimed(
                    created.receiptId,
                    consumerId(),
                    invocation,
                    created.consumedAt,
                    created.consumerRevision,
                )
            } else {
                require(existing.logicalInvocationDigest == invocation.logicalInvocationDigest) {
                    "Fixture execution context was replayed with different invocation evidence."
                }
                AgentExecutionContextConsumption.replayed(
                    existing.receiptId,
                    consumerId(),
                    invocation,
                    existing.consumedAt,
                    existing.consumerRevision,
                )
            }
        },
    )

    private class ClaimRecord(
        val receiptId: Identifier,
        val logicalInvocationDigest: String,
        val consumedAt: Long,
        val consumerRevision: String,
    )
}

private class FixtureToolExecutor(
    private val descriptor: AgentToolDescriptor,
) : AgentDescriptorBoundToolExecutor {
    override fun providerId(): ProviderId = descriptor.providerId

    override fun toolId(): ToolId = descriptor.toolId

    override fun descriptorDigest(): String = descriptor.descriptorDigest

    override fun start(
        invocation: AgentExecutableToolInvocation,
        observer: AgentToolObserver,
    ): AgentToolCall {
        invocation.requireExecutor(providerId(), toolId())
        val result = AgentToolResult(
            invocation.invocation.invocationId,
            AgentToolResultStatus.SUCCEEDED,
            listOf(AgentTextContentBlock(AgentContentOrigin.TOOL, "updated")),
            invocation.preparedAt + 1,
            usage = AgentUsage(toolCalls = 1, durationMillis = 1, costMicros = 1),
        )
        return object : AgentToolCall {
            override fun invocationId(): Identifier = invocation.invocation.invocationId

            override fun completion(): CompletionStage<AgentToolResult> = completed(result)

            override fun cancel(cancellation: AgentCancellation): CompletionStage<Boolean> = completed(false)
        }
    }
}

private class FixtureEvaluator(
    private val providerId: ProviderId,
    private val criterion: AgentCapabilityId,
) : AgentEvaluator {
    private val descriptor = AgentEvaluatorDescriptor(providerId, setOf(criterion), 0)

    override fun descriptor(): AgentEvaluatorDescriptor = descriptor

    override fun start(
        request: AgentEvaluationRequest,
        observer: AgentEvaluationObserver,
    ): AgentEvaluationCall {
        val result = AgentEvaluationResult(
            request.requestId,
            providerId,
            request.criteria.associateWith { 1.0 },
            listOf(AgentEvaluationFinding(criterion, AgentEvaluationOutcome.PASS, "fixture.pass")),
            request.requestedAt + 1,
            "fixture evaluation passed",
        )
        return object : AgentEvaluationCall {
            override fun requestId(): Identifier = request.requestId

            override fun completion(): CompletionStage<AgentEvaluationResult> = completed(result)

            override fun cancel(cancellation: AgentCancellation): CompletionStage<Boolean> = completed(false)
        }
    }
}

private fun <T : Any> completed(value: T): CompletionStage<T> = CompletableFuture.completedFuture(value)
