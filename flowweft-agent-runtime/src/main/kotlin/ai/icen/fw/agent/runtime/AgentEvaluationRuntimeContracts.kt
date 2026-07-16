package ai.icen.fw.agent.runtime

import ai.icen.fw.agent.api.AgentCancellationToken
import ai.icen.fw.agent.api.AgentEvaluationCase
import ai.icen.fw.agent.api.AgentEvaluationDiagnostic
import ai.icen.fw.agent.api.AgentEvaluationDiagnosticReason
import ai.icen.fw.agent.api.AgentEvaluationDiagnosticStatus
import ai.icen.fw.agent.api.AgentEvaluationObservation
import ai.icen.fw.agent.api.AgentEvaluationObservationContext
import ai.icen.fw.agent.api.AgentEvaluationProviderSnapshot
import ai.icen.fw.agent.api.AgentEvaluationSuite
import ai.icen.fw.agent.api.ProviderId
import ai.icen.fw.core.id.Identifier
import java.util.concurrent.CompletionStage

enum class AgentEvaluationRunStatus {
    QUEUED,
    RUNNING,
    COMPLETED,
    FAILED,
    CANCELLED,
    EXPIRED,
    ;

    fun isTerminal(): Boolean = this == COMPLETED || this == FAILED || this == CANCELLED || this == EXPIRED
}

/** Owner-scoped idempotency identity. The caller's raw key is never persisted. */
class AgentEvaluationIdempotencyScope private constructor(
    tenantId: Identifier,
    principalId: Identifier,
    principalType: String,
    authorizationRevision: String,
    suiteId: Identifier,
    suiteDigest: String,
    providerSnapshotDigest: String,
    idempotencyKeyDigest: String,
) {
    val tenantId: Identifier = requireRuntimeIdentifier(tenantId, "Agent evaluation tenant is invalid.")
    val principalId: Identifier = requireRuntimeIdentifier(principalId, "Agent evaluation principal is invalid.")
    val principalType: String = requireRuntimeCode(principalType, "Agent evaluation principal type is invalid.")
    val authorizationRevision: String = requireRuntimeToken(
        authorizationRevision,
        MAX_RUNTIME_CODE_POINTS,
        "Agent evaluation authorization revision is invalid.",
    )
    val suiteId: Identifier = requireRuntimeIdentifier(suiteId, "Agent evaluation suite identifier is invalid.")
    val suiteDigest: String = requireRuntimeDigest(suiteDigest, "Agent evaluation suite digest is invalid.")
    val providerSnapshotDigest: String = requireRuntimeDigest(
        providerSnapshotDigest,
        "Agent evaluation provider snapshot digest is invalid.",
    )
    val idempotencyKeyDigest: String = requireRuntimeDigest(
        idempotencyKeyDigest,
        "Agent evaluation idempotency digest is invalid.",
    )
    val scopeDigest: String = AgentRuntimeDigest("flowweft.agent.evaluation.idempotency-scope.v1")
        .add(this.tenantId.value)
        .add(this.principalType)
        .add(this.principalId.value)
        .add(this.authorizationRevision)
        .add(this.suiteId.value)
        .add(this.suiteDigest)
        .add(this.providerSnapshotDigest)
        .add(this.idempotencyKeyDigest)
        .finish()

    override fun equals(other: Any?): Boolean =
        other is AgentEvaluationIdempotencyScope && scopeDigest == other.scopeDigest

    override fun hashCode(): Int = scopeDigest.hashCode()
    override fun toString(): String = "AgentEvaluationIdempotencyScope(<redacted>)"

    companion object {
        @JvmStatic
        fun restore(
            tenantId: Identifier,
            principalId: Identifier,
            principalType: String,
            authorizationRevision: String,
            suiteId: Identifier,
            suiteDigest: String,
            providerSnapshotDigest: String,
            idempotencyKeyDigest: String,
        ): AgentEvaluationIdempotencyScope = AgentEvaluationIdempotencyScope(
            tenantId,
            principalId,
            principalType,
            authorizationRevision,
            suiteId,
            suiteDigest,
            providerSnapshotDigest,
            idempotencyKeyDigest,
        )
    }
}

/** Trusted request for one durable suite execution. */
class AgentEvaluationRunRequest(
    requestId: Identifier,
    tenantId: Identifier,
    principalId: Identifier,
    principalType: String,
    authorizationRevision: String,
    val suite: AgentEvaluationSuite,
    val providerSnapshot: AgentEvaluationProviderSnapshot,
    idempotencyKey: String,
    val requestedAt: Long,
    val deadlineAt: Long,
    val maximumAttempts: Int,
) {
    val requestId: Identifier = requireRuntimeIdentifier(requestId, "Agent evaluation request identifier is invalid.")
    val tenantId: Identifier = requireRuntimeIdentifier(tenantId, "Agent evaluation request tenant is invalid.")
    val principalId: Identifier = requireRuntimeIdentifier(principalId, "Agent evaluation request principal is invalid.")
    val principalType: String = requireRuntimeCode(principalType, "Agent evaluation request principal type is invalid.")
    val authorizationRevision: String = requireRuntimeToken(
        authorizationRevision,
        MAX_RUNTIME_CODE_POINTS,
        "Agent evaluation request authorization revision is invalid.",
    )
    val idempotencyScope: AgentEvaluationIdempotencyScope
    val requestBindingDigest: String

    init {
        require(requestedAt >= 0L && deadlineAt > requestedAt) {
            "Agent evaluation request lifetime is invalid."
        }
        require(maximumAttempts in 1..MAX_RUNTIME_ATTEMPTS) {
            "Agent evaluation attempt limit is invalid."
        }
        require(providerSnapshot.isCurrent(requestedAt)) {
            "Agent evaluation provider snapshot is not current at request time."
        }
        require(suite.cases.all { case -> providerSnapshot.supports(case.capabilityId) }) {
            "Agent evaluation provider snapshot does not support every case capability."
        }
        val keyDigest = AgentRuntimeDigest("flowweft.agent.evaluation.idempotency-key.v1")
            .add(requireRuntimeToken(idempotencyKey, MAX_RUNTIME_CODE_POINTS, "Agent evaluation idempotency key is invalid."))
            .finish()
        idempotencyScope = AgentEvaluationIdempotencyScope.restore(
            this.tenantId,
            this.principalId,
            this.principalType,
            this.authorizationRevision,
            suite.suiteId,
            suite.suiteDigest,
            providerSnapshot.snapshotDigest,
            keyDigest,
        )
        requestBindingDigest = AgentRuntimeDigest("flowweft.agent.evaluation.request.v1")
            .add(idempotencyScope.scopeDigest)
            .add(providerSnapshot.providerId.value)
            .add(requestedAt)
            .add(deadlineAt)
            .add(maximumAttempts)
            .finish()
    }

    override fun toString(): String = "AgentEvaluationRunRequest(<redacted>)"
}

class AgentEvaluationRunKey(
    tenantId: Identifier,
    evaluationId: Identifier,
) {
    val tenantId: Identifier = requireRuntimeIdentifier(tenantId, "Agent evaluation key tenant is invalid.")
    val evaluationId: Identifier = requireRuntimeIdentifier(evaluationId, "Agent evaluation identifier is invalid.")

    override fun equals(other: Any?): Boolean = other is AgentEvaluationRunKey &&
        tenantId == other.tenantId && evaluationId == other.evaluationId

    override fun hashCode(): Int = 31 * tenantId.hashCode() + evaluationId.hashCode()
    override fun toString(): String = "AgentEvaluationRunKey(<redacted>)"
}

class AgentEvaluationLease(
    leaseId: Identifier,
    val ownerId: ProviderId,
    val fencingToken: Long,
    val acquiredAt: Long,
    val expiresAt: Long,
) {
    val leaseId: Identifier = requireRuntimeIdentifier(leaseId, "Agent evaluation lease identifier is invalid.")

    init {
        require(fencingToken > 0L) { "Agent evaluation fencing token must be positive." }
        require(acquiredAt >= 0L && expiresAt > acquiredAt) { "Agent evaluation lease lifetime is invalid." }
    }

    fun isCurrent(atTime: Long): Boolean = atTime in acquiredAt until expiresAt

    fun matches(other: AgentEvaluationLease): Boolean = leaseId == other.leaseId && ownerId == other.ownerId &&
        fencingToken == other.fencingToken && acquiredAt == other.acquiredAt && expiresAt == other.expiresAt

    override fun toString(): String = "AgentEvaluationLease(ownerId=$ownerId, fencingToken=$fencingToken)"
}

/** Payload-free, persistence-safe evidence. Raw fixture data and evaluator output are excluded. */
class AgentEvaluationCaseEvidence(
    caseId: Identifier,
    caseDigest: String,
    val passed: Boolean,
    observationDigests: Collection<String>,
    val diagnostic: AgentEvaluationDiagnostic,
    val completedAt: Long,
) {
    val caseId: Identifier = requireRuntimeIdentifier(caseId, "Agent evaluation evidence case is invalid.")
    val caseDigest: String = requireRuntimeDigest(caseDigest, "Agent evaluation evidence case digest is invalid.")
    val observationDigests: List<String>
    val evidenceDigest: String

    init {
        val digestSnapshot = runtimeImmutableList(
            observationDigests.map { digest ->
                requireRuntimeDigest(digest, "Agent evaluation observation digest is invalid.")
            },
            "Agent evaluation evidence contains too many observations.",
        )
        require(digestSnapshot.isNotEmpty()) { "Agent evaluation case evidence requires an observation." }
        require(digestSnapshot.toSet().size == digestSnapshot.size) {
            "Agent evaluation observation digests must be unique."
        }
        require(completedAt >= 0L) { "Agent evaluation evidence time must not be negative." }
        require(diagnostic.status == AgentEvaluationDiagnosticStatus.READY) {
            "Agent evaluation case evidence requires a ready evaluator diagnostic."
        }
        require(diagnostic.observedAt <= completedAt) {
            "Agent evaluation diagnostic cannot follow its durable case evidence."
        }
        this.observationDigests = java.util.Collections.unmodifiableList(digestSnapshot.sorted())
        val digest = AgentRuntimeDigest("flowweft.agent.evaluation.case-evidence.v1")
            .add(this.caseId.value)
            .add(this.caseDigest)
            .add(passed)
            .add(diagnostic.status.name)
            .add(diagnostic.reason?.value ?: "-")
            .add(completedAt)
            .add(this.observationDigests.size)
        this.observationDigests.forEach(digest::add)
        evidenceDigest = digest.finish()
    }

    override fun toString(): String =
        "AgentEvaluationCaseEvidence(passed=$passed, status=${diagnostic.status})"
}

class AgentEvaluationRunState private constructor(
    evaluationId: Identifier,
    val requestId: Identifier,
    val idempotencyScope: AgentEvaluationIdempotencyScope,
    requestBindingDigest: String,
    val suite: AgentEvaluationSuite,
    val providerSnapshot: AgentEvaluationProviderSnapshot,
    val status: AgentEvaluationRunStatus,
    val stateVersion: Long,
    val attempt: Int,
    evidence: Collection<AgentEvaluationCaseEvidence>,
    val lease: AgentEvaluationLease?,
    val diagnostic: AgentEvaluationDiagnostic?,
    cancellationReason: String?,
    val createdAt: Long,
    val updatedAt: Long,
    val deadlineAt: Long,
    val maximumAttempts: Int,
) {
    val evaluationId: Identifier = requireRuntimeIdentifier(evaluationId, "Durable Agent evaluation identifier is invalid.")
    val requestBindingDigest: String = requireRuntimeDigest(
        requestBindingDigest,
        "Durable Agent evaluation request digest is invalid.",
    )
    val tenantId: Identifier = idempotencyScope.tenantId
    val principalId: Identifier = idempotencyScope.principalId
    val principalType: String = idempotencyScope.principalType
    val authorizationRevision: String = idempotencyScope.authorizationRevision
    val evidence: List<AgentEvaluationCaseEvidence> = runtimeImmutableList(
        evidence,
        "Durable Agent evaluation contains too much evidence.",
    )
    val cancellationReason: String? = cancellationReason?.let { reason ->
        requireRuntimeCode(reason, "Agent evaluation cancellation reason is invalid.")
    }

    init {
        requireRuntimeIdentifier(requestId, "Durable Agent evaluation request identifier is invalid.")
        require(stateVersion >= 0L && attempt in 0..maximumAttempts && maximumAttempts in 1..MAX_RUNTIME_ATTEMPTS) {
            "Durable Agent evaluation version or attempt is invalid."
        }
        require(createdAt >= 0L && updatedAt >= createdAt && deadlineAt > createdAt) {
            "Durable Agent evaluation timestamps are invalid."
        }
        require(idempotencyScope.suiteId == suite.suiteId && idempotencyScope.suiteDigest == suite.suiteDigest &&
            idempotencyScope.providerSnapshotDigest == providerSnapshot.snapshotDigest
        ) { "Durable Agent evaluation scope does not match its suite or provider snapshot." }
        require(this.evidence.map { item -> item.caseId }.toSet().size == this.evidence.size) {
            "Durable Agent evaluation case evidence must be unique."
        }
        this.evidence.forEach { item ->
            val case = suite.cases.firstOrNull { candidate -> candidate.caseId == item.caseId }
            require(case != null && case.bindingDigest == item.caseDigest) {
                "Durable Agent evaluation evidence does not match its suite case."
            }
            require(item.diagnostic.providerId == providerSnapshot.providerId &&
                item.diagnostic.snapshotDigest == providerSnapshot.snapshotDigest &&
                (item.diagnostic.capabilityId == null || item.diagnostic.capabilityId == case.capabilityId)
            ) { "Durable Agent evaluation evidence does not match its provider snapshot." }
        }
        require((status == AgentEvaluationRunStatus.RUNNING) == (lease != null)) {
            "Only a running Agent evaluation may retain a lease."
        }
        require(lease == null || lease.isCurrent(updatedAt) && lease.expiresAt <= deadlineAt) {
            "Durable Agent evaluation is outside its retained lease."
        }
        require(status != AgentEvaluationRunStatus.COMPLETED || this.evidence.size == suite.cases.size) {
            "A completed Agent evaluation requires evidence for every case."
        }
        require(status != AgentEvaluationRunStatus.COMPLETED || diagnostic?.status == AgentEvaluationDiagnosticStatus.READY) {
            "A completed Agent evaluation requires a ready diagnostic."
        }
        require(status != AgentEvaluationRunStatus.FAILED && status != AgentEvaluationRunStatus.EXPIRED ||
            diagnostic != null && diagnostic.status != AgentEvaluationDiagnosticStatus.READY
        ) { "A failed or expired Agent evaluation requires a non-ready diagnostic." }
        require((status == AgentEvaluationRunStatus.CANCELLED) == (this.cancellationReason != null)) {
            "Agent evaluation cancellation state and reason must agree."
        }
    }

    fun key(): AgentEvaluationRunKey = AgentEvaluationRunKey(tenantId, evaluationId)

    fun claimed(newLease: AgentEvaluationLease, atTime: Long): AgentEvaluationRunState {
        val recoveringExpiredLease = status == AgentEvaluationRunStatus.RUNNING &&
            lease != null && !lease.isCurrent(atTime)
        require((status == AgentEvaluationRunStatus.QUEUED || recoveringExpiredLease) &&
            atTime >= updatedAt && atTime < deadlineAt
        ) {
            "Agent evaluation cannot be claimed in its current state."
        }
        require(newLease.acquiredAt == atTime && newLease.expiresAt <= deadlineAt) {
            "Agent evaluation claim lease does not match its lifetime."
        }
        val nextAttempt = if (recoveringExpiredLease) attempt else attempt + 1
        return evolve(AgentEvaluationRunStatus.RUNNING, atTime, nextAttempt, evidence, newLease, diagnostic, null)
    }

    fun progressed(
        currentLease: AgentEvaluationLease,
        renewedLease: AgentEvaluationLease,
        caseEvidence: AgentEvaluationCaseEvidence,
        atTime: Long,
    ): AgentEvaluationRunState {
        require(status == AgentEvaluationRunStatus.RUNNING && lease?.matches(currentLease) == true &&
            currentLease.isCurrent(atTime) && renewedLease.leaseId == currentLease.leaseId &&
            renewedLease.ownerId == currentLease.ownerId && renewedLease.fencingToken == currentLease.fencingToken &&
            renewedLease.acquiredAt == currentLease.acquiredAt && renewedLease.expiresAt >= currentLease.expiresAt &&
            renewedLease.expiresAt <= deadlineAt
        ) { "Agent evaluation progress does not hold the current fencing lease." }
        require(evidence.none { item -> item.caseId == caseEvidence.caseId }) {
            "Agent evaluation case evidence is already durable."
        }
        return evolve(
            AgentEvaluationRunStatus.RUNNING,
            atTime,
            attempt,
            evidence + caseEvidence,
            renewedLease,
            diagnostic,
            null,
        )
    }

    fun heartbeat(
        currentLease: AgentEvaluationLease,
        renewedLease: AgentEvaluationLease,
        atTime: Long,
    ): AgentEvaluationRunState {
        require(status == AgentEvaluationRunStatus.RUNNING && lease?.matches(currentLease) == true &&
            currentLease.isCurrent(atTime) && renewedLease.leaseId == currentLease.leaseId &&
            renewedLease.ownerId == currentLease.ownerId && renewedLease.fencingToken == currentLease.fencingToken &&
            renewedLease.acquiredAt == currentLease.acquiredAt && renewedLease.expiresAt >= currentLease.expiresAt &&
            renewedLease.expiresAt <= deadlineAt
        ) { "Agent evaluation heartbeat does not hold the current fencing lease." }
        return evolve(
            AgentEvaluationRunStatus.RUNNING,
            atTime,
            attempt,
            evidence,
            renewedLease,
            diagnostic,
            null,
        )
    }

    fun completed(currentLease: AgentEvaluationLease, atTime: Long): AgentEvaluationRunState {
        require(status == AgentEvaluationRunStatus.RUNNING && lease?.matches(currentLease) == true &&
            currentLease.isCurrent(atTime) && evidence.size == suite.cases.size
        ) { "Agent evaluation cannot complete without its lease and full evidence." }
        val ready = AgentEvaluationDiagnostic(
            AgentEvaluationDiagnosticStatus.READY,
            null,
            providerSnapshot.providerId,
            null,
            providerSnapshot.snapshotDigest,
            atTime,
        )
        return evolve(AgentEvaluationRunStatus.COMPLETED, atTime, attempt, evidence, null, ready, null)
    }

    fun retry(currentLease: AgentEvaluationLease, diagnostic: AgentEvaluationDiagnostic, atTime: Long): AgentEvaluationRunState {
        require(status == AgentEvaluationRunStatus.RUNNING && lease?.matches(currentLease) == true &&
            currentLease.isCurrent(atTime) && attempt < maximumAttempts && atTime < deadlineAt &&
            diagnostic.status != AgentEvaluationDiagnosticStatus.READY
        ) { "Agent evaluation cannot be queued for retry." }
        return evolve(AgentEvaluationRunStatus.QUEUED, atTime, attempt, evidence, null, diagnostic, null)
    }

    fun failed(
        currentLease: AgentEvaluationLease,
        diagnostic: AgentEvaluationDiagnostic,
        atTime: Long,
        expired: Boolean,
    ): AgentEvaluationRunState {
        require(status == AgentEvaluationRunStatus.RUNNING && lease?.matches(currentLease) == true &&
            diagnostic.status != AgentEvaluationDiagnosticStatus.READY && atTime >= updatedAt
        ) { "Agent evaluation cannot fail without its fencing lease and diagnostic." }
        return evolve(
            if (expired) AgentEvaluationRunStatus.EXPIRED else AgentEvaluationRunStatus.FAILED,
            atTime,
            attempt,
            evidence,
            null,
            diagnostic,
            null,
        )
    }

    fun expiredBeforeClaim(diagnostic: AgentEvaluationDiagnostic, atTime: Long): AgentEvaluationRunState {
        require(status == AgentEvaluationRunStatus.QUEUED && atTime >= deadlineAt &&
            diagnostic.status == AgentEvaluationDiagnosticStatus.EXPIRED
        ) { "Agent evaluation cannot expire before claim in its current state." }
        return evolve(
            AgentEvaluationRunStatus.EXPIRED,
            atTime,
            attempt,
            evidence,
            null,
            diagnostic,
            null,
        )
    }

    fun cancelled(reasonCode: String, atTime: Long): AgentEvaluationRunState {
        require(!status.isTerminal() && atTime >= updatedAt) { "Agent evaluation cannot be cancelled." }
        val diagnostic = AgentEvaluationDiagnostic(
            AgentEvaluationDiagnosticStatus.FAILED,
            AgentEvaluationDiagnosticReason("evaluation.cancelled"),
            providerSnapshot.providerId,
            null,
            providerSnapshot.snapshotDigest,
            atTime,
        )
        return evolve(
            AgentEvaluationRunStatus.CANCELLED,
            atTime,
            attempt,
            evidence,
            null,
            diagnostic,
            requireRuntimeCode(reasonCode, "Agent evaluation cancellation reason is invalid."),
        )
    }

    private fun evolve(
        nextStatus: AgentEvaluationRunStatus,
        atTime: Long,
        nextAttempt: Int,
        nextEvidence: Collection<AgentEvaluationCaseEvidence>,
        nextLease: AgentEvaluationLease?,
        nextDiagnostic: AgentEvaluationDiagnostic?,
        nextCancellationReason: String?,
    ): AgentEvaluationRunState {
        require(stateVersion < Long.MAX_VALUE && atTime >= updatedAt) {
            "Agent evaluation state version or time is invalid."
        }
        return AgentEvaluationRunState(
            evaluationId,
            requestId,
            idempotencyScope,
            requestBindingDigest,
            suite,
            providerSnapshot,
            nextStatus,
            stateVersion + 1L,
            nextAttempt,
            nextEvidence,
            nextLease,
            nextDiagnostic,
            nextCancellationReason,
            createdAt,
            atTime,
            deadlineAt,
            maximumAttempts,
        )
    }

    override fun toString(): String =
        "AgentEvaluationRunState(status=$status, version=$stateVersion, attempt=$attempt)"

    companion object {
        @JvmStatic
        fun initial(evaluationId: Identifier, request: AgentEvaluationRunRequest): AgentEvaluationRunState =
            AgentEvaluationRunState(
                evaluationId,
                request.requestId,
                request.idempotencyScope,
                request.requestBindingDigest,
                request.suite,
                request.providerSnapshot,
                AgentEvaluationRunStatus.QUEUED,
                0L,
                0,
                emptyList(),
                null,
                null,
                null,
                request.requestedAt,
                request.requestedAt,
                request.deadlineAt,
                request.maximumAttempts,
            )

        @JvmStatic
        fun restore(
            evaluationId: Identifier,
            requestId: Identifier,
            idempotencyScope: AgentEvaluationIdempotencyScope,
            requestBindingDigest: String,
            suite: AgentEvaluationSuite,
            providerSnapshot: AgentEvaluationProviderSnapshot,
            status: AgentEvaluationRunStatus,
            stateVersion: Long,
            attempt: Int,
            evidence: Collection<AgentEvaluationCaseEvidence>,
            lease: AgentEvaluationLease?,
            diagnostic: AgentEvaluationDiagnostic?,
            cancellationReason: String?,
            createdAt: Long,
            updatedAt: Long,
            deadlineAt: Long,
            maximumAttempts: Int,
        ): AgentEvaluationRunState = AgentEvaluationRunState(
            evaluationId,
            requestId,
            idempotencyScope,
            requestBindingDigest,
            suite,
            providerSnapshot,
            status,
            stateVersion,
            attempt,
            evidence,
            lease,
            diagnostic,
            cancellationReason,
            createdAt,
            updatedAt,
            deadlineAt,
            maximumAttempts,
        )
    }
}

class AgentEvaluationCreateResult(
    val created: Boolean,
    val state: AgentEvaluationRunState,
)

class AgentEvaluationLeaseClaim(
    val key: AgentEvaluationRunKey,
    val ownerId: ProviderId,
    leaseId: Identifier,
    val requestedAt: Long,
    val leaseDurationMillis: Long,
) {
    val leaseId: Identifier = requireRuntimeIdentifier(leaseId, "Agent evaluation lease claim is invalid.")

    init {
        require(requestedAt >= 0L && leaseDurationMillis > 0L &&
            requestedAt <= Long.MAX_VALUE - leaseDurationMillis
        ) { "Agent evaluation lease claim lifetime is invalid." }
    }
}

enum class AgentEvaluationLeaseClaimStatus {
    ACQUIRED,
    BUSY,
    MISSING,
    TERMINAL,
}

class AgentEvaluationLeaseClaimResult(
    val status: AgentEvaluationLeaseClaimStatus,
    val state: AgentEvaluationRunState?,
) {
    init {
        require((status == AgentEvaluationLeaseClaimStatus.MISSING) == (state == null)) {
            "Agent evaluation lease claim result is invalid."
        }
        require(status != AgentEvaluationLeaseClaimStatus.ACQUIRED || state?.lease != null) {
            "An acquired Agent evaluation requires lease evidence."
        }
    }
}

class AgentEvaluationStateCommit(
    val key: AgentEvaluationRunKey,
    val expectedStateVersion: Long,
    val expectedLease: AgentEvaluationLease?,
    val committedAt: Long,
    val nextState: AgentEvaluationRunState,
) {
    init {
        require(expectedStateVersion >= 0L && nextState.stateVersion == expectedStateVersion + 1L &&
            nextState.key() == key && committedAt >= 0L
        ) { "Agent evaluation store commit has an invalid CAS transition." }
    }
}

enum class AgentEvaluationCommitStatus {
    APPLIED,
    VERSION_CONFLICT,
    LEASE_LOST,
    MISSING,
}

class AgentEvaluationCommitResult(
    val status: AgentEvaluationCommitStatus,
    val state: AgentEvaluationRunState?,
) {
    init {
        require((status == AgentEvaluationCommitStatus.MISSING) == (state == null)) {
            "Agent evaluation commit result is invalid."
        }
    }
}

/**
 * Transactional persistence boundary. Every command is inert data: implementations must never
 * invoke fixture/evaluator/provider callbacks while a storage transaction is open.
 */
interface AgentEvaluationDurableStore {
    fun create(initialState: AgentEvaluationRunState): AgentEvaluationCreateResult

    fun load(key: AgentEvaluationRunKey): AgentEvaluationRunState?

    fun findByIdempotency(scope: AgentEvaluationIdempotencyScope): AgentEvaluationRunState?

    fun claim(claim: AgentEvaluationLeaseClaim): AgentEvaluationLeaseClaimResult

    fun heartbeat(commit: AgentEvaluationStateCommit): AgentEvaluationCommitResult

    fun complete(commit: AgentEvaluationStateCommit): AgentEvaluationCommitResult

    fun fail(commit: AgentEvaluationStateCommit): AgentEvaluationCommitResult

    fun cancel(commit: AgentEvaluationStateCommit): AgentEvaluationCommitResult

    fun recoverable(atTime: Long, limit: Int): List<AgentEvaluationRunState>
}

/** Ephemeral fixture bytes. They may be evaluated but must never enter durable state or diagnostics. */
class AgentEvaluationFixture(
    fixtureId: Identifier,
    mediaType: String,
    payload: ByteArray,
    payloadDigest: String,
) {
    val fixtureId: Identifier = requireRuntimeIdentifier(fixtureId, "Agent evaluation fixture identifier is invalid.")
    val mediaType: String = requireRuntimeToken(mediaType, MAX_RUNTIME_CODE_POINTS, "Agent evaluation fixture media type is invalid.")
    private val bytes: ByteArray = payload.copyOf()
    val payloadDigest: String = requireRuntimeDigest(payloadDigest, "Agent evaluation fixture digest is invalid.")

    init {
        require(bytes.isNotEmpty() && bytes.size <= MAX_RUNTIME_ARGUMENT_BYTES) {
            "Agent evaluation fixture payload size is invalid."
        }
        require(runtimeSha256(bytes) == this.payloadDigest) { "Agent evaluation fixture payload digest does not match." }
    }

    fun payload(): ByteArray = bytes.copyOf()

    override fun toString(): String = "AgentEvaluationFixture(fixtureId=<redacted>, size=${bytes.size})"
}

class AgentEvaluationFixtureLoadRequest(
    requestId: Identifier,
    evaluationId: Identifier,
    val context: AgentEvaluationObservationContext,
    fixtureId: Identifier,
    inputDigest: String,
    val requestedAt: Long,
    val deadlineAt: Long,
) {
    val requestId: Identifier = requireRuntimeIdentifier(requestId, "Agent evaluation fixture request is invalid.")
    val evaluationId: Identifier = requireRuntimeIdentifier(evaluationId, "Agent evaluation fixture run is invalid.")
    val fixtureId: Identifier = requireRuntimeIdentifier(fixtureId, "Agent evaluation fixture reference is invalid.")
    val inputDigest: String = requireRuntimeDigest(inputDigest, "Agent evaluation fixture input digest is invalid.")

    init {
        require(requestedAt >= 0L && deadlineAt > requestedAt) { "Agent evaluation fixture request lifetime is invalid." }
    }

    override fun toString(): String = "AgentEvaluationFixtureLoadRequest(<redacted>)"
}

fun interface AgentEvaluationFixturePort {
    fun load(request: AgentEvaluationFixtureLoadRequest): CompletionStage<AgentEvaluationFixture>
}

class AgentEvaluationCaseExecutionRequest(
    requestId: Identifier,
    evaluationId: Identifier,
    val context: AgentEvaluationObservationContext,
    val case: AgentEvaluationCase,
    val fixture: AgentEvaluationFixture,
    val providerSnapshot: AgentEvaluationProviderSnapshot,
    val requestedAt: Long,
    val deadlineAt: Long,
    val cancellationToken: AgentCancellationToken,
) {
    val requestId: Identifier = requireRuntimeIdentifier(requestId, "Agent evaluation case request is invalid.")
    val evaluationId: Identifier = requireRuntimeIdentifier(evaluationId, "Agent evaluation case run is invalid.")
    val operationDigest: String

    init {
        require(context.caseId == case.caseId && context.caseDigest == case.bindingDigest) {
            "Agent evaluation execution context does not match its case."
        }
        require(context.providerSnapshotDigest == providerSnapshot.snapshotDigest) {
            "Agent evaluation execution context does not match its provider snapshot."
        }
        require(fixture.fixtureId == case.fixtureId && fixture.payloadDigest == case.inputDigest) {
            "Agent evaluation fixture does not match its fixed case."
        }
        require(providerSnapshot.supports(case.capabilityId)) {
            "Agent evaluation provider does not support the case capability."
        }
        require(requestedAt >= context.observedAt && deadlineAt > requestedAt) {
            "Agent evaluation case lifetime is invalid."
        }
        operationDigest = AgentRuntimeDigest("flowweft.agent.evaluation.case-operation.v1")
            .add(evaluationId.value)
            .add(context.suiteDigest)
            .add(context.caseDigest)
            .add(context.tenantId.value)
            .add(context.principalType)
            .add(context.principalId.value)
            .add(context.authorizationRevision)
            .add(context.providerSnapshotDigest)
            .add(case.bindingDigest)
            .add(providerSnapshot.snapshotDigest)
            .finish()
    }

    override fun toString(): String = "AgentEvaluationCaseExecutionRequest(<redacted>)"
}

class AgentEvaluationCaseExecutionResult(
    requestId: Identifier,
    observations: Collection<AgentEvaluationObservation>,
    val diagnostic: AgentEvaluationDiagnostic,
    val completedAt: Long,
) {
    val requestId: Identifier = requireRuntimeIdentifier(requestId, "Agent evaluation case result request is invalid.")
    val observations: List<AgentEvaluationObservation> = runtimeImmutableList(
        observations,
        "Agent evaluation case result contains too many observations.",
    )

    init {
        require(completedAt >= 0L) { "Agent evaluation case completion time must not be negative." }
        require(diagnostic.status != AgentEvaluationDiagnosticStatus.READY || this.observations.isNotEmpty()) {
            "A ready Agent evaluation case result requires observations."
        }
        require(this.observations.map { observation -> observation.kind() }.toSet().size == this.observations.size) {
            "Agent evaluation case result observation kinds must be unique."
        }
    }

    fun requireValidFor(request: AgentEvaluationCaseExecutionRequest) {
        require(requestId == request.requestId && completedAt in request.requestedAt..request.deadlineAt &&
            diagnostic.observedAt in request.requestedAt..completedAt
        ) {
            "Agent evaluation case result does not match its request lifetime."
        }
        observations.forEach { observation ->
            require(observation.context().bindingDigest == request.context.bindingDigest) {
                "Agent evaluation observation escaped its trusted execution binding."
            }
        }
        require(diagnostic.providerId == request.providerSnapshot.providerId) {
            "Agent evaluation diagnostic provider does not match its request."
        }
        require(diagnostic.snapshotDigest == request.providerSnapshot.snapshotDigest) {
            "Agent evaluation diagnostic snapshot does not match its request."
        }
        require(diagnostic.capabilityId == null || diagnostic.capabilityId == request.case.capabilityId) {
            "Agent evaluation diagnostic capability does not match its request."
        }
    }
}

interface AgentEvaluationCaseEvaluatorPort {
    /** Local immutable descriptor access; implementations must not perform evaluation here. */
    fun snapshot(): AgentEvaluationProviderSnapshot

    /** Provider call. The stable [AgentEvaluationCaseExecutionRequest.operationDigest] is its idempotency key. */
    fun evaluate(request: AgentEvaluationCaseExecutionRequest): CompletionStage<AgentEvaluationCaseExecutionResult>
}

enum class AgentEvaluationFailureKind {
    RETRYABLE,
    PERMANENT,
    TIMEOUT,
}

class AgentEvaluationFailureDecision(
    val kind: AgentEvaluationFailureKind,
    val reason: AgentEvaluationDiagnosticReason,
)

fun interface AgentEvaluationFailureClassifier {
    /** Must return a safe reason only; raw exception messages are never persisted or logged by this runtime. */
    fun classify(failure: Throwable): AgentEvaluationFailureDecision
}

class AgentEvaluationCancellationRequest(
    val key: AgentEvaluationRunKey,
    principalId: Identifier,
    principalType: String,
    authorizationRevision: String,
    reasonCode: String,
    val requestedAt: Long,
) {
    val principalId: Identifier = requireRuntimeIdentifier(principalId, "Agent evaluation cancellation principal is invalid.")
    val principalType: String = requireRuntimeCode(principalType, "Agent evaluation cancellation principal type is invalid.")
    val authorizationRevision: String = requireRuntimeToken(
        authorizationRevision,
        MAX_RUNTIME_CODE_POINTS,
        "Agent evaluation cancellation authorization revision is invalid.",
    )
    val reasonCode: String = requireRuntimeCode(reasonCode, "Agent evaluation cancellation reason is invalid.")

    init {
        require(requestedAt >= 0L) { "Agent evaluation cancellation time must not be negative." }
    }
}

class AgentEvaluationRuntimeConfiguration @JvmOverloads constructor(
    val leaseDurationMillis: Long = 30_000L,
) {
    init {
        require(leaseDurationMillis in 1L..300_000L) { "Agent evaluation lease duration is invalid." }
    }
}

class AgentEvaluationRuntimeException(
    val reason: AgentEvaluationDiagnosticReason,
) : RuntimeException(reason.value) {
    override fun toString(): String = "AgentEvaluationRuntimeException(reason=$reason)"
}
