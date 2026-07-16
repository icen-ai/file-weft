package ai.icen.fw.workflow.cycle.guard

class WorkflowCycleGuardPhase private constructor(code: String) {
    val code: String = WorkflowCycleGuardSupport.code(code, "Cycle guard phase")
    override fun equals(other: Any?): Boolean =
        this === other || other is WorkflowCycleGuardPhase && code == other.code
    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "WorkflowCycleGuardPhase(<redacted>)"

    companion object {
        @JvmField val PREPARE = WorkflowCycleGuardPhase("prepare")
        @JvmField val COMMIT = WorkflowCycleGuardPhase("commit")
    }
}

class WorkflowCycleBudgetPolicyRequest private constructor(
    val command: WorkflowCycleGuardCommand,
    val phase: WorkflowCycleGuardPhase,
    authorizationDecisionDigest: String,
) {
    val authorizationDecisionDigest: String = WorkflowCycleGuardSupport.sha(
        authorizationDecisionDigest,
        "Cycle budget authorization decision digest",
    )
    val requestDigest: String = WorkflowCycleGuardSupport.sha256(
        "flowweft-workflow-cycle-budget-policy-request-v1",
        command.requestDigest,
        phase.code,
        this.authorizationDecisionDigest,
    )

    override fun toString(): String = "WorkflowCycleBudgetPolicyRequest(<redacted>)"

    companion object {
        @JvmStatic fun of(
            command: WorkflowCycleGuardCommand,
            phase: WorkflowCycleGuardPhase,
            authorizationDecisionDigest: String,
        ): WorkflowCycleBudgetPolicyRequest = WorkflowCycleBudgetPolicyRequest(
            command,
            phase,
            authorizationDecisionDigest,
        )
    }
}

/** Exact policy selected for one definition version, node, operation and subject revision scope. */
class WorkflowCycleBudgetPolicy private constructor(
    requestDigest: String,
    scopeDigest: String,
    policyId: String,
    policyVersion: String,
    policyDigest: String,
    authorityRevision: String,
    authorityDigest: String,
    maximumPerCycle: Int,
    maximumPerInstance: Int,
    evaluatedAtEpochMilli: Long,
    validUntilEpochMilli: Long,
) {
    val requestDigest: String = WorkflowCycleGuardSupport.sha(requestDigest, "Cycle budget request digest")
    val scopeDigest: String = WorkflowCycleGuardSupport.sha(scopeDigest, "Cycle budget scope digest")
    val policyId: String = WorkflowCycleGuardSupport.code(policyId, "Cycle budget policy id")
    val policyVersion: String = WorkflowCycleGuardSupport.text(
        policyVersion,
        "Cycle budget policy version",
        128,
    )
    val policyDigest: String = WorkflowCycleGuardSupport.sha(policyDigest, "Cycle budget policy digest")
    val authorityRevision: String = WorkflowCycleGuardSupport.text(
        authorityRevision,
        "Cycle budget authority revision",
        256,
    )
    val authorityDigest: String = WorkflowCycleGuardSupport.sha(
        authorityDigest,
        "Cycle budget authority digest",
    )
    val maximumPerCycle: Int = maximumPerCycle.also {
        require(it in 1..MAXIMUM_BUDGET) { "Cycle budget per-cycle limit is invalid." }
    }
    val maximumPerInstance: Int = maximumPerInstance.also {
        require(it in this.maximumPerCycle..MAXIMUM_BUDGET) {
            "Cycle budget per-instance limit is invalid."
        }
    }
    val evaluatedAtEpochMilli: Long = WorkflowCycleGuardSupport.nonNegative(
        evaluatedAtEpochMilli,
        "Cycle budget evaluation time",
    )
    val validUntilEpochMilli: Long = WorkflowCycleGuardSupport.nonNegative(
        validUntilEpochMilli,
        "Cycle budget validity",
    )
    val contentDigest: String = WorkflowCycleGuardSupport.sha256(
        "flowweft-workflow-cycle-budget-policy-content-v1",
        this.scopeDigest,
        this.policyId,
        this.policyVersion,
        this.policyDigest,
        this.authorityRevision,
        this.authorityDigest,
        this.maximumPerCycle.toString(),
        this.maximumPerInstance.toString(),
    )
    val resolutionDigest: String = WorkflowCycleGuardSupport.sha256(
        "flowweft-workflow-cycle-budget-policy-resolution-v1",
        this.requestDigest,
        contentDigest,
        this.evaluatedAtEpochMilli.toString(),
        this.validUntilEpochMilli.toString(),
    )

    init {
        require(this.validUntilEpochMilli >= this.evaluatedAtEpochMilli) {
            "Cycle budget policy validity window is invalid."
        }
        require(!this.policyVersion.equals("latest", ignoreCase = true)) {
            "Cycle budget policy must use an exact version."
        }
        require(!this.authorityRevision.equals("latest", ignoreCase = true)) {
            "Cycle budget policy must use an exact authority revision."
        }
    }

    fun matches(request: WorkflowCycleBudgetPolicyRequest): Boolean =
        requestDigest == request.requestDigest &&
            scopeDigest == request.command.scope.scopeDigest &&
            evaluatedAtEpochMilli <= request.command.requestedAtEpochMilli &&
            validUntilEpochMilli >= request.command.requestedAtEpochMilli

    override fun toString(): String = "WorkflowCycleBudgetPolicy(<redacted>)"

    companion object {
        const val MAXIMUM_BUDGET: Int = 1_000_000

        @JvmStatic fun of(
            requestDigest: String,
            scopeDigest: String,
            policyId: String,
            policyVersion: String,
            policyDigest: String,
            authorityRevision: String,
            authorityDigest: String,
            maximumPerCycle: Int,
            maximumPerInstance: Int,
            evaluatedAtEpochMilli: Long,
            validUntilEpochMilli: Long,
        ): WorkflowCycleBudgetPolicy = WorkflowCycleBudgetPolicy(
            requestDigest,
            scopeDigest,
            policyId,
            policyVersion,
            policyDigest,
            authorityRevision,
            authorityDigest,
            maximumPerCycle,
            maximumPerInstance,
            evaluatedAtEpochMilli,
            validUntilEpochMilli,
        )
    }
}

fun interface WorkflowCycleBudgetPolicyPort {
    /** Missing or unknown operation/classification returns null; callers never get an implicit limit. */
    fun resolve(request: WorkflowCycleBudgetPolicyRequest): WorkflowCycleBudgetPolicy?
}

/** Durable receipt and counter state. Persistence implementations own atomic cross-cycle totals. */
class WorkflowCycleGuardRecord private constructor(
    val scope: WorkflowCycleGuardScope,
    policyId: String,
    policyVersion: String,
    policyDigest: String,
    policyContentDigest: String,
    policyAuthorityRevision: String,
    maximumPerCycle: Int,
    maximumPerInstance: Int,
    perCycleCount: Int,
    instanceOperationCount: Int,
    guardRevision: Long,
    lastIdempotencyKey: String,
    lastRequestDigest: String,
    lastAuthorizationDecisionDigest: String,
    updatedAtEpochMilli: Long,
) {
    val policyId: String = WorkflowCycleGuardSupport.code(policyId, "Cycle guard record policy id")
    val policyVersion: String = WorkflowCycleGuardSupport.text(
        policyVersion,
        "Cycle guard record policy version",
        128,
    )
    val policyDigest: String = WorkflowCycleGuardSupport.sha(policyDigest, "Cycle guard record policy digest")
    val policyContentDigest: String = WorkflowCycleGuardSupport.sha(
        policyContentDigest,
        "Cycle guard record policy content digest",
    )
    val policyAuthorityRevision: String = WorkflowCycleGuardSupport.text(
        policyAuthorityRevision,
        "Cycle guard record policy authority revision",
        256,
    )
    val maximumPerCycle: Int = maximumPerCycle
    val maximumPerInstance: Int = maximumPerInstance
    val perCycleCount: Int = perCycleCount
    val instanceOperationCount: Int = instanceOperationCount
    val guardRevision: Long = WorkflowCycleGuardSupport.nonNegative(
        guardRevision,
        "Cycle guard record revision",
    )
    val lastIdempotencyKey: String = WorkflowCycleGuardSupport.text(
        lastIdempotencyKey,
        "Cycle guard record idempotency key",
        128,
    )
    val lastRequestDigest: String = WorkflowCycleGuardSupport.sha(
        lastRequestDigest,
        "Cycle guard record request digest",
    )
    val lastAuthorizationDecisionDigest: String = WorkflowCycleGuardSupport.sha(
        lastAuthorizationDecisionDigest,
        "Cycle guard record authorization decision digest",
    )
    val updatedAtEpochMilli: Long = WorkflowCycleGuardSupport.nonNegative(
        updatedAtEpochMilli,
        "Cycle guard record update time",
    )
    val recordDigest: String

    init {
        require(!this.policyVersion.equals("latest", ignoreCase = true)) {
            "Cycle guard record policy must use an exact version."
        }
        require(!this.policyAuthorityRevision.equals("latest", ignoreCase = true)) {
            "Cycle guard record must use an exact policy authority revision."
        }
        require(this.maximumPerCycle in 1..WorkflowCycleBudgetPolicy.MAXIMUM_BUDGET &&
            this.maximumPerInstance in this.maximumPerCycle..WorkflowCycleBudgetPolicy.MAXIMUM_BUDGET &&
            this.perCycleCount in 1..this.maximumPerCycle &&
            this.instanceOperationCount in this.perCycleCount..this.maximumPerInstance &&
            this.guardRevision > 0L
        ) { "Cycle guard durable counters are invalid." }
        recordDigest = WorkflowCycleGuardSupport.sha256(
            "flowweft-workflow-cycle-guard-record-v1",
            scope.scopeDigest,
            this.policyId,
            this.policyVersion,
            this.policyDigest,
            this.policyContentDigest,
            this.policyAuthorityRevision,
            this.maximumPerCycle.toString(),
            this.maximumPerInstance.toString(),
            this.perCycleCount.toString(),
            this.instanceOperationCount.toString(),
            this.guardRevision.toString(),
            this.lastIdempotencyKey,
            this.lastRequestDigest,
            this.lastAuthorizationDecisionDigest,
            this.updatedAtEpochMilli.toString(),
        )
    }

    fun matches(request: WorkflowCycleGuardConsumeRequest): Boolean =
        matchesCommandAndPolicy(request.command, request.policy) &&
            lastAuthorizationDecisionDigest == request.authorizationDecisionDigest

    fun matchesCommandAndPolicy(
        command: WorkflowCycleGuardCommand,
        policy: WorkflowCycleBudgetPolicy,
    ): Boolean =
        matchesCommand(command) &&
            matchesPolicy(policy)

    fun matchesPolicy(policy: WorkflowCycleBudgetPolicy): Boolean =
        policyId == policy.policyId &&
            policyVersion == policy.policyVersion &&
            policyDigest == policy.policyDigest &&
            policyContentDigest == policy.contentDigest &&
            policyAuthorityRevision == policy.authorityRevision &&
            maximumPerCycle == policy.maximumPerCycle &&
            maximumPerInstance == policy.maximumPerInstance

    fun matchesCommand(command: WorkflowCycleGuardCommand): Boolean =
        scope == command.scope &&
            lastIdempotencyKey == command.idempotencyKey &&
            lastRequestDigest == command.requestDigest

    override fun toString(): String = "WorkflowCycleGuardRecord(<redacted>)"

    companion object {
        @JvmStatic fun of(
            scope: WorkflowCycleGuardScope,
            policyId: String,
            policyVersion: String,
            policyDigest: String,
            policyContentDigest: String,
            policyAuthorityRevision: String,
            maximumPerCycle: Int,
            maximumPerInstance: Int,
            perCycleCount: Int,
            instanceOperationCount: Int,
            guardRevision: Long,
            lastIdempotencyKey: String,
            lastRequestDigest: String,
            lastAuthorizationDecisionDigest: String,
            updatedAtEpochMilli: Long,
        ): WorkflowCycleGuardRecord = WorkflowCycleGuardRecord(
            scope,
            policyId,
            policyVersion,
            policyDigest,
            policyContentDigest,
            policyAuthorityRevision,
            maximumPerCycle,
            maximumPerInstance,
            perCycleCount,
            instanceOperationCount,
            guardRevision,
            lastIdempotencyKey,
            lastRequestDigest,
            lastAuthorizationDecisionDigest,
            updatedAtEpochMilli,
        )
    }
}

class WorkflowCycleGuardConsumeRequest private constructor(
    val command: WorkflowCycleGuardCommand,
    val policy: WorkflowCycleBudgetPolicy,
    authorizationDecisionDigest: String,
) {
    val authorizationDecisionDigest: String = WorkflowCycleGuardSupport.sha(
        authorizationDecisionDigest,
        "Cycle guard consume authorization decision digest",
    )
    val requestDigest: String = WorkflowCycleGuardSupport.sha256(
        "flowweft-workflow-cycle-guard-consume-request-v1",
        command.requestDigest,
        policy.contentDigest,
        this.authorizationDecisionDigest,
    )

    override fun toString(): String = "WorkflowCycleGuardConsumeRequest(<redacted>)"

    companion object {
        @JvmStatic fun of(
            command: WorkflowCycleGuardCommand,
            policy: WorkflowCycleBudgetPolicy,
            authorizationDecisionDigest: String,
        ): WorkflowCycleGuardConsumeRequest = WorkflowCycleGuardConsumeRequest(
            command,
            policy,
            authorizationDecisionDigest,
        )
    }
}

class WorkflowCycleGuardStoreCode private constructor(code: String) {
    val code: String = WorkflowCycleGuardSupport.code(code, "Cycle guard store result")
    override fun equals(other: Any?): Boolean =
        this === other || other is WorkflowCycleGuardStoreCode && code == other.code
    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "WorkflowCycleGuardStoreCode(<redacted>)"

    companion object {
        @JvmField val APPLIED = WorkflowCycleGuardStoreCode("applied")
        @JvmField val REPLAYED = WorkflowCycleGuardStoreCode("replayed")
        @JvmField val LIMIT_REACHED = WorkflowCycleGuardStoreCode("limit-reached")
        @JvmField val POLICY_CONFLICT = WorkflowCycleGuardStoreCode("policy-conflict")
        @JvmField val VERSION_CONFLICT = WorkflowCycleGuardStoreCode("version-conflict")
        @JvmField val IDEMPOTENCY_CONFLICT = WorkflowCycleGuardStoreCode("idempotency-conflict")
        @JvmField val OUTCOME_UNKNOWN = WorkflowCycleGuardStoreCode("outcome-unknown")
    }
}

class WorkflowCycleGuardStoreResult private constructor(
    val code: WorkflowCycleGuardStoreCode,
    val record: WorkflowCycleGuardRecord?,
) {
    init {
        require((code == WorkflowCycleGuardStoreCode.APPLIED ||
            code == WorkflowCycleGuardStoreCode.REPLAYED) == (record != null)
        ) { "Cycle guard store result shape is invalid." }
    }

    override fun toString(): String = "WorkflowCycleGuardStoreResult(<redacted>)"

    companion object {
        @JvmStatic fun success(
            code: WorkflowCycleGuardStoreCode,
            record: WorkflowCycleGuardRecord,
        ): WorkflowCycleGuardStoreResult = WorkflowCycleGuardStoreResult(code, record)

        @JvmStatic fun failure(code: WorkflowCycleGuardStoreCode): WorkflowCycleGuardStoreResult =
            WorkflowCycleGuardStoreResult(code, null)
    }
}

class WorkflowCycleGuardReceiptLookup private constructor(val command: WorkflowCycleGuardCommand) {
    val requestDigest: String = WorkflowCycleGuardSupport.sha256(
        "flowweft-workflow-cycle-guard-receipt-lookup-v1",
        command.scope.scopeDigest,
        command.idempotencyKey,
        command.requestDigest,
    )
    override fun toString(): String = "WorkflowCycleGuardReceiptLookup(<redacted>)"

    companion object {
        @JvmStatic fun of(command: WorkflowCycleGuardCommand): WorkflowCycleGuardReceiptLookup =
            WorkflowCycleGuardReceiptLookup(command)
    }
}

class WorkflowCycleGuardLookupCode private constructor(code: String) {
    val code: String = WorkflowCycleGuardSupport.code(code, "Cycle guard lookup result")
    override fun equals(other: Any?): Boolean =
        this === other || other is WorkflowCycleGuardLookupCode && code == other.code
    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "WorkflowCycleGuardLookupCode(<redacted>)"

    companion object {
        @JvmField val FOUND = WorkflowCycleGuardLookupCode("found")
        @JvmField val NOT_FOUND = WorkflowCycleGuardLookupCode("not-found")
        @JvmField val CONFLICT = WorkflowCycleGuardLookupCode("conflict")
        @JvmField val OUTCOME_UNKNOWN = WorkflowCycleGuardLookupCode("outcome-unknown")
    }
}

class WorkflowCycleGuardLookupResult private constructor(
    val code: WorkflowCycleGuardLookupCode,
    val record: WorkflowCycleGuardRecord?,
) {
    init {
        require((code == WorkflowCycleGuardLookupCode.FOUND) == (record != null)) {
            "Cycle guard lookup result shape is invalid."
        }
    }
    override fun toString(): String = "WorkflowCycleGuardLookupResult(<redacted>)"

    companion object {
        @JvmStatic fun found(record: WorkflowCycleGuardRecord): WorkflowCycleGuardLookupResult =
            WorkflowCycleGuardLookupResult(WorkflowCycleGuardLookupCode.FOUND, record)

        @JvmStatic fun absent(code: WorkflowCycleGuardLookupCode): WorkflowCycleGuardLookupResult {
            require(code != WorkflowCycleGuardLookupCode.FOUND) { "Cycle guard absent lookup code is invalid." }
            return WorkflowCycleGuardLookupResult(code, null)
        }
    }
}

/**
 * Production implementations must use durable storage and one atomic transaction/CAS for the
 * cycle row, cross-cycle instance-operation total, idempotency receipt and increment. The first
 * exact policy content selected for an existing cycle/aggregate is pinned: implementations return
 * POLICY_CONFLICT instead of silently replacing limits or resetting counters. The same transaction
 * verifies the command's expected workflow-instance version when the host persistence exposes it.
 * No in-memory default is provided by this module.
 */
interface WorkflowCycleGuardPersistencePort {
    fun consume(request: WorkflowCycleGuardConsumeRequest): WorkflowCycleGuardStoreResult
    fun findReceipt(request: WorkflowCycleGuardReceiptLookup): WorkflowCycleGuardLookupResult
    fun load(scope: WorkflowCycleGuardScope): WorkflowCycleGuardLookupResult
}

class WorkflowCycleGuardDiagnosticCode private constructor(code: String) {
    val code: String = WorkflowCycleGuardSupport.code(code, "Cycle guard diagnostic status")
    override fun equals(other: Any?): Boolean =
        this === other || other is WorkflowCycleGuardDiagnosticCode && code == other.code
    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "WorkflowCycleGuardDiagnosticCode(<redacted>)"

    companion object {
        @JvmField val HEALTHY = WorkflowCycleGuardDiagnosticCode("healthy")
        @JvmField val NEAR_LIMIT = WorkflowCycleGuardDiagnosticCode("near-limit")
        @JvmField val EXHAUSTED = WorkflowCycleGuardDiagnosticCode("exhausted")
        @JvmField val NOT_FOUND = WorkflowCycleGuardDiagnosticCode("not-found")
        @JvmField val AUTHORIZATION_DENIED = WorkflowCycleGuardDiagnosticCode("authorization-denied")
        @JvmField val STORE_UNAVAILABLE = WorkflowCycleGuardDiagnosticCode("store-unavailable")
        @JvmField val RECEIPT_DRIFT = WorkflowCycleGuardDiagnosticCode("receipt-drift")
    }
}

class WorkflowCycleGuardDiagnostic private constructor(
    val code: WorkflowCycleGuardDiagnosticCode,
    val record: WorkflowCycleGuardRecord?,
    remainingPerCycle: Int?,
    remainingPerInstance: Int?,
) {
    val remainingPerCycle: Int? = remainingPerCycle
    val remainingPerInstance: Int? = remainingPerInstance

    init {
        val hasRecord = record != null
        val observed = code == WorkflowCycleGuardDiagnosticCode.HEALTHY ||
            code == WorkflowCycleGuardDiagnosticCode.NEAR_LIMIT ||
            code == WorkflowCycleGuardDiagnosticCode.EXHAUSTED
        require(hasRecord == observed) { "Cycle guard diagnostic code and record are inconsistent." }
        require(hasRecord == (this.remainingPerCycle != null && this.remainingPerInstance != null)) {
            "Cycle guard diagnostic shape is invalid."
        }
        require(!hasRecord || this.remainingPerCycle!! >= 0 && this.remainingPerInstance!! >= 0) {
            "Cycle guard diagnostic remaining budget is invalid."
        }
    }

    override fun toString(): String = "WorkflowCycleGuardDiagnostic(<redacted>)"

    companion object {
        @JvmStatic fun observed(
            code: WorkflowCycleGuardDiagnosticCode,
            record: WorkflowCycleGuardRecord,
            remainingPerCycle: Int,
            remainingPerInstance: Int,
        ): WorkflowCycleGuardDiagnostic = WorkflowCycleGuardDiagnostic(
            code,
            record,
            remainingPerCycle,
            remainingPerInstance,
        )

        @JvmStatic fun unavailable(code: WorkflowCycleGuardDiagnosticCode): WorkflowCycleGuardDiagnostic =
            WorkflowCycleGuardDiagnostic(code, null, null, null)
    }
}
