package ai.icen.fw.governance.runtime

import ai.icen.fw.governance.api.GovernanceAuthorizationSnapshot
import ai.icen.fw.governance.api.GovernanceCallContext
import ai.icen.fw.governance.api.GovernanceDeletionReconciler
import ai.icen.fw.governance.api.GovernanceDeletionStage
import ai.icen.fw.governance.api.GovernanceDeletionStepExecutor
import ai.icen.fw.governance.api.GovernanceDoctorFinding
import ai.icen.fw.governance.api.GovernanceDoctorMode
import ai.icen.fw.governance.api.GovernanceEffectiveClock
import ai.icen.fw.governance.api.GovernancePrincipalRef
import ai.icen.fw.governance.api.GovernancePurpose
import ai.icen.fw.governance.api.GovernanceResourceRef
import ai.icen.fw.governance.api.GovernanceRetentionPolicySnapshot
import java.util.concurrent.CompletionStage

/** Trusted host invocation before authorization. It is never accepted as authorization evidence. */
class GovernanceTrustedInvocation private constructor(
    tenantId: String,
    val principal: GovernancePrincipalRef,
    val purpose: GovernancePurpose,
    val resource: GovernanceResourceRef,
    idempotencyKey: String,
    val requestedAtEpochMilli: Long,
    val deadlineEpochMilli: Long,
) {
    val tenantId: String = GovernanceRuntimeSupport.text(
        tenantId, GovernanceRuntimeSupport.MAX_ID_BYTES, "Governance runtime tenant is invalid.",
    )
    val idempotencyKey: String = GovernanceRuntimeSupport.text(
        idempotencyKey, GovernanceRuntimeSupport.MAX_ID_BYTES, "Governance runtime idempotency key is invalid.",
    )
    val invocationDigest: String

    init {
        require(requestedAtEpochMilli >= 0L && deadlineEpochMilli > requestedAtEpochMilli) {
            "Governance runtime invocation window is invalid."
        }
        invocationDigest = GovernanceRuntimeSupport.digest("flowweft-governance-runtime-trusted-invocation-v1")
            .text(this.tenantId)
            .text(principal.type)
            .text(principal.id)
            .text(purpose.code)
            .text(resource.referenceDigest)
            .text(this.idempotencyKey)
            .longValue(requestedAtEpochMilli)
            .longValue(deadlineEpochMilli)
            .finish()
    }

    override fun toString(): String = "GovernanceTrustedInvocation(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            tenantId: String,
            principal: GovernancePrincipalRef,
            purpose: GovernancePurpose,
            resource: GovernanceResourceRef,
            idempotencyKey: String,
            requestedAtEpochMilli: Long,
            deadlineEpochMilli: Long,
        ): GovernanceTrustedInvocation = GovernanceTrustedInvocation(
            tenantId,
            principal,
            purpose,
            resource,
            idempotencyKey,
            requestedAtEpochMilli,
            deadlineEpochMilli,
        )
    }
}

class GovernanceRuntimeAuthorizationRequest private constructor(
    val invocation: GovernanceTrustedInvocation,
    val purpose: GovernancePurpose,
    requestId: String,
    idempotencyKey: String,
) {
    val requestId: String = GovernanceRuntimeSupport.opaque(
        requestId, "Governance runtime authorization request identifier is invalid.",
    )
    val idempotencyKey: String = GovernanceRuntimeSupport.text(
        idempotencyKey,
        GovernanceRuntimeSupport.MAX_ID_BYTES,
        "Governance runtime authorization idempotency key is invalid.",
    )
    val requestDigest: String = GovernanceRuntimeSupport.digest(
        "flowweft-governance-runtime-authorization-request-v1",
    )
        .text(invocation.invocationDigest)
        .text(purpose.code)
        .text(this.requestId)
        .text(this.idempotencyKey)
        .finish()

    override fun toString(): String = "GovernanceRuntimeAuthorizationRequest(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            invocation: GovernanceTrustedInvocation,
            purpose: GovernancePurpose,
            requestId: String,
            idempotencyKey: String,
        ): GovernanceRuntimeAuthorizationRequest = GovernanceRuntimeAuthorizationRequest(
            invocation, purpose, requestId, idempotencyKey,
        )
    }
}

fun interface GovernanceRuntimeAuthorizationPort {
    /** Returns fresh exact evidence or throws a value-free host exception; runtime always revalidates it. */
    fun authorize(request: GovernanceRuntimeAuthorizationRequest): GovernanceAuthorizationSnapshot
}

class GovernanceRuntimeIdKind private constructor(code: String) {
    val code: String = GovernanceRuntimeSupport.code(code, "Governance runtime id kind is invalid.")
    override fun equals(other: Any?): Boolean = this === other || other is GovernanceRuntimeIdKind && code == other.code
    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "GovernanceRuntimeIdKind(<redacted>)"

    companion object {
        @JvmField val REQUEST = GovernanceRuntimeIdKind("request")
        @JvmField val PLAN = GovernanceRuntimeIdKind("plan")
        @JvmField val STEP = GovernanceRuntimeIdKind("step")
        @JvmField val OUTBOX = GovernanceRuntimeIdKind("outbox")
    }
}

class GovernanceRuntimeIdRequest private constructor(
    val kind: GovernanceRuntimeIdKind,
    tenantId: String,
    seedDigest: String,
    val ordinal: Int,
) {
    val tenantId: String = GovernanceRuntimeSupport.text(
        tenantId, GovernanceRuntimeSupport.MAX_ID_BYTES, "Governance runtime id tenant is invalid.",
    )
    val seedDigest: String = GovernanceRuntimeSupport.sha256(
        seedDigest, "Governance runtime id seed digest is invalid.",
    )

    init {
        require(ordinal >= 0) { "Governance runtime id ordinal is invalid." }
    }

    override fun toString(): String = "GovernanceRuntimeIdRequest(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            kind: GovernanceRuntimeIdKind,
            tenantId: String,
            seedDigest: String,
            ordinal: Int,
        ): GovernanceRuntimeIdRequest = GovernanceRuntimeIdRequest(kind, tenantId, seedDigest, ordinal)
    }
}

fun interface GovernanceRuntimeIdPort {
    /** Must return an opaque, collision-resistant identifier; never a path, URL, or credential. */
    fun nextId(request: GovernanceRuntimeIdRequest): String
}

class GovernanceClockObservationRequest private constructor(
    tenantId: String,
    val resource: GovernanceResourceRef,
    val observedAtEpochMilli: Long,
    val requiredUntilEpochMilli: Long,
) {
    val tenantId: String = GovernanceRuntimeSupport.text(
        tenantId, GovernanceRuntimeSupport.MAX_ID_BYTES, "Governance runtime clock tenant is invalid.",
    )
    val requestDigest: String

    init {
        require(observedAtEpochMilli >= 0L && requiredUntilEpochMilli > observedAtEpochMilli) {
            "Governance runtime clock observation window is invalid."
        }
        requestDigest = GovernanceRuntimeSupport.digest("flowweft-governance-runtime-clock-request-v1")
            .text(this.tenantId)
            .text(resource.referenceDigest)
            .longValue(observedAtEpochMilli)
            .longValue(requiredUntilEpochMilli)
            .finish()
    }

    override fun toString(): String = "GovernanceClockObservationRequest(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            tenantId: String,
            resource: GovernanceResourceRef,
            observedAtEpochMilli: Long,
            requiredUntilEpochMilli: Long,
        ): GovernanceClockObservationRequest = GovernanceClockObservationRequest(
            tenantId, resource, observedAtEpochMilli, requiredUntilEpochMilli,
        )
    }
}

interface GovernanceRuntimeClockPort {
    fun nowEpochMilli(): Long

    /** Returns an auditable effective clock; runtime rejects mismatched or stale observations. */
    fun observe(request: GovernanceClockObservationRequest): GovernanceEffectiveClock
}

class GovernanceRetentionPolicyRequest private constructor(
    val context: GovernanceCallContext,
    val clock: GovernanceEffectiveClock,
) {
    val resource: GovernanceResourceRef = context.authorization.resource
    val requestDigest: String

    init {
        require(context.purpose == GovernancePurpose.EVALUATE_RETENTION) {
            "Governance runtime policy lookup requires retention-evaluation purpose."
        }
        require(clock.observedAtEpochMilli in context.requestedAtEpochMilli..context.deadlineEpochMilli &&
            clock.expiresAtEpochMilli >= context.deadlineEpochMilli
        ) { "Governance runtime policy clock is stale." }
        requestDigest = GovernanceRuntimeSupport.digest("flowweft-governance-runtime-policy-request-v1")
            .text(context.contextDigest)
            .text(resource.referenceDigest)
            .text(clock.clockDigest)
            .finish()
    }

    override fun toString(): String = "GovernanceRetentionPolicyRequest(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            context: GovernanceCallContext,
            clock: GovernanceEffectiveClock,
        ): GovernanceRetentionPolicyRequest = GovernanceRetentionPolicyRequest(context, clock)
    }
}

fun interface GovernanceRetentionPolicyPort {
    fun load(request: GovernanceRetentionPolicyRequest): CompletionStage<GovernanceRetentionPolicySnapshot>
}

/** One host-resolved opaque target for an existing API deletion stage. */
class GovernanceDeletionTarget private constructor(
    val stage: GovernanceDeletionStage,
    targetRef: String,
    targetRevision: String,
    targetDigest: String,
) {
    val targetRef: String = GovernanceRuntimeSupport.opaque(
        targetRef, "Governance runtime deletion target reference is invalid.",
    )
    val targetRevision: String = GovernanceRuntimeSupport.text(
        targetRevision,
        GovernanceRuntimeSupport.MAX_REVISION_BYTES,
        "Governance runtime deletion target revision is invalid.",
    )
    val targetDigest: String = GovernanceRuntimeSupport.sha256(
        targetDigest, "Governance runtime deletion target digest is invalid.",
    )
    val targetBindingDigest: String = GovernanceRuntimeSupport.digest(
        "flowweft-governance-runtime-deletion-target-v1",
    )
        .text(stage.name)
        .text(this.targetRef)
        .text(this.targetRevision)
        .text(this.targetDigest)
        .finish()

    override fun toString(): String = "GovernanceDeletionTarget(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            stage: GovernanceDeletionStage,
            targetRef: String,
            targetRevision: String,
            targetDigest: String,
        ): GovernanceDeletionTarget = GovernanceDeletionTarget(stage, targetRef, targetRevision, targetDigest)
    }
}

class GovernanceDeletionTargetRequest private constructor(
    val context: GovernanceCallContext,
    assessmentDigest: String,
) {
    val assessmentDigest: String = GovernanceRuntimeSupport.sha256(
        assessmentDigest, "Governance runtime deletion assessment digest is invalid.",
    )
    val requestDigest: String

    init {
        require(context.purpose == GovernancePurpose.PLAN_SECURE_DELETION) {
            "Governance runtime target lookup requires deletion-planning purpose."
        }
        requestDigest = GovernanceRuntimeSupport.digest("flowweft-governance-runtime-target-request-v1")
            .text(context.contextDigest)
            .text(this.assessmentDigest)
            .finish()
    }

    override fun toString(): String = "GovernanceDeletionTargetRequest(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            context: GovernanceCallContext,
            assessmentDigest: String,
        ): GovernanceDeletionTargetRequest = GovernanceDeletionTargetRequest(context, assessmentDigest)
    }
}

fun interface GovernanceDeletionTargetPort {
    /** Must return exactly one target for every fixed API stage; runtime reorders and validates. */
    fun targets(request: GovernanceDeletionTargetRequest): CompletionStage<List<GovernanceDeletionTarget>>
}

class GovernanceDeletionProviderDescriptor private constructor(
    providerId: String,
    providerRevision: String,
    val executor: GovernanceDeletionStepExecutor,
    val reconciler: GovernanceDeletionReconciler,
) {
    val providerId: String = GovernanceRuntimeSupport.code(
        providerId, "Governance runtime deletion provider is invalid.",
    )
    val providerRevision: String = GovernanceRuntimeSupport.text(
        providerRevision,
        GovernanceRuntimeSupport.MAX_REVISION_BYTES,
        "Governance runtime deletion provider revision is invalid.",
    )

    override fun toString(): String = "GovernanceDeletionProviderDescriptor(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            providerId: String,
            providerRevision: String,
            executor: GovernanceDeletionStepExecutor,
            reconciler: GovernanceDeletionReconciler,
        ): GovernanceDeletionProviderDescriptor = GovernanceDeletionProviderDescriptor(
            providerId, providerRevision, executor, reconciler,
        )
    }
}

fun interface GovernanceDeletionProviderRegistry {
    fun find(stage: GovernanceDeletionStage): GovernanceDeletionProviderDescriptor?
}

class GovernanceMetricCode private constructor(code: String) {
    val code: String = GovernanceRuntimeSupport.code(code, "Governance runtime metric code is invalid.")
    override fun equals(other: Any?): Boolean = this === other || other is GovernanceMetricCode && code == other.code
    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "GovernanceMetricCode(<redacted>)"

    companion object {
        @JvmField val PLAN_CREATED = GovernanceMetricCode("plan-created")
        @JvmField val IDEMPOTENT_REPLAY = GovernanceMetricCode("idempotent-replay")
        @JvmField val LEGAL_HOLD_BLOCKED = GovernanceMetricCode("legal-hold-blocked")
        @JvmField val STEP_DISPATCHED = GovernanceMetricCode("step-dispatched")
        @JvmField val STEP_SUCCEEDED = GovernanceMetricCode("step-succeeded")
        @JvmField val STEP_FAILED = GovernanceMetricCode("step-failed")
        @JvmField val RETRY_SCHEDULED = GovernanceMetricCode("retry-scheduled")
        @JvmField val OUTCOME_UNKNOWN = GovernanceMetricCode("outcome-unknown")
        @JvmField val RECONCILED = GovernanceMetricCode("reconciled")
        @JvmField val CAS_CONFLICT = GovernanceMetricCode("cas-conflict")
        @JvmField val STORE_OUTCOME_UNKNOWN = GovernanceMetricCode("store-outcome-unknown")
    }
}

/** Low-cardinality, value-free metric; tenant, principal, ids and provider text are deliberately absent. */
class GovernanceMetric private constructor(
    val code: GovernanceMetricCode,
    val stage: GovernanceDeletionStage?,
    val count: Long,
) {
    init {
        require(count > 0L) { "Governance runtime metric count is invalid." }
    }

    override fun toString(): String = "GovernanceMetric(code=${code.code}, stage=$stage, count=$count)"

    companion object {
        @JvmStatic
        @JvmOverloads
        fun of(
            code: GovernanceMetricCode,
            stage: GovernanceDeletionStage? = null,
            count: Long = 1L,
        ): GovernanceMetric = GovernanceMetric(code, stage, count)
    }
}

fun interface GovernanceMetricsPort {
    fun record(metric: GovernanceMetric)
}

/** Wake-up only. The authoritative plan and receipts remain in the durable repository/outbox. */
fun interface GovernanceWorkerSignalPort {
    fun signal(record: GovernanceOutboxRecord): CompletionStage<Void>
}

/** Value-free Doctor source. It must not execute or reconcile a deletion. */
fun interface GovernanceRuntimeDiagnosticSource {
    fun findings(mode: GovernanceDoctorMode, observedAtEpochMilli: Long): Collection<GovernanceDoctorFinding>
}

/** Builds a fresh API call context and revalidates every authorization field. */
class GovernanceAuthorizedCallFactory(
    private val authorization: GovernanceRuntimeAuthorizationPort,
    private val identifiers: GovernanceRuntimeIdPort,
) {
    fun create(
        invocation: GovernanceTrustedInvocation,
        purpose: GovernancePurpose,
        operationCode: String,
    ): GovernanceCallContext {
        require(isAllowedSubPurpose(invocation.purpose, purpose)) {
            "Governance runtime invocation cannot escalate to the requested sub-purpose."
        }
        val operation = GovernanceRuntimeSupport.code(
            operationCode, "Governance runtime operation code is invalid.",
        )
        val seed = GovernanceRuntimeSupport.digest("flowweft-governance-runtime-call-seed-v1")
            .text(invocation.invocationDigest)
            .text(purpose.code)
            .text(operation)
            .finish()
        val requestId = GovernanceRuntimeSupport.opaque(
            identifiers.nextId(
                GovernanceRuntimeIdRequest.of(GovernanceRuntimeIdKind.REQUEST, invocation.tenantId, seed, 0),
            ),
            "Governance runtime request id provider returned an invalid identifier.",
        )
        val idempotencyKey = GovernanceRuntimeSupport.text(
            "g-$seed",
            GovernanceRuntimeSupport.MAX_ID_BYTES,
            "Governance runtime derived idempotency key is invalid.",
        )
        val request = GovernanceRuntimeAuthorizationRequest.of(
            invocation, purpose, requestId, idempotencyKey,
        )
        val snapshot = authorization.authorize(request)
        return GovernanceCallContext.of(
            requestId,
            invocation.tenantId,
            invocation.principal,
            purpose,
            snapshot,
            idempotencyKey,
            invocation.requestedAtEpochMilli,
            invocation.deadlineEpochMilli,
        )
    }

    private fun isAllowedSubPurpose(root: GovernancePurpose, requested: GovernancePurpose): Boolean = when (root) {
        GovernancePurpose.PLAN_SECURE_DELETION -> requested == GovernancePurpose.RESOLVE_LEGAL_HOLD ||
            requested == GovernancePurpose.EVALUATE_RETENTION || requested == GovernancePurpose.PLAN_SECURE_DELETION

        GovernancePurpose.EXECUTE_SECURE_DELETION -> requested == GovernancePurpose.RESOLVE_LEGAL_HOLD ||
            requested == GovernancePurpose.EVALUATE_RETENTION || requested == GovernancePurpose.EXECUTE_SECURE_DELETION

        GovernancePurpose.RECONCILE_SECURE_DELETION -> requested == GovernancePurpose.RESOLVE_LEGAL_HOLD ||
            requested == GovernancePurpose.EVALUATE_RETENTION ||
            requested == GovernancePurpose.RECONCILE_SECURE_DELETION

        else -> requested == root
    }
}
