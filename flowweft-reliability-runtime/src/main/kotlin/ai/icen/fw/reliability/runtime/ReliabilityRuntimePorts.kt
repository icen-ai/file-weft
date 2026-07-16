package ai.icen.fw.reliability.runtime

import ai.icen.fw.reliability.api.ReliabilityAction
import ai.icen.fw.reliability.api.ReliabilityAuthorizationSnapshot
import ai.icen.fw.reliability.api.ReliabilityCallContext
import ai.icen.fw.reliability.api.ReliabilityComponentScope
import ai.icen.fw.reliability.api.ReliabilityDoctorFinding
import ai.icen.fw.reliability.api.ReliabilityDoctorMode
import ai.icen.fw.reliability.api.ReliabilityEnvironmentRef
import ai.icen.fw.reliability.api.ReliabilityOperationKind
import ai.icen.fw.reliability.api.ReliabilityPrincipalRef
import ai.icen.fw.reliability.api.ReliabilityProviderDescriptor
import ai.icen.fw.reliability.api.ReliabilityProviderSpi
import ai.icen.fw.reliability.api.ReliabilityPurpose
import ai.icen.fw.reliability.api.ReliabilityRecoveryObjectiveSet
import ai.icen.fw.reliability.api.ReliabilityResourceRef
import java.util.concurrent.CompletionStage

/** Trusted host invocation. The raw idempotency key is immediately reduced to a SHA-256 digest. */
class ReliabilityTrustedInvocation private constructor(
    tenantId: String,
    val principal: ReliabilityPrincipalRef,
    val purpose: ReliabilityPurpose,
    val action: ReliabilityAction,
    val resource: ReliabilityResourceRef,
    rawIdempotencyKey: String,
    val requestedAtEpochMilli: Long,
    val deadlineEpochMilli: Long,
) {
    val tenantId: String = ReliabilityRuntimeSupport.text(
        tenantId, ReliabilityRuntimeSupport.MAX_ID_BYTES, "Reliability runtime tenant is invalid.",
    )
    val idempotencyDigest: String = ReliabilityRuntimeSupport.hashSecret(
        rawIdempotencyKey, "Reliability runtime idempotency key is invalid.",
    )
    val invocationDigest: String

    init {
        require(requestedAtEpochMilli >= 0L && deadlineEpochMilli > requestedAtEpochMilli &&
            deadlineEpochMilli - requestedAtEpochMilli <= ReliabilityCallContext.MAX_CALL_WINDOW_MILLIS
        ) { "Reliability runtime invocation dispatch window is invalid." }
        invocationDigest = ReliabilityRuntimeSupport.digest("flowweft-reliability-runtime-invocation-v1")
            .text(this.tenantId)
            .text(principal.type)
            .text(principal.id)
            .text(purpose.name)
            .text(action.name)
            .text(resource.referenceDigest)
            .text(this.idempotencyDigest)
            .longValue(requestedAtEpochMilli)
            .longValue(deadlineEpochMilli)
            .finish()
    }

    override fun toString(): String = "ReliabilityTrustedInvocation(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            tenantId: String,
            principal: ReliabilityPrincipalRef,
            purpose: ReliabilityPurpose,
            action: ReliabilityAction,
            resource: ReliabilityResourceRef,
            rawIdempotencyKey: String,
            requestedAtEpochMilli: Long,
            deadlineEpochMilli: Long,
        ): ReliabilityTrustedInvocation = ReliabilityTrustedInvocation(
            tenantId,
            principal,
            purpose,
            action,
            resource,
            rawIdempotencyKey,
            requestedAtEpochMilli,
            deadlineEpochMilli,
        )
    }
}

class ReliabilityRuntimeAuthorizationRequest private constructor(
    val invocation: ReliabilityTrustedInvocation,
    operationCode: String,
    argumentDigest: String,
    requestId: String,
) {
    val operationCode: String = ReliabilityRuntimeSupport.code(
        operationCode, "Reliability runtime authorization operation is invalid.",
    )
    val argumentDigest: String = ReliabilityRuntimeSupport.sha256(
        argumentDigest, "Reliability runtime authorization argument digest is invalid.",
    )
    val requestId: String = ReliabilityRuntimeSupport.opaque(
        requestId, "Reliability runtime authorization request id is invalid.",
    )
    val requestDigest: String = ReliabilityRuntimeSupport.digest(
        "flowweft-reliability-runtime-authorization-request-v1",
    )
        .text(invocation.invocationDigest)
        .text(this.operationCode)
        .text(this.argumentDigest)
        .text(this.requestId)
        .finish()

    override fun toString(): String = "ReliabilityRuntimeAuthorizationRequest(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            invocation: ReliabilityTrustedInvocation,
            operationCode: String,
            argumentDigest: String,
            requestId: String,
        ): ReliabilityRuntimeAuthorizationRequest = ReliabilityRuntimeAuthorizationRequest(
            invocation, operationCode, argumentDigest, requestId,
        )
    }
}

fun interface ReliabilityRuntimeAuthorizationPort {
    /** Must query the authoritative host policy now; cached or ambient authorization is invalid. */
    fun authorize(request: ReliabilityRuntimeAuthorizationRequest): ReliabilityAuthorizationSnapshot
}

enum class ReliabilityRuntimeIdKind { REQUEST, RUN, PROVIDER_OPERATION, OUTBOX, SLO_EVALUATION }

class ReliabilityRuntimeIdRequest private constructor(
    val kind: ReliabilityRuntimeIdKind,
    tenantId: String,
    seedDigest: String,
    val ordinal: Int,
) {
    val tenantId: String = ReliabilityRuntimeSupport.text(
        tenantId, ReliabilityRuntimeSupport.MAX_ID_BYTES, "Reliability runtime id tenant is invalid.",
    )
    val seedDigest: String = ReliabilityRuntimeSupport.sha256(
        seedDigest, "Reliability runtime id seed is invalid.",
    )

    init {
        require(ordinal >= 0) { "Reliability runtime id ordinal is invalid." }
    }

    companion object {
        @JvmStatic
        fun of(
            kind: ReliabilityRuntimeIdKind,
            tenantId: String,
            seedDigest: String,
            ordinal: Int,
        ): ReliabilityRuntimeIdRequest = ReliabilityRuntimeIdRequest(kind, tenantId, seedDigest, ordinal)
    }
}

fun interface ReliabilityRuntimeIdPort {
    fun nextId(request: ReliabilityRuntimeIdRequest): String
}

fun interface ReliabilityRuntimeClock {
    fun nowEpochMilli(): Long
}

class ReliabilityTopologyRequest private constructor(
    val context: ReliabilityCallContext,
    val environment: ReliabilityEnvironmentRef,
    val requiredUntilEpochMilli: Long,
) {
    val requestDigest: String

    init {
        require(context.tenantId == environment.tenantId && context.resource == environment.resource &&
            requiredUntilEpochMilli >= context.requestedAtEpochMilli
        ) { "Reliability topology request is not bound to the exact tenant and environment." }
        requestDigest = ReliabilityRuntimeSupport.digest("flowweft-reliability-runtime-topology-request-v1")
            .text(context.contextDigest)
            .text(environment.bindingDigest)
            .longValue(requiredUntilEpochMilli)
            .finish()
    }

    companion object {
        @JvmStatic
        fun of(
            context: ReliabilityCallContext,
            environment: ReliabilityEnvironmentRef,
            requiredUntilEpochMilli: Long,
        ): ReliabilityTopologyRequest = ReliabilityTopologyRequest(
            context, environment, requiredUntilEpochMilli,
        )
    }
}

class ReliabilityTopologySnapshot private constructor(
    val environment: ReliabilityEnvironmentRef,
    components: Collection<ReliabilityComponentScope>,
    sourceRevision: String,
    sourceDigest: String,
    val observedAtEpochMilli: Long,
    val expiresAtEpochMilli: Long,
) {
    val components: List<ReliabilityComponentScope> = ReliabilityRuntimeSupport.immutable(
        components.sortedBy { it.scopeDigest },
        ReliabilityRecoveryObjectiveSet.MAX_COMPONENTS,
        "Reliability topology components are invalid.",
    )
    val sourceRevision: String = ReliabilityRuntimeSupport.text(
        sourceRevision, ReliabilityRuntimeSupport.MAX_REVISION_BYTES, "Reliability topology revision is invalid.",
    )
    val sourceDigest: String = ReliabilityRuntimeSupport.sha256(
        sourceDigest, "Reliability topology source digest is invalid.",
    )
    val snapshotDigest: String

    init {
        require(this.components.isNotEmpty() &&
            this.components.map { it.scopeDigest }.toSet().size == this.components.size
        ) { "Reliability topology must contain unique components." }
        require(observedAtEpochMilli >= 0L && expiresAtEpochMilli > observedAtEpochMilli) {
            "Reliability topology lifetime is invalid."
        }
        val writer = ReliabilityRuntimeSupport.digest("flowweft-reliability-runtime-topology-snapshot-v1")
            .text(environment.bindingDigest)
            .text(this.sourceRevision)
            .text(this.sourceDigest)
            .longValue(observedAtEpochMilli)
            .longValue(expiresAtEpochMilli)
            .integer(this.components.size)
        this.components.forEach { writer.text(it.scopeDigest) }
        snapshotDigest = writer.finish()
    }

    fun isFreshAt(atEpochMilli: Long): Boolean =
        observedAtEpochMilli <= atEpochMilli && atEpochMilli < expiresAtEpochMilli

    companion object {
        @JvmStatic
        fun of(
            environment: ReliabilityEnvironmentRef,
            components: Collection<ReliabilityComponentScope>,
            sourceRevision: String,
            sourceDigest: String,
            observedAtEpochMilli: Long,
            expiresAtEpochMilli: Long,
        ): ReliabilityTopologySnapshot = ReliabilityTopologySnapshot(
            environment, components, sourceRevision, sourceDigest, observedAtEpochMilli, expiresAtEpochMilli,
        )
    }
}

fun interface ReliabilityTopologySource {
    fun load(request: ReliabilityTopologyRequest): ReliabilityTopologySnapshot
}

class ReliabilityRecoveryPolicyRequest private constructor(
    val context: ReliabilityCallContext,
    val environment: ReliabilityEnvironmentRef,
    topologySnapshotDigest: String,
) {
    val topologySnapshotDigest: String = ReliabilityRuntimeSupport.sha256(
        topologySnapshotDigest, "Reliability runtime topology snapshot digest is invalid.",
    )
    val requestDigest: String = ReliabilityRuntimeSupport.digest(
        "flowweft-reliability-runtime-recovery-policy-request-v1",
    )
        .text(context.contextDigest)
        .text(environment.bindingDigest)
        .text(this.topologySnapshotDigest)
        .finish()

    companion object {
        @JvmStatic
        fun of(
            context: ReliabilityCallContext,
            environment: ReliabilityEnvironmentRef,
            topologySnapshotDigest: String,
        ): ReliabilityRecoveryPolicyRequest = ReliabilityRecoveryPolicyRequest(
            context, environment, topologySnapshotDigest,
        )
    }
}

fun interface ReliabilityRecoveryPolicySource {
    fun load(request: ReliabilityRecoveryPolicyRequest): ReliabilityRecoveryObjectiveSet
}

class ReliabilityRegisteredProvider private constructor(
    val descriptor: ReliabilityProviderDescriptor,
    val spi: ReliabilityProviderSpi,
) {
    override fun toString(): String = "ReliabilityRegisteredProvider(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            descriptor: ReliabilityProviderDescriptor,
            spi: ReliabilityProviderSpi,
        ): ReliabilityRegisteredProvider = ReliabilityRegisteredProvider(descriptor, spi)
    }
}

fun interface ReliabilityProviderRegistry {
    fun find(providerId: String): ReliabilityRegisteredProvider?
}

enum class ReliabilityStoreCode { STORED, REPLAY, CONFLICT, NOT_FOUND, OUTCOME_UNKNOWN }

class ReliabilityStoreResult private constructor(
    val code: ReliabilityStoreCode,
    val run: ReliabilityRun?,
) {
    companion object {
        @JvmStatic
        fun of(code: ReliabilityStoreCode, run: ReliabilityRun?): ReliabilityStoreResult =
            ReliabilityStoreResult(code, run)
    }
}

/**
 * JDBC implementations must make each method one short local transaction. `createOrLoad` and
 * `compareAndSet` atomically persist the run and outbox; they never call providers or authorization.
 */
interface ReliabilityRunRepository {
    fun createOrLoad(run: ReliabilityRun, outbox: ReliabilityOutboxRecord): ReliabilityStoreResult

    fun load(tenantId: String, runId: String): ReliabilityRun?

    fun findByIdempotency(tenantId: String, idempotencyDigest: String): ReliabilityRun?

    fun claim(
        tenantId: String,
        runId: String,
        expectedVersion: Long,
        ownerId: String,
        nowEpochMilli: Long,
        leaseUntilEpochMilli: Long,
    ): ReliabilityStoreResult

    fun compareAndSet(
        tenantId: String,
        runId: String,
        expectedVersion: Long,
        expectedFencingToken: Long,
        candidate: ReliabilityRun,
        outbox: ReliabilityOutboxRecord,
    ): ReliabilityStoreResult
}

enum class ReliabilityOutboxClaimCode { CLAIMED, EMPTY, CONFLICT, OUTCOME_UNKNOWN }

class ReliabilityOutboxClaimResult private constructor(
    val code: ReliabilityOutboxClaimCode,
    val record: ReliabilityOutboxRecord?,
    val fencingToken: Long,
) {
    init {
        require((code == ReliabilityOutboxClaimCode.CLAIMED) == (record != null && fencingToken > 0L)) {
            "Reliability outbox claim result is inconsistent."
        }
    }

    companion object {
        @JvmStatic
        @JvmOverloads
        fun of(
            code: ReliabilityOutboxClaimCode,
            record: ReliabilityOutboxRecord? = null,
            fencingToken: Long = 0L,
        ): ReliabilityOutboxClaimResult = ReliabilityOutboxClaimResult(code, record, fencingToken)
    }
}

interface ReliabilityOutboxRepository {
    fun claimNext(ownerId: String, nowEpochMilli: Long, leaseUntilEpochMilli: Long): ReliabilityOutboxClaimResult
    fun acknowledge(outboxId: String, ownerId: String, fencingToken: Long): ReliabilityStoreCode
}

fun interface ReliabilityWorkerSignalPort {
    fun signal(record: ReliabilityOutboxRecord): CompletionStage<Void>
}

enum class ReliabilityRuntimeMetricCode {
    INTENT_CREATED,
    IDEMPOTENT_REPLAY,
    LEASE_CONFLICT,
    DISPATCH_STARTED,
    OPERATION_SUCCEEDED,
    OPERATION_FAILED,
    OUTCOME_UNKNOWN,
    RECONCILIATION_STARTED,
    RECONCILED,
    CANCELLED,
    TIMED_OUT,
    PROVIDER_DRIFT,
    AUTHORIZATION_DENIED,
    STORE_OUTCOME_UNKNOWN,
    SLO_EVALUATED,
    SLO_ALERTED,
}

/** Closed, value-free, low-cardinality metric. */
class ReliabilityRuntimeMetric private constructor(
    val code: ReliabilityRuntimeMetricCode,
    val operationKind: ReliabilityOperationKind?,
) {
    companion object {
        @JvmStatic
        @JvmOverloads
        fun of(
            code: ReliabilityRuntimeMetricCode,
            operationKind: ReliabilityOperationKind? = null,
        ): ReliabilityRuntimeMetric = ReliabilityRuntimeMetric(code, operationKind)
    }
}

fun interface ReliabilityRuntimeMetrics {
    fun record(metric: ReliabilityRuntimeMetric)

    companion object {
        @JvmField val NOOP: ReliabilityRuntimeMetrics = ReliabilityRuntimeMetrics { }
    }
}

fun interface ReliabilityRuntimeDiagnosticSource {
    fun findings(mode: ReliabilityDoctorMode, observedAtEpochMilli: Long): Collection<ReliabilityDoctorFinding>
}

/** Test/fault-injection seam. Production uses NOOP. Hooks run only after repository transactions return. */
interface ReliabilityRuntimeFaultHook {
    fun afterIntentStored(run: ReliabilityRun) {}
    fun afterCallStarted(run: ReliabilityRun) {}
    fun afterProviderReturned(run: ReliabilityRun) {}

    companion object {
        @JvmField val NOOP: ReliabilityRuntimeFaultHook = object : ReliabilityRuntimeFaultHook {}
    }
}
