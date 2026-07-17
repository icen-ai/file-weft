package ai.icen.fw.agent.api

import ai.icen.fw.core.id.Identifier
import java.util.LinkedHashMap
import java.util.concurrent.CompletionStage

/** Provider-neutral tool metadata. The schema bytes are canonical JSON Schema, never an SDK object. */
class AgentToolDescriptor @JvmOverloads constructor(
    val providerId: ProviderId,
    val toolId: ToolId,
    displayName: String,
    description: String,
    val risk: AgentToolRisk,
    inputSchema: ByteArray,
    schemaDigest: String,
    capabilities: Collection<AgentCapabilityId>,
    val idempotent: Boolean,
    val maximumResultBytes: Int,
    val maximumCostMicros: Long = 0L,
    val maximumDurationMillis: Long = 60_000L,
) {
    val displayName: String = requireAgentToken(
        displayName,
        AgentContractLimits.MAX_NAME_CODE_POINTS,
        "Agent tool display name is invalid.",
    )
    val description: String = requireAgentContent(
        description,
        AgentContractLimits.MAX_DESCRIPTION_CODE_POINTS,
        "Agent tool description is invalid.",
    )
    val schemaDigest: String = requireSha256(schemaDigest, "Agent tool schema digest is invalid.")
    val descriptorDigest: String
    val capabilities: Set<AgentCapabilityId>
    private val inputSchemaSnapshot: ByteArray

    val inputSchema: ByteArray
        get() = inputSchemaSnapshot.copyOf()

    init {
        val schemaSnapshot = immutableAgentBytes(inputSchema)
        val capabilitySnapshot = immutableAgentList(capabilities)
        require(schemaSnapshot.isNotEmpty()) { "Agent tool input schema must not be empty." }
        require(schemaSnapshot.size <= AgentContractLimits.MAX_SCHEMA_BYTES) { "Agent tool input schema is too large." }
        requireUtf8(schemaSnapshot, "Agent tool input schema must be valid UTF-8 JSON Schema.")
        requireDigestMatches(schemaSnapshot, this.schemaDigest, "Agent tool schema digest does not match its bytes.")
        require(capabilitySnapshot.size <= AgentContractLimits.MAX_CAPABILITIES) {
            "Agent tool declares too many capabilities."
        }
        require(capabilitySnapshot.toSet().size == capabilitySnapshot.size) {
            "Agent tool capability identifiers must be unique."
        }
        require(maximumResultBytes in 1..AgentContractLimits.MAX_BINARY_BYTES) {
            "Agent tool result limit is invalid."
        }
        require(maximumCostMicros >= 0L) { "Agent tool cost reservation must not be negative." }
        require(maximumDurationMillis in 1L..86_400_000L) {
            "Agent tool duration reservation is invalid."
        }
        inputSchemaSnapshot = schemaSnapshot
        this.capabilities = immutableAgentSet(capabilitySnapshot)
        val descriptorHasher = AgentDigestBuilder("flowweft.agent.tool-descriptor.v1")
            .add(providerId.value)
            .add(toolId.value)
            .add(this.displayName)
            .add(this.description)
            .add(risk.name)
            .add(this.schemaDigest)
            .add(idempotent)
            .add(maximumResultBytes)
            .add(maximumCostMicros)
            .add(maximumDurationMillis)
        this.capabilities.map { capability -> capability.value }.sorted().forEach(descriptorHasher::add)
        descriptorDigest = descriptorHasher.finish()
    }
}

/** Immutable descriptor snapshot returned by a provider. */
class AgentToolCatalog(
    val providerId: ProviderId,
    descriptors: List<AgentToolDescriptor>,
) {
    val descriptors: List<AgentToolDescriptor>
    private val descriptorsById: Map<ToolId, AgentToolDescriptor>

    init {
        val descriptorSnapshot = immutableAgentList(descriptors)
        require(descriptorSnapshot.size <= AgentContractLimits.MAX_TOOLS) {
            "Agent tool catalog contains too many tools."
        }
        val index = LinkedHashMap<ToolId, AgentToolDescriptor>(descriptorSnapshot.size)
        descriptorSnapshot.forEach { descriptor ->
            require(descriptor.providerId == providerId) {
                "Agent tool catalog contains a descriptor from a different provider."
            }
            require(index.put(descriptor.toolId, descriptor) == null) {
                "Agent tool catalog identifiers must be unique."
            }
        }
        this.descriptors = descriptorSnapshot
        descriptorsById = immutableAgentMap(index)
    }

    fun find(toolId: ToolId): AgentToolDescriptor? = descriptorsById[toolId]
}

/** Descriptor discovery is side-effect-free; remote capability refresh belongs in adapter/runtime code. */
interface AgentToolDescriptorProvider {
    fun providerId(): ProviderId

    fun descriptors(context: AgentRunContext): AgentToolCatalog
}

enum class AgentToolResultStatus {
    SUCCEEDED,
    FAILED,
    CANCELLED,
    OUTCOME_UNKNOWN,
}

/** Tool result block used inside a TOOL-role message. */
class AgentToolResultContentBlock @JvmOverloads constructor(
    callId: String,
    val toolId: ToolId,
    val status: AgentToolResultStatus,
    blocks: List<AgentContentBlock> = emptyList(),
    safeErrorCode: String? = null,
) : AgentSizedContentBlock {
    val callId: String = requireStableAgentId(callId, "Agent tool result call identifier is invalid.")
    val blocks: List<AgentContentBlock>
    val safeErrorCode: String? = safeErrorCode?.let { requireAgentCode(it, "Agent tool result error code is invalid.") }
    private val contentBindingDigest: String
    private val nestedBindings: List<AgentToolResultBlockBinding>
    private val canonicalPayloadSize: Long

    init {
        val blockSnapshot = immutableAgentList(blocks)
        require(blockSnapshot.size <= AgentContractLimits.MAX_BLOCKS_PER_MESSAGE) {
            "Agent tool result contains too many content blocks."
        }
        require(status != AgentToolResultStatus.SUCCEEDED || blockSnapshot.isNotEmpty()) {
            "Successful Agent tool results require content."
        }
        require(
            status != AgentToolResultStatus.FAILED && status != AgentToolResultStatus.OUTCOME_UNKNOWN ||
                this.safeErrorCode != null,
        ) { "Failed or outcome-unknown Agent tool results require a safe error code." }
        val bindings = blockSnapshot.map { block ->
            requireAgentToolResultPayloadBlock(block)
            val sized = block as? AgentSizedContentBlock
                ?: throw IllegalArgumentException("Agent tool result extension blocks must declare an exact size.")
            val size = sized.canonicalPayloadSizeBytes()
            require(size >= 0L) { "Agent tool result block size is invalid." }
            AgentToolResultBlockBinding(block.kind(), block.origin(), block.bindingDigest(), size)
        }
        this.blocks = blockSnapshot
        nestedBindings = immutableAgentList(bindings)
        canonicalPayloadSize = bindings.fold(0L) { total, binding ->
            try {
                Math.addExact(total, binding.sizeBytes)
            } catch (_: ArithmeticException) {
                throw IllegalArgumentException("Agent tool result size overflowed.")
            }
        }
        val digest = AgentDigestBuilder("flowweft.agent.content.tool-result.v1")
            .add(KIND)
            .add(origin().name)
            .add(this.callId)
            .add(toolId.value)
            .add(status.name)
            .add(this.safeErrorCode ?: "-")
            .add(blockSnapshot.size)
        blockSnapshot.forEachIndexed { index, block ->
            val binding = nestedBindings[index]
            digest.add(binding.kind).add(binding.origin.name).add(binding.digest).add(binding.sizeBytes)
        }
        contentBindingDigest = digest.finish()
    }

    override fun kind(): String = KIND

    override fun origin(): AgentContentOrigin = AgentContentOrigin.TOOL

    override fun bindingDigest(): String = contentBindingDigest

    override fun canonicalPayloadSizeBytes(): Long = canonicalPayloadSize

    fun requireBindingIntact() {
        require(blocks.size == nestedBindings.size) { "Agent tool result content binding changed." }
        blocks.forEachIndexed { index, block ->
            requireAgentToolResultPayloadBlock(block)
            val binding = nestedBindings[index]
            val sized = block as? AgentSizedContentBlock
                ?: throw IllegalArgumentException("Agent tool result extension blocks must declare an exact size.")
            require(
                block.kind() == binding.kind &&
                    block.origin() == binding.origin &&
                    block.bindingDigest() == binding.digest &&
                    sized.canonicalPayloadSizeBytes() == binding.sizeBytes,
            ) { "Agent tool result content binding changed." }
        }
    }

    companion object {
        const val KIND: String = "tool-result"
    }
}

/**
 * Final authorization result for a tool invocation. It cannot cross [AgentToolExecutor] until a
 * configured [AgentExecutionContextConsumer] returns a fresh durable claim and the runtime creates
 * [AgentExecutableToolInvocation]. Construction fails unless policy, optional approval, a fresh
 * execution-time authorization, canonical argument bytes and all bindings form one unexpired chain.
 *
 * Before the first side effect, the runtime must atomically consume [executionContextId] in this
 * tenant. An idempotent replay may return or reconcile the recorded outcome, but must not repeat the
 * side effect as a new execution.
 */
class AuthorizedToolInvocation private constructor(
    invocationId: Identifier,
    val proposal: AgentPolicyProposal,
    val descriptor: AgentToolDescriptor,
    val policyDecision: AgentPolicyDecision,
    val executionAuthorizationRequest: AgentAuthorizationRequest,
    val executionAuthorizationDecision: AgentAuthorizationDecision,
    val approvalRequest: AgentApprovalRequest?,
    val approvalDecision: AgentApprovalDecision?,
    arguments: ByteArray,
    idempotencyKey: String,
    val attempt: Int,
    val startedAt: Long,
    val deadlineAt: Long,
    val cancellationToken: AgentCancellationToken,
) {
    val invocationId: Identifier = requireOpaqueIdentifier(invocationId, "Agent tool invocation identifier is invalid.")
    val tenantId: Identifier = proposal.tenantId
    val principalId: Identifier = proposal.principalId
    val principalType: String = proposal.principalType
    val runId: Identifier = proposal.runId
    val stepId: Identifier = proposal.stepId
    val providerId: ProviderId = proposal.toolProviderId
    val toolId: ToolId = proposal.toolId
    val descriptorDigest: String = proposal.descriptorDigest
    val schemaDigest: String = proposal.schemaDigest
    val argumentsDigest: String = proposal.argumentsDigest
    val idempotencyKeyDigest: String = proposal.idempotencyKeyDigest
    val policyRevision: String = policyDecision.policyRevision
    val authorizationRequestId: Identifier = proposal.authorizationRequestId
    val executionContextId: Identifier = proposal.executionContextId
    val authorizationProviderId: ProviderId = executionAuthorizationDecision.providerId
    val initialAuthorizationDecisionId: Identifier = proposal.authorizationDecisionId
    val executionAuthorizationDecisionId: Identifier = executionAuthorizationDecision.decisionId
    val authorizationRevision: String = executionAuthorizationDecision.authorizationRevision
    val authorizationAction: String = proposal.authorizationRequest.action
    val authorizationResourceType: String = proposal.authorizationRequest.resourceType
    val authorizationResourceId: Identifier = proposal.authorizationRequest.resourceId
    val authorizationResourceRevision: String = proposal.authorizationRequest.resourceRevision
    val authorizationPurpose: String = proposal.authorizationRequest.purpose
    val idempotencyKey: String = requireAgentToken(
        idempotencyKey,
        AgentContractLimits.MAX_IDEMPOTENCY_KEY_CODE_POINTS,
        "Agent tool invocation idempotency key is invalid.",
    )
    private val argumentsSnapshot: ByteArray
    val logicalInvocationDigest: String

    val arguments: ByteArray
        get() = argumentsSnapshot.copyOf()

    init {
        val approvedArgumentsSnapshot = immutableAgentBytes(arguments)
        require(approvedArgumentsSnapshot.isNotEmpty()) { "Agent tool invocation arguments must not be empty." }
        require(approvedArgumentsSnapshot.size <= AgentContractLimits.MAX_ARGUMENT_BYTES) {
            "Agent tool invocation arguments are too large."
        }
        requireUtf8(approvedArgumentsSnapshot, "Agent tool invocation arguments must be valid UTF-8 canonical JSON.")
        requireDigestMatches(
            approvedArgumentsSnapshot,
            argumentsDigest,
            "Agent tool invocation arguments do not match the approved digest.",
        )
        proposal.authorizationRequest.requireArguments(approvedArgumentsSnapshot)
        proposal.authorizationRequest.requireIdempotencyKey(this.idempotencyKey)
        cancellationToken.cancellation()?.let { cancellation -> throw AgentCancellationException(cancellation) }
        require(attempt in 1..AgentContractLimits.MAX_ATTEMPTS) { "Agent tool invocation attempt is invalid." }
        requireNonNegativeTime(startedAt, "Agent tool invocation start time must not be negative.")
        require(deadlineAt > startedAt && deadlineAt <= proposal.expiresAt) {
            "Agent tool invocation deadline must follow start time and remain within proposal validity."
        }
        proposal.requireMatches(descriptor)
        policyDecision.requireValidFor(proposal, startedAt)
        require(deadlineAt <= policyDecision.expiresAt) {
            "Agent tool invocation deadline exceeds policy validity."
        }
        val finalGateAt = when (policyDecision.outcome) {
            AgentPolicyOutcome.ALLOW -> {
                require(approvalRequest == null && approvalDecision == null) {
                    "Directly allowed Agent tool invocations must not carry unrelated approval evidence."
                }
                policyDecision.decidedAt
            }
            AgentPolicyOutcome.DENY -> throw IllegalArgumentException("Denied Agent tool invocations cannot execute.")
            AgentPolicyOutcome.REQUIRE_APPROVAL -> {
                val request = requireNotNull(approvalRequest) {
                    "Approval-requiring Agent tool invocations require an approval request."
                }
                val decision = requireNotNull(approvalDecision) {
                    "Approval-requiring Agent tool invocations require an approval decision."
                }
                decision.requireApprovedFor(request, proposal, policyDecision, startedAt)
                require(deadlineAt <= request.expiresAt) {
                    "Agent tool invocation deadline exceeds approval validity."
                }
                decision.decidedAt
            }
        }
        executionAuthorizationRequest.requireExecutionRecheckOf(proposal.authorizationRequest)
        require(executionAuthorizationRequest.requestedAt >= finalGateAt) {
            "Agent execution authorization request must be created after the final policy or approval gate."
        }
        executionAuthorizationDecision.requireAllowedFor(executionAuthorizationRequest, startedAt)
        require(executionAuthorizationDecision.providerId == proposal.authorizationProviderId) {
            "Agent execution authorization provider does not match the policy-bound provider."
        }
        require(executionAuthorizationDecision.decisionId != proposal.authorizationDecisionId) {
            "Agent execution requires a fresh authorization decision after its final policy gate."
        }
        require(executionAuthorizationDecision.authorizationRevision == proposal.authorizationRevision) {
            "Agent authorization revision changed before execution; policy and approval must be reevaluated."
        }
        require(executionAuthorizationDecision.decidedAt >= finalGateAt) {
            "Agent execution authorization must be decided after the final policy or approval gate."
        }
        require(deadlineAt <= executionAuthorizationDecision.expiresAt) {
            "Agent tool invocation deadline exceeds execution authorization validity."
        }
        require(deadlineAt <= executionAuthorizationRequest.expiresAt) {
            "Agent tool invocation deadline exceeds execution authorization request validity."
        }
        argumentsSnapshot = approvedArgumentsSnapshot
        logicalInvocationDigest = AgentDigestBuilder("flowweft.agent.logical-invocation.v1")
            .add(this.invocationId.value)
            .add(proposal.proposalId.value)
            .add(policyDecision.decisionId.value)
            .add(policyDecision.policyInputDigest)
            .add(policyDecision.policyRevision)
            .add(approvalRequest?.requestId?.value ?: "-")
            .add(approvalDecision?.decisionId?.value ?: "-")
            .add(approvalRequest?.nonce ?: "-")
            .add(approvalRequest?.operatorType ?: "-")
            .add(approvalRequest?.operatorId?.value ?: "-")
            .add(executionAuthorizationRequest.requestId.value)
            .add(executionAuthorizationDecision.decisionId.value)
            .add(executionAuthorizationDecision.authorizationRevision)
            .add(executionAuthorizationDecision.expiresAt)
            .add(proposal.authorizationBindingDigest)
            .add(this.descriptorDigest)
            .add(this.argumentsDigest)
            .add(this.idempotencyKeyDigest)
            .add(this.authorizationResourceRevision)
            .finish()
    }

    override fun toString(): String = "AuthorizedToolInvocation(toolId=${toolId.value}, attempt=$attempt)"

    companion object {
        @JvmStatic
        fun authorize(
            invocationId: Identifier,
            proposal: AgentPolicyProposal,
            descriptor: AgentToolDescriptor,
            policyDecision: AgentPolicyDecision,
            executionAuthorizationRequest: AgentAuthorizationRequest,
            executionAuthorizationDecision: AgentAuthorizationDecision,
            approvalRequest: AgentApprovalRequest?,
            approvalDecision: AgentApprovalDecision?,
            arguments: ByteArray,
            idempotencyKey: String,
            attempt: Int,
            startedAt: Long,
            deadlineAt: Long,
            cancellationToken: AgentCancellationToken,
        ): AuthorizedToolInvocation = AuthorizedToolInvocation(
            invocationId,
            proposal,
            descriptor,
            policyDecision,
            executionAuthorizationRequest,
            executionAuthorizationDecision,
            approvalRequest,
            approvalDecision,
            arguments,
            idempotencyKey,
            attempt,
            startedAt,
            deadlineAt,
            cancellationToken,
        )
    }
}

/** A consumption returned by durable storage is either a fresh claim or an existing replay. */
enum class AgentExecutionContextConsumptionStatus {
    CLAIMED,
    REPLAYED,
}

/**
 * Durable evidence for the atomic `(tenant, executionContextId)` single-consumption record.
 * A replay is evidence for reconciliation only and can never create an executable invocation.
 */
class AgentExecutionContextConsumption private constructor(
    receiptId: Identifier,
    val consumerId: ProviderId,
    invocation: AuthorizedToolInvocation,
    val status: AgentExecutionContextConsumptionStatus,
    val consumedAt: Long,
    consumerRevision: String,
) {
    val receiptId: Identifier = requireOpaqueIdentifier(receiptId, "Agent execution receipt identifier is invalid.")
    val tenantId: Identifier = invocation.tenantId
    val executionContextId: Identifier = invocation.executionContextId
    val invocationId: Identifier = invocation.invocationId
    val logicalInvocationDigest: String = invocation.logicalInvocationDigest
    val idempotencyKeyDigest: String = invocation.idempotencyKeyDigest
    val consumerRevision: String = requireAgentToken(
        consumerRevision,
        AgentContractLimits.MAX_ID_CODE_POINTS,
        "Agent execution consumer revision is invalid.",
    )

    init {
        requireNonNegativeTime(consumedAt, "Agent execution context consumption time must not be negative.")
        require(consumedAt < invocation.deadlineAt) {
            "Agent execution context consumption must precede the invocation deadline."
        }
        require(status != AgentExecutionContextConsumptionStatus.CLAIMED || consumedAt >= invocation.startedAt) {
            "A fresh Agent execution context claim must occur after invocation authorization."
        }
    }

    fun requireMatches(invocation: AuthorizedToolInvocation, atTime: Long) {
        require(
            tenantId == invocation.tenantId &&
                executionContextId == invocation.executionContextId &&
                invocationId == invocation.invocationId &&
                logicalInvocationDigest == invocation.logicalInvocationDigest &&
                idempotencyKeyDigest == invocation.idempotencyKeyDigest,
        ) { "Agent execution context receipt does not match the invocation." }
        require(atTime >= consumedAt && atTime < invocation.deadlineAt) {
            "Agent execution context receipt is not valid at the requested time."
        }
    }

    override fun toString(): String = "AgentExecutionContextConsumption(status=$status)"

    companion object {
        @JvmStatic
        fun claimed(
            receiptId: Identifier,
            consumerId: ProviderId,
            invocation: AuthorizedToolInvocation,
            consumedAt: Long,
            consumerRevision: String,
        ): AgentExecutionContextConsumption = AgentExecutionContextConsumption(
            receiptId,
            consumerId,
            invocation,
            AgentExecutionContextConsumptionStatus.CLAIMED,
            consumedAt,
            consumerRevision,
        )

        @JvmStatic
        fun replayed(
            receiptId: Identifier,
            consumerId: ProviderId,
            invocation: AuthorizedToolInvocation,
            consumedAt: Long,
            consumerRevision: String,
        ): AgentExecutionContextConsumption = AgentExecutionContextConsumption(
            receiptId,
            consumerId,
            invocation,
            AgentExecutionContextConsumptionStatus.REPLAYED,
            consumedAt,
            consumerRevision,
        )
    }
}

/** Only a freshly claimed execution context can cross the tool-executor boundary. */
class AgentExecutableToolInvocation private constructor(
    val invocation: AuthorizedToolInvocation,
    val consumption: AgentExecutionContextConsumption,
    val finalAuthorizationRequest: AgentAuthorizationRequest,
    val finalAuthorizationDecision: AgentAuthorizationDecision,
    val dispatchFenceRequest: AgentDispatchAuthorizationFenceRequest,
    val dispatchFenceConsumption: AgentDispatchAuthorizationFenceConsumption,
    expectedExecutionConsumerId: ProviderId,
    expectedDispatchConsumerId: ProviderId,
    val maximumCostMicros: Long,
    val maximumDurationMillis: Long,
    val preparedAt: Long,
) {
    val executionBindingDigest: String

    init {
        require(consumption.consumerId == expectedExecutionConsumerId) {
            "Agent execution context receipt does not come from the selected consumer."
        }
        invocation.cancellationToken.cancellation()?.let { cancellation -> throw AgentCancellationException(cancellation) }
        consumption.requireMatches(invocation, preparedAt)
        require(consumption.status == AgentExecutionContextConsumptionStatus.CLAIMED) {
            "A replayed Agent execution context may only be reconciled, never executed again."
        }
        finalAuthorizationRequest.requireFinalExecutionRecheckOf(invocation.executionAuthorizationRequest)
        require(finalAuthorizationRequest.requestedAt >= consumption.consumedAt) {
            "Final Agent execution authorization must be requested after the one-time context claim."
        }
        finalAuthorizationDecision.requireAllowedFor(finalAuthorizationRequest, preparedAt)
        require(finalAuthorizationDecision.providerId == invocation.authorizationProviderId) {
            "Final Agent execution authorization came from a different provider."
        }
        require(finalAuthorizationDecision.authorizationRevision == invocation.authorizationRevision) {
            "Agent authorization revision changed after the one-time context claim."
        }
        require(finalAuthorizationDecision.decidedAt >= consumption.consumedAt) {
            "Final Agent execution authorization must be decided after the one-time context claim."
        }
        require(dispatchFenceRequest.consumerId == expectedDispatchConsumerId) {
            "Agent dispatch fence does not belong to the selected runtime consumer."
        }
        require(dispatchFenceRequest.logicalInvocationDigest == invocation.logicalInvocationDigest &&
            dispatchFenceRequest.finalAuthorizationRequestId == finalAuthorizationRequest.requestId &&
            dispatchFenceRequest.finalAuthorizationDecisionId == finalAuthorizationDecision.decisionId
        ) { "Agent dispatch fence changed the executable authorization chain." }
        dispatchFenceConsumption.requireMatches(dispatchFenceRequest, preparedAt)
        require(dispatchFenceConsumption.status == AgentDispatchAuthorizationFenceStatus.CONSUMED) {
            "A replayed Agent dispatch fence may only be reconciled, never executed again."
        }
        require(maximumCostMicros == invocation.descriptor.maximumCostMicros) {
            "Agent executable cost reservation does not match the descriptor."
        }
        require(maximumDurationMillis > 0L && maximumDurationMillis <= invocation.descriptor.maximumDurationMillis) {
            "Agent executable duration reservation is invalid."
        }
        require(preparedAt <= Long.MAX_VALUE - maximumDurationMillis &&
            preparedAt + maximumDurationMillis <= invocation.deadlineAt
        ) { "Agent executable duration reservation exceeds its authorization deadline." }
        executionBindingDigest = AgentDigestBuilder("flowweft.agent.executable-tool-invocation.v1")
            .add(invocation.logicalInvocationDigest)
            .add(consumption.receiptId.value)
            .add(consumption.consumerId.value)
            .add(consumption.consumerRevision)
            .add(consumption.consumedAt)
            .add(finalAuthorizationRequest.requestId.value)
            .add(finalAuthorizationRequest.parentRequestId?.value ?: "-")
            .add(finalAuthorizationRequest.bindingDigest)
            .add(finalAuthorizationRequest.requestedAt)
            .add(finalAuthorizationRequest.expiresAt)
            .add(finalAuthorizationDecision.decisionId.value)
            .add(finalAuthorizationDecision.providerId.value)
            .add(finalAuthorizationDecision.phase.name)
            .add(finalAuthorizationDecision.outcome.name)
            .add(finalAuthorizationDecision.bindingDigest)
            .add(finalAuthorizationDecision.authorizationRevision)
            .add(finalAuthorizationDecision.decidedAt)
            .add(finalAuthorizationDecision.expiresAt)
            .add(dispatchFenceRequest.fenceId.value)
            .add(dispatchFenceRequest.consumerId.value)
            .add(dispatchFenceRequest.bindingDigest)
            .add(dispatchFenceConsumption.receiptId.value)
            .add(dispatchFenceConsumption.providerRevision)
            .add(dispatchFenceConsumption.consumedAt)
            .add(maximumCostMicros)
            .add(maximumDurationMillis)
            .add(preparedAt)
            .finish()
    }

    override fun toString(): String = "AgentExecutableToolInvocation(toolId=${invocation.toolId.value})"

    fun requireExecutor(providerId: ProviderId, toolId: ToolId) {
        require(invocation.providerId == providerId && invocation.toolId == toolId) {
            "Agent executable invocation does not match the selected tool executor."
        }
    }

    companion object {
        @JvmStatic
        fun create(
            invocation: AuthorizedToolInvocation,
            consumption: AgentExecutionContextConsumption,
            finalAuthorizationRequest: AgentAuthorizationRequest,
            finalAuthorizationDecision: AgentAuthorizationDecision,
            dispatchFenceRequest: AgentDispatchAuthorizationFenceRequest,
            dispatchFenceConsumption: AgentDispatchAuthorizationFenceConsumption,
            expectedExecutionConsumerId: ProviderId,
            expectedDispatchConsumerId: ProviderId,
            maximumCostMicros: Long,
            maximumDurationMillis: Long,
            preparedAt: Long,
        ): AgentExecutableToolInvocation = AgentExecutableToolInvocation(
            invocation,
            consumption,
            finalAuthorizationRequest,
            finalAuthorizationDecision,
            dispatchFenceRequest,
            dispatchFenceConsumption,
            expectedExecutionConsumerId,
            expectedDispatchConsumerId,
            maximumCostMicros,
            maximumDurationMillis,
            preparedAt,
        )
    }
}

/** Stable, extensible reason code for fail-closed execution-context consumption failures. */
class AgentExecutionContextFailureCode(value: String) {
    val value: String = requireAgentCode(value, "Agent execution context failure code is invalid.")

    override fun equals(other: Any?): Boolean = other is AgentExecutionContextFailureCode && value == other.value

    override fun hashCode(): Int = value.hashCode()

    override fun toString(): String = value

    companion object {
        @JvmField val BINDING_MISMATCH = AgentExecutionContextFailureCode("BINDING_MISMATCH")
        @JvmField val EXPIRED = AgentExecutionContextFailureCode("EXPIRED")
        @JvmField val REVOKED = AgentExecutionContextFailureCode("REVOKED")
        @JvmField val STORE_UNAVAILABLE = AgentExecutionContextFailureCode("STORE_UNAVAILABLE")
        @JvmField val PROTOCOL = AgentExecutionContextFailureCode("PROTOCOL")
    }
}

/** Safe exception only; storage errors, SQL text and credentials must remain implementation-internal. */
class AgentExecutionContextException @JvmOverloads constructor(
    val code: AgentExecutionContextFailureCode,
    safeMessage: String? = null,
    val retryable: Boolean = false,
) : RuntimeException(
    requireOptionalAgentContent(
        safeMessage,
        AgentContractLimits.MAX_DESCRIPTION_CODE_POINTS,
        "Agent execution context failure message is invalid.",
    ) ?: code.value,
) {
    override fun toString(): String = "AgentExecutionContextException(code=$code, retryable=$retryable)"
}

/**
 * Durable implementations must atomically insert-or-read by `(tenantId, executionContextId)`.
 * They return CLAIMED only for the winning insert, REPLAYED only for an identical persisted
 * binding, and fail with BINDING_MISMATCH when the key already exists for different evidence.
 */
interface AgentExecutionContextConsumer {
    fun consumerId(): ProviderId

    fun consume(invocation: AuthorizedToolInvocation, consumedAt: Long): CompletionStage<AgentExecutionContextConsumption>
}

class AgentToolResult @JvmOverloads constructor(
    invocationId: Identifier,
    val status: AgentToolResultStatus,
    blocks: List<AgentContentBlock>,
    val completedAt: Long,
    safeErrorCode: String? = null,
    outcomeReference: Identifier? = null,
    val usage: AgentUsage = AgentUsage(toolCalls = 1),
) {
    val invocationId: Identifier = requireOpaqueIdentifier(invocationId, "Agent tool result invocation identifier is invalid.")
    val blocks: List<AgentContentBlock>
    val safeErrorCode: String? = safeErrorCode?.let { requireAgentCode(it, "Agent tool result error code is invalid.") }
    val outcomeReference: Identifier? = outcomeReference?.let {
        requireOpaqueIdentifier(it, "Agent tool outcome reference is invalid.")
    }
    val bindingDigest: String
    val canonicalPayloadSizeBytes: Long
    private val blockBindings: List<AgentToolResultBlockBinding>

    init {
        val blockSnapshot = immutableAgentList(blocks)
        requireNonNegativeTime(completedAt, "Agent tool completion time must not be negative.")
        require(blockSnapshot.size <= AgentContractLimits.MAX_BLOCKS_PER_MESSAGE) {
            "Agent tool result contains too many content blocks."
        }
        require(status != AgentToolResultStatus.SUCCEEDED || blockSnapshot.isNotEmpty()) {
            "Successful Agent tool results require content."
        }
        require(
            status != AgentToolResultStatus.FAILED && status != AgentToolResultStatus.OUTCOME_UNKNOWN ||
                this.safeErrorCode != null,
        ) { "Failed or outcome-unknown Agent tool results require a safe error code." }
        require(status != AgentToolResultStatus.OUTCOME_UNKNOWN || this.outcomeReference != null) {
            "Outcome-unknown Agent tool results require a reconciliation reference."
        }
        require(status == AgentToolResultStatus.OUTCOME_UNKNOWN || this.outcomeReference == null) {
            "Only outcome-unknown Agent tool results may carry a reconciliation reference."
        }
        require(usage.inputTokens == 0L && usage.outputTokens == 0L &&
            usage.modelCalls == 0 && usage.toolCalls == 1
        ) { "Agent tool result usage must describe exactly one non-model tool call." }
        val bindings = blockSnapshot.map { block ->
            requireAgentToolResultPayloadBlock(block)
            val sized = block as? AgentSizedContentBlock
                ?: throw IllegalArgumentException("Agent tool result extension blocks must declare an exact size.")
            val size = sized.canonicalPayloadSizeBytes()
            require(size >= 0L) { "Agent tool result block size is invalid." }
            AgentToolResultBlockBinding(block.kind(), block.origin(), block.bindingDigest(), size)
        }
        this.blocks = blockSnapshot
        blockBindings = immutableAgentList(bindings)
        canonicalPayloadSizeBytes = bindings.fold(0L) { total, binding ->
            try {
                Math.addExact(total, binding.sizeBytes)
            } catch (_: ArithmeticException) {
                throw IllegalArgumentException("Agent tool result size overflowed.")
            }
        }
        val digest = AgentDigestBuilder("flowweft.agent.tool-result.v1")
            .add(this.invocationId.value)
            .add(status.name)
            .add(completedAt)
            .add(this.safeErrorCode ?: "-")
            .add(this.outcomeReference?.value ?: "-")
            .add(usage.costMicros)
            .add(usage.durationMillis)
            .add(usage.additionalUnits.size)
            .add(blockSnapshot.size)
            .add(canonicalPayloadSizeBytes)
        usage.additionalUnits.toSortedMap().forEach { (name, value) -> digest.add(name).add(value) }
        blockBindings.forEach { binding ->
            digest.add(binding.kind).add(binding.origin.name).add(binding.digest).add(binding.sizeBytes)
        }
        bindingDigest = digest.finish()
    }

    fun requireBindingIntact() {
        require(blocks.size == blockBindings.size) { "Agent tool result content binding changed." }
        blocks.forEachIndexed { index, block ->
            requireAgentToolResultPayloadBlock(block)
            val expected = blockBindings[index]
            val sized = block as? AgentSizedContentBlock
                ?: throw IllegalArgumentException("Agent tool result extension blocks must declare an exact size.")
            require(
                block.kind() == expected.kind &&
                    block.origin() == expected.origin &&
                    block.bindingDigest() == expected.digest &&
                    sized.canonicalPayloadSizeBytes() == expected.sizeBytes,
            ) { "Agent tool result content binding changed." }
        }
    }
}

private class AgentToolResultBlockBinding(
    val kind: String,
    val origin: AgentContentOrigin,
    val digest: String,
    val sizeBytes: Long,
)

private fun requireAgentToolResultPayloadBlock(block: AgentContentBlock) {
    requireAgentContentBlockContract(block)
    require(
        block.origin() == AgentContentOrigin.TOOL ||
            block.origin() == AgentContentOrigin.RETRIEVAL ||
            block.origin() == AgentContentOrigin.MODEL ||
            block.origin() == AgentContentOrigin.A2A ||
            block.origin() == AgentContentOrigin.MEMORY,
    ) { "Agent tool result blocks must retain an untrusted data origin." }
    require(block !is AgentToolCallContentBlock && block !is AgentToolResultContentBlock) {
        "Agent tool results cannot nest execution-control blocks."
    }
}

interface AgentToolEvent {
    val invocationId: Identifier
    val sequence: Long
    val occurredAt: Long
}

/** Bounded progress signal. It deliberately carries no tool arguments or result payload. */
class AgentToolProgressEvent(
    invocationId: Identifier,
    override val sequence: Long,
    override val occurredAt: Long,
    stageCode: String,
    val progressPercent: Int,
) : AgentToolEvent {
    override val invocationId: Identifier = requireOpaqueIdentifier(
        invocationId,
        "Agent tool event invocation identifier is invalid.",
    )
    val stageCode: String = requireAgentCode(stageCode, "Agent tool progress stage is invalid.")

    init {
        requirePositiveSequence(sequence, "Agent tool event sequence must be positive.")
        requireNonNegativeTime(occurredAt, "Agent tool event time must not be negative.")
        require(progressPercent in 0..100) { "Agent tool progress percent must be between 0 and 100." }
    }
}

interface AgentToolObserver {
    fun onEvent(event: AgentToolEvent)

    companion object {
        @JvmField
        val NOOP: AgentToolObserver = object : AgentToolObserver {
            override fun onEvent(event: AgentToolEvent) = Unit
        }
    }
}

interface AgentToolCall {
    fun invocationId(): Identifier

    fun completion(): CompletionStage<AgentToolResult>

    fun cancel(cancellation: AgentCancellation): CompletionStage<Boolean>
}

interface AgentToolExecutor {
    fun providerId(): ProviderId

    fun toolId(): ToolId

    fun start(invocation: AgentExecutableToolInvocation, observer: AgentToolObserver): AgentToolCall
}

/**
 * Production executor binding used by the durable runtime to reject a provider whose current
 * schema/risk/capability contract drifted after model selection and authorization. Legacy/custom
 * dispatchers that cannot attest the exact descriptor remain usable as API objects but fail closed
 * at the production runtime boundary.
 */
interface AgentDescriptorBoundToolExecutor : AgentToolExecutor {
    fun descriptorDigest(): String
}
