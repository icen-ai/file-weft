package ai.icen.fw.agent.web.runtime

import ai.icen.fw.agent.api.AgentApprovalDecision
import ai.icen.fw.agent.api.AgentApprovalRequest
import ai.icen.fw.agent.api.AgentBudget
import ai.icen.fw.agent.api.AgentCapabilityId
import ai.icen.fw.agent.api.AgentCitation
import ai.icen.fw.agent.api.AgentMessage
import ai.icen.fw.agent.api.AgentMessageRole
import ai.icen.fw.agent.api.AgentRunEvent
import ai.icen.fw.agent.api.AgentRunRequest
import ai.icen.fw.agent.api.AgentRunSnapshot
import ai.icen.fw.agent.api.AgentRunStatus
import ai.icen.fw.agent.api.AgentCancellation
import ai.icen.fw.agent.api.AgentToolRisk
import ai.icen.fw.agent.evaluation.AgentEvaluationEvaluatorReference
import ai.icen.fw.agent.evaluation.AgentEvaluationRegressionReport
import ai.icen.fw.agent.runtime.AgentEvaluationRunState
import ai.icen.fw.agent.runtime.AgentEvaluationRunStatus
import ai.icen.fw.agent.runtime.AgentEvaluationRunRequest
import ai.icen.fw.agent.runtime.AgentRunCommandContext
import ai.icen.fw.agent.web.api.AgentWebConversationDto
import ai.icen.fw.agent.web.api.AgentWebConversationSummaryDto
import ai.icen.fw.agent.web.api.AgentWebCursor
import ai.icen.fw.agent.web.api.AgentWebEvaluationRunDto
import ai.icen.fw.agent.web.api.AgentWebEvaluationStatusCode
import ai.icen.fw.agent.web.api.AgentWebPageQuery
import ai.icen.fw.agent.web.api.AgentWebProviderConfigurationDto
import ai.icen.fw.agent.web.api.AgentWebRunDto
import ai.icen.fw.agent.web.api.AgentWebToolConfirmationDecisionDto
import ai.icen.fw.agent.web.api.AgentWebToolConfirmationDetailDto
import ai.icen.fw.agent.web.api.AgentWebToolConfirmationSummaryDto
import ai.icen.fw.core.id.Identifier

class AgentWebStoredPage<T>(
    items: Collection<T>,
    val nextCursor: AgentWebCursor?,
) {
    val items: List<T> = immutableRuntimeList(items, MAX_WEB_RUNTIME_PAGE_SIZE, "Agent Web stored page")
}

class AgentWebStoredDurablePage<T>(
    items: Collection<T>,
    val nextSequence: Long?,
    val nextCursor: AgentWebCursor?,
    val cursorIssuedAt: Long?,
    val cursorExpiresAt: Long?,
) {
    val items: List<T> = immutableRuntimeList(items, MAX_WEB_RUNTIME_PAGE_SIZE, "Agent Web durable stored page")

    init {
        require((nextSequence == null) == (nextCursor == null) &&
            (nextSequence == null) == (cursorIssuedAt == null) &&
            (nextSequence == null) == (cursorExpiresAt == null)
        ) { "Agent Web durable page cursor fields do not agree." }
        require(nextSequence == null || nextSequence > 0L && cursorIssuedAt!! >= 0L &&
            cursorExpiresAt!! > cursorIssuedAt
        ) {
            "Agent Web durable page cursor is invalid."
        }
    }
}

/** Scoped digest; the raw Idempotency-Key never crosses a persistence port. */
class AgentWebMutationScope private constructor(
    scopeDigest: String,
    commandDigest: String,
    action: AgentWebAuthorizationAction,
    aggregateId: Identifier,
) {
    val scopeDigest: String = webRuntimeDigest(scopeDigest, "Agent Web mutation scope")
    val commandDigest: String = webRuntimeDigest(commandDigest, "Agent Web mutation command")
    val action: AgentWebAuthorizationAction = action
    val aggregateId: Identifier = aggregateId

    override fun equals(other: Any?): Boolean = other is AgentWebMutationScope && scopeDigest == other.scopeDigest
    override fun hashCode(): Int = scopeDigest.hashCode()
    override fun toString(): String = "AgentWebMutationScope(<redacted>)"

    companion object {
        @JvmStatic
        fun bind(
            context: ai.icen.fw.agent.web.api.AgentWebTrustedContext,
            rawIdempotencyKey: String,
            action: AgentWebAuthorizationAction,
            aggregateId: Identifier,
            commandDigest: String,
        ): AgentWebMutationScope {
            val checkedCommand = webRuntimeDigest(commandDigest, "Agent Web mutation command")
            val idempotencyToken = agentWebDerivedIdempotencyToken(
                context,
                rawIdempotencyKey,
                action,
                aggregateId,
            )
            // The idempotency identity deliberately excludes the command digest. This lets the
            // journal distinguish an exact replay from reuse of the same key for another command.
            val scope = AgentWebRuntimeDigest("flowweft.agent.web.runtime.mutation-scope.v2")
                .add(idempotencyToken)
                .finish()
            return AgentWebMutationScope(scope, checkedCommand, action, aggregateId)
        }
    }
}

/** Stable opaque token for downstream use cases; the raw HTTP key never crosses a port. */
internal fun agentWebDerivedIdempotencyToken(
    context: ai.icen.fw.agent.web.api.AgentWebTrustedContext,
    rawIdempotencyKey: String,
    action: AgentWebAuthorizationAction,
    aggregateId: Identifier,
): String {
    val rawKeyDigest = AgentWebRuntimeDigest("flowweft.agent.web.runtime.raw-idempotency-key.v1")
        .add(webRuntimeToken(rawIdempotencyKey, "Agent Web idempotency key"))
        .finish()
    return AgentWebRuntimeDigest("flowweft.agent.web.runtime.downstream-idempotency-scope.v1")
        .add(context.tenantId.value)
        .add(context.principalType)
        .add(context.principalId.value)
        .add(action.value)
        .add(aggregateId.value)
        .add(rawKeyDigest)
        .finish()
}

enum class AgentWebMutationStatus {
    RESERVED,
    SUCCEEDED,
    FAILED,
    OUTCOME_UNKNOWN,
}

class AgentWebMutationRecord(
    val scope: AgentWebMutationScope,
    operationId: Identifier,
    val status: AgentWebMutationStatus,
    resultResourceId: Identifier?,
    val resultVersion: Long?,
    diagnosticCode: String?,
    val createdAt: Long,
    val updatedAt: Long,
) {
    val operationId: Identifier = operationId
    val resultResourceId: Identifier? = resultResourceId
    val diagnosticCode: String? = diagnosticCode?.let { webRuntimeCode(it, "Agent Web mutation diagnostic") }

    init {
        require(createdAt >= 0L && updatedAt >= createdAt) { "Agent Web mutation timestamps are invalid." }
        require((status == AgentWebMutationStatus.SUCCEEDED) == (this.resultResourceId != null)) {
            "Agent Web successful mutation must identify its result."
        }
        require((status == AgentWebMutationStatus.SUCCEEDED) == (resultVersion != null)) {
            "Agent Web successful mutation must identify its result version."
        }
        require(resultVersion == null || resultVersion >= 0L) { "Agent Web mutation result version is invalid." }
        require((status == AgentWebMutationStatus.FAILED || status == AgentWebMutationStatus.OUTCOME_UNKNOWN) ==
            (this.diagnosticCode != null)
        ) { "Agent Web mutation diagnostic does not match its status." }
    }
}

enum class AgentWebMutationReserveStatus {
    CREATED,
    REPLAY,
    CONFLICT,
}

class AgentWebMutationReserveResult(
    val status: AgentWebMutationReserveStatus,
    val record: AgentWebMutationRecord,
)

class AgentWebMutationTransition(
    val scope: AgentWebMutationScope,
    operationId: Identifier,
    val expectedStatus: AgentWebMutationStatus,
    val nextStatus: AgentWebMutationStatus,
    resultResourceId: Identifier?,
    val resultVersion: Long?,
    diagnosticCode: String?,
    val transitionedAt: Long,
) {
    val operationId: Identifier = operationId
    val resultResourceId: Identifier? = resultResourceId
    val diagnosticCode: String? = diagnosticCode?.let { webRuntimeCode(it, "Agent Web mutation diagnostic") }

    init {
        require((expectedStatus == AgentWebMutationStatus.RESERVED &&
            nextStatus != AgentWebMutationStatus.RESERVED) ||
            (expectedStatus == AgentWebMutationStatus.OUTCOME_UNKNOWN &&
                (nextStatus == AgentWebMutationStatus.SUCCEEDED || nextStatus == AgentWebMutationStatus.FAILED))
        ) {
            "Agent Web mutation transition must close one reservation."
        }
        require(transitionedAt >= 0L) { "Agent Web mutation transition time is invalid." }
        require((nextStatus == AgentWebMutationStatus.SUCCEEDED) == (this.resultResourceId != null)) {
            "Agent Web mutation success result is invalid."
        }
        require((nextStatus == AgentWebMutationStatus.SUCCEEDED) == (resultVersion != null)) {
            "Agent Web mutation success version is invalid."
        }
        require((nextStatus == AgentWebMutationStatus.FAILED || nextStatus == AgentWebMutationStatus.OUTCOME_UNKNOWN) ==
            (this.diagnosticCode != null)
        ) { "Agent Web mutation transition diagnostic is invalid." }
    }
}

interface AgentWebMutationJournal {
    /** Must atomically reserve a scoped command or return the exact existing record. */
    fun reserve(
        scope: AgentWebAuthorizedPersistenceScope,
        mutation: AgentWebMutationScope,
        operationId: Identifier,
        requestedAt: Long,
    ): AgentWebMutationReserveResult

    fun compareAndSet(
        scope: AgentWebAuthorizedPersistenceScope,
        transition: AgentWebMutationTransition,
    ): AgentWebMutationRecord
}

/** Fail-closed journal admission used by every application coordinator. */
internal fun AgentWebMutationJournal.reserveBound(
    scope: AgentWebAuthorizedPersistenceScope,
    mutation: AgentWebMutationScope,
    proposedOperationId: Identifier,
    requestedAt: Long,
): AgentWebMutationReserveResult {
    repositoryAttests(scope.action == mutation.action && scope.target.resourceId == mutation.aggregateId)
    val result = reserve(scope, mutation, proposedOperationId, requestedAt)
    val record = result.record
    repositoryAttests(record.scope == mutation && record.scope.scopeDigest == mutation.scopeDigest &&
        record.scope.action == mutation.action && record.scope.aggregateId == mutation.aggregateId
    )
    when (result.status) {
        AgentWebMutationReserveStatus.CREATED -> repositoryAttests(
            record.operationId == proposedOperationId && record.status == AgentWebMutationStatus.RESERVED &&
                record.scope.commandDigest == mutation.commandDigest &&
                record.resultResourceId == null && record.resultVersion == null && record.diagnosticCode == null &&
                record.createdAt == requestedAt && record.updatedAt == requestedAt,
        )
        AgentWebMutationReserveStatus.REPLAY -> repositoryAttests(
            record.scope.commandDigest == mutation.commandDigest && record.createdAt <= requestedAt &&
                record.updatedAt <= requestedAt,
        )
        AgentWebMutationReserveStatus.CONFLICT -> repositoryAttests(
            record.scope.commandDigest != mutation.commandDigest && record.operationId != proposedOperationId &&
                record.createdAt <= requestedAt && record.updatedAt <= requestedAt,
        )
    }
    return result
}

/** Fail-closed CAS completion; a repository cannot acknowledge another transition. */
internal fun AgentWebMutationJournal.transitionBound(
    scope: AgentWebAuthorizedPersistenceScope,
    transition: AgentWebMutationTransition,
): AgentWebMutationRecord {
    repositoryAttests(scope.action == transition.scope.action &&
        scope.target.resourceId == transition.scope.aggregateId)
    val actual = compareAndSet(scope, transition)
    repositoryAttests(actual.scope == transition.scope && actual.scope.scopeDigest == transition.scope.scopeDigest &&
        actual.scope.commandDigest == transition.scope.commandDigest &&
        actual.scope.action == transition.scope.action &&
        actual.scope.aggregateId == transition.scope.aggregateId &&
        actual.operationId == transition.operationId && actual.status == transition.nextStatus &&
        actual.resultResourceId == transition.resultResourceId && actual.resultVersion == transition.resultVersion &&
        actual.diagnosticCode == transition.diagnosticCode && actual.updatedAt == transition.transitionedAt
    )
    return actual
}

private fun repositoryAttests(condition: Boolean) {
    if (!condition) throw AgentWebUnavailableException()
}

class AgentWebOutboxEvent(
    eventId: Identifier,
    tenantId: Identifier,
    operationId: Identifier,
    aggregateId: Identifier,
    eventType: String,
    payloadReferenceDigest: String,
    val occurredAt: Long,
) {
    val eventId: Identifier = eventId
    val tenantId: Identifier = tenantId
    val operationId: Identifier = operationId
    val aggregateId: Identifier = aggregateId
    val eventType: String = webRuntimeCode(eventType, "Agent Web outbox event type")
    val payloadReferenceDigest: String = webRuntimeDigest(
        payloadReferenceDigest,
        "Agent Web outbox payload reference",
    )

    init {
        require(occurredAt >= 0L) { "Agent Web outbox event time is invalid." }
    }

    override fun toString(): String = "AgentWebOutboxEvent(type=$eventType, <redacted>)"
}

fun interface AgentWebOutboxPort {
    /** Must enlist in the caller's transaction; callbacks and provider I/O are forbidden. */
    fun append(scope: AgentWebAuthorizedPersistenceScope, event: AgentWebOutboxEvent)
}

enum class AgentWebExternalOperationStatus {
    PREPARED,
    SUCCEEDED,
    REJECTED,
    OUTCOME_UNKNOWN,
}

class AgentWebRunStartIntent(
    operationId: Identifier,
    val mutation: AgentWebMutationScope,
    conversationId: Identifier,
    val expectedConversationVersion: Long,
    val request: AgentRunRequest,
    val status: AgentWebExternalOperationStatus,
    diagnosticCode: String?,
    val createdAt: Long,
    val updatedAt: Long,
) {
    val operationId: Identifier = operationId
    val conversationId: Identifier = conversationId
    val diagnosticCode: String? = diagnosticCode?.let { webRuntimeCode(it, "Agent Web start diagnostic") }
    val bindingDigest: String

    init {
        require(expectedConversationVersion >= 0L && createdAt >= 0L && updatedAt >= createdAt) {
            "Agent Web run-start intent is invalid."
        }
        require((status == AgentWebExternalOperationStatus.REJECTED ||
            status == AgentWebExternalOperationStatus.OUTCOME_UNKNOWN) == (this.diagnosticCode != null)
        ) { "Agent Web run-start diagnostic does not match its status." }
        require(mutation.operationAggregateMatches(conversationId, AgentWebAuthorizationAction.RUN_CREATE))
        bindingDigest = AgentWebRuntimeDigest("flowweft.agent.web.runtime.run-start-intent.v1")
            .add(operationId.value)
            .add(mutation.scopeDigest)
            .add(mutation.commandDigest)
            .add(conversationId.value)
            .add(expectedConversationVersion)
            .add(request.bindingDigest)
            .add(status.name)
            .add(this.diagnosticCode ?: "-")
            .add(createdAt)
            .add(updatedAt)
            .finish()
    }

    fun completed(atTime: Long): AgentWebRunStartIntent = AgentWebRunStartIntent(
        operationId, mutation, conversationId, expectedConversationVersion, request,
        AgentWebExternalOperationStatus.SUCCEEDED, null, createdAt, atTime,
    )

    fun failed(code: String, unknown: Boolean, atTime: Long): AgentWebRunStartIntent = AgentWebRunStartIntent(
        operationId, mutation, conversationId, expectedConversationVersion, request,
        if (unknown) AgentWebExternalOperationStatus.OUTCOME_UNKNOWN else AgentWebExternalOperationStatus.REJECTED,
        code, createdAt, atTime,
    )
}

class AgentWebRunCancelIntent(
    operationId: Identifier,
    val mutation: AgentWebMutationScope,
    runId: Identifier,
    val expectedRunVersion: Long,
    val context: AgentRunCommandContext,
    val cancellation: AgentCancellation,
    val status: AgentWebExternalOperationStatus,
    diagnosticCode: String?,
    val createdAt: Long,
    val updatedAt: Long,
) {
    val operationId: Identifier = operationId
    val runId: Identifier = runId
    val diagnosticCode: String? = diagnosticCode?.let { webRuntimeCode(it, "Agent Web cancellation diagnostic") }
    val bindingDigest: String

    init {
        require(expectedRunVersion >= 0L && createdAt >= 0L && updatedAt >= createdAt) {
            "Agent Web cancellation intent is invalid."
        }
        require((status == AgentWebExternalOperationStatus.REJECTED ||
            status == AgentWebExternalOperationStatus.OUTCOME_UNKNOWN) == (this.diagnosticCode != null)
        ) { "Agent Web cancellation diagnostic does not match its status." }
        require(mutation.operationAggregateMatches(runId, AgentWebAuthorizationAction.RUN_CANCEL))
        bindingDigest = AgentWebRuntimeDigest("flowweft.agent.web.runtime.run-cancel-intent.v1")
            .add(operationId.value)
            .add(mutation.scopeDigest)
            .add(mutation.commandDigest)
            .add(runId.value)
            .add(expectedRunVersion)
            .add(context.tenantId.value)
            .add(context.principalType)
            .add(context.principalId.value)
            .add(context.requestId.value)
            .add(context.authenticatedAt)
            .add(cancellation.reasonCode)
            .add(cancellation.requestedAt)
            .add(status.name)
            .add(this.diagnosticCode ?: "-")
            .add(createdAt)
            .add(updatedAt)
            .finish()
    }

    fun completed(atTime: Long): AgentWebRunCancelIntent = AgentWebRunCancelIntent(
        operationId, mutation, runId, expectedRunVersion, context, cancellation,
        AgentWebExternalOperationStatus.SUCCEEDED, null, createdAt, atTime,
    )

    fun failed(code: String, unknown: Boolean, atTime: Long): AgentWebRunCancelIntent = AgentWebRunCancelIntent(
        operationId, mutation, runId, expectedRunVersion, context, cancellation,
        if (unknown) AgentWebExternalOperationStatus.OUTCOME_UNKNOWN else AgentWebExternalOperationStatus.REJECTED,
        code, createdAt, atTime,
    )
}

class AgentWebEvaluationTriggerIntent(
    operationId: Identifier,
    val mutation: AgentWebMutationScope,
    val request: AgentEvaluationRunRequest,
    val evaluator: AgentEvaluationEvaluatorReference,
    val status: AgentWebExternalOperationStatus,
    diagnosticCode: String?,
    val createdAt: Long,
    val updatedAt: Long,
) {
    val operationId: Identifier = operationId
    val diagnosticCode: String? = diagnosticCode?.let { webRuntimeCode(it, "Agent Web evaluation diagnostic") }
    val bindingDigest: String

    init {
        require(createdAt >= 0L && updatedAt >= createdAt) { "Agent Web evaluation intent is invalid." }
        require((status == AgentWebExternalOperationStatus.REJECTED ||
            status == AgentWebExternalOperationStatus.OUTCOME_UNKNOWN) == (this.diagnosticCode != null)
        ) { "Agent Web evaluation intent diagnostic does not match its status." }
        require(mutation.operationAggregateMatches(request.tenantId, AgentWebAuthorizationAction.EVALUATION_TRIGGER))
        bindingDigest = AgentWebRuntimeDigest("flowweft.agent.web.runtime.evaluation-intent.v1")
            .add(operationId.value)
            .add(mutation.scopeDigest)
            .add(mutation.commandDigest)
            .add(request.requestBindingDigest)
            .add(evaluator.bindingDigest)
            .add(status.name)
            .add(this.diagnosticCode ?: "-")
            .add(createdAt)
            .add(updatedAt)
            .finish()
    }

    fun completed(atTime: Long): AgentWebEvaluationTriggerIntent = AgentWebEvaluationTriggerIntent(
        operationId, mutation, request, evaluator, AgentWebExternalOperationStatus.SUCCEEDED, null, createdAt, atTime,
    )

    fun failed(code: String, unknown: Boolean, atTime: Long): AgentWebEvaluationTriggerIntent =
        AgentWebEvaluationTriggerIntent(
            operationId, mutation, request, evaluator,
            if (unknown) AgentWebExternalOperationStatus.OUTCOME_UNKNOWN else AgentWebExternalOperationStatus.REJECTED,
            code, createdAt, atTime,
        )
}

private fun AgentWebMutationScope.operationAggregateMatches(
    expectedAggregateId: Identifier,
    expectedAction: AgentWebAuthorizationAction,
): Boolean = aggregateId == expectedAggregateId && action == expectedAction

/**
 * Durable canonical commands referenced by the outbox. Implementations use CAS by operation id;
 * they never execute callbacks while enlisted in a transaction.
 */
interface AgentWebExternalOperationRepository {
    fun createStart(scope: AgentWebAuthorizedPersistenceScope, intent: AgentWebRunStartIntent): Boolean
    fun updateStart(
        scope: AgentWebAuthorizedPersistenceScope,
        expectedStatus: AgentWebExternalOperationStatus,
        intent: AgentWebRunStartIntent,
    ): Boolean
    fun loadStart(scope: AgentWebAuthorizedPersistenceScope, operationId: Identifier): AgentWebRunStartIntent?

    fun createCancellation(scope: AgentWebAuthorizedPersistenceScope, intent: AgentWebRunCancelIntent): Boolean
    fun updateCancellation(
        scope: AgentWebAuthorizedPersistenceScope,
        expectedStatus: AgentWebExternalOperationStatus,
        intent: AgentWebRunCancelIntent,
    ): Boolean
    fun loadCancellation(scope: AgentWebAuthorizedPersistenceScope, operationId: Identifier): AgentWebRunCancelIntent?

    fun createEvaluation(scope: AgentWebAuthorizedPersistenceScope, intent: AgentWebEvaluationTriggerIntent): Boolean
    fun updateEvaluation(
        scope: AgentWebAuthorizedPersistenceScope,
        expectedStatus: AgentWebExternalOperationStatus,
        intent: AgentWebEvaluationTriggerIntent,
    ): Boolean
    fun loadEvaluation(scope: AgentWebAuthorizedPersistenceScope, operationId: Identifier): AgentWebEvaluationTriggerIntent?
}

internal fun AgentWebExternalOperationRepository.createStartBound(
    scope: AgentWebAuthorizedPersistenceScope,
    intent: AgentWebRunStartIntent,
) {
    repositoryAttests(intent.status == AgentWebExternalOperationStatus.PREPARED &&
        createStart(scope, intent))
    repositoryAttests(loadStart(scope, intent.operationId)?.bindingDigest == intent.bindingDigest)
}

internal fun AgentWebExternalOperationRepository.updateStartBound(
    scope: AgentWebAuthorizedPersistenceScope,
    expectedStatus: AgentWebExternalOperationStatus,
    intent: AgentWebRunStartIntent,
) {
    repositoryAttests(validExternalTransition(expectedStatus, intent.status) &&
        updateStart(scope, expectedStatus, intent))
    repositoryAttests(loadStart(scope, intent.operationId)?.bindingDigest == intent.bindingDigest)
}

internal fun AgentWebExternalOperationRepository.createCancellationBound(
    scope: AgentWebAuthorizedPersistenceScope,
    intent: AgentWebRunCancelIntent,
) {
    repositoryAttests(intent.status == AgentWebExternalOperationStatus.PREPARED &&
        createCancellation(scope, intent))
    repositoryAttests(loadCancellation(scope, intent.operationId)?.bindingDigest == intent.bindingDigest)
}

internal fun AgentWebExternalOperationRepository.updateCancellationBound(
    scope: AgentWebAuthorizedPersistenceScope,
    expectedStatus: AgentWebExternalOperationStatus,
    intent: AgentWebRunCancelIntent,
) {
    repositoryAttests(validExternalTransition(expectedStatus, intent.status) &&
        updateCancellation(scope, expectedStatus, intent))
    repositoryAttests(loadCancellation(scope, intent.operationId)?.bindingDigest == intent.bindingDigest)
}

internal fun AgentWebExternalOperationRepository.createEvaluationBound(
    scope: AgentWebAuthorizedPersistenceScope,
    intent: AgentWebEvaluationTriggerIntent,
) {
    repositoryAttests(intent.status == AgentWebExternalOperationStatus.PREPARED &&
        createEvaluation(scope, intent))
    repositoryAttests(loadEvaluation(scope, intent.operationId)?.bindingDigest == intent.bindingDigest)
}

internal fun AgentWebExternalOperationRepository.updateEvaluationBound(
    scope: AgentWebAuthorizedPersistenceScope,
    expectedStatus: AgentWebExternalOperationStatus,
    intent: AgentWebEvaluationTriggerIntent,
) {
    repositoryAttests(validExternalTransition(expectedStatus, intent.status) &&
        updateEvaluation(scope, expectedStatus, intent))
    repositoryAttests(loadEvaluation(scope, intent.operationId)?.bindingDigest == intent.bindingDigest)
}

private fun validExternalTransition(
    expectedStatus: AgentWebExternalOperationStatus,
    nextStatus: AgentWebExternalOperationStatus,
): Boolean = when (expectedStatus) {
    AgentWebExternalOperationStatus.PREPARED -> nextStatus != AgentWebExternalOperationStatus.PREPARED
    AgentWebExternalOperationStatus.OUTCOME_UNKNOWN ->
        nextStatus == AgentWebExternalOperationStatus.SUCCEEDED ||
            nextStatus == AgentWebExternalOperationStatus.REJECTED
    AgentWebExternalOperationStatus.SUCCEEDED,
    AgentWebExternalOperationStatus.REJECTED -> false
}

class AgentWebConversationRecord(
    val tenantId: Identifier,
    val createdBy: Identifier,
    val createdByType: String,
    conversationId: Identifier,
    title: String,
    val defaultCapabilityId: AgentCapabilityId,
    val defaultBudget: AgentBudget,
    val latestRunStatus: AgentRunStatus?,
    val stateVersion: Long,
    val createdAt: Long,
    val updatedAt: Long,
) {
    val conversationId: Identifier = conversationId
    val title: String = webRuntimeToken(title, "Agent Web conversation title")
    val bindingDigest: String

    init {
        webRuntimeCode(createdByType, "Agent Web conversation creator type")
        require(stateVersion >= 0L && createdAt >= 0L && updatedAt >= createdAt) {
            "Agent Web conversation record is invalid."
        }
        bindingDigest = AgentWebRuntimeDigest("flowweft.agent.web.runtime.conversation-record.v1")
            .add(tenantId.value)
            .add(createdBy.value)
            .add(createdByType)
            .add(conversationId.value)
            .add(this.title)
            .add(defaultCapabilityId.value)
            .addBudget(defaultBudget)
            .add(latestRunStatus?.name ?: "-")
            .add(stateVersion)
            .add(createdAt)
            .add(updatedAt)
            .finish()
    }

    fun projection(): AgentWebConversationDto = AgentWebConversationDto(
        AgentWebConversationSummaryDto(
            conversationId,
            title,
            latestRunStatus,
            stateVersion,
            createdAt,
            updatedAt,
        ),
        defaultCapabilityId,
        defaultBudget,
    )

    fun withRun(status: AgentRunStatus, atTime: Long): AgentWebConversationRecord {
        require(stateVersion < Long.MAX_VALUE && atTime >= updatedAt) { "Agent Web conversation CAS is invalid." }
        return AgentWebConversationRecord(
            tenantId, createdBy, createdByType, conversationId, title, defaultCapabilityId, defaultBudget,
            status, stateVersion + 1L, createdAt, atTime,
        )
    }
}

enum class AgentWebRepositoryWriteStatus {
    APPLIED,
    REPLAYED,
    VERSION_CONFLICT,
    MISSING,
}

class AgentWebConversationWriteResult(
    val status: AgentWebRepositoryWriteStatus,
    val record: AgentWebConversationRecord?,
)

interface AgentWebConversationRepository {
    fun create(
        scope: AgentWebAuthorizedPersistenceScope,
        record: AgentWebConversationRecord,
    ): AgentWebConversationWriteResult

    fun find(
        scope: AgentWebAuthorizedPersistenceScope,
        conversationId: Identifier,
    ): AgentWebConversationRecord?

    /** Cursor and membership must be calculated over only this authorized scope. */
    fun list(
        scope: AgentWebAuthorizedPersistenceScope,
        query: AgentWebPageQuery,
    ): AgentWebStoredPage<AgentWebConversationRecord>

    fun compareAndSet(
        scope: AgentWebAuthorizedPersistenceScope,
        expectedStateVersion: Long,
        next: AgentWebConversationRecord,
    ): AgentWebConversationWriteResult
}

class AgentWebRunRecord(
    val tenantId: Identifier,
    conversationId: Identifier,
    val snapshot: AgentRunSnapshot,
    val deadlineAt: Long,
) {
    val conversationId: Identifier = conversationId
    val bindingDigest: String

    init {
        require(snapshot.tenantId == tenantId && deadlineAt > snapshot.createdAt) {
            "Agent Web run record does not match its trusted tenant or lifetime."
        }
        val digest = AgentWebRuntimeDigest("flowweft.agent.web.runtime.run-record.v1")
            .add(tenantId.value)
            .add(conversationId.value)
            .add(snapshot.runId.value)
            .add(snapshot.capabilityId.value)
            .add(snapshot.status.name)
            .addBudget(snapshot.budget)
            .addUsage(snapshot.usage)
            .add(snapshot.stateVersion)
            .add(snapshot.createdAt)
            .add(snapshot.updatedAt)
            .add(snapshot.currentStepId?.value ?: "-")
            .add(snapshot.failure?.category?.value ?: "-")
            .add(snapshot.failure?.code ?: "-")
            .add(snapshot.failure?.safeMessage ?: "-")
            .add(deadlineAt)
            .add(snapshot.messages.size)
        snapshot.messages.forEach { message -> digest.add(message.bindingDigest) }
        bindingDigest = digest.finish()
    }

    fun projection(): AgentWebRunDto = AgentWebRunDto(
        snapshot.runId,
        conversationId,
        snapshot.capabilityId,
        snapshot.status,
        snapshot.budget,
        snapshot.usage,
        snapshot.stateVersion,
        snapshot.createdAt,
        snapshot.updatedAt,
        deadlineAt,
        failure = snapshot.failure,
    )
}

class AgentWebCitationRecord(
    runId: Identifier,
    messageId: Identifier,
    val citation: AgentCitation,
    securityFilterReceiptDigest: String,
    val filteredAt: Long,
) {
    val runId: Identifier = runId
    val messageId: Identifier = messageId
    val securityFilterReceiptDigest: String = webRuntimeDigest(
        securityFilterReceiptDigest,
        "Agent Web citation security filter receipt",
    )

    init {
        require(filteredAt >= 0L) { "Agent Web citation filter time is invalid." }
    }
}

class AgentWebVisibleMessageRecord(
    runId: Identifier,
    val sequence: Long,
    val message: AgentMessage,
    citations: Collection<AgentWebCitationRecord>,
) {
    val runId: Identifier = runId
    val citations: List<AgentWebCitationRecord> = immutableRuntimeList(
        citations,
        1_000,
        "Agent Web visible message citations",
    )

    init {
        message.requireBindingIntact()
        require(sequence > 0L && (message.role == AgentMessageRole.USER || message.role == AgentMessageRole.ASSISTANT)) {
            "Agent Web visible message record is invalid."
        }
        require(this.citations.all { it.runId == this.runId && it.messageId == message.id }) {
            "Agent Web visible message citations do not match their message."
        }
    }
}

class AgentWebRunEventRecord(
    val event: AgentRunEvent,
    val stateVersion: Long,
) {
    init {
        require(stateVersion >= 0L) { "Agent Web event state version is invalid." }
    }
}

class AgentWebRunWriteResult(
    val status: AgentWebRepositoryWriteStatus,
    val record: AgentWebRunRecord?,
)

interface AgentWebRunProjectionRepository {
    fun create(
        scope: AgentWebAuthorizedPersistenceScope,
        record: AgentWebRunRecord,
        initialMessage: AgentWebVisibleMessageRecord,
        initialEvents: Collection<AgentWebRunEventRecord>,
    ): AgentWebRunWriteResult

    fun find(scope: AgentWebAuthorizedPersistenceScope, runId: Identifier): AgentWebRunRecord?

    fun listByConversation(
        scope: AgentWebAuthorizedPersistenceScope,
        conversationId: Identifier,
        query: AgentWebPageQuery,
    ): AgentWebStoredPage<AgentWebRunRecord>

    fun compareAndSet(
        scope: AgentWebAuthorizedPersistenceScope,
        expectedStateVersion: Long,
        next: AgentWebRunRecord,
        events: Collection<AgentWebRunEventRecord>,
    ): AgentWebRunWriteResult

    fun messages(
        scope: AgentWebAuthorizedPersistenceScope,
        runId: Identifier,
        query: AgentWebPageQuery,
    ): AgentWebStoredDurablePage<AgentWebVisibleMessageRecord>

    fun events(
        scope: AgentWebAuthorizedPersistenceScope,
        runId: Identifier,
        query: AgentWebPageQuery,
    ): AgentWebStoredDurablePage<AgentWebRunEventRecord>

    fun citations(
        scope: AgentWebAuthorizedPersistenceScope,
        runId: Identifier,
        query: AgentWebPageQuery,
    ): AgentWebStoredPage<AgentWebCitationRecord>
}

class AgentWebConfirmationRecord(
    val request: AgentApprovalRequest,
    val risk: AgentToolRisk,
    toolDisplayName: String,
    val stateVersion: Long,
    val runStateVersion: Long,
    val decision: AgentApprovalDecision?,
    val decisionMutationScope: AgentWebMutationScope?,
) {
    val toolDisplayName: String = webRuntimeToken(toolDisplayName, "Agent Web confirmation tool name")
    val bindingDigest: String

    init {
        require(stateVersion >= 0L && runStateVersion >= 0L) { "Agent Web confirmation versions are invalid." }
        require((decision == null) == (decisionMutationScope == null)) {
            "Agent Web confirmation consumption evidence is incomplete."
        }
        require(decision == null || decision.requestId == request.requestId) {
            "Agent Web confirmation decision does not match its request."
        }
        bindingDigest = AgentWebRuntimeDigest("flowweft.agent.web.runtime.confirmation-record.v1")
            .add(request.evidenceDigest)
            .add(risk.name)
            .add(this.toolDisplayName)
            .add(stateVersion)
            .add(runStateVersion)
            .add(decision?.decisionId?.value ?: "-")
            .add(decision?.outcome?.name ?: "-")
            .add(decision?.decidedAt ?: -1L)
            .add(decision?.reasonCode ?: "-")
            .add(decisionMutationScope?.scopeDigest ?: "-")
            .add(decisionMutationScope?.commandDigest ?: "-")
            .finish()
    }

    fun summary(): AgentWebToolConfirmationSummaryDto =
        AgentWebToolConfirmationSummaryDto(request, risk, stateVersion)

    fun detail(): AgentWebToolConfirmationDetailDto =
        AgentWebToolConfirmationDetailDto(request, risk, toolDisplayName, stateVersion)

    fun decisionProjection(): AgentWebToolConfirmationDecisionDto? =
        decision?.let { AgentWebToolConfirmationDecisionDto(it, stateVersion) }
}

enum class AgentWebConfirmationConsumeStatus {
    APPLIED,
    REPLAYED,
    ALREADY_DECIDED,
    VERSION_CONFLICT,
    MISSING,
}

class AgentWebConfirmationConsumeResult(
    val status: AgentWebConfirmationConsumeStatus,
    val record: AgentWebConfirmationRecord?,
)

interface AgentWebConfirmationRepository {
    fun listPending(
        scope: AgentWebAuthorizedPersistenceScope,
        query: AgentWebPageQuery,
    ): AgentWebStoredPage<AgentWebConfirmationRecord>

    fun load(scope: AgentWebAuthorizedPersistenceScope, requestId: Identifier): AgentWebConfirmationRecord?

    /** CAS plus one-time decision consumption; the canonical request is rechecked by the caller in this transaction. */
    fun consume(
        scope: AgentWebAuthorizedPersistenceScope,
        requestId: Identifier,
        expectedStateVersion: Long,
        mutation: AgentWebMutationScope,
        decision: AgentApprovalDecision,
        decidedAt: Long,
    ): AgentWebConfirmationConsumeResult
}

class AgentWebProviderConfigurationRecord(
    val tenantId: Identifier,
    val projection: AgentWebProviderConfigurationDto,
) {
    val bindingDigest: String

    init {
        val digest = AgentWebRuntimeDigest("flowweft.agent.web.runtime.provider-configuration-record.v1")
            .add(tenantId.value)
            .add(projection.profileId.value)
            .add(projection.providerId.value)
            .add(projection.connectionProfileReference.value)
            .add(projection.credentialReference?.value ?: "-")
            .add(projection.modelId?.value ?: "-")
            .add(projection.enabled)
            .add(projection.configurationRevision)
            .add(projection.stateVersion)
            .add(projection.createdAt)
            .add(projection.updatedAt)
            .add(projection.capabilities.size)
        projection.capabilities.sortedBy { capability -> capability.value }.forEach { capability ->
            digest.add(capability.value)
        }
        bindingDigest = digest.finish()
    }
}

class AgentWebProviderConfigurationWriteResult(
    val status: AgentWebRepositoryWriteStatus,
    val record: AgentWebProviderConfigurationRecord?,
)

interface AgentWebProviderConfigurationRepository {
    fun list(
        scope: AgentWebAuthorizedPersistenceScope,
        query: AgentWebPageQuery,
    ): AgentWebStoredPage<AgentWebProviderConfigurationRecord>

    fun find(
        scope: AgentWebAuthorizedPersistenceScope,
        profileId: Identifier,
    ): AgentWebProviderConfigurationRecord?

    fun put(
        scope: AgentWebAuthorizedPersistenceScope,
        expectedStateVersion: Long,
        record: AgentWebProviderConfigurationRecord,
    ): AgentWebProviderConfigurationWriteResult
}

class AgentWebEvaluationRecord(
    val state: AgentEvaluationRunState,
    val evaluator: AgentEvaluationEvaluatorReference,
    val result: AgentEvaluationRegressionReport? = null,
) {
    val bindingDigest: String

    init {
        val digest = AgentWebRuntimeDigest("flowweft.agent.web.runtime.evaluation-record.v1")
            .add(state.evaluationId.value)
            .add(state.requestId.value)
            .add(state.idempotencyScope.scopeDigest)
            .add(state.requestBindingDigest)
            .add(state.suite.suiteDigest)
            .add(state.providerSnapshot.snapshotDigest)
            .add(state.status.name)
            .add(state.stateVersion)
            .add(state.attempt)
            .add(state.lease?.leaseId?.value ?: "-")
            .add(state.lease?.ownerId?.value ?: "-")
            .add(state.lease?.fencingToken ?: -1L)
            .add(state.lease?.acquiredAt ?: -1L)
            .add(state.lease?.expiresAt ?: -1L)
            .add(state.diagnostic?.status?.name ?: "-")
            .add(state.diagnostic?.reason?.value ?: "-")
            .add(state.diagnostic?.providerId?.value ?: "-")
            .add(state.diagnostic?.capabilityId?.value ?: "-")
            .add(state.diagnostic?.snapshotDigest ?: "-")
            .add(state.diagnostic?.observedAt ?: -1L)
            .add(state.cancellationReason ?: "-")
            .add(state.createdAt)
            .add(state.updatedAt)
            .add(state.deadlineAt)
            .add(state.maximumAttempts)
            .add(evaluator.bindingDigest)
            .add(result?.reportDigest ?: "-")
            .add(state.evidence.size)
        state.evidence.sortedBy { evidence -> evidence.caseId.value }.forEach { evidence ->
            digest.add(evidence.evidenceDigest)
        }
        bindingDigest = digest.finish()
    }

    fun projection(): AgentWebEvaluationRunDto = AgentWebEvaluationRunDto(
        state.evaluationId,
        ai.icen.fw.agent.evaluation.AgentEvaluationDatasetReference.from(state.suite),
        state.providerSnapshot,
        evaluator,
        when (state.status) {
            AgentEvaluationRunStatus.QUEUED -> AgentWebEvaluationStatusCode.QUEUED
            AgentEvaluationRunStatus.RUNNING -> AgentWebEvaluationStatusCode.RUNNING
            AgentEvaluationRunStatus.COMPLETED -> AgentWebEvaluationStatusCode.COMPLETED
            AgentEvaluationRunStatus.FAILED -> AgentWebEvaluationStatusCode.FAILED
            AgentEvaluationRunStatus.CANCELLED -> AgentWebEvaluationStatusCode.CANCELLED
            AgentEvaluationRunStatus.EXPIRED -> AgentWebEvaluationStatusCode.EXPIRED
        },
        state.evidence.size,
        state.suite.cases.size,
        state.stateVersion,
        state.createdAt,
        state.updatedAt,
        state.deadlineAt,
        state.diagnostic?.reason?.value,
    )
}

class AgentWebEvaluationWriteResult(
    val status: AgentWebRepositoryWriteStatus,
    val record: AgentWebEvaluationRecord?,
)

interface AgentWebEvaluationRepository {
    fun create(
        scope: AgentWebAuthorizedPersistenceScope,
        record: AgentWebEvaluationRecord,
    ): AgentWebEvaluationWriteResult

    fun find(
        scope: AgentWebAuthorizedPersistenceScope,
        evaluationId: Identifier,
    ): AgentWebEvaluationRecord?

    fun list(
        scope: AgentWebAuthorizedPersistenceScope,
        query: AgentWebPageQuery,
    ): AgentWebStoredPage<AgentWebEvaluationRecord>
}

internal fun AgentWebConversationWriteResult.requireExact(
    expected: AgentWebConversationRecord,
): AgentWebConversationRecord = exactRepositoryRecord(status, record, expected, "conversation") {
    actual, canonical -> actual.bindingDigest == canonical.bindingDigest
}

internal fun AgentWebRunWriteResult.requireExact(expected: AgentWebRunRecord): AgentWebRunRecord =
    exactRepositoryRecord(status, record, expected, "run") { actual, canonical ->
        actual.bindingDigest == canonical.bindingDigest
    }

internal fun AgentWebProviderConfigurationWriteResult.requireExact(
    expected: AgentWebProviderConfigurationRecord,
): AgentWebProviderConfigurationRecord = exactRepositoryRecord(
    status,
    record,
    expected,
    "provider configuration",
) { actual, canonical -> actual.bindingDigest == canonical.bindingDigest }

internal fun AgentWebEvaluationWriteResult.requireExact(expected: AgentWebEvaluationRecord): AgentWebEvaluationRecord =
    exactRepositoryRecord(status, record, expected, "evaluation") { actual, canonical ->
        actual.bindingDigest == canonical.bindingDigest
    }

private fun <T> exactRepositoryRecord(
    status: AgentWebRepositoryWriteStatus,
    actual: T?,
    expected: T,
    @Suppress("UNUSED_PARAMETER") label: String,
    same: (T, T) -> Boolean,
): T {
    if ((status != AgentWebRepositoryWriteStatus.APPLIED &&
            status != AgentWebRepositoryWriteStatus.REPLAYED) || actual == null || !same(actual, expected)
    ) throw AgentWebUnavailableException()
    return actual
}

private fun AgentWebRuntimeDigest.addBudget(budget: AgentBudget): AgentWebRuntimeDigest =
    add(budget.maximumInputTokens)
        .add(budget.maximumOutputTokens)
        .add(budget.maximumModelCalls)
        .add(budget.maximumToolCalls)
        .add(budget.maximumDurationMillis)
        .add(budget.maximumCostMicros)

private fun AgentWebRuntimeDigest.addUsage(usage: ai.icen.fw.agent.api.AgentUsage): AgentWebRuntimeDigest {
    add(usage.inputTokens)
        .add(usage.outputTokens)
        .add(usage.modelCalls)
        .add(usage.toolCalls)
        .add(usage.durationMillis)
        .add(usage.costMicros)
        .add(usage.additionalUnits.size)
    usage.additionalUnits.toSortedMap().forEach { (unit, value) -> add(unit).add(value) }
    return this
}
