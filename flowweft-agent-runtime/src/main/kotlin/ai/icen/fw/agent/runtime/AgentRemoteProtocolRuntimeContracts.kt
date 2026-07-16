package ai.icen.fw.agent.runtime

import ai.icen.fw.agent.api.AgentRemoteAuthorizationProvider
import ai.icen.fw.agent.api.AgentRemoteOperationKind
import ai.icen.fw.agent.api.AgentRemotePeerProfile
import ai.icen.fw.agent.api.AgentRemoteProtocolDispatchRequest
import ai.icen.fw.agent.api.AgentRemoteProtocolDispatchResult
import ai.icen.fw.agent.api.AgentRemoteProtocolInvocationRequest
import ai.icen.fw.agent.api.AgentRemoteProtocolKind
import ai.icen.fw.agent.api.AgentRemoteProtocolOutcomeReconciler
import ai.icen.fw.agent.api.AgentRemoteProtocolProvider
import ai.icen.fw.agent.api.AgentRemoteProtocolReconciliationOutcome
import ai.icen.fw.agent.api.AgentRemoteProtocolReconciliationRequest
import ai.icen.fw.agent.api.AgentRemoteProtocolReconciliationResult
import ai.icen.fw.agent.api.AgentRemoteProtocolResultStatus
import ai.icen.fw.agent.api.AgentUsage
import ai.icen.fw.agent.api.ProviderId
import ai.icen.fw.core.id.Identifier

class AgentRemoteProtocolInvocationKey(
    tenantId: Identifier,
    invocationId: Identifier,
) {
    val tenantId: Identifier = requireRuntimeIdentifier(tenantId, "Agent remote journal tenant is invalid.")
    val invocationId: Identifier = requireRuntimeIdentifier(invocationId, "Agent remote journal invocation is invalid.")

    override fun equals(other: Any?): Boolean = other is AgentRemoteProtocolInvocationKey &&
        tenantId == other.tenantId && invocationId == other.invocationId

    override fun hashCode(): Int = 31 * tenantId.hashCode() + invocationId.hashCode()

    override fun toString(): String = "AgentRemoteProtocolInvocationKey(values=<redacted>)"
}

/**
 * Idempotency identity deliberately excludes arguments so changed arguments conflict instead of
 * creating another call. For a side-effect operation the tenant-scoped execution context replaces
 * the peer-specific scope, so one consumed context cannot be reused through MCP, A2A or another peer.
 */
class AgentRemoteProtocolIdempotencyScope private constructor(
    tenantId: Identifier,
    principalId: Identifier,
    principalType: String,
    val peerId: ProviderId,
    val protocol: AgentRemoteProtocolKind,
    val operation: AgentRemoteOperationKind,
    idempotencyKeyDigest: String,
    executionContextId: Identifier?,
) {
    val tenantId: Identifier = requireRuntimeIdentifier(tenantId, "Agent remote idempotency tenant is invalid.")
    val principalId: Identifier = requireRuntimeIdentifier(principalId, "Agent remote idempotency principal is invalid.")
    val principalType: String = requireRuntimeCode(principalType, "Agent remote idempotency principal type is invalid.")
    val idempotencyKeyDigest: String = requireRuntimeDigest(
        idempotencyKeyDigest,
        "Agent remote idempotency key digest is invalid.",
    )
    val executionContextId: Identifier? = executionContextId?.let {
        requireRuntimeIdentifier(it, "Agent remote one-time execution context is invalid.")
    }
    val scopeDigest: String = if (this.executionContextId == null) {
        AgentRuntimeDigest("flowweft.agent.remote.idempotency-scope.v1")
            .add(this.tenantId.value)
            .add(this.principalType)
            .add(this.principalId.value)
            .add(peerId.value)
            .add(protocol.name)
            .add(operation.name)
            .add(this.idempotencyKeyDigest)
            .finish()
    } else {
        // The journal atomically consumes this tenant-scoped context across every peer and protocol.
        AgentRuntimeDigest("flowweft.agent.remote.execution-context-scope.v1")
            .add(this.tenantId.value)
            .add(this.executionContextId.value)
            .finish()
    }

    override fun equals(other: Any?): Boolean = other is AgentRemoteProtocolIdempotencyScope &&
        scopeDigest == other.scopeDigest

    override fun hashCode(): Int = scopeDigest.hashCode()

    override fun toString(): String = "AgentRemoteProtocolIdempotencyScope(<redacted>)"

    companion object {
        @JvmStatic
        fun from(request: AgentRemoteProtocolInvocationRequest): AgentRemoteProtocolIdempotencyScope =
            AgentRemoteProtocolIdempotencyScope(
                request.operation.context.tenantId,
                request.operation.context.principalId,
                request.operation.context.principalType,
                request.operation.peerId,
                request.operation.protocol,
                request.operation.operation,
                request.idempotencyKeyDigest,
                request.executionContextId,
            )
    }
}

enum class AgentRemoteProtocolExecutionStatus {
    RESERVED,
    DISPATCHING,
    REDIRECT_PENDING,
    OUTCOME_UNKNOWN,
    RECONCILING,
    SUCCEEDED,
    FAILED,
    CANCELLATION_CONFIRMED,
    CANCELLATION_REJECTED,
    CANCELLED_BEFORE_DISPATCH,
    ;

    fun isTerminal(): Boolean = this == SUCCEEDED || this == FAILED ||
        this == CANCELLATION_CONFIRMED || this == CANCELLATION_REJECTED ||
        this == CANCELLED_BEFORE_DISPATCH
}

/** Durable projection; the journal implementation owns serialization and atomic compare-and-set. */
class AgentRemoteProtocolInvocationState private constructor(
    invocationId: Identifier,
    val idempotencyScope: AgentRemoteProtocolIdempotencyScope,
    val invocation: AgentRemoteProtocolInvocationRequest,
    val profile: AgentRemotePeerProfile,
    val status: AgentRemoteProtocolExecutionStatus,
    val stateVersion: Long,
    val hopIndex: Int,
    val usage: AgentUsage,
    val lastDispatch: AgentRemoteProtocolDispatchRequest?,
    val lastDispatchResult: AgentRemoteProtocolDispatchResult?,
    val reconciliationRequest: AgentRemoteProtocolReconciliationRequest?,
    val reconciliationResult: AgentRemoteProtocolReconciliationResult?,
    authorizationRevision: String?,
    failureCode: String?,
    unknownEvidenceDigest: String?,
    val createdAt: Long,
    val updatedAt: Long,
) {
    val invocationId: Identifier = requireRuntimeIdentifier(invocationId, "Agent remote state identifier is invalid.")
    val tenantId: Identifier = idempotencyScope.tenantId
    val principalId: Identifier = idempotencyScope.principalId
    val principalType: String = idempotencyScope.principalType
    val authorizationRevision: String? = authorizationRevision?.let {
        requireRuntimeToken(it, MAX_RUNTIME_CODE_POINTS, "Agent remote authorization revision is invalid.")
    }
    val failureCode: String? = failureCode?.let {
        requireRuntimeCode(it, "Agent remote failure code is invalid.")
    }
    val unknownEvidenceDigest: String? = unknownEvidenceDigest?.let {
        requireRuntimeDigest(it, "Agent remote unknown evidence digest is invalid.")
    }
    val stateDigest: String

    init {
        require(invocation.operation.context.tenantId == tenantId &&
            invocation.operation.context.principalId == principalId &&
            invocation.operation.context.principalType == principalType &&
            invocation.operation.peerId == idempotencyScope.peerId &&
            invocation.operation.protocol == idempotencyScope.protocol &&
            invocation.operation.operation == idempotencyScope.operation &&
            invocation.idempotencyKeyDigest == idempotencyScope.idempotencyKeyDigest &&
            invocation.executionContextId == idempotencyScope.executionContextId &&
            AgentRemoteProtocolIdempotencyScope.from(invocation) == idempotencyScope
        ) { "Agent remote state changed its trusted subject or idempotency identity." }
        require(profile.peerId == invocation.operation.peerId &&
            profile.protocol == invocation.operation.protocol &&
            profile.profileDigest == invocation.approvedProfileDigest
        ) { "Agent remote state profile differs from the admitted invocation." }
        require(stateVersion >= 0L && hopIndex >= 0) { "Agent remote state version or hop is invalid." }
        require(createdAt >= invocation.requestedAt && updatedAt >= createdAt &&
            updatedAt <= invocation.reconciliationDeadlineAt
        ) {
            "Agent remote state timestamps are invalid."
        }
        require(invocation.budget.allows(usage)) { "Agent remote state usage exceeds its budget." }
        require(lastDispatch == null || lastDispatch.invocation.bindingDigest == invocation.bindingDigest) {
            "Agent remote state contains another dispatch."
        }
        require(lastDispatchResult == null || lastDispatch != null &&
            lastDispatchResult.dispatchRequestId == lastDispatch.requestId &&
            lastDispatchResult.dispatchBindingDigest == lastDispatch.bindingDigest
        ) { "Agent remote state contains another dispatch result." }
        when (status) {
            AgentRemoteProtocolExecutionStatus.RESERVED -> require(
                createdAt < invocation.deadlineAt && lastDispatch == null && lastDispatchResult == null &&
                    reconciliationRequest == null,
            ) { "Reserved Agent remote state already contains external evidence." }
            AgentRemoteProtocolExecutionStatus.DISPATCHING -> require(
                lastDispatch != null && lastDispatchResult == null && reconciliationRequest == null,
            ) { "Dispatching Agent remote state is incomplete." }
            AgentRemoteProtocolExecutionStatus.REDIRECT_PENDING -> require(
                lastDispatchResult?.status == AgentRemoteProtocolResultStatus.REDIRECT &&
                    reconciliationRequest == null,
            ) { "Agent remote redirect state is incomplete." }
            AgentRemoteProtocolExecutionStatus.OUTCOME_UNKNOWN -> require(
                lastDispatch != null && this.unknownEvidenceDigest != null && this.failureCode != null &&
                    reconciliationRequest == null,
            ) { "Agent remote outcome-unknown state is incomplete." }
            AgentRemoteProtocolExecutionStatus.RECONCILING -> require(
                lastDispatch != null && this.unknownEvidenceDigest != null && reconciliationRequest != null &&
                    reconciliationResult == null && this.failureCode != null,
            ) { "Agent remote reconciliation state is incomplete." }
            AgentRemoteProtocolExecutionStatus.SUCCEEDED -> require(
                lastDispatchResult?.status == AgentRemoteProtocolResultStatus.SUCCEEDED ||
                    reconciliationResult?.outcome == AgentRemoteProtocolReconciliationOutcome.SUCCEEDED,
            ) { "Successful Agent remote state lacks a known result." }
            AgentRemoteProtocolExecutionStatus.FAILED -> require(
                this.failureCode != null ||
                    lastDispatchResult?.status == AgentRemoteProtocolResultStatus.FAILED ||
                    reconciliationResult?.outcome == AgentRemoteProtocolReconciliationOutcome.FAILED,
            ) { "Failed Agent remote state lacks safe failure evidence." }
            AgentRemoteProtocolExecutionStatus.CANCELLATION_CONFIRMED -> require(
                lastDispatchResult?.status == AgentRemoteProtocolResultStatus.CANCELLATION_CONFIRMED ||
                    reconciliationResult?.outcome == AgentRemoteProtocolReconciliationOutcome.CANCELLATION_CONFIRMED,
            ) { "Confirmed Agent remote cancellation lacks a remote result." }
            AgentRemoteProtocolExecutionStatus.CANCELLATION_REJECTED -> require(
                lastDispatchResult?.status == AgentRemoteProtocolResultStatus.CANCELLATION_REJECTED ||
                    reconciliationResult?.outcome == AgentRemoteProtocolReconciliationOutcome.CANCELLATION_REJECTED,
            ) { "Rejected Agent remote cancellation lacks a remote result." }
            AgentRemoteProtocolExecutionStatus.CANCELLED_BEFORE_DISPATCH -> require(
                (lastDispatch == null || lastDispatchResult?.status == AgentRemoteProtocolResultStatus.REDIRECT) &&
                    this.failureCode != null,
            ) { "Pre-dispatch cancellation cannot contain an unresolved remote dispatch." }
        }
        val digest = AgentRuntimeDigest("flowweft.agent.remote.invocation-state.v1")
            .add(this.invocationId.value)
            .add(idempotencyScope.scopeDigest)
            .add(invocation.bindingDigest)
            .add(profile.profileDigest)
            .add(status.name)
            .add(stateVersion)
            .add(hopIndex)
            .add(usage.inputTokens)
            .add(usage.outputTokens)
            .add(usage.modelCalls)
            .add(usage.toolCalls)
            .add(usage.durationMillis)
            .add(usage.costMicros)
            .add(lastDispatch?.bindingDigest ?: "-")
            .add(lastDispatchResult?.resultDigest ?: "-")
            .add(reconciliationRequest?.bindingDigest ?: "-")
            .add(reconciliationResult?.resultDigest ?: "-")
            .add(this.authorizationRevision ?: "-")
            .add(this.failureCode ?: "-")
            .add(this.unknownEvidenceDigest ?: "-")
            .add(createdAt)
            .add(updatedAt)
            .add(usage.additionalUnits.size)
        usage.additionalUnits.toSortedMap().forEach { (name, value) -> digest.add(name).add(value) }
        stateDigest = digest.finish()
    }

    fun key(): AgentRemoteProtocolInvocationKey = AgentRemoteProtocolInvocationKey(tenantId, invocationId)

    fun failedBeforeDispatch(code: String, atTime: Long): AgentRemoteProtocolInvocationState {
        require(status == AgentRemoteProtocolExecutionStatus.RESERVED ||
            status == AgentRemoteProtocolExecutionStatus.REDIRECT_PENDING
        ) { "An externally dispatched Agent remote operation cannot fail as pre-dispatch." }
        return next(
            status = AgentRemoteProtocolExecutionStatus.FAILED,
            failureCode = requireRuntimeCode(code, "Agent remote failure code is invalid."),
            updatedAt = transitionTime(atTime),
        )
    }

    fun cancelledBeforeDispatch(cancellationCode: String, atTime: Long): AgentRemoteProtocolInvocationState {
        require(status == AgentRemoteProtocolExecutionStatus.RESERVED ||
            status == AgentRemoteProtocolExecutionStatus.REDIRECT_PENDING
        ) { "An externally dispatched Agent remote operation cannot be cancelled as pre-dispatch." }
        return next(
            status = AgentRemoteProtocolExecutionStatus.CANCELLED_BEFORE_DISPATCH,
            failureCode = requireRuntimeCode(cancellationCode, "Agent remote cancellation code is invalid."),
            updatedAt = transitionTime(atTime),
        )
    }

    fun dispatching(
        dispatch: AgentRemoteProtocolDispatchRequest,
        authorizationRevision: String,
        atTime: Long,
    ): AgentRemoteProtocolInvocationState {
        require(status == AgentRemoteProtocolExecutionStatus.RESERVED ||
            status == AgentRemoteProtocolExecutionStatus.REDIRECT_PENDING
        ) { "Agent remote state cannot begin another dispatch." }
        require(dispatch.hopIndex == hopIndex && dispatch.invocation.bindingDigest == invocation.bindingDigest) {
            "Agent remote dispatch changed its hop or invocation."
        }
        val reservedUsage = if (lastDispatch == null) addUsage(usage, AgentUsage(toolCalls = 1)) else usage
        require(invocation.budget.allows(reservedUsage)) { "Agent remote dispatch exhausted its budget." }
        return next(
            status = AgentRemoteProtocolExecutionStatus.DISPATCHING,
            usage = reservedUsage,
            lastDispatch = dispatch,
            lastDispatchResult = null,
            authorizationRevision = authorizationRevision,
            failureCode = null,
            updatedAt = transitionTime(atTime),
        )
    }

    fun redirected(result: AgentRemoteProtocolDispatchResult, atTime: Long): AgentRemoteProtocolInvocationState {
        requireDispatchResult(result, AgentRemoteProtocolResultStatus.REDIRECT)
        val nextUsage = addUsage(usage, result.usage)
        require(invocation.budget.allows(nextUsage)) { "Agent remote redirect exhausted its budget." }
        return next(
            status = AgentRemoteProtocolExecutionStatus.REDIRECT_PENDING,
            hopIndex = Math.addExact(hopIndex, 1),
            usage = nextUsage,
            lastDispatchResult = result,
            updatedAt = transitionTime(atTime),
        )
    }

    fun completed(result: AgentRemoteProtocolDispatchResult, atTime: Long): AgentRemoteProtocolInvocationState {
        require(status == AgentRemoteProtocolExecutionStatus.DISPATCHING) {
            "Agent remote state is not awaiting a dispatch result."
        }
        requireDispatchResult(result, result.status)
        require(result.status != AgentRemoteProtocolResultStatus.REDIRECT &&
            result.status != AgentRemoteProtocolResultStatus.OUTCOME_UNKNOWN
        ) { "Agent remote result requires redirect or reconciliation handling." }
        val nextUsage = addUsage(usage, result.usage)
        require(invocation.budget.allows(nextUsage)) { "Agent remote result exceeded its budget." }
        return next(
            status = executionStatus(result.status),
            usage = nextUsage,
            lastDispatchResult = result,
            failureCode = result.safeFailureCode,
            updatedAt = transitionTime(atTime),
        )
    }

    fun outcomeUnknown(result: AgentRemoteProtocolDispatchResult, atTime: Long): AgentRemoteProtocolInvocationState {
        requireDispatchResult(result, AgentRemoteProtocolResultStatus.OUTCOME_UNKNOWN)
        val nextUsage = addUsage(usage, result.usage)
        require(invocation.budget.allows(nextUsage)) { "Agent remote unknown result exceeded its budget." }
        return next(
            status = AgentRemoteProtocolExecutionStatus.OUTCOME_UNKNOWN,
            usage = nextUsage,
            lastDispatchResult = result,
            failureCode = result.safeFailureCode,
            unknownEvidenceDigest = result.evidenceDigest,
            updatedAt = transitionTime(atTime),
        )
    }

    fun outcomeUnknown(evidenceDigest: String, code: String, atTime: Long): AgentRemoteProtocolInvocationState {
        require(status == AgentRemoteProtocolExecutionStatus.DISPATCHING && lastDispatch != null) {
            "Agent remote state has no dispatched operation to reconcile."
        }
        return next(
            status = AgentRemoteProtocolExecutionStatus.OUTCOME_UNKNOWN,
            failureCode = requireRuntimeCode(code, "Agent remote unknown outcome code is invalid."),
            unknownEvidenceDigest = requireRuntimeDigest(
                evidenceDigest,
                "Agent remote unknown outcome evidence is invalid.",
            ),
            updatedAt = transitionTime(atTime),
        )
    }

    fun reconciling(
        request: AgentRemoteProtocolReconciliationRequest,
        authorizationRevision: String,
        atTime: Long,
    ): AgentRemoteProtocolInvocationState {
        require(status == AgentRemoteProtocolExecutionStatus.OUTCOME_UNKNOWN && lastDispatch != null &&
            request.originalDispatch.bindingDigest == lastDispatch.bindingDigest &&
            request.unknownEvidenceDigest == unknownEvidenceDigest
        ) { "Agent remote reconciliation does not bind the unknown operation." }
        return next(
            status = AgentRemoteProtocolExecutionStatus.RECONCILING,
            reconciliationRequest = request,
            reconciliationResult = null,
            authorizationRevision = authorizationRevision,
            updatedAt = transitionTime(atTime),
        )
    }

    fun reconciled(result: AgentRemoteProtocolReconciliationResult, atTime: Long): AgentRemoteProtocolInvocationState {
        require(status == AgentRemoteProtocolExecutionStatus.RECONCILING && reconciliationRequest != null &&
            result.requestId == reconciliationRequest.requestId &&
            result.requestBindingDigest == reconciliationRequest.bindingDigest
        ) { "Agent remote reconciliation result belongs to another request." }
        val nextUsage = addUsage(usage, result.usage)
        require(invocation.budget.allows(nextUsage)) { "Agent remote reconciliation exceeded its budget." }
        if (result.outcome == AgentRemoteProtocolReconciliationOutcome.STILL_UNKNOWN) {
            return next(
                status = AgentRemoteProtocolExecutionStatus.OUTCOME_UNKNOWN,
                usage = nextUsage,
                reconciliationRequest = null,
                reconciliationResult = result,
                failureCode = result.safeFailureCode,
                updatedAt = transitionTime(atTime),
            )
        }
        return next(
            status = reconciliationStatus(result.outcome),
            usage = nextUsage,
            reconciliationResult = result,
            failureCode = result.safeFailureCode,
            updatedAt = transitionTime(atTime),
        )
    }

    fun reconciliationUnavailable(code: String, atTime: Long): AgentRemoteProtocolInvocationState {
        require(status == AgentRemoteProtocolExecutionStatus.RECONCILING && unknownEvidenceDigest != null) {
            "Agent remote state is not reconciling an unknown operation."
        }
        return next(
            status = AgentRemoteProtocolExecutionStatus.OUTCOME_UNKNOWN,
            reconciliationRequest = null,
            failureCode = requireRuntimeCode(code, "Agent remote reconciliation failure code is invalid."),
            updatedAt = transitionTime(atTime),
        )
    }

    private fun requireDispatchResult(
        result: AgentRemoteProtocolDispatchResult,
        expected: AgentRemoteProtocolResultStatus,
    ) {
        require(status == AgentRemoteProtocolExecutionStatus.DISPATCHING && lastDispatch != null &&
            result.status == expected && result.dispatchRequestId == lastDispatch.requestId &&
            result.dispatchBindingDigest == lastDispatch.bindingDigest
        ) { "Agent remote dispatch result belongs to another operation." }
    }

    private fun next(
        status: AgentRemoteProtocolExecutionStatus,
        stateVersion: Long = Math.addExact(this.stateVersion, 1L),
        hopIndex: Int = this.hopIndex,
        usage: AgentUsage = this.usage,
        lastDispatch: AgentRemoteProtocolDispatchRequest? = this.lastDispatch,
        lastDispatchResult: AgentRemoteProtocolDispatchResult? = this.lastDispatchResult,
        reconciliationRequest: AgentRemoteProtocolReconciliationRequest? = this.reconciliationRequest,
        reconciliationResult: AgentRemoteProtocolReconciliationResult? = this.reconciliationResult,
        authorizationRevision: String? = this.authorizationRevision,
        failureCode: String? = this.failureCode,
        unknownEvidenceDigest: String? = this.unknownEvidenceDigest,
        updatedAt: Long,
    ): AgentRemoteProtocolInvocationState = AgentRemoteProtocolInvocationState(
        invocationId,
        idempotencyScope,
        invocation,
        profile,
        status,
        stateVersion,
        hopIndex,
        usage,
        lastDispatch,
        lastDispatchResult,
        reconciliationRequest,
        reconciliationResult,
        authorizationRevision,
        failureCode,
        unknownEvidenceDigest,
        createdAt,
        updatedAt,
    )

    private fun transitionTime(atTime: Long): Long {
        require(atTime >= updatedAt && atTime <= invocation.reconciliationDeadlineAt) {
            "Agent remote transition time is invalid."
        }
        return atTime
    }

    override fun toString(): String =
        "AgentRemoteProtocolInvocationState(status=$status, protocol=${profile.protocol}, evidence=<redacted>)"

    companion object {
        @JvmStatic
        fun initial(
            invocationId: Identifier,
            request: AgentRemoteProtocolInvocationRequest,
            profile: AgentRemotePeerProfile,
            createdAt: Long,
        ): AgentRemoteProtocolInvocationState = AgentRemoteProtocolInvocationState(
            invocationId,
            AgentRemoteProtocolIdempotencyScope.from(request),
            request,
            profile,
            AgentRemoteProtocolExecutionStatus.RESERVED,
            0L,
            0,
            request.usageBeforeDispatch,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            createdAt,
            createdAt,
        )

        /** Restores durable protocol state and fails closed if any persisted field was changed. */
        @JvmStatic
        fun restore(
            invocationId: Identifier,
            invocation: AgentRemoteProtocolInvocationRequest,
            profile: AgentRemotePeerProfile,
            status: AgentRemoteProtocolExecutionStatus,
            stateVersion: Long,
            hopIndex: Int,
            usage: AgentUsage,
            lastDispatch: AgentRemoteProtocolDispatchRequest?,
            lastDispatchResult: AgentRemoteProtocolDispatchResult?,
            reconciliationRequest: AgentRemoteProtocolReconciliationRequest?,
            reconciliationResult: AgentRemoteProtocolReconciliationResult?,
            authorizationRevision: String?,
            failureCode: String?,
            unknownEvidenceDigest: String?,
            createdAt: Long,
            updatedAt: Long,
            expectedStateDigest: String,
        ): AgentRemoteProtocolInvocationState {
            val restored = AgentRemoteProtocolInvocationState(
                invocationId,
                AgentRemoteProtocolIdempotencyScope.from(invocation),
                invocation,
                profile,
                status,
                stateVersion,
                hopIndex,
                usage,
                lastDispatch,
                lastDispatchResult,
                reconciliationRequest,
                reconciliationResult,
                authorizationRevision,
                failureCode,
                unknownEvidenceDigest,
                createdAt,
                updatedAt,
            )
            require(restored.stateDigest == requireRuntimeDigest(
                expectedStateDigest,
                "Stored Agent remote state digest is invalid.",
            )) { "Stored Agent remote state digest does not match its fields." }
            return restored
        }
    }
}

enum class AgentRemoteProtocolReserveStatus {
    CREATED,
    REPLAY,
    CONFLICT,
}

class AgentRemoteProtocolReserveResult(
    val status: AgentRemoteProtocolReserveStatus,
    val state: AgentRemoteProtocolInvocationState,
)

class AgentRemoteProtocolStateCommit(
    val key: AgentRemoteProtocolInvocationKey,
    val expectedStateVersion: Long,
    val expectedStateDigest: String,
    val nextState: AgentRemoteProtocolInvocationState,
) {
    init {
        require(expectedStateVersion >= 0L) { "Agent remote expected state version is invalid." }
        requireRuntimeDigest(expectedStateDigest, "Agent remote expected state digest is invalid.")
        require(nextState.key() == key && nextState.stateVersion == expectedStateVersion + 1L) {
            "Agent remote state commit changed its key or skipped a version."
        }
    }
}

enum class AgentRemoteProtocolCommitStatus {
    APPLIED,
    CONFLICT,
    NOT_FOUND,
}

class AgentRemoteProtocolCommitResult(
    val status: AgentRemoteProtocolCommitStatus,
    val state: AgentRemoteProtocolInvocationState?,
)

/**
 * Purpose-specific durable journal, not a raw domain repository. [reserve] must atomically enforce
 * uniqueness of [AgentRemoteProtocolIdempotencyScope.scopeDigest] and return REPLAY only when the
 * existing invocation binding is byte-for-byte equivalent; changed bindings return CONFLICT.
 */
interface AgentRemoteProtocolDispatchJournal {
    fun reserve(initialState: AgentRemoteProtocolInvocationState): AgentRemoteProtocolReserveResult

    fun load(key: AgentRemoteProtocolInvocationKey): AgentRemoteProtocolInvocationState?

    fun findByIdempotency(scope: AgentRemoteProtocolIdempotencyScope): AgentRemoteProtocolInvocationState?

    fun compareAndSet(commit: AgentRemoteProtocolStateCommit): AgentRemoteProtocolCommitResult

    fun outcomeUnknown(atTime: Long, limit: Int): List<AgentRemoteProtocolInvocationState>
}

/** Host-owned profile lookup; a peer identifier never bypasses tenant-scoped configuration. */
fun interface AgentRemotePeerProfileRegistry {
    fun find(
        tenantId: Identifier,
        peerId: ProviderId,
        protocol: AgentRemoteProtocolKind,
    ): AgentRemotePeerProfile?
}

fun interface AgentRemoteAuthorizationProviderRegistry {
    fun find(providerId: ProviderId): AgentRemoteAuthorizationProvider?
}

fun interface AgentRemoteProtocolProviderRegistry {
    fun find(peerId: ProviderId, protocol: AgentRemoteProtocolKind): AgentRemoteProtocolProvider?
}

fun interface AgentRemoteProtocolReconcilerRegistry {
    fun find(peerId: ProviderId, protocol: AgentRemoteProtocolKind): AgentRemoteProtocolOutcomeReconciler?
}

class AgentRemoteProtocolRuntimeConfiguration @JvmOverloads constructor(
    val authorizationTtlMillis: Long = 30_000L,
    val credentialLeaseTtlMillis: Long = 30_000L,
) {
    init {
        require(authorizationTtlMillis in 1L..300_000L) {
            "Agent remote authorization TTL is invalid."
        }
        require(credentialLeaseTtlMillis in 1L..300_000L) {
            "Agent remote credential lease TTL is invalid."
        }
    }
}

class AgentRemoteProtocolRuntimeException(
    code: String,
) : RuntimeException("Agent remote protocol operation failed: ${requireRuntimeCode(code, "Agent remote runtime code is invalid.")}") {
    val code: String = requireRuntimeCode(code, "Agent remote runtime code is invalid.")

    override fun toString(): String = "AgentRemoteProtocolRuntimeException(code=$code)"
}

private fun executionStatus(status: AgentRemoteProtocolResultStatus): AgentRemoteProtocolExecutionStatus = when (status) {
    AgentRemoteProtocolResultStatus.SUCCEEDED -> AgentRemoteProtocolExecutionStatus.SUCCEEDED
    AgentRemoteProtocolResultStatus.FAILED -> AgentRemoteProtocolExecutionStatus.FAILED
    AgentRemoteProtocolResultStatus.CANCELLATION_CONFIRMED ->
        AgentRemoteProtocolExecutionStatus.CANCELLATION_CONFIRMED
    AgentRemoteProtocolResultStatus.CANCELLATION_REJECTED ->
        AgentRemoteProtocolExecutionStatus.CANCELLATION_REJECTED
    AgentRemoteProtocolResultStatus.REDIRECT,
    AgentRemoteProtocolResultStatus.OUTCOME_UNKNOWN -> throw IllegalArgumentException(
        "Agent remote result requires nonterminal handling.",
    )
}

private fun reconciliationStatus(
    outcome: AgentRemoteProtocolReconciliationOutcome,
): AgentRemoteProtocolExecutionStatus = when (outcome) {
    AgentRemoteProtocolReconciliationOutcome.SUCCEEDED -> AgentRemoteProtocolExecutionStatus.SUCCEEDED
    AgentRemoteProtocolReconciliationOutcome.FAILED -> AgentRemoteProtocolExecutionStatus.FAILED
    AgentRemoteProtocolReconciliationOutcome.CANCELLATION_CONFIRMED ->
        AgentRemoteProtocolExecutionStatus.CANCELLATION_CONFIRMED
    AgentRemoteProtocolReconciliationOutcome.CANCELLATION_REJECTED ->
        AgentRemoteProtocolExecutionStatus.CANCELLATION_REJECTED
    AgentRemoteProtocolReconciliationOutcome.STILL_UNKNOWN -> throw IllegalArgumentException(
        "Agent remote reconciliation remains unknown.",
    )
}

private fun addUsage(first: AgentUsage, second: AgentUsage): AgentUsage = try {
    first.plus(second)
} catch (_: ArithmeticException) {
    throw IllegalArgumentException("Agent remote usage overflowed.")
}
