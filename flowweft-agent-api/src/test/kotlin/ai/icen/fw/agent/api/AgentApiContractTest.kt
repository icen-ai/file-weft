package ai.icen.fw.agent.api

import ai.icen.fw.core.id.Identifier
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class AgentApiContractTest {

    @Test
    fun `content message and run binding digests cover canonical payload`() {
        val firstBlock = AgentTextContentBlock(AgentContentOrigin.USER, "first prompt")
        val secondBlock = AgentTextContentBlock(AgentContentOrigin.USER, "second prompt")
        assertNotEquals(firstBlock.bindingDigest(), secondBlock.bindingDigest())

        val messageId = id("message-binding")
        val firstMessage = AgentMessage(messageId, AgentMessageRole.USER, listOf(firstBlock), 10)
        val secondMessage = AgentMessage(messageId, AgentMessageRole.USER, listOf(secondBlock), 10)
        assertNotEquals(firstMessage.bindingDigest, secondMessage.bindingDigest)

        val context = AgentRunContext(id("tenant-binding"), id("user-binding"), "USER", id("request-binding"), 10)
        val firstRequest = AgentRunRequest(
            context,
            AgentCapabilityId("agent.answer"),
            listOf(firstMessage),
            budget(),
            "binding-key",
            1_000,
            AgentCancellationToken.NONE,
        )
        val secondRequest = AgentRunRequest(
            context,
            AgentCapabilityId("agent.answer"),
            listOf(secondMessage),
            budget(),
            "binding-key",
            1_000,
            AgentCancellationToken.NONE,
        )
        assertNotEquals(firstRequest.bindingDigest, secondRequest.bindingDigest)

        assertFailsWith<IllegalArgumentException> {
            AgentMessage(
                id("message-invalid-extension"),
                AgentMessageRole.USER,
                listOf(object : AgentContentBlock {
                    override fun kind(): String = "invalid-extension"
                    override fun origin(): AgentContentOrigin = AgentContentOrigin.USER
                    override fun bindingDigest(): String = "not-a-sha256"
                }),
                10,
            )
        }
    }

    @Test
    fun `contracts defensively snapshot collections maps and byte arrays`() {
        val capabilityValues = mutableListOf(AgentCapabilityId("agent.answer"))
        val schema = "{\"type\":\"object\"}".toByteArray(StandardCharsets.UTF_8)
        val originalSchema = schema.copyOf()
        val descriptor = AgentToolDescriptor(
            ProviderId("provider.local"),
            ToolId("document.read"),
            "Read document",
            "Reads one already-authorized document.",
            AgentToolRisk.READ_ONLY,
            schema,
            digest(schema),
            capabilityValues,
            true,
            4_096,
        )
        val blocks = mutableListOf<AgentContentBlock>(AgentTextContentBlock(AgentContentOrigin.USER, "hello"))
        val message = AgentMessage(id("message-1"), AgentMessageRole.USER, blocks, 10)
        val usageDimensions = linkedMapOf("cached.tokens" to 4L)
        val usage = AgentUsage(additionalUnits = usageDimensions)
        val scores = linkedMapOf(AgentCapabilityId("evaluation.groundedness") to 0.9)
        val evaluation = AgentEvaluationResult(
            id("evaluation-1"),
            ProviderId("evaluator.local"),
            scores,
            emptyList(),
            20,
        )

        schema[0] = 0
        descriptor.inputSchema[1] = 0
        capabilityValues += AgentCapabilityId("agent.other")
        blocks.clear()
        usageDimensions["cached.tokens"] = 99
        scores.clear()

        assertContentEquals(originalSchema, descriptor.inputSchema)
        assertEquals(setOf(AgentCapabilityId("agent.answer")), descriptor.capabilities)
        assertEquals(1, message.blocks.size)
        assertEquals(4L, usage.additionalUnits["cached.tokens"])
        assertEquals(0.9, evaluation.scores[AgentCapabilityId("evaluation.groundedness")])
        assertFailsWith<UnsupportedOperationException> {
            @Suppress("UNCHECKED_CAST")
            (usage.additionalUnits as MutableMap<String, Long>)["other"] = 1
        }

        val binaryBytes = "payload".toByteArray(StandardCharsets.UTF_8)
        val originalBinary = binaryBytes.copyOf()
        val binary = AgentBinaryContentBlock(
            AgentContentOrigin.USER,
            "application/octet-stream",
            binaryBytes,
            digest(binaryBytes),
        )
        binaryBytes[0] = 0
        binary.data[1] = 0
        assertContentEquals(originalBinary, binary.data)
    }

    @Test
    fun `invalid run states and unsafe unicode fail closed`() {
        val tenantId = id("tenant-1")
        val capability = AgentCapabilityId("agent.answer")

        assertFalse(AgentRunStatus.COMPLETED.canTransitionTo(AgentRunStatus.RUNNING))
        assertFailsWith<IllegalArgumentException> {
            AgentRunStatusChangedEvent(
                id("run-1"),
                tenantId,
                1,
                10,
                AgentRunStatus.COMPLETED,
                AgentRunStatus.RUNNING,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AgentRunSnapshot(
                id("run-1"),
                tenantId,
                capability,
                AgentRunStatus.FAILED,
                emptyList(),
                budget(),
                AgentUsage(),
                1,
                10,
                20,
            )
        }
        assertFailsWith<IllegalArgumentException> { ProviderId("provider\uFDD0unsafe") }
        assertFailsWith<IllegalArgumentException> { ProviderId("\u2003provider") }
        assertFailsWith<IllegalArgumentException> {
            AgentMessage(
                id("message-1"),
                AgentMessageRole.SYSTEM,
                listOf(AgentTextContentBlock(AgentContentOrigin.RETRIEVAL, "untrusted")),
                10,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AgentRunRequest(
                AgentRunContext(tenantId, id("user-1"), "USER", id("request-duration"), 10),
                capability,
                listOf(
                    AgentMessage(
                        id("message-duration"),
                        AgentMessageRole.USER,
                        listOf(AgentTextContentBlock(AgentContentOrigin.USER, "hello")),
                        10,
                    ),
                ),
                budget(),
                "duration-bound",
                60_011,
                AgentCancellationToken.NONE,
            )
        }
    }

    @Test
    fun `three phase authorization approval and fresh consumption bind one execution`() {
        val fixture = approvalFixture()
        val originalArguments = fixture.arguments.copyOf()
        val invocation = fixture.invocation

        fixture.arguments[0] = 0
        assertContentEquals(originalArguments, invocation.arguments)
        assertEquals(fixture.preflight.bindingDigest, fixture.executionRecheck.bindingDigest)
        assertEquals(AgentAuthorizationPhase.POLICY_PREFLIGHT, fixture.preflight.phase)
        assertEquals(AgentAuthorizationPhase.EXECUTION_RECHECK, fixture.executionRecheck.phase)
        assertEquals(fixture.preflight.requestId, fixture.executionRecheck.parentRequestId)
        assertEquals("resource-revision-7", invocation.authorizationResourceRevision)
        assertEquals(fixture.descriptor.descriptorDigest, invocation.descriptorDigest)

        val claimed = AgentExecutionContextConsumption.claimed(
            id("receipt-1"),
            ProviderId("execution-store.local"),
            invocation,
            400,
            "store-revision-1",
        )
        val finalRecheck = AgentAuthorizationRequest.finalExecutionRecheck(
            id("authorization-final-request-1"),
            fixture.executionRecheck,
            401,
            700,
        )
        val finalAuthorization = AgentAuthorizationDecision.allow(
            id("authorization-final-1"),
            ProviderId("authorization.local"),
            finalRecheck,
            "authorization-3",
            402,
            700,
        )
        val dispatchFence = AgentDispatchAuthorizationFenceRequest(
            id("dispatch-fence-1"),
            ProviderId("runtime.worker"),
            invocation,
            finalRecheck,
            finalAuthorization,
            403,
            700,
        )
        val dispatchReceipt = AgentDispatchAuthorizationFenceConsumption.consumed(
            id("dispatch-fence-receipt-1"),
            dispatchFence,
            404,
            "authorization-store-1",
        )
        val executable = AgentExecutableToolInvocation.create(
            invocation,
            claimed,
            finalRecheck,
            finalAuthorization,
            dispatchFence,
            dispatchReceipt,
            ProviderId("execution-store.local"),
            ProviderId("runtime.worker"),
            fixture.descriptor.maximumCostMicros,
            295,
            405,
        )
        assertEquals(invocation, executable.invocation)
        assertEquals(AgentAuthorizationPhase.FINAL_EXECUTION_RECHECK, finalRecheck.phase)
        assertEquals(fixture.executionRecheck.requestId, finalRecheck.parentRequestId)
        assertEquals(fixture.executionRecheck.bindingDigest, finalRecheck.bindingDigest)
        assertTrue(finalRecheck.requestedAt > claimed.consumedAt)

        val replayed = AgentExecutionContextConsumption.replayed(
            id("receipt-1"),
            ProviderId("execution-store.local"),
            invocation,
            400,
            "store-revision-1",
        )
        assertFailsWith<IllegalArgumentException> {
            AgentExecutableToolInvocation.create(
                invocation,
                replayed,
                finalRecheck,
                finalAuthorization,
                dispatchFence,
                dispatchReceipt,
                ProviderId("execution-store.local"),
                ProviderId("runtime.worker"),
                fixture.descriptor.maximumCostMicros,
                295,
                405,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AgentExecutableToolInvocation.create(
                invocation,
                claimed,
                finalRecheck,
                finalAuthorization,
                dispatchFence,
                dispatchReceipt,
                ProviderId("execution-store.other"),
                ProviderId("runtime.worker"),
                fixture.descriptor.maximumCostMicros,
                295,
                405,
            )
        }
        val replayedDispatch = AgentDispatchAuthorizationFenceConsumption.replayed(
            id("dispatch-fence-receipt-1"),
            dispatchFence,
            404,
            "authorization-store-1",
        )
        assertFailsWith<IllegalArgumentException> {
            AgentExecutableToolInvocation.create(
                invocation,
                claimed,
                finalRecheck,
                finalAuthorization,
                dispatchFence,
                replayedDispatch,
                ProviderId("execution-store.local"),
                ProviderId("runtime.worker"),
                fixture.descriptor.maximumCostMicros,
                295,
                405,
            )
        }

        val safeText = listOf(
            fixture.preflight.toString(),
            fixture.initialAuthorization.toString(),
            fixture.proposal.toString(),
            fixture.policyDecision.toString(),
            fixture.approvalRequest.toString(),
            fixture.approvalDecision.toString(),
            invocation.toString(),
            claimed.toString(),
            dispatchFence.toString(),
            dispatchReceipt.toString(),
            executable.toString(),
        ).joinToString("|")
        assertFalse(safeText.contains("tenant-1"))
        assertFalse(safeText.contains("document-1"))
        assertFalse(safeText.contains(fixture.preflight.argumentsDigest))
        assertFalse(safeText.contains("nonce-1"))
    }

    @Test
    fun `provider substitution stale rechecks changed input and replay paths fail closed`() {
        val fixture = approvalFixture()

        assertFailsWith<IllegalArgumentException> {
            AgentAuthorizationDecision.allow(
                id("authorization-forged-provider"),
                ProviderId("authorization.other"),
                fixture.preflight,
                "authorization-3",
                75,
                950,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AgentPolicyDecision.requireApproval(
                id("policy-forged-provider"),
                ProviderId("policy.other"),
                fixture.proposal,
                "policy-7",
                200,
                900,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            fixture.initialAuthorization.requireAllowedFor(fixture.preflight, fixture.initialAuthorization.expiresAt)
        }
        assertFailsWith<IllegalArgumentException> {
            AgentPolicyProposal.create(
                id("proposal-no-tool-budget"),
                ProviderId("policy.local"),
                fixture.preflight,
                fixture.initialAuthorization,
                AgentToolRisk.REVERSIBLE_WRITE,
                AgentBudget(1_000, 100, 1, 0, 1_000),
                AgentUsage(),
                100,
                900,
            )
        }
        assertFailsWith<AgentCancellationException> {
            authorize(
                fixture,
                cancellationToken = object : AgentCancellationToken {
                    override fun cancellation(): AgentCancellation = AgentCancellation("operator.cancelled", 399)
                },
            )
        }

        val changedArguments = "{\"documentId\":\"document-2\"}".toByteArray(StandardCharsets.UTF_8)
        assertFailsWith<IllegalArgumentException> {
            authorize(fixture, arguments = changedArguments)
        }
        assertFailsWith<IllegalArgumentException> {
            authorize(fixture, idempotencyKey = "tenant-1:run-1:step-1:other-operation")
        }

        val staleRevisionDecision = AgentAuthorizationDecision.allow(
            id("authorization-stale-revision"),
            ProviderId("authorization.local"),
            fixture.executionRecheck,
            "authorization-4",
            350,
            850,
        )
        assertFailsWith<IllegalArgumentException> {
            authorize(fixture, executionAuthorizationDecision = staleRevisionDecision)
        }

        val prematureRecheck = AgentAuthorizationRequest.executionRecheck(
            id("authorization-execution-premature"),
            fixture.preflight,
            275,
            850,
        )
        val prematureDecision = AgentAuthorizationDecision.allow(
            id("authorization-premature"),
            ProviderId("authorization.local"),
            prematureRecheck,
            "authorization-3",
            325,
            850,
        )
        assertFailsWith<IllegalArgumentException> {
            authorize(
                fixture,
                executionAuthorizationRequest = prematureRecheck,
                executionAuthorizationDecision = prematureDecision,
            )
        }

        assertFailsWith<IllegalArgumentException> {
            AuthorizedToolInvocation.authorize(
                id("invocation-reused-preflight"),
                fixture.proposal,
                fixture.descriptor,
                fixture.policyDecision,
                fixture.preflight,
                fixture.initialAuthorization,
                fixture.approvalRequest,
                fixture.approvalDecision,
                fixture.arguments,
                fixture.idempotencyKey,
                1,
                400,
                700,
                AgentCancellationToken.NONE,
            )
        }

        val otherPrincipalPreflight = AgentAuthorizationRequest.preflight(
            id("authorization-request-other"),
            id("execution-context-other"),
            id("tenant-1"),
            id("principal-2"),
            "USER",
            id("run-1"),
            id("step-1"),
            ProviderId("authorization.local"),
            fixture.descriptor,
            fixture.arguments,
            fixture.idempotencyKey,
            "document:publish",
            "document",
            id("document-1"),
            "resource-revision-7",
            "agent.tool.document-publish",
            50,
            1_000,
        )
        val otherPrincipalRecheck = AgentAuthorizationRequest.executionRecheck(
            id("authorization-execution-other"),
            otherPrincipalPreflight,
            325,
            850,
        )
        val otherPrincipalDecision = AgentAuthorizationDecision.allow(
            id("authorization-other-principal"),
            ProviderId("authorization.local"),
            otherPrincipalRecheck,
            "authorization-3",
            350,
            850,
        )
        assertFailsWith<IllegalArgumentException> {
            authorize(
                fixture,
                executionAuthorizationRequest = otherPrincipalRecheck,
                executionAuthorizationDecision = otherPrincipalDecision,
            )
        }

        val changedDescriptor = AgentToolDescriptor(
            fixture.descriptor.providerId,
            fixture.descriptor.toolId,
            "Publish a different operation",
            "A changed human-visible contract must invalidate approval.",
            fixture.descriptor.risk,
            fixture.descriptor.inputSchema,
            fixture.descriptor.schemaDigest,
            fixture.descriptor.capabilities,
            fixture.descriptor.idempotent,
            fixture.descriptor.maximumResultBytes,
        )
        assertFalse(changedDescriptor.descriptorDigest == fixture.descriptor.descriptorDigest)
        assertFailsWith<IllegalArgumentException> {
            authorize(fixture, descriptor = changedDescriptor)
        }
    }

    @Test
    fun `citation and evaluation never accept cross tenant evidence`() {
        val output = AgentMessage(
            id("message-output"),
            AgentMessageRole.ASSISTANT,
            listOf(AgentTextContentBlock(AgentContentOrigin.MODEL, "answer")),
            20,
        )
        val citation = AgentCitation(
            id("citation-1"),
            id("tenant-other"),
            id("document-1"),
            id("version-1"),
            id("evidence-1"),
            digest("content".toByteArray(StandardCharsets.UTF_8)),
        )

        assertFailsWith<IllegalArgumentException> {
            AgentEvaluationRequest(
                id("evaluation-request-1"),
                id("tenant-1"),
                id("run-1"),
                output,
                listOf(citation),
                setOf(AgentCapabilityId("evaluation.groundedness")),
                30,
                100,
                AgentCancellationToken.NONE,
            )
        }
    }

    @Test
    fun `reserved block kinds cannot be forged by open extensions`() {
        val forgedCitation = object : AgentContentBlock {
            override fun kind(): String = AgentCitationContentBlock.KIND
            override fun origin(): AgentContentOrigin = AgentContentOrigin.RETRIEVAL
            override fun bindingDigest(): String = digest("forged-citation".toByteArray(StandardCharsets.UTF_8))
        }

        assertFailsWith<IllegalArgumentException> {
            AgentMessage(
                id("forged-citation-message"),
                AgentMessageRole.CONTEXT,
                listOf(forgedCitation),
                10,
            )
        }
    }

    @Test
    fun `mutable extension binding is detected before a provider boundary`() {
        class MutableBlock(var payload: String) : AgentContentBlock {
            override fun kind(): String = "extension.mutable"
            override fun origin(): AgentContentOrigin = AgentContentOrigin.USER
            override fun bindingDigest(): String = digest(payload.toByteArray(StandardCharsets.UTF_8))
        }
        val block = MutableBlock("approved-payload")
        val message = AgentMessage(id("mutable-message"), AgentMessageRole.USER, listOf(block), 10)
        block.payload = "mutated-secret-payload"

        assertFailsWith<IllegalArgumentException> { message.requireBindingIntact() }
        assertFailsWith<IllegalArgumentException> {
            LanguageModelRequest(
                id("mutable-model-request"),
                id("tenant-mutable"),
                ProviderId("model.local"),
                ModelId("model.local"),
                listOf(message),
                emptyList(),
                100,
                20,
                100,
                AgentCancellationToken.NONE,
            )
        }
    }

    @Test
    fun `evaluation revalidates mutable output before trusting a provider result`() {
        var payload = "safe"
        val block = object : AgentContentBlock {
            override fun kind(): String = "extension.mutable-evaluation"
            override fun origin(): AgentContentOrigin = AgentContentOrigin.MODEL
            override fun bindingDigest(): String = digest(payload.toByteArray(StandardCharsets.UTF_8))
        }
        val criterion = AgentCapabilityId("evaluation.grounded")
        val request = AgentEvaluationRequest(
            id("evaluation-request-mutable"),
            id("tenant-evaluation"),
            id("run-evaluation"),
            AgentMessage(
                id("evaluation-output"),
                AgentMessageRole.ASSISTANT,
                listOf(block),
                10,
            ),
            emptyList(),
            listOf(criterion),
            10,
            20,
            AgentCancellationToken.NONE,
        )
        val descriptor = AgentEvaluatorDescriptor(
            ProviderId("evaluator.local"),
            listOf(criterion),
            0,
        )
        val result = AgentEvaluationResult(
            request.requestId,
            descriptor.providerId,
            mapOf(criterion to 1.0),
            emptyList(),
            15,
        )
        payload = "poisoned"

        assertFailsWith<IllegalArgumentException> {
            result.requireValidFor(request, descriptor)
        }
    }

    @Test
    fun `model response metering cannot exceed exact request reservations`() {
        val descriptor = LanguageModelDescriptor(
            ProviderId("model.local"),
            ModelId("model.local"),
            "Local model",
            setOf(AgentCapabilityId("agent.answer")),
            100,
            100,
            false,
            false,
        )
        val request = LanguageModelRequest(
            id("reserved-model-request"),
            id("tenant-reserved-model"),
            descriptor.providerId,
            descriptor.modelId,
            listOf(
                AgentMessage(
                    id("reserved-model-message"),
                    AgentMessageRole.USER,
                    listOf(AgentTextContentBlock(AgentContentOrigin.USER, "hello")),
                    10,
                ),
            ),
            emptyList(),
            3,
            20,
            100,
            AgentCancellationToken.NONE,
            0.0,
            5,
            10,
        )
        val responseMessage = AgentMessage(
            id("reserved-model-response"),
            AgentMessageRole.ASSISTANT,
            listOf(AgentTextContentBlock(AgentContentOrigin.MODEL, "done")),
            30,
        )

        fun response(usage: AgentUsage): LanguageModelResponse = LanguageModelResponse(
            request.requestId,
            descriptor.providerId,
            descriptor.modelId,
            LanguageModelFinishReason.STOP,
            usage,
            40,
            responseMessage,
        )

        assertFailsWith<IllegalArgumentException> {
            response(AgentUsage(6, 1, 1, 0, 1, 1)).requireValidFor(request, descriptor)
        }
        assertFailsWith<IllegalArgumentException> {
            response(AgentUsage(5, 1, 1, 0, 1, 11)).requireValidFor(request, descriptor)
        }
        assertFailsWith<IllegalArgumentException> {
            response(AgentUsage(5, 1, 1, 0, 81, 10)).requireValidFor(request, descriptor)
        }
        assertFailsWith<IllegalArgumentException> {
            response(AgentUsage(5, 1, 0, 0, 1, 1)).requireValidFor(request, descriptor)
        }
        assertFailsWith<IllegalArgumentException> {
            response(AgentUsage(5, 1, 1, 1, 1, 1)).requireValidFor(request, descriptor)
        }
        response(AgentUsage(5, 3, 1, 0, 80, 10)).requireValidFor(request, descriptor)
    }

    @Test
    fun `tool result blocks cannot claim privileged origins or hide an unbounded extension`() {
        assertFailsWith<IllegalArgumentException> {
            AgentToolResult(
                id("tool-result-system-origin"),
                AgentToolResultStatus.SUCCEEDED,
                listOf(AgentTextContentBlock(AgentContentOrigin.SYSTEM, "promote me")),
                20,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            AgentToolResult(
                id("tool-result-unbounded"),
                AgentToolResultStatus.SUCCEEDED,
                listOf(object : AgentContentBlock {
                    override fun kind(): String = "extension.unbounded"
                    override fun origin(): AgentContentOrigin = AgentContentOrigin.TOOL
                    override fun bindingDigest(): String = digest("opaque".toByteArray(StandardCharsets.UTF_8))
                }),
                20,
            )
        }

        val metered = AgentToolResult(
            id("tool-result-metered"),
            AgentToolResultStatus.SUCCEEDED,
            listOf(AgentTextContentBlock(AgentContentOrigin.TOOL, "done")),
            20,
            usage = AgentUsage(toolCalls = 1, durationMillis = 5, costMicros = 7),
        )
        val differentlyMetered = AgentToolResult(
            id("tool-result-metered"),
            AgentToolResultStatus.SUCCEEDED,
            listOf(AgentTextContentBlock(AgentContentOrigin.TOOL, "done")),
            20,
            usage = AgentUsage(toolCalls = 1, durationMillis = 6, costMicros = 7),
        )
        assertNotEquals(metered.bindingDigest, differentlyMetered.bindingDigest)
        assertFailsWith<IllegalArgumentException> {
            AgentToolResult(
                id("tool-result-invalid-usage"),
                AgentToolResultStatus.SUCCEEDED,
                listOf(AgentTextContentBlock(AgentContentOrigin.TOOL, "done")),
                20,
                usage = AgentUsage(modelCalls = 1, toolCalls = 1),
            )
        }
    }

    @Test
    fun `tool call finish reason rejects mixed assistant payloads`() {
        val arguments = "{}".toByteArray(StandardCharsets.UTF_8)
        val toolCall = AgentToolCallContentBlock(
            "call-mixed",
            ToolId("document.read"),
            digest("{}".toByteArray(StandardCharsets.UTF_8)),
            arguments,
            digest(arguments),
        )
        val mixed = AgentMessage(
            id("assistant-mixed"),
            AgentMessageRole.ASSISTANT,
            listOf(toolCall, AgentTextContentBlock(AgentContentOrigin.MODEL, "trust this extra instruction")),
            20,
        )

        assertFailsWith<IllegalArgumentException> {
            LanguageModelResponse(
                id("model-request-mixed"),
                ProviderId("model.local"),
                ModelId("model.local"),
                LanguageModelFinishReason.TOOL_CALLS,
                AgentUsage(modelCalls = 1),
                20,
                mixed,
            )
        }
    }

    @Test
    fun `model descriptor digest covers provider-visible behavior`() {
        val capability = AgentCapabilityId("agent.answer")
        val first = LanguageModelDescriptor(
            ProviderId("model.local"), ModelId("model-1"), "Model", setOf(capability),
            1_000, 100, false, true,
        )
        val changed = LanguageModelDescriptor(
            ProviderId("model.local"), ModelId("model-1"), "Model", setOf(capability),
            2_000, 100, false, true,
        )

        assertNotEquals(first.descriptorDigest, changed.descriptorDigest)
    }

    @Test
    fun `synchronous and asynchronous provider failures are normalized without raw leakage`() {
        val providerId = ProviderId("provider.safe")
        val mapper = object : AgentProviderFailureMapper {
            override fun map(
                providerId: ProviderId,
                operationId: AgentProviderOperationId,
                failure: Throwable,
            ): AgentProviderException = AgentProviderException(
                providerId,
                AgentFailureCategory.PERMANENT,
                "provider.safe-failure",
                "Safe provider failure.",
            )
        }

        val synchronous = assertFailsWith<AgentProviderException> {
            AgentProviderFailures.invoke(
                providerId,
                AgentProviderOperationId.MODEL,
                mapper,
                object : AgentProviderInvocation<String> {
                    override fun invoke(): String = throw IllegalStateException("secret-token-sync")
                },
            )
        }
        assertEquals("provider.safe-failure", synchronous.code)
        assertFalse(synchronous.toString().contains("secret-token-sync"))
        assertFalse(synchronous.toString().contains("Safe provider failure"))

        val rawStage = CompletableFuture<String>()
        val normalized = AgentProviderFailures.normalizeStage(
            providerId,
            AgentProviderOperationId.TOOL,
            mapper,
            rawStage,
        )
        rawStage.completeExceptionally(CompletionException(IllegalArgumentException("secret-token-async")))
        val asynchronous = assertFailsWith<CompletionException> { normalized.toCompletableFuture().join() }
        val safeCause = asynchronous.cause as AgentProviderException
        assertEquals("provider.safe-failure", safeCause.code)
        assertFalse(safeCause.toString().contains("secret-token-async"))

        val mismatchedMapper = object : AgentProviderFailureMapper {
            override fun map(
                providerId: ProviderId,
                operationId: AgentProviderOperationId,
                failure: Throwable,
            ): AgentProviderException = AgentProviderException(
                ProviderId("provider.other"),
                AgentFailureCategory.PERMANENT,
                "provider.wrong",
            )
        }
        val mismatch = AgentProviderFailures.normalize(
            providerId,
            AgentProviderOperationId.EVALUATION,
            mismatchedMapper,
            IllegalStateException("secret-token-mismatch"),
        )
        assertEquals("provider.mapper-mismatch", mismatch.code)
        assertFalse(mismatch.toString().contains("secret-token-mismatch"))
    }

    private fun approvalFixture(): ApprovalFixture {
        val arguments = "{\"documentId\":\"document-1\"}".toByteArray(StandardCharsets.UTF_8)
        val schema = "{\"type\":\"object\"}".toByteArray(StandardCharsets.UTF_8)
        val descriptor = AgentToolDescriptor(
            ProviderId("tools.local"),
            ToolId("document.publish"),
            "Publish document",
            "Publishes one already-authorized document.",
            AgentToolRisk.REVERSIBLE_WRITE,
            schema,
            digest(schema),
            setOf(AgentCapabilityId("agent.answer")),
            true,
            4_096,
        )
        val idempotencyKey = "tenant-1:run-1:step-1:document.publish"
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
            "document:publish",
            "document",
            id("document-1"),
            "resource-revision-7",
            "agent.tool.document-publish",
            50,
            1_000,
        )
        val initialAuthorization = AgentAuthorizationDecision.allow(
            id("authorization-initial-1"),
            ProviderId("authorization.local"),
            preflight,
            "authorization-3",
            75,
            950,
        )
        val proposal = AgentPolicyProposal.create(
            id("proposal-1"),
            ProviderId("policy.local"),
            preflight,
            initialAuthorization,
            AgentToolRisk.REVERSIBLE_WRITE,
            budget(),
            AgentUsage(),
            100,
            900,
        )
        val policyDecision = AgentPolicyDecision.requireApproval(
            id("policy-decision-1"),
            ProviderId("policy.local"),
            proposal,
            "policy-7",
            200,
            900,
        )
        val approvalRequest = AgentApprovalRequest.create(
            id("approval-request-1"),
            proposal,
            policyDecision,
            id("operator-1"),
            "USER",
            "nonce-1",
            250,
            800,
        )
        val approvalDecision = AgentApprovalDecision.approve(
            id("approval-decision-1"),
            approvalRequest,
            id("operator-1"),
            "USER",
            300,
        )
        val executionRecheck = AgentAuthorizationRequest.executionRecheck(
            id("authorization-execution-request-1"),
            preflight,
            325,
            850,
        )
        val executionAuthorization = AgentAuthorizationDecision.allow(
            id("authorization-execution-1"),
            ProviderId("authorization.local"),
            executionRecheck,
            "authorization-3",
            350,
            850,
        )
        val fixture = ApprovalFixture(
            arguments,
            descriptor,
            idempotencyKey,
            preflight,
            initialAuthorization,
            proposal,
            policyDecision,
            approvalRequest,
            approvalDecision,
            executionRecheck,
            executionAuthorization,
        )
        fixture.invocation = authorize(fixture)
        return fixture
    }

    private fun authorize(
        fixture: ApprovalFixture,
        descriptor: AgentToolDescriptor = fixture.descriptor,
        executionAuthorizationRequest: AgentAuthorizationRequest = fixture.executionRecheck,
        executionAuthorizationDecision: AgentAuthorizationDecision = fixture.executionAuthorization,
        arguments: ByteArray = fixture.arguments,
        idempotencyKey: String = fixture.idempotencyKey,
        cancellationToken: AgentCancellationToken = AgentCancellationToken.NONE,
    ): AuthorizedToolInvocation = AuthorizedToolInvocation.authorize(
        id("invocation-1"),
        fixture.proposal,
        descriptor,
        fixture.policyDecision,
        executionAuthorizationRequest,
        executionAuthorizationDecision,
        fixture.approvalRequest,
        fixture.approvalDecision,
        arguments,
        idempotencyKey,
        1,
        400,
        700,
        cancellationToken,
    )

    private fun budget(): AgentBudget = AgentBudget(
        maximumInputTokens = 10_000,
        maximumOutputTokens = 2_000,
        maximumModelCalls = 10,
        maximumToolCalls = 5,
        maximumDurationMillis = 60_000,
        maximumCostMicros = 1_000_000,
    )

    private fun id(value: String): Identifier = Identifier(value)

    private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
        .digest(bytes)
        .joinToString(separator = "") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private class ApprovalFixture(
        val arguments: ByteArray,
        val descriptor: AgentToolDescriptor,
        val idempotencyKey: String,
        val preflight: AgentAuthorizationRequest,
        val initialAuthorization: AgentAuthorizationDecision,
        val proposal: AgentPolicyProposal,
        val policyDecision: AgentPolicyDecision,
        val approvalRequest: AgentApprovalRequest,
        val approvalDecision: AgentApprovalDecision,
        val executionRecheck: AgentAuthorizationRequest,
        val executionAuthorization: AgentAuthorizationDecision,
    ) {
        lateinit var invocation: AuthorizedToolInvocation
    }
}
