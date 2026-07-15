package ai.icen.fw.agent.runtime

import ai.icen.fw.agent.api.AgentApprovalDecision
import ai.icen.fw.agent.api.AgentApprovalOutcome
import ai.icen.fw.agent.api.AgentApprovalRequest
import ai.icen.fw.agent.api.AgentAuthorizationDecision
import ai.icen.fw.agent.api.AgentAuthorizationOutcome
import ai.icen.fw.agent.api.AgentAuthorizationPhase
import ai.icen.fw.agent.api.AgentAuthorizationRequest
import ai.icen.fw.agent.api.AgentBudget
import ai.icen.fw.agent.api.AgentCancellation
import ai.icen.fw.agent.api.AgentCancellationToken
import ai.icen.fw.agent.api.AgentCapabilityId
import ai.icen.fw.agent.api.AgentContentBlock
import ai.icen.fw.agent.api.AgentContentOrigin
import ai.icen.fw.agent.api.AgentDispatchAuthorizationFenceConsumption
import ai.icen.fw.agent.api.AgentDispatchAuthorizationFenceRequest
import ai.icen.fw.agent.api.AgentDispatchAuthorizationFenceStatus
import ai.icen.fw.agent.api.AgentExecutionContextConsumption
import ai.icen.fw.agent.api.AgentExecutionContextConsumptionStatus
import ai.icen.fw.agent.api.AgentFailureCategory
import ai.icen.fw.agent.api.AgentMessage
import ai.icen.fw.agent.api.AgentMessageRole
import ai.icen.fw.agent.api.AgentPolicyDecision
import ai.icen.fw.agent.api.AgentPolicyOutcome
import ai.icen.fw.agent.api.AgentPolicyProposal
import ai.icen.fw.agent.api.AgentRunApprovalRequiredEvent
import ai.icen.fw.agent.api.AgentRunContext
import ai.icen.fw.agent.api.AgentRunEvent
import ai.icen.fw.agent.api.AgentRunFailure
import ai.icen.fw.agent.api.AgentRunMessageEvent
import ai.icen.fw.agent.api.AgentRunStatus
import ai.icen.fw.agent.api.AgentRunStatusChangedEvent
import ai.icen.fw.agent.api.AgentRunUsageEvent
import ai.icen.fw.agent.api.AgentToolCallContentBlock
import ai.icen.fw.agent.api.AgentToolDescriptor
import ai.icen.fw.agent.api.AgentToolRisk
import ai.icen.fw.agent.api.AuthorizedToolInvocation
import ai.icen.fw.agent.api.LanguageModelDescriptor
import ai.icen.fw.agent.api.ModelId
import ai.icen.fw.agent.api.ProviderId
import ai.icen.fw.agent.api.ToolId
import ai.icen.fw.agent.api.AgentUsage
import ai.icen.fw.core.id.Identifier
import java.nio.charset.StandardCharsets

/**
 * Deterministic, reflection-free binary codec for the complete durable Agent projection and its
 * ordered event ledger. The content registry is the only polymorphic extension boundary.
 */
class AgentDurableMementoCodec @JvmOverloads constructor(
    private val contentRegistry: AgentContentBlockPersistenceRegistry = AgentContentBlockPersistenceRegistry(),
) {
    fun encodeState(state: AgentDurableRunState): AgentDurableStateMemento = AgentDurableStateMemento.create(
        encodeAgentMementoFrame(STATE_MAGIC, AGENT_STATE_MEMENTO_MAX_BYTES) { writeState(state) },
    )

    @JvmOverloads
    fun decodeState(
        memento: AgentDurableStateMemento,
        cancellationToken: AgentCancellationToken = AgentCancellationToken.NONE,
    ): AgentDurableRunState = decodeAgentMementoFrame(
        memento.payload,
        STATE_MAGIC,
        AGENT_STATE_MEMENTO_MAX_BYTES,
    ) { formatVersion -> readState(cancellationToken, formatVersion) }

    fun encodeEvent(event: AgentRunEvent): AgentRunEventMemento = AgentRunEventMemento.create(
        encodeAgentMementoFrame(EVENT_MAGIC, AGENT_EVENT_MEMENTO_MAX_BYTES) { writeEvent(event) },
    )

    fun decodeEvent(memento: AgentRunEventMemento): AgentRunEvent = decodeAgentMementoFrame(
        memento.payload,
        EVENT_MAGIC,
        AGENT_EVENT_MEMENTO_MAX_BYTES,
    ) { _ -> readEvent() }

    private fun AgentMementoWriter.writeState(state: AgentDurableRunState) {
        identifier(state.runId)
        runContext(state.context)
        text(state.capabilityId.value)
        list(state.messages, MAX_STATE_MESSAGES) { message(it) }
        budget(state.budget)
        usage(state.usage)
        enum(state.status)
        long(state.stateVersion)
        long(state.eventSequence)
        long(state.checkpointSequence)
        long(state.createdAt)
        long(state.updatedAt)
        long(state.deadlineAt)
        idempotencyScope(state.idempotencyScope)
        text(state.idempotencyReplayDigest, DIGEST_BYTES)
        admission(state.admission)
        list(state.steps, MAX_STATE_LEDGER_ITEMS) { runtimeStep(it) }
        list(state.checkpoints, MAX_STATE_LEDGER_ITEMS) { checkpoint(it) }
        nullable(state.currentStepId) { identifier(it) }
        nullable(state.pendingOperation) { pendingOperation(it) }
        nullable(state.lease) { lease(it) }
        nullable(state.cancellation) { cancellation(it) }
        nullable(state.failure) { failure(it) }
        list(state.incidents, MAX_STATE_LEDGER_ITEMS) { incident(it) }
    }

    private fun AgentMementoReader.readState(
        cancellationToken: AgentCancellationToken,
        formatVersion: Int,
    ): AgentDurableRunState {
        val runId = identifier()
        val context = runContext()
        val capability = AgentCapabilityId(text())
        val messages = list(MAX_STATE_MESSAGES) { message() }
        val budget = budget()
        val usage = usage()
        val status = enum<AgentRunStatus>()
        val stateVersion = long()
        val eventSequence = long()
        val checkpointSequence = long()
        val createdAt = long()
        val updatedAt = long()
        val deadlineAt = long()
        val idempotencyScope = idempotencyScope()
        val storedIdempotencyReplayDigest = if (formatVersion >= 2) text(DIGEST_BYTES) else null
        val admission = admission()
        val idempotencyReplayDigest = storedIdempotencyReplayDigest
            ?: runtimeLegacyIdempotencyReplayDigest(admission.bindingDigest)
        val steps = list(MAX_STATE_LEDGER_ITEMS) { runtimeStep() }
        val checkpoints = list(MAX_STATE_LEDGER_ITEMS) { checkpoint() }
        val currentStepId = nullable { identifier() }
        val pending = nullable { pendingOperation(cancellationToken) }
        val lease = nullable { lease() }
        val cancellation = nullable { cancellation() }
        val failure = nullable { failure() }
        val incidents = list(MAX_STATE_LEDGER_ITEMS) { incident() }
        require(admission.outcome == AgentRunAdmissionOutcome.ALLOW &&
            admission.scopeDigest == idempotencyScope.scopeDigest &&
            admission.requestDeadlineAt == deadlineAt &&
            admission.requestRequestedAt >= context.initiatedAt && admission.requestRequestedAt <= createdAt &&
            createdAt >= admission.decidedAt && createdAt < admission.expiresAt
        ) { "Stored Agent admission evidence is not valid for the restored run." }
        return AgentDurableRunState.restore(
            runId,
            context,
            capability,
            messages,
            budget,
            usage,
            status,
            stateVersion,
            eventSequence,
            checkpointSequence,
            createdAt,
            updatedAt,
            deadlineAt,
            idempotencyScope,
            admission,
            steps,
            checkpoints,
            currentStepId,
            pending,
            lease,
            cancellation,
            failure,
            incidents,
            idempotencyReplayDigest,
        )
    }

    private fun AgentMementoWriter.identifier(value: Identifier) = text(value.value, MAX_IDENTIFIER_BYTES)

    private fun AgentMementoReader.identifier(): Identifier = Identifier(text(MAX_IDENTIFIER_BYTES))

    private fun AgentMementoWriter.enum(value: Enum<*>) = text(value.name, MAX_ENUM_BYTES)

    private inline fun <reified T : Enum<T>> AgentMementoReader.enum(): T {
        val name = text(MAX_ENUM_BYTES)
        return enumValues<T>().firstOrNull { candidate -> candidate.name == name }
            ?: throw IllegalArgumentException("Agent memento enum value is unsupported.")
    }

    private fun AgentMementoWriter.runContext(value: AgentRunContext) {
        identifier(value.tenantId)
        identifier(value.principalId)
        text(value.principalType)
        identifier(value.requestId)
        long(value.initiatedAt)
        nullable(value.locale) { text(it) }
    }

    private fun AgentMementoReader.runContext(): AgentRunContext = AgentRunContext(
        identifier(),
        identifier(),
        text(),
        identifier(),
        long(),
        nullable { text() },
    )

    private fun AgentMementoWriter.budget(value: AgentBudget) {
        long(value.maximumInputTokens)
        long(value.maximumOutputTokens)
        int(value.maximumModelCalls)
        int(value.maximumToolCalls)
        long(value.maximumDurationMillis)
        long(value.maximumCostMicros)
    }

    private fun AgentMementoReader.budget(): AgentBudget = AgentBudget(
        long(),
        long(),
        int(),
        int(),
        long(),
        long(),
    )

    private fun AgentMementoWriter.usage(value: AgentUsage) {
        long(value.inputTokens)
        long(value.outputTokens)
        int(value.modelCalls)
        int(value.toolCalls)
        long(value.durationMillis)
        long(value.costMicros)
        val sorted = value.additionalUnits.toSortedMap()
        list(sorted.entries, MAX_USAGE_DIMENSIONS) { entry ->
            text(entry.key)
            long(entry.value)
        }
    }

    private fun AgentMementoReader.usage(): AgentUsage {
        val inputTokens = long()
        val outputTokens = long()
        val modelCalls = int()
        val toolCalls = int()
        val durationMillis = long()
        val costMicros = long()
        val units = linkedMapOf<String, Long>()
        var previous: String? = null
        list(MAX_USAGE_DIMENSIONS) {
            val name = text()
            require(previous == null || previous!! < name) { "Agent usage dimensions are not canonically sorted." }
            previous = name
            require(units.put(name, long()) == null) { "Agent usage dimensions contain duplicates." }
        }
        return AgentUsage(inputTokens, outputTokens, modelCalls, toolCalls, durationMillis, costMicros, units)
    }

    private fun AgentMementoWriter.message(value: AgentMessage) {
        value.requireBindingIntact()
        identifier(value.id)
        enum(value.role)
        long(value.createdAt)
        text(value.bindingDigest, DIGEST_BYTES)
        list(value.blocks, MAX_MESSAGE_BLOCKS) { block -> contentBlock(block) }
    }

    private fun AgentMementoReader.message(): AgentMessage {
        val id = identifier()
        val role = enum<AgentMessageRole>()
        val createdAt = long()
        val expectedDigest = text(DIGEST_BYTES)
        val blocks = list(MAX_MESSAGE_BLOCKS) { contentBlock() }
        return AgentMessage(id, role, blocks, createdAt).also { restored ->
            require(restored.bindingDigest == expectedDigest) {
                "Stored Agent message binding digest does not match its content."
            }
        }
    }

    private fun AgentMementoWriter.contentBlock(value: AgentContentBlock) {
        val encoded = contentRegistry.encode(value)
        text(encoded.kind)
        enum(encoded.origin)
        int(encoded.codecVersion)
        text(encoded.bindingDigest, DIGEST_BYTES)
        text(encoded.payloadDigest, DIGEST_BYTES)
        nullable(encoded.canonicalPayloadSizeBytes, ::long)
        bytes(encoded.payload, MAX_CONTENT_PAYLOAD_BYTES)
    }

    private fun AgentMementoReader.contentBlock(): AgentContentBlock {
        val kind = text()
        val origin = enum<AgentContentOrigin>()
        val codecVersion = int()
        val bindingDigest = text(DIGEST_BYTES)
        val payloadDigest = text(DIGEST_BYTES)
        val canonicalSize = nullable { long() }
        val payload = bytes(MAX_CONTENT_PAYLOAD_BYTES)
        val encoded = AgentEncodedContentBlock.restore(
            kind,
            origin,
            codecVersion,
            bindingDigest,
            payload,
            canonicalSize,
            payloadDigest,
        )
        return contentRegistry.decode(encoded)
    }

    private fun AgentMementoWriter.modelDescriptor(value: LanguageModelDescriptor) {
        text(value.providerId.value)
        text(value.modelId.value)
        text(value.displayName)
        long(value.maximumInputTokens)
        long(value.maximumOutputTokens)
        boolean(value.supportsStreaming)
        boolean(value.supportsTools)
        long(value.maximumCostMicros)
        long(value.maximumDurationMillis)
        val capabilities = value.capabilities.map { capability -> capability.value }.sorted()
        list(capabilities, MAX_CAPABILITIES, ::text)
        text(value.descriptorDigest, DIGEST_BYTES)
    }

    private fun AgentMementoReader.modelDescriptor(): LanguageModelDescriptor {
        val providerId = ProviderId(text())
        val modelId = ModelId(text())
        val displayName = text()
        val maximumInputTokens = long()
        val maximumOutputTokens = long()
        val supportsStreaming = boolean()
        val supportsTools = boolean()
        val maximumCostMicros = long()
        val maximumDurationMillis = long()
        val capabilities = list(MAX_CAPABILITIES) { AgentCapabilityId(text()) }
        val expectedDigest = text(DIGEST_BYTES)
        return LanguageModelDescriptor(
            providerId,
            modelId,
            displayName,
            capabilities,
            maximumInputTokens,
            maximumOutputTokens,
            supportsStreaming,
            supportsTools,
            maximumCostMicros,
            maximumDurationMillis,
        ).also { restored ->
            require(restored.descriptorDigest == expectedDigest) {
                "Stored Agent model descriptor digest does not match its fields."
            }
        }
    }

    private fun AgentMementoWriter.toolDescriptor(value: AgentToolDescriptor) {
        text(value.providerId.value)
        text(value.toolId.value)
        text(value.displayName)
        text(value.description)
        enum(value.risk)
        bytes(value.inputSchema, MAX_SCHEMA_BYTES)
        text(value.schemaDigest, DIGEST_BYTES)
        val capabilities = value.capabilities.map { capability -> capability.value }.sorted()
        list(capabilities, MAX_CAPABILITIES, ::text)
        boolean(value.idempotent)
        int(value.maximumResultBytes)
        long(value.maximumCostMicros)
        long(value.maximumDurationMillis)
        text(value.descriptorDigest, DIGEST_BYTES)
    }

    private fun AgentMementoReader.toolDescriptor(): AgentToolDescriptor {
        val providerId = ProviderId(text())
        val toolId = ToolId(text())
        val displayName = text()
        val description = text()
        val risk = enum<AgentToolRisk>()
        val schema = bytes(MAX_SCHEMA_BYTES)
        val schemaDigest = text(DIGEST_BYTES)
        val capabilities = list(MAX_CAPABILITIES) { AgentCapabilityId(text()) }
        val idempotent = boolean()
        val maximumResultBytes = int()
        val maximumCostMicros = long()
        val maximumDurationMillis = long()
        val expectedDigest = text(DIGEST_BYTES)
        return AgentToolDescriptor(
            providerId,
            toolId,
            displayName,
            description,
            risk,
            schema,
            schemaDigest,
            capabilities,
            idempotent,
            maximumResultBytes,
            maximumCostMicros,
            maximumDurationMillis,
        ).also { restored ->
            require(restored.descriptorDigest == expectedDigest) {
                "Stored Agent tool descriptor digest does not match its fields."
            }
        }
    }

    private fun AgentMementoWriter.idempotencyScope(value: AgentRunIdempotencyScope) {
        identifier(value.tenantId)
        identifier(value.principalId)
        text(value.principalType)
        text(value.capabilityId.value)
        text(value.idempotencyKeyDigest, DIGEST_BYTES)
        text(value.scopeDigest, DIGEST_BYTES)
    }

    private fun AgentMementoReader.idempotencyScope(): AgentRunIdempotencyScope {
        val scope = AgentRunIdempotencyScope.of(
            identifier(),
            identifier(),
            text(),
            AgentCapabilityId(text()),
            text(DIGEST_BYTES),
        )
        val expectedDigest = text(DIGEST_BYTES)
        require(scope.scopeDigest == expectedDigest) { "Stored Agent idempotency scope digest does not match." }
        return scope
    }

    private fun AgentMementoWriter.admission(value: AgentRunAdmissionDecision) {
        identifier(value.decisionId)
        text(value.providerId.value)
        identifier(value.requestId)
        text(value.bindingDigest, DIGEST_BYTES)
        text(value.scopeDigest, DIGEST_BYTES)
        enum(value.outcome)
        text(value.authorizationRevision)
        long(value.decidedAt)
        long(value.expiresAt)
        nullable(value.reasonCode, ::text)
        long(value.requestRequestedAt)
        long(value.requestDeadlineAt)
        text(value.decisionDigest, DIGEST_BYTES)
    }

    private fun AgentMementoReader.admission(): AgentRunAdmissionDecision = AgentRunAdmissionDecision.restore(
        identifier(),
        ProviderId(text()),
        identifier(),
        text(DIGEST_BYTES),
        text(DIGEST_BYTES),
        enum(),
        text(),
        long(),
        long(),
        nullable { text() },
        long(),
        long(),
        text(DIGEST_BYTES),
    )

    private fun AgentMementoWriter.runtimeStep(value: AgentRuntimeStep) {
        identifier(value.stepId)
        enum(value.kind)
        enum(value.status)
        identifier(value.operationId)
        int(value.attempt)
        long(value.createdAt)
        long(value.updatedAt)
    }

    private fun AgentMementoReader.runtimeStep(): AgentRuntimeStep = AgentRuntimeStep(
        identifier(),
        enum(),
        enum(),
        identifier(),
        int(),
        long(),
        long(),
    )

    private fun AgentMementoWriter.checkpoint(value: AgentRuntimeCheckpoint) {
        identifier(value.checkpointId)
        identifier(value.runId)
        identifier(value.tenantId)
        identifier(value.stepId)
        identifier(value.operationId)
        text(value.checkpointCode)
        text(value.operationDigest, DIGEST_BYTES)
        long(value.checkpointSequence)
        long(value.createdAt)
        text(value.checkpointDigest, DIGEST_BYTES)
    }

    private fun AgentMementoReader.checkpoint(): AgentRuntimeCheckpoint {
        val restored = AgentRuntimeCheckpoint(
            identifier(),
            identifier(),
            identifier(),
            identifier(),
            identifier(),
            text(),
            text(DIGEST_BYTES),
            long(),
            long(),
        )
        val expectedDigest = text(DIGEST_BYTES)
        require(restored.checkpointDigest == expectedDigest) {
            "Stored Agent checkpoint digest does not match its fields."
        }
        return restored
    }

    private fun AgentMementoWriter.incident(value: AgentRuntimeIncident) {
        identifier(value.incidentId)
        identifier(value.runId)
        identifier(value.tenantId)
        nullable(value.stepId) { identifier(it) }
        text(value.code)
        enum(value.status)
        boolean(value.retryable)
        long(value.createdAt)
        nullable(value.resolvedAt, ::long)
    }

    private fun AgentMementoReader.incident(): AgentRuntimeIncident = AgentRuntimeIncident(
        identifier(),
        identifier(),
        identifier(),
        nullable { identifier() },
        text(),
        enum(),
        boolean(),
        long(),
        nullable { long() },
    )

    private fun AgentMementoWriter.lease(value: AgentRunLease) {
        identifier(value.leaseId)
        text(value.ownerId.value)
        long(value.fencingToken)
        long(value.acquiredAt)
        long(value.expiresAt)
    }

    private fun AgentMementoReader.lease(): AgentRunLease = AgentRunLease(
        identifier(),
        ProviderId(text()),
        long(),
        long(),
        long(),
    )

    private fun AgentMementoWriter.cancellation(value: AgentCancellation) {
        text(value.reasonCode)
        long(value.requestedAt)
    }

    private fun AgentMementoReader.cancellation(): AgentCancellation = AgentCancellation(text(), long())

    private fun AgentMementoWriter.failure(value: AgentRunFailure) {
        text(value.category.value)
        text(value.code)
        nullable(value.safeMessage, ::text)
    }

    private fun AgentMementoReader.failure(): AgentRunFailure = AgentRunFailure(
        AgentFailureCategory(text()),
        text(),
        nullable { text() },
    )

    private fun AgentMementoWriter.pendingOperation(value: AgentPendingOperation) {
        when (value) {
            is AgentPendingModelOperation -> {
                byte(PENDING_MODEL_TAG)
                pendingModel(value)
            }
            is AgentPendingToolOperation -> {
                byte(PENDING_TOOL_TAG)
                pendingTool(value)
            }
            else -> throw IllegalArgumentException(
                "Agent pending operation type '${value.javaClass.name}' is not persistable.",
            )
        }
    }

    private fun AgentMementoReader.pendingOperation(
        cancellationToken: AgentCancellationToken,
    ): AgentPendingOperation = when (val tag = byte()) {
        PENDING_MODEL_TAG -> pendingModel()
        PENDING_TOOL_TAG -> pendingTool(cancellationToken)
        else -> throw IllegalArgumentException("Agent pending operation tag $tag is unsupported.")
    }

    private fun AgentMementoWriter.pendingModel(value: AgentPendingModelOperation) {
        identifier(value.operationId)
        identifier(value.stepId)
        identifier(value.requestId)
        modelDescriptor(value.descriptor)
        list(value.tools, MAX_TOOLS) { toolDescriptor(it) }
        long(value.maximumInputTokens)
        long(value.maximumOutputTokens)
        long(value.maximumCostMicros)
        long(value.maximumDurationMillis)
        long(value.deadlineAt)
        int(value.attempt)
        enum(value.phase)
        identifier(value.checkpointId)
        nullable(value.claimedLeaseId) { identifier(it) }
        long(value.createdAt)
        long(value.updatedAt)
        text(value.operationDigest, DIGEST_BYTES)
    }

    private fun AgentMementoReader.pendingModel(): AgentPendingModelOperation {
        val operation = AgentPendingModelOperation(
            identifier(),
            identifier(),
            identifier(),
            modelDescriptor(),
            list(MAX_TOOLS) { toolDescriptor() },
            long(),
            long(),
            long(),
            long(),
            long(),
            int(),
            enum(),
            identifier(),
            nullable { identifier() },
            long(),
            long(),
        )
        val expectedDigest = text(DIGEST_BYTES)
        require(operation.operationDigest == expectedDigest) {
            "Stored Agent pending-model digest does not match its fields."
        }
        return operation
    }

    private fun AgentMementoWriter.toolPlan(value: AgentToolExecutionPlan) {
        contentBlock(value.call)
        toolDescriptor(value.descriptor)
        text(value.authorizationProviderId.value)
        text(value.policyProviderId.value)
        text(value.idempotencyKey)
        text(value.action)
        text(value.resourceType)
        identifier(value.resourceId)
        text(value.resourceRevision)
        text(value.purpose)
        nullable(value.operatorId) { identifier(it) }
        nullable(value.operatorType, ::text)
        long(value.deadlineAt)
        text(value.planDigest, DIGEST_BYTES)
    }

    private fun AgentMementoReader.toolPlan(): AgentToolExecutionPlan {
        val call = contentBlock() as? AgentToolCallContentBlock
            ?: throw IllegalArgumentException("Stored Agent tool plan does not contain a canonical tool call.")
        val plan = AgentToolExecutionPlan(
            call,
            toolDescriptor(),
            ProviderId(text()),
            ProviderId(text()),
            text(),
            text(),
            text(),
            identifier(),
            text(),
            text(),
            nullable { identifier() },
            nullable { text() },
            long(),
        )
        val expectedDigest = text(DIGEST_BYTES)
        require(plan.planDigest == expectedDigest) { "Stored Agent tool plan digest does not match its fields." }
        return plan
    }

    private fun AgentMementoWriter.pendingTool(value: AgentPendingToolOperation) {
        identifier(value.operationId)
        identifier(value.stepId)
        toolPlan(value.plan)
        int(value.attempt)
        enum(value.phase)
        nullable(value.invocationId) { identifier(it) }
        nullable(value.invocationStartedAt, ::long)
        nullable(value.invocationDeadlineAt, ::long)
        authorizationRequest(value.preflightRequest)
        nullable(value.initialAuthorization) { authorizationDecision(it) }
        nullable(value.proposal) { policyProposal(it) }
        nullable(value.policyDecision) { policyDecision(it) }
        nullable(value.approvalRequest) { approvalRequest(it) }
        nullable(value.approvalDecision) { approvalDecision(it) }
        nullable(value.executionRecheck) { authorizationRequest(it) }
        nullable(value.executionAuthorization) { authorizationDecision(it) }
        nullable(value.consumption) { consumption ->
            text(consumption.logicalInvocationDigest, DIGEST_BYTES)
            executionConsumption(consumption)
        }
        nullable(value.finalExecutionRecheck) { authorizationRequest(it) }
        nullable(value.finalExecutionAuthorization) { authorizationDecision(it) }
        nullable(value.dispatchFenceRequest) { dispatchFenceRequest(it) }
        nullable(value.dispatchFenceConsumption) { dispatchFenceConsumption(it) }
        nullable(value.toolDispatchedAt, ::long)
        nullable(value.reservedCostMicros, ::long)
        nullable(value.reservedDurationMillis, ::long)
        identifier(value.checkpointId)
        nullable(value.claimedLeaseId) { identifier(it) }
        long(value.createdAt)
        long(value.updatedAt)
        text(value.operationDigest, DIGEST_BYTES)
    }

    private fun AgentMementoReader.pendingTool(
        cancellationToken: AgentCancellationToken,
    ): AgentPendingToolOperation {
        val operationId = identifier()
        val stepId = identifier()
        val plan = toolPlan()
        val attempt = int()
        val phase = enum<AgentPendingToolPhase>()
        val invocationId = nullable { identifier() }
        val invocationStartedAt = nullable { long() }
        val invocationDeadlineAt = nullable { long() }
        val preflight = authorizationRequest()
        val initialAuthorization = nullable { authorizationDecision(preflight) }
        val proposal = nullable {
            policyProposal(
                preflight,
                requireNotNull(initialAuthorization) {
                    "Stored Agent policy proposal lacks initial authorization evidence."
                },
            )
        }
        val policyDecision = nullable {
            policyDecision(requireNotNull(proposal) { "Stored Agent policy decision lacks its proposal." })
        }
        val approvalRequest = nullable { approvalRequest() }.also { request ->
            if (request != null) {
                request.requireValidFor(
                    requireNotNull(proposal) { "Stored Agent approval request lacks its proposal." },
                    requireNotNull(policyDecision) { "Stored Agent approval request lacks its policy decision." },
                    request.requestedAt,
                )
            }
        }
        val approvalDecision = nullable {
            approvalDecision(requireNotNull(approvalRequest) { "Stored Agent approval decision lacks its request." })
        }
        val executionRecheck = nullable { authorizationRequest() }.also { request ->
            if (request != null) request.requireExecutionRecheckOf(preflight)
        }
        val executionAuthorization = nullable {
            authorizationDecision(
                requireNotNull(executionRecheck) {
                    "Stored Agent execution authorization lacks its request."
                },
            )
        }
        val invocation = if (invocationId != null) {
            AuthorizedToolInvocation.authorize(
                invocationId,
                requireNotNull(proposal) { "Stored Agent invocation lacks a policy proposal." },
                plan.descriptor,
                requireNotNull(policyDecision) { "Stored Agent invocation lacks a policy decision." },
                requireNotNull(executionRecheck) { "Stored Agent invocation lacks an execution recheck." },
                requireNotNull(executionAuthorization) {
                    "Stored Agent invocation lacks execution authorization."
                },
                approvalRequest,
                approvalDecision,
                plan.arguments,
                plan.idempotencyKey,
                attempt,
                requireNotNull(invocationStartedAt) { "Stored Agent invocation lacks its start time." },
                requireNotNull(invocationDeadlineAt) { "Stored Agent invocation lacks its deadline." },
                cancellationToken,
            )
        } else {
            require(invocationStartedAt == null && invocationDeadlineAt == null) {
                "Stored Agent invocation lifetime exists without an invocation identifier."
            }
            null
        }
        val consumption = nullable {
            val expectedLogicalDigest = text(DIGEST_BYTES)
            val authorized = requireNotNull(invocation) { "Stored Agent execution receipt lacks its invocation." }
            require(authorized.logicalInvocationDigest == expectedLogicalDigest) {
                "Stored Agent logical invocation digest does not match its authorization chain."
            }
            executionConsumption(authorized)
        }
        val finalExecutionRecheck = nullable { authorizationRequest() }.also { request ->
            if (request != null) {
                request.requireFinalExecutionRecheckOf(
                    requireNotNull(executionRecheck) { "Stored final Agent recheck lacks its parent." },
                )
            }
        }
        val finalExecutionAuthorization = nullable {
            authorizationDecision(
                requireNotNull(finalExecutionRecheck) {
                    "Stored final Agent authorization lacks its request."
                },
            )
        }
        val dispatchFenceRequest = nullable {
            dispatchFenceRequest(
                requireNotNull(invocation) { "Stored Agent dispatch fence lacks its invocation." },
                requireNotNull(finalExecutionRecheck) { "Stored Agent dispatch fence lacks its final request." },
                requireNotNull(finalExecutionAuthorization) {
                    "Stored Agent dispatch fence lacks final authorization."
                },
            )
        }
        val dispatchFenceConsumption = nullable {
            dispatchFenceConsumption(
                requireNotNull(dispatchFenceRequest) { "Stored Agent dispatch receipt lacks its fence request." },
            )
        }
        val toolDispatchedAt = nullable { long() }
        val reservedCostMicros = nullable { long() }
        val reservedDurationMillis = nullable { long() }
        val checkpointId = identifier()
        val claimedLeaseId = nullable { identifier() }
        val createdAt = long()
        val updatedAt = long()
        val restored = AgentPendingToolOperation(
            operationId,
            stepId,
            plan,
            attempt,
            phase,
            preflight,
            initialAuthorization,
            proposal,
            policyDecision,
            approvalRequest,
            approvalDecision,
            executionRecheck,
            executionAuthorization,
            consumption,
            finalExecutionRecheck,
            finalExecutionAuthorization,
            dispatchFenceRequest,
            dispatchFenceConsumption,
            invocationId,
            invocationStartedAt,
            invocationDeadlineAt,
            toolDispatchedAt,
            reservedCostMicros,
            reservedDurationMillis,
            checkpointId,
            claimedLeaseId,
            createdAt,
            updatedAt,
        )
        val expectedDigest = text(DIGEST_BYTES)
        require(restored.operationDigest == expectedDigest) {
            "Stored Agent pending-tool digest does not match its evidence chain."
        }
        return restored
    }

    private fun AgentMementoWriter.authorizationRequest(value: AgentAuthorizationRequest) {
        identifier(value.requestId)
        nullable(value.parentRequestId) { identifier(it) }
        enum(value.phase)
        text(value.authorizationProviderId.value)
        identifier(value.executionContextId)
        identifier(value.tenantId)
        identifier(value.principalId)
        text(value.principalType)
        identifier(value.runId)
        identifier(value.stepId)
        text(value.toolProviderId.value)
        text(value.toolId.value)
        enum(value.toolRisk)
        text(value.descriptorDigest, DIGEST_BYTES)
        text(value.schemaDigest, DIGEST_BYTES)
        text(value.argumentsDigest, DIGEST_BYTES)
        text(value.idempotencyKeyDigest, DIGEST_BYTES)
        text(value.action)
        text(value.resourceType)
        identifier(value.resourceId)
        text(value.resourceRevision)
        text(value.purpose)
        long(value.requestedAt)
        long(value.expiresAt)
        text(value.bindingDigest, DIGEST_BYTES)
    }

    private fun AgentMementoReader.authorizationRequest(): AgentAuthorizationRequest = AgentAuthorizationRequest.restore(
        identifier(),
        nullable { identifier() },
        enum(),
        ProviderId(text()),
        identifier(),
        identifier(),
        identifier(),
        text(),
        identifier(),
        identifier(),
        ProviderId(text()),
        ToolId(text()),
        enum(),
        text(DIGEST_BYTES),
        text(DIGEST_BYTES),
        text(DIGEST_BYTES),
        text(DIGEST_BYTES),
        text(),
        text(),
        identifier(),
        text(),
        text(),
        long(),
        long(),
        text(DIGEST_BYTES),
    )

    private fun AgentMementoWriter.authorizationDecision(value: AgentAuthorizationDecision) {
        identifier(value.decisionId)
        text(value.providerId.value)
        enum(value.outcome)
        text(value.authorizationRevision)
        long(value.decidedAt)
        long(value.expiresAt)
        nullable(value.reasonCode, ::text)
        text(value.bindingDigest, DIGEST_BYTES)
    }

    private fun AgentMementoReader.authorizationDecision(
        request: AgentAuthorizationRequest,
    ): AgentAuthorizationDecision {
        val decisionId = identifier()
        val providerId = ProviderId(text())
        val outcome = enum<AgentAuthorizationOutcome>()
        val revision = text()
        val decidedAt = long()
        val expiresAt = long()
        val reasonCode = nullable { text() }
        val expectedBinding = text(DIGEST_BYTES)
        val decision = when (outcome) {
            AgentAuthorizationOutcome.ALLOW -> AgentAuthorizationDecision.allow(
                decisionId,
                providerId,
                request,
                revision,
                decidedAt,
                expiresAt,
                reasonCode,
            )
            AgentAuthorizationOutcome.DENY -> AgentAuthorizationDecision.deny(
                decisionId,
                providerId,
                request,
                revision,
                decidedAt,
                expiresAt,
                requireNotNull(reasonCode) { "Stored denied Agent authorization lacks a reason code." },
            )
        }
        require(decision.bindingDigest == expectedBinding) {
            "Stored Agent authorization decision binding does not match its request."
        }
        return decision
    }

    private fun AgentMementoWriter.policyProposal(value: AgentPolicyProposal) {
        identifier(value.proposalId)
        text(value.policyProviderId.value)
        enum(value.risk)
        budget(value.budget)
        usage(value.usage)
        long(value.requestedAt)
        long(value.expiresAt)
        text(value.policyInputDigest, DIGEST_BYTES)
    }

    private fun AgentMementoReader.policyProposal(
        authorizationRequest: AgentAuthorizationRequest,
        authorizationDecision: AgentAuthorizationDecision,
    ): AgentPolicyProposal {
        val proposal = AgentPolicyProposal.create(
            identifier(),
            ProviderId(text()),
            authorizationRequest,
            authorizationDecision,
            enum(),
            budget(),
            usage(),
            long(),
            long(),
        )
        val expectedDigest = text(DIGEST_BYTES)
        require(proposal.policyInputDigest == expectedDigest) {
            "Stored Agent policy-input digest does not match its fields."
        }
        return proposal
    }

    private fun AgentMementoWriter.policyDecision(value: AgentPolicyDecision) {
        identifier(value.decisionId)
        text(value.providerId.value)
        enum(value.outcome)
        text(value.policyRevision)
        long(value.decidedAt)
        long(value.expiresAt)
        nullable(value.reasonCode, ::text)
        text(value.policyInputDigest, DIGEST_BYTES)
    }

    private fun AgentMementoReader.policyDecision(proposal: AgentPolicyProposal): AgentPolicyDecision {
        val decisionId = identifier()
        val providerId = ProviderId(text())
        val outcome = enum<AgentPolicyOutcome>()
        val revision = text()
        val decidedAt = long()
        val expiresAt = long()
        val reasonCode = nullable { text() }
        val expectedInputDigest = text(DIGEST_BYTES)
        val decision = when (outcome) {
            AgentPolicyOutcome.ALLOW -> AgentPolicyDecision.allow(
                decisionId,
                providerId,
                proposal,
                revision,
                decidedAt,
                expiresAt,
                reasonCode,
            )
            AgentPolicyOutcome.DENY -> AgentPolicyDecision.deny(
                decisionId,
                providerId,
                proposal,
                revision,
                decidedAt,
                expiresAt,
                requireNotNull(reasonCode) { "Stored denied Agent policy decision lacks a reason code." },
            )
            AgentPolicyOutcome.REQUIRE_APPROVAL -> AgentPolicyDecision.requireApproval(
                decisionId,
                providerId,
                proposal,
                revision,
                decidedAt,
                expiresAt,
                reasonCode,
            )
        }
        require(decision.policyInputDigest == expectedInputDigest) {
            "Stored Agent policy decision does not match its proposal."
        }
        return decision
    }

    private fun AgentMementoWriter.approvalRequest(value: AgentApprovalRequest) {
        identifier(value.requestId)
        identifier(value.proposalId)
        identifier(value.policyDecisionId)
        text(value.policyProviderId.value)
        text(value.policyInputDigest, DIGEST_BYTES)
        text(value.policyRevision)
        long(value.policyExpiresAt)
        enum(value.policyOutcome)
        identifier(value.tenantId)
        identifier(value.principalId)
        text(value.principalType)
        identifier(value.runId)
        identifier(value.stepId)
        text(value.toolProviderId.value)
        text(value.toolId.value)
        text(value.descriptorDigest, DIGEST_BYTES)
        text(value.schemaDigest, DIGEST_BYTES)
        text(value.argumentsDigest, DIGEST_BYTES)
        text(value.idempotencyKeyDigest, DIGEST_BYTES)
        identifier(value.authorizationRequestId)
        identifier(value.executionContextId)
        text(value.authorizationProviderId.value)
        identifier(value.authorizationDecisionId)
        text(value.authorizationBindingDigest, DIGEST_BYTES)
        text(value.authorizationRevision)
        long(value.authorizationRequestExpiresAt)
        long(value.authorizationExpiresAt)
        text(value.authorizationAction)
        text(value.authorizationResourceType)
        identifier(value.authorizationResourceId)
        text(value.authorizationResourceRevision)
        text(value.authorizationPurpose)
        identifier(value.operatorId)
        text(value.operatorType)
        text(value.nonce)
        long(value.requestedAt)
        long(value.expiresAt)
        text(value.evidenceDigest, DIGEST_BYTES)
    }

    private fun AgentMementoReader.approvalRequest(): AgentApprovalRequest = AgentApprovalRequest.restore(
        identifier(),
        identifier(),
        identifier(),
        ProviderId(text()),
        text(DIGEST_BYTES),
        text(),
        long(),
        enum(),
        identifier(),
        identifier(),
        text(),
        identifier(),
        identifier(),
        ProviderId(text()),
        ToolId(text()),
        text(DIGEST_BYTES),
        text(DIGEST_BYTES),
        text(DIGEST_BYTES),
        text(DIGEST_BYTES),
        identifier(),
        identifier(),
        ProviderId(text()),
        identifier(),
        text(DIGEST_BYTES),
        text(),
        long(),
        long(),
        text(),
        text(),
        identifier(),
        text(),
        text(),
        identifier(),
        text(),
        text(),
        long(),
        long(),
        text(DIGEST_BYTES),
    )

    private fun AgentMementoWriter.approvalDecision(value: AgentApprovalDecision) {
        identifier(value.decisionId)
        enum(value.outcome)
        identifier(value.operatorId)
        text(value.operatorType)
        long(value.decidedAt)
        nullable(value.reasonCode, ::text)
        identifier(value.requestId)
    }

    private fun AgentMementoReader.approvalDecision(request: AgentApprovalRequest): AgentApprovalDecision {
        val decisionId = identifier()
        val outcome = enum<AgentApprovalOutcome>()
        val operatorId = identifier()
        val operatorType = text()
        val decidedAt = long()
        val reasonCode = nullable { text() }
        val expectedRequestId = identifier()
        require(expectedRequestId == request.requestId) {
            "Stored Agent approval decision does not match its request."
        }
        return when (outcome) {
            AgentApprovalOutcome.APPROVED -> AgentApprovalDecision.approve(
                decisionId,
                request,
                operatorId,
                operatorType,
                decidedAt,
                reasonCode,
            )
            AgentApprovalOutcome.REJECTED -> AgentApprovalDecision.reject(
                decisionId,
                request,
                operatorId,
                operatorType,
                decidedAt,
                requireNotNull(reasonCode) { "Stored rejected Agent approval lacks a reason code." },
            )
        }
    }

    private fun AgentMementoWriter.executionConsumption(value: AgentExecutionContextConsumption) {
        identifier(value.receiptId)
        text(value.consumerId.value)
        enum(value.status)
        long(value.consumedAt)
        text(value.consumerRevision)
        identifier(value.executionContextId)
        identifier(value.invocationId)
        text(value.idempotencyKeyDigest, DIGEST_BYTES)
    }

    private fun AgentMementoReader.executionConsumption(
        invocation: AuthorizedToolInvocation,
    ): AgentExecutionContextConsumption {
        val receiptId = identifier()
        val consumerId = ProviderId(text())
        val status = enum<AgentExecutionContextConsumptionStatus>()
        val consumedAt = long()
        val revision = text()
        val executionContextId = identifier()
        val invocationId = identifier()
        val idempotencyDigest = text(DIGEST_BYTES)
        require(executionContextId == invocation.executionContextId && invocationId == invocation.invocationId &&
            idempotencyDigest == invocation.idempotencyKeyDigest
        ) { "Stored Agent execution receipt identifiers do not match its invocation." }
        return when (status) {
            AgentExecutionContextConsumptionStatus.CLAIMED -> AgentExecutionContextConsumption.claimed(
                receiptId,
                consumerId,
                invocation,
                consumedAt,
                revision,
            )
            AgentExecutionContextConsumptionStatus.REPLAYED -> AgentExecutionContextConsumption.replayed(
                receiptId,
                consumerId,
                invocation,
                consumedAt,
                revision,
            )
        }
    }

    private fun AgentMementoWriter.dispatchFenceRequest(value: AgentDispatchAuthorizationFenceRequest) {
        identifier(value.fenceId)
        text(value.consumerId.value)
        long(value.requestedAt)
        long(value.expiresAt)
        text(value.bindingDigest, DIGEST_BYTES)
    }

    private fun AgentMementoReader.dispatchFenceRequest(
        invocation: AuthorizedToolInvocation,
        finalRequest: AgentAuthorizationRequest,
        finalDecision: AgentAuthorizationDecision,
    ): AgentDispatchAuthorizationFenceRequest {
        val request = AgentDispatchAuthorizationFenceRequest(
            identifier(),
            ProviderId(text()),
            invocation,
            finalRequest,
            finalDecision,
            long(),
            long(),
        )
        val expectedDigest = text(DIGEST_BYTES)
        require(request.bindingDigest == expectedDigest) {
            "Stored Agent dispatch-fence digest does not match its authorization chain."
        }
        return request
    }

    private fun AgentMementoWriter.dispatchFenceConsumption(
        value: AgentDispatchAuthorizationFenceConsumption,
    ) {
        identifier(value.receiptId)
        enum(value.status)
        long(value.consumedAt)
        text(value.providerRevision)
        identifier(value.fenceId)
        text(value.requestBindingDigest, DIGEST_BYTES)
    }

    private fun AgentMementoReader.dispatchFenceConsumption(
        request: AgentDispatchAuthorizationFenceRequest,
    ): AgentDispatchAuthorizationFenceConsumption {
        val receiptId = identifier()
        val status = enum<AgentDispatchAuthorizationFenceStatus>()
        val consumedAt = long()
        val revision = text()
        val fenceId = identifier()
        val bindingDigest = text(DIGEST_BYTES)
        require(fenceId == request.fenceId && bindingDigest == request.bindingDigest) {
            "Stored Agent dispatch-fence receipt does not match its request."
        }
        return when (status) {
            AgentDispatchAuthorizationFenceStatus.CONSUMED -> AgentDispatchAuthorizationFenceConsumption.consumed(
                receiptId,
                request,
                consumedAt,
                revision,
            )
            AgentDispatchAuthorizationFenceStatus.REPLAYED -> AgentDispatchAuthorizationFenceConsumption.replayed(
                receiptId,
                request,
                consumedAt,
                revision,
            )
        }
    }

    private fun AgentMementoWriter.writeEvent(value: AgentRunEvent) {
        when (value) {
            is AgentRunStatusChangedEvent -> {
                byte(EVENT_STATUS_TAG)
                eventIdentity(value)
                nullable(value.previousStatus) { enum(it) }
                enum(value.currentStatus)
                nullable(value.reasonCode, ::text)
            }
            is AgentRunMessageEvent -> {
                byte(EVENT_MESSAGE_TAG)
                eventIdentity(value)
                message(value.message)
            }
            is AgentRunUsageEvent -> {
                byte(EVENT_USAGE_TAG)
                eventIdentity(value)
                usage(value.cumulativeUsage)
            }
            is AgentRunApprovalRequiredEvent -> {
                byte(EVENT_APPROVAL_TAG)
                eventIdentity(value)
                approvalRequest(value.approvalRequest)
            }
            is AgentRuntimeCheckpointEvent -> {
                byte(EVENT_CHECKPOINT_TAG)
                eventIdentity(value)
                checkpoint(value.checkpoint)
            }
            is AgentRuntimeIncidentEvent -> {
                byte(EVENT_INCIDENT_TAG)
                eventIdentity(value)
                incident(value.incident)
            }
            else -> throw IllegalArgumentException(
                "Agent run event type '${value.javaClass.name}' is not persistable.",
            )
        }
    }

    private fun AgentMementoReader.readEvent(): AgentRunEvent = when (val tag = byte()) {
        EVENT_STATUS_TAG -> eventIdentity { runId, tenantId, sequence, occurredAt ->
            AgentRunStatusChangedEvent(
                runId,
                tenantId,
                sequence,
                occurredAt,
                nullable { enum<AgentRunStatus>() },
                enum(),
                nullable { text() },
            )
        }
        EVENT_MESSAGE_TAG -> eventIdentity { runId, tenantId, sequence, occurredAt ->
            AgentRunMessageEvent(runId, tenantId, sequence, occurredAt, message())
        }
        EVENT_USAGE_TAG -> eventIdentity { runId, tenantId, sequence, occurredAt ->
            AgentRunUsageEvent(runId, tenantId, sequence, occurredAt, usage())
        }
        EVENT_APPROVAL_TAG -> eventIdentity { runId, tenantId, sequence, occurredAt ->
            AgentRunApprovalRequiredEvent(runId, tenantId, sequence, occurredAt, approvalRequest())
        }
        EVENT_CHECKPOINT_TAG -> eventIdentity { runId, tenantId, sequence, occurredAt ->
            AgentRuntimeCheckpointEvent(runId, tenantId, sequence, occurredAt, checkpoint())
        }
        EVENT_INCIDENT_TAG -> eventIdentity { runId, tenantId, sequence, occurredAt ->
            AgentRuntimeIncidentEvent(runId, tenantId, sequence, occurredAt, incident())
        }
        else -> throw IllegalArgumentException("Agent run event tag $tag is unsupported.")
    }

    private fun AgentMementoWriter.eventIdentity(value: AgentRunEvent) {
        identifier(value.runId)
        identifier(value.tenantId)
        long(value.sequence)
        long(value.occurredAt)
    }

    private fun <T : AgentRunEvent> AgentMementoReader.eventIdentity(
        factory: (Identifier, Identifier, Long, Long) -> T,
    ): T = factory(identifier(), identifier(), long(), long())

    private companion object {
        val STATE_MAGIC: ByteArray = "FWAGSTAT".toByteArray(StandardCharsets.US_ASCII)
        val EVENT_MAGIC: ByteArray = "FWAGEVNT".toByteArray(StandardCharsets.US_ASCII)

        const val PENDING_MODEL_TAG = 1
        const val PENDING_TOOL_TAG = 2

        const val EVENT_STATUS_TAG = 1
        const val EVENT_MESSAGE_TAG = 2
        const val EVENT_USAGE_TAG = 3
        const val EVENT_APPROVAL_TAG = 4
        const val EVENT_CHECKPOINT_TAG = 5
        const val EVENT_INCIDENT_TAG = 6

        const val MAX_IDENTIFIER_BYTES = 4 * 1_024
        const val MAX_ENUM_BYTES = 128
        const val DIGEST_BYTES = 64
        const val MAX_CONTENT_PAYLOAD_BYTES = 16 * 1_024 * 1_024
        const val MAX_SCHEMA_BYTES = 1 * 1_024 * 1_024
        const val MAX_STATE_MESSAGES = 512
        const val MAX_MESSAGE_BLOCKS = 128
        const val MAX_STATE_LEDGER_ITEMS = 1_024
        const val MAX_TOOLS = 128
        const val MAX_CAPABILITIES = 128
        const val MAX_USAGE_DIMENSIONS = 64
    }
}
