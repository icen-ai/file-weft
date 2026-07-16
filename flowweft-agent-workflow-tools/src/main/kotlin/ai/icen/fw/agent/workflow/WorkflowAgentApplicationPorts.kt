package ai.icen.fw.agent.workflow

import ai.icen.fw.agent.api.AgentExecutableToolInvocation
import ai.icen.fw.agent.api.AgentPolicyOutcome
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.workflow.api.WorkflowPrincipalRef
import java.util.concurrent.CompletionStage

/** Trusted identity and exact Agent evidence delivered to Workflow application use cases. */
class WorkflowAgentExecutionContext internal constructor(
    executable: AgentExecutableToolInvocation,
    val command: WorkflowAgentCommand,
) {
    private val authorized = executable.invocation
    val tenantId: Identifier = authorized.tenantId
    val principalId: Identifier = authorized.principalId
    val principalType: String = authorized.principalType
    val principal: WorkflowPrincipalRef = WorkflowPrincipalRef.of(principalType, principalId.value)
    val runId: Identifier = authorized.runId
    val stepId: Identifier = authorized.stepId
    val invocationId: Identifier = authorized.invocationId
    val executionContextId: Identifier = authorized.executionContextId
    val authorizationDecisionId: Identifier = executable.finalAuthorizationDecision.decisionId
    val authorizationRevision: String = executable.finalAuthorizationDecision.authorizationRevision
    val authorizationExpiresAt: Long = executable.finalAuthorizationDecision.expiresAt
    val preparedAt: Long = executable.preparedAt
    val argumentsDigest: String = authorized.argumentsDigest
    val descriptorDigest: String = authorized.descriptorDigest
    val schemaDigest: String = authorized.schemaDigest
    val idempotencyKey: String = authorized.idempotencyKey
    val authorityContextDigest: String

    init {
        require(authorized.arguments.contentEquals(command.arguments) && argumentsDigest == command.argumentsDigest) {
            "Workflow Agent command changed after authorization."
        }
        require(authorized.authorizationAction == command.operation.action &&
            authorized.authorizationResourceType == command.operation.resourceType &&
            authorized.authorizationResourceId.value == command.resourceId &&
            authorized.authorizationResourceRevision == command.resourceRevision &&
            authorized.authorizationPurpose == command.purpose && idempotencyKey == command.idempotencyKey
        ) { "Workflow Agent command scope changed after authorization." }
        require(executable.finalAuthorizationDecision.authorizationRevision == authorized.authorizationRevision &&
            executable.preparedAt < executable.finalAuthorizationDecision.expiresAt
        ) { "Workflow Agent authorization revision or expiry drifted before dispatch." }
        if (command.operation.confirmationRequired) {
            val request = requireNotNull(authorized.approvalRequest) {
                "Workflow Agent high-risk operation requires exact confirmation."
            }
            val decision = requireNotNull(authorized.approvalDecision) {
                "Workflow Agent high-risk operation requires an approved confirmation."
            }
            require(authorized.policyDecision.outcome == AgentPolicyOutcome.REQUIRE_APPROVAL &&
                request.operatorId == principalId && request.operatorType == principalType &&
                decision.operatorId == principalId && decision.operatorType == principalType
            ) { "Workflow Agent confirmation does not belong to the current principal." }
            decision.requireApprovedFor(request, authorized.proposal, authorized.policyDecision, executable.preparedAt)
        }
        authorityContextDigest = WorkflowAgentDigest("flowweft.agent.workflow.authority-context.v1")
            .add(tenantId.value)
            .add(principalType)
            .add(principalId.value)
            .add(runId.value)
            .add(stepId.value)
            .add(invocationId.value)
            .add(executionContextId.value)
            .add(authorizationDecisionId.value)
            .add(authorizationRevision)
            .add(authorizationExpiresAt)
            .add(command.commandDigest)
            .finish()
    }

    override fun toString(): String = "WorkflowAgentExecutionContext(<redacted>)"
}

/** Fresh Workflow-specific authorization request made after the Agent one-time dispatch fence. */
class WorkflowAgentExecutionAuthorizationRequest internal constructor(
    val context: WorkflowAgentExecutionContext,
    val requestedAt: Long,
) {
    val action: String = context.command.operation.action
    val resourceType: String = context.command.operation.resourceType
    val resourceId: String = context.command.resourceId
    val resourceRevision: String = context.command.resourceRevision
    val purpose: String = context.command.purpose
    val requestDigest: String

    init {
        require(requestedAt >= context.preparedAt && requestedAt < context.authorizationExpiresAt) {
            "Workflow Agent execution authorization request is outside its current authority window."
        }
        requestDigest = WorkflowAgentDigest("flowweft.agent.workflow.execution-authorization.v1")
            .add(context.authorityContextDigest)
            .add(action)
            .add(resourceType)
            .add(resourceId)
            .add(resourceRevision)
            .add(purpose)
            .add(context.command.commandDigest)
            .add(requestedAt)
            .finish()
    }

    override fun toString(): String = "WorkflowAgentExecutionAuthorizationRequest(<redacted>)"
}

enum class WorkflowAgentExecutionAuthorizationOutcome {
    AUTHORIZED,
    DENIED,
}

/** Payload-free Workflow authorization evidence; denial codes are safe structured codes only. */
class WorkflowAgentExecutionAuthorizationDecision private constructor(
    decisionId: String,
    request: WorkflowAgentExecutionAuthorizationRequest,
    val outcome: WorkflowAgentExecutionAuthorizationOutcome,
    authorityRevision: String,
    authorityDigest: String,
    val evaluatedAt: Long,
    val expiresAt: Long,
    reasonCode: String?,
) {
    val decisionId: String = workflowAgentId(decisionId, "Workflow authorization decision id")
    val requestDigest: String = request.requestDigest
    val authorityRevision: String = workflowAgentText(authorityRevision, 512, "authority revision")
    val authorityDigest: String = workflowAgentRequireSha256(authorityDigest, "authority")
    val reasonCode: String? = reasonCode?.let { workflowAgentCode(it, "authorization reason") }
    val decisionDigest: String

    init {
        require(evaluatedAt >= request.requestedAt && evaluatedAt < request.context.authorizationExpiresAt &&
            expiresAt > evaluatedAt && expiresAt <= request.context.authorizationExpiresAt
        ) { "Workflow Agent execution authorization lifetime is invalid." }
        require(outcome != WorkflowAgentExecutionAuthorizationOutcome.DENIED || this.reasonCode != null) {
            "Workflow Agent authorization denial requires a safe reason code."
        }
        decisionDigest = WorkflowAgentDigest("flowweft.agent.workflow.execution-decision.v1")
            .add(this.decisionId)
            .add(requestDigest)
            .add(outcome.name)
            .add(this.authorityRevision)
            .add(this.authorityDigest)
            .add(evaluatedAt)
            .add(expiresAt)
            .add(this.reasonCode ?: "-")
            .finish()
    }

    internal fun requireAuthorizedFor(request: WorkflowAgentExecutionAuthorizationRequest, now: Long) {
        require(requestDigest == request.requestDigest &&
            authorityRevision == request.context.authorizationRevision &&
            evaluatedAt >= request.requestedAt && now >= evaluatedAt && now < expiresAt &&
            outcome == WorkflowAgentExecutionAuthorizationOutcome.AUTHORIZED
        ) { "Workflow Agent execution authorization is denied, stale, or drifted." }
    }

    override fun toString(): String = "WorkflowAgentExecutionAuthorizationDecision(outcome=$outcome)"

    companion object {
        @JvmStatic
        fun authorize(
            decisionId: String,
            request: WorkflowAgentExecutionAuthorizationRequest,
            authorityRevision: String,
            authorityDigest: String,
            evaluatedAt: Long,
            expiresAt: Long,
        ): WorkflowAgentExecutionAuthorizationDecision = WorkflowAgentExecutionAuthorizationDecision(
            decisionId,
            request,
            WorkflowAgentExecutionAuthorizationOutcome.AUTHORIZED,
            authorityRevision,
            authorityDigest,
            evaluatedAt,
            expiresAt,
            null,
        )

        @JvmStatic
        fun deny(
            decisionId: String,
            request: WorkflowAgentExecutionAuthorizationRequest,
            authorityRevision: String,
            authorityDigest: String,
            evaluatedAt: Long,
            expiresAt: Long,
            reasonCode: String,
        ): WorkflowAgentExecutionAuthorizationDecision = WorkflowAgentExecutionAuthorizationDecision(
            decisionId,
            request,
            WorkflowAgentExecutionAuthorizationOutcome.DENIED,
            authorityRevision,
            authorityDigest,
            evaluatedAt,
            expiresAt,
            reasonCode,
        )
    }
}

/** Must authorize from the request alone; resource existence must not become an authorization oracle. */
fun interface WorkflowAgentExecutionAuthorizationPort {
    fun authorize(
        request: WorkflowAgentExecutionAuthorizationRequest,
    ): WorkflowAgentExecutionAuthorizationDecision
}

/** Only this type can cross an application mutation port. */
class WorkflowAgentAuthorizedCommand internal constructor(
    val context: WorkflowAgentExecutionContext,
    val command: WorkflowAgentCommand,
    val authorization: WorkflowAgentExecutionAuthorizationDecision,
) {
    val authorizationEvidenceDigest: String = WorkflowAgentDigest(
        "flowweft.agent.workflow.authorized-command.v1",
    )
        .add(context.authorityContextDigest)
        .add(command.commandDigest)
        .add(authorization.decisionDigest)
        .finish()

    val payload: ByteArray
        get() = command.payload

    override fun toString(): String = "WorkflowAgentAuthorizedCommand(operation=${command.operation.toolId.value})"
}

enum class WorkflowAgentUseCaseStatus {
    SUCCEEDED,
    REJECTED,
    OUTCOME_UNKNOWN,
}

/** Safe application result. Raw exceptions, SQL, credentials and unrestricted variables never cross this type. */
class WorkflowAgentUseCaseResult private constructor(
    val status: WorkflowAgentUseCaseStatus,
    resultCode: String,
    safeResult: ByteArray?,
    safeResultDigest: String?,
    outcomeReference: Identifier?,
    val retryable: Boolean,
) {
    val resultCode: String = workflowAgentCode(resultCode, "result")
    private val safeResultSnapshot: ByteArray?
    val safeResultDigest: String?
    val outcomeReference: Identifier? = outcomeReference

    val safeResult: ByteArray?
        get() = safeResultSnapshot?.copyOf()

    init {
        require((status == WorkflowAgentUseCaseStatus.SUCCEEDED) == (safeResult != null)) {
            "Workflow Agent success result payload binding is invalid."
        }
        require((status == WorkflowAgentUseCaseStatus.OUTCOME_UNKNOWN) == (outcomeReference != null)) {
            "Workflow Agent unknown-outcome reference binding is invalid."
        }
        val checkedResult = safeResult?.let {
            WorkflowAgentCanonicalJson.requireCanonicalObject(it, WORKFLOW_AGENT_MAX_RESULT_BYTES)
        }
        val checkedDigest = safeResultDigest?.let { workflowAgentRequireSha256(it, "safe result") }
        require((checkedResult == null) == (checkedDigest == null)) {
            "Workflow Agent safe result digest binding is incomplete."
        }
        require(checkedResult == null || workflowAgentSha256(checkedResult) == checkedDigest) {
            "Workflow Agent safe result digest does not match its bytes."
        }
        safeResultSnapshot = checkedResult?.copyOf()
        this.safeResultDigest = checkedDigest
    }

    override fun toString(): String = "WorkflowAgentUseCaseResult(status=$status, resultCode=$resultCode)"

    companion object {
        @JvmStatic
        fun succeeded(
            resultCode: String,
            safeResult: ByteArray,
            safeResultDigest: String,
        ): WorkflowAgentUseCaseResult = WorkflowAgentUseCaseResult(
            WorkflowAgentUseCaseStatus.SUCCEEDED,
            resultCode,
            safeResult,
            safeResultDigest,
            null,
            false,
        )

        @JvmStatic
        @JvmOverloads
        fun rejected(resultCode: String, retryable: Boolean = false): WorkflowAgentUseCaseResult =
            WorkflowAgentUseCaseResult(
                WorkflowAgentUseCaseStatus.REJECTED,
                resultCode,
                null,
                null,
                null,
                retryable,
            )

        @JvmStatic
        @JvmOverloads
        fun outcomeUnknown(
            resultCode: String,
            outcomeReference: Identifier,
            retryable: Boolean = true,
        ): WorkflowAgentUseCaseResult = WorkflowAgentUseCaseResult(
            WorkflowAgentUseCaseStatus.OUTCOME_UNKNOWN,
            resultCode,
            null,
            null,
            outcomeReference,
            retryable,
        )
    }
}

interface WorkflowAgentDefinitionUseCasePort {
    fun saveDraft(command: WorkflowAgentAuthorizedCommand): CompletionStage<WorkflowAgentUseCaseResult>

    fun publish(command: WorkflowAgentAuthorizedCommand): CompletionStage<WorkflowAgentUseCaseResult>

    fun retire(command: WorkflowAgentAuthorizedCommand): CompletionStage<WorkflowAgentUseCaseResult>
}

interface WorkflowAgentInstanceUseCasePort {
    fun start(command: WorkflowAgentAuthorizedCommand): CompletionStage<WorkflowAgentUseCaseResult>

    fun suspend(command: WorkflowAgentAuthorizedCommand): CompletionStage<WorkflowAgentUseCaseResult>

    fun resume(command: WorkflowAgentAuthorizedCommand): CompletionStage<WorkflowAgentUseCaseResult>

    fun cancel(command: WorkflowAgentAuthorizedCommand): CompletionStage<WorkflowAgentUseCaseResult>

    fun terminate(command: WorkflowAgentAuthorizedCommand): CompletionStage<WorkflowAgentUseCaseResult>
}

interface WorkflowAgentHumanTaskUseCasePort {
    fun approve(command: WorkflowAgentAuthorizedCommand): CompletionStage<WorkflowAgentUseCaseResult>

    fun reject(command: WorkflowAgentAuthorizedCommand): CompletionStage<WorkflowAgentUseCaseResult>

    fun claim(command: WorkflowAgentAuthorizedCommand): CompletionStage<WorkflowAgentUseCaseResult>

    fun unclaim(command: WorkflowAgentAuthorizedCommand): CompletionStage<WorkflowAgentUseCaseResult>

    fun delegate(command: WorkflowAgentAuthorizedCommand): CompletionStage<WorkflowAgentUseCaseResult>

    fun transfer(command: WorkflowAgentAuthorizedCommand): CompletionStage<WorkflowAgentUseCaseResult>

    fun addSign(command: WorkflowAgentAuthorizedCommand): CompletionStage<WorkflowAgentUseCaseResult>

    fun returnTask(command: WorkflowAgentAuthorizedCommand): CompletionStage<WorkflowAgentUseCaseResult>
}

fun interface WorkflowAgentIncidentUseCasePort {
    fun repair(command: WorkflowAgentAuthorizedCommand): CompletionStage<WorkflowAgentUseCaseResult>
}

/** Explicit ports only: no repository, persistence handle or raw domain mutation interface is exposed. */
class WorkflowAgentApplicationPorts(
    val definitions: WorkflowAgentDefinitionUseCasePort,
    val instances: WorkflowAgentInstanceUseCasePort,
    val humanTasks: WorkflowAgentHumanTaskUseCasePort,
    val incidents: WorkflowAgentIncidentUseCasePort,
)
