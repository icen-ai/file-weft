package ai.icen.fw.agent.workflow

import ai.icen.fw.agent.api.AgentCancellation
import ai.icen.fw.agent.api.AgentCancellationException
import ai.icen.fw.agent.api.AgentContentOrigin
import ai.icen.fw.agent.api.AgentDescriptorBoundToolExecutor
import ai.icen.fw.agent.api.AgentExecutableToolInvocation
import ai.icen.fw.agent.api.AgentRunContext
import ai.icen.fw.agent.api.AgentSizedContentBlock
import ai.icen.fw.agent.api.AgentToolCall
import ai.icen.fw.agent.api.AgentToolCatalog
import ai.icen.fw.agent.api.AgentToolDescriptor
import ai.icen.fw.agent.api.AgentToolDescriptorProvider
import ai.icen.fw.agent.api.AgentToolExecutor
import ai.icen.fw.agent.api.AgentToolObserver
import ai.icen.fw.agent.api.AgentToolResult
import ai.icen.fw.agent.api.AgentToolResultStatus
import ai.icen.fw.agent.api.AgentUsage
import ai.icen.fw.agent.api.ProviderId
import ai.icen.fw.agent.api.ToolId
import ai.icen.fw.agent.runtime.AgentToolExecutorRegistry
import ai.icen.fw.core.id.Identifier
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.LinkedHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

fun interface WorkflowAgentClock {
    fun currentTimeMillis(): Long

    companion object {
        @JvmField
        val SYSTEM: WorkflowAgentClock = WorkflowAgentClock { System.currentTimeMillis() }
    }
}

/** Structured, bounded and immutable tool output. It cannot carry privileged content origin. */
class WorkflowAgentResultContentBlock internal constructor(
    val status: WorkflowAgentUseCaseStatus,
    resultCode: String,
    safeJson: ByteArray,
) : AgentSizedContentBlock {
    val resultCode: String = workflowAgentCode(resultCode, "result block")
    private val safeJsonSnapshot: ByteArray = WorkflowAgentCanonicalJson.requireCanonicalObject(
        safeJson,
        WORKFLOW_AGENT_MAX_RESULT_BYTES,
    )
    val safeJsonDigest: String = workflowAgentSha256(safeJsonSnapshot)
    private val contentDigest: String = WorkflowAgentDigest("flowweft.agent.workflow.result-block.v1")
        .add(KIND)
        .add(status.name)
        .add(this.resultCode)
        .add(safeJsonSnapshot.size)
        .add(safeJsonDigest)
        .finish()

    val safeJson: ByteArray
        get() = safeJsonSnapshot.copyOf()

    override fun kind(): String = KIND

    override fun origin(): AgentContentOrigin = AgentContentOrigin.TOOL

    override fun bindingDigest(): String = contentDigest

    override fun canonicalPayloadSizeBytes(): Long = safeJsonSnapshot.size.toLong()

    override fun toString(): String = "WorkflowAgentResultContentBlock(status=$status, resultCode=$resultCode)"

    companion object {
        const val KIND: String = "flowweft-workflow-result"
    }
}

/** One descriptor-bound executor. Construction is internal so callers cannot pair it with another operation. */
internal class WorkflowAgentToolExecutor(
    private val operation: WorkflowAgentOperation,
    private val descriptor: AgentToolDescriptor,
    private val authorizationPort: WorkflowAgentExecutionAuthorizationPort,
    private val ports: WorkflowAgentApplicationPorts,
    private val clock: WorkflowAgentClock,
) : AgentDescriptorBoundToolExecutor {
    override fun providerId(): ProviderId = WorkflowAgentToolCatalog.PROVIDER_ID

    override fun toolId(): ToolId = operation.toolId

    override fun descriptorDigest(): String = descriptor.descriptorDigest

    override fun start(
        invocation: AgentExecutableToolInvocation,
        observer: AgentToolObserver,
    ): AgentToolCall {
        val invocationId = invocation.invocation.invocationId
        val startedAt = try {
            currentTime()
        } catch (_: RuntimeException) {
            return ImmediateWorkflowAgentToolCall(
                invocationId,
                CompletableFuture.completedFuture(
                    failed(invocationId, "WORKFLOW_CLOCK_UNAVAILABLE", invocation.preparedAt),
                ),
            )
        }
        val stage = try {
            invocation.requireExecutor(providerId(), toolId())
            require(invocation.invocation.descriptorDigest == descriptor.descriptorDigest &&
                invocation.invocation.schemaDigest == descriptor.schemaDigest &&
                invocation.invocation.descriptor.risk == descriptor.risk
            ) { "Workflow Agent descriptor drifted before execution." }
            invocation.invocation.cancellationToken.cancellation()?.let { throw AgentCancellationException(it) }
            require(startedAt >= invocation.preparedAt && startedAt < invocation.invocation.deadlineAt &&
                startedAt < invocation.finalAuthorizationDecision.expiresAt
            ) { "Workflow Agent invocation expired before execution." }
            val command = WorkflowAgentCommand.decode(toolId(), invocation.invocation.arguments)
            require(command.operation == operation) { "Workflow Agent command operation changed before execution." }
            val context = WorkflowAgentExecutionContext(invocation, command)
            val authorizationRequest = WorkflowAgentExecutionAuthorizationRequest(context, startedAt)
            val decision = authorizationPort.authorize(authorizationRequest)
            decision.requireAuthorizedFor(authorizationRequest, startedAt)
            val authorizedCommand = WorkflowAgentAuthorizedCommand(context, command, decision)
            route(authorizedCommand)
        } catch (_: AgentCancellationException) {
            return ImmediateWorkflowAgentToolCall(
                invocationId,
                CompletableFuture.completedFuture(cancelled(invocationId, startedAt)),
            )
        } catch (_: IllegalArgumentException) {
            return ImmediateWorkflowAgentToolCall(
                invocationId,
                CompletableFuture.completedFuture(failed(invocationId, "WORKFLOW_BINDING_REJECTED", startedAt)),
            )
        } catch (_: RuntimeException) {
            return ImmediateWorkflowAgentToolCall(
                invocationId,
                CompletableFuture.completedFuture(failed(invocationId, "WORKFLOW_AUTHORIZATION_UNAVAILABLE", startedAt)),
            )
        }
        val normalized = stage.handle { result, failure ->
            val completedAt = completionTime(startedAt)
            val duration = (completedAt - startedAt).coerceAtLeast(0L)
            if (failure != null || result == null) {
                failed(invocationId, "WORKFLOW_USE_CASE_FAILURE", completedAt, duration)
            } else {
                toolResult(invocationId, result, completedAt, duration)
            }
        }
        return ImmediateWorkflowAgentToolCall(invocationId, normalized)
    }

    private fun route(command: WorkflowAgentAuthorizedCommand): CompletionStage<WorkflowAgentUseCaseResult> =
        when (operation) {
            WorkflowAgentOperation.SAVE_DEFINITION_DRAFT -> ports.definitions.saveDraft(command)
            WorkflowAgentOperation.PUBLISH_DEFINITION -> ports.definitions.publish(command)
            WorkflowAgentOperation.RETIRE_DEFINITION -> ports.definitions.retire(command)
            WorkflowAgentOperation.START_INSTANCE -> ports.instances.start(command)
            WorkflowAgentOperation.SUSPEND_INSTANCE -> ports.instances.suspend(command)
            WorkflowAgentOperation.RESUME_INSTANCE -> ports.instances.resume(command)
            WorkflowAgentOperation.CANCEL_INSTANCE -> ports.instances.cancel(command)
            WorkflowAgentOperation.TERMINATE_INSTANCE -> ports.instances.terminate(command)
            WorkflowAgentOperation.APPROVE_HUMAN_TASK -> ports.humanTasks.approve(command)
            WorkflowAgentOperation.REJECT_HUMAN_TASK -> ports.humanTasks.reject(command)
            WorkflowAgentOperation.CLAIM_HUMAN_TASK -> ports.humanTasks.claim(command)
            WorkflowAgentOperation.UNCLAIM_HUMAN_TASK -> ports.humanTasks.unclaim(command)
            WorkflowAgentOperation.DELEGATE_HUMAN_TASK -> ports.humanTasks.delegate(command)
            WorkflowAgentOperation.TRANSFER_HUMAN_TASK -> ports.humanTasks.transfer(command)
            WorkflowAgentOperation.ADD_SIGN_HUMAN_TASK -> ports.humanTasks.addSign(command)
            WorkflowAgentOperation.RETURN_HUMAN_TASK -> ports.humanTasks.returnTask(command)
            WorkflowAgentOperation.REPAIR_INCIDENT -> ports.incidents.repair(command)
            else -> throw IllegalArgumentException("Workflow Agent operation is unsupported.")
        }

    private fun toolResult(
        invocationId: Identifier,
        result: WorkflowAgentUseCaseResult,
        completedAt: Long,
        duration: Long,
    ): AgentToolResult = when (result.status) {
        WorkflowAgentUseCaseStatus.SUCCEEDED -> {
            val bytes = requireNotNull(result.safeResult)
            AgentToolResult(
                invocationId,
                AgentToolResultStatus.SUCCEEDED,
                listOf(WorkflowAgentResultContentBlock(result.status, result.resultCode, bytes)),
                completedAt,
                usage = usage(duration),
            )
        }
        WorkflowAgentUseCaseStatus.REJECTED -> AgentToolResult(
            invocationId,
            AgentToolResultStatus.FAILED,
            listOf(errorBlock(result)),
            completedAt,
            result.resultCode,
            usage = usage(duration),
        )
        WorkflowAgentUseCaseStatus.OUTCOME_UNKNOWN -> AgentToolResult(
            invocationId,
            AgentToolResultStatus.OUTCOME_UNKNOWN,
            listOf(errorBlock(result)),
            completedAt,
            result.resultCode,
            requireNotNull(result.outcomeReference),
            usage(duration),
        )
    }

    private fun errorBlock(result: WorkflowAgentUseCaseResult): WorkflowAgentResultContentBlock {
        val json = ("{\"resultCode\":\"${result.resultCode}\",\"retryable\":${result.retryable}," +
            "\"status\":\"${result.status.name.lowercase()}\"}").toByteArray(StandardCharsets.UTF_8)
        return WorkflowAgentResultContentBlock(result.status, result.resultCode, json)
    }

    private fun failed(
        invocationId: Identifier,
        code: String,
        completedAt: Long,
        duration: Long = 0L,
    ): AgentToolResult = AgentToolResult(
        invocationId,
        AgentToolResultStatus.FAILED,
        listOf(
            WorkflowAgentResultContentBlock(
                WorkflowAgentUseCaseStatus.REJECTED,
                code,
                "{\"resultCode\":\"$code\",\"retryable\":false,\"status\":\"rejected\"}"
                    .toByteArray(StandardCharsets.UTF_8),
            ),
        ),
        completedAt,
        code,
        usage = usage(duration),
    )

    private fun cancelled(invocationId: Identifier, completedAt: Long): AgentToolResult = AgentToolResult(
        invocationId,
        AgentToolResultStatus.CANCELLED,
        emptyList(),
        completedAt,
        usage = usage(0L),
    )

    private fun usage(duration: Long): AgentUsage = AgentUsage(
        toolCalls = 1,
        durationMillis = duration,
        costMicros = 0L,
    )

    private fun currentTime(): Long =
        clock.currentTimeMillis().also { require(it >= 0L) { "Workflow Agent clock is invalid." } }

    private fun completionTime(fallback: Long): Long = try {
        clock.currentTimeMillis().also { require(it >= 0L) }.coerceAtLeast(fallback)
    } catch (_: RuntimeException) {
        fallback
    }
}

private class ImmediateWorkflowAgentToolCall(
    private val invocationId: Identifier,
    private val result: CompletionStage<AgentToolResult>,
) : AgentToolCall {
    override fun invocationId(): Identifier = invocationId

    override fun completion(): CompletionStage<AgentToolResult> = result

    /** Workflow application calls are durable/idempotent; cancellation cannot pretend to undo a dispatch. */
    override fun cancel(cancellation: AgentCancellation): CompletionStage<Boolean> =
        CompletableFuture.completedFuture(false)
}

/** Ready-to-register descriptor provider and executor registry for the complete first Workflow slice. */
class WorkflowAgentToolSuite @JvmOverloads constructor(
    val catalog: WorkflowAgentToolCatalog,
    authorizationPort: WorkflowAgentExecutionAuthorizationPort,
    ports: WorkflowAgentApplicationPorts,
    clock: WorkflowAgentClock = WorkflowAgentClock.SYSTEM,
) : AgentToolDescriptorProvider, AgentToolExecutorRegistry {
    private val executors: Map<ToolId, AgentToolExecutor>

    init {
        val values = LinkedHashMap<ToolId, AgentToolExecutor>()
        WorkflowAgentOperation.BUILT_INS.forEach { operation ->
            val descriptor = requireNotNull(catalog.descriptor(operation.toolId))
            values[operation.toolId] = WorkflowAgentToolExecutor(
                operation,
                descriptor,
                authorizationPort,
                ports,
                clock,
            )
        }
        executors = Collections.unmodifiableMap(values)
    }

    constructor(
        authorizationPort: WorkflowAgentExecutionAuthorizationPort,
        ports: WorkflowAgentApplicationPorts,
    ) : this(WorkflowAgentToolCatalog(), authorizationPort, ports, WorkflowAgentClock.SYSTEM)

    override fun providerId(): ProviderId = catalog.providerId()

    override fun descriptors(context: AgentRunContext): AgentToolCatalog = catalog.descriptors(context)

    override fun find(providerId: ProviderId, toolId: ToolId): AgentToolExecutor? =
        if (providerId == providerId()) executors[toolId] else null

    fun executor(toolId: ToolId): AgentToolExecutor? = executors[toolId]
}
