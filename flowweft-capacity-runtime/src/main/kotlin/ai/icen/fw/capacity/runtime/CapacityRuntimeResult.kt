package ai.icen.fw.capacity.runtime

import ai.icen.fw.capacity.api.CapacityProviderErrorCode
import ai.icen.fw.capacity.api.CapacityTrustedContext
import ai.icen.fw.capacity.api.ResourceScope
import ai.icen.fw.capacity.api.WorkloadKind
import ai.icen.fw.core.id.Identifier

/** Open, code-only failure vocabulary. Unknown provider outcomes remain fail-closed. */
class CapacityRuntimeErrorCode(value: String) {
    val value: String = requireRuntimeCode(value, "Capacity runtime error code")

    override fun equals(other: Any?): Boolean = other is CapacityRuntimeErrorCode && value == other.value
    override fun hashCode(): Int = value.hashCode()
    override fun toString(): String = value

    companion object {
        @JvmField val INVALID_REQUEST = CapacityRuntimeErrorCode("invalid_request")
        @JvmField val UNAUTHENTICATED = CapacityRuntimeErrorCode("unauthenticated")
        @JvmField val AUTHORIZATION_UNAVAILABLE = CapacityRuntimeErrorCode("authorization_unavailable")
        @JvmField val AUTHORIZATION_REVOKED = CapacityRuntimeErrorCode("authorization_revoked")
        @JvmField val SECURITY_DEGRADATION_FORBIDDEN =
            CapacityRuntimeErrorCode("security_degradation_forbidden")
        @JvmField val POLICY_UNAVAILABLE = CapacityRuntimeErrorCode("policy_unavailable")
        @JvmField val POLICY_INVALID = CapacityRuntimeErrorCode("policy_invalid")
        @JvmField val PROVIDER_UNSUPPORTED = CapacityRuntimeErrorCode("provider_unsupported")
        @JvmField val PROVIDER_UNAVAILABLE = CapacityRuntimeErrorCode("provider_unavailable")
        @JvmField val PROVIDER_REVISION_DRIFT = CapacityRuntimeErrorCode("provider_revision_drift")
        @JvmField val TRANSACTION_BOUNDARY_VIOLATION =
            CapacityRuntimeErrorCode("transaction_boundary_violation")
        @JvmField val STATE_CONFLICT = CapacityRuntimeErrorCode("state_conflict")
        @JvmField val POLICY_CHANGED = CapacityRuntimeErrorCode("policy_changed")
        @JvmField val LEASE_EXPIRED = CapacityRuntimeErrorCode("lease_expired")
        @JvmField val NOT_FOUND = CapacityRuntimeErrorCode("not_found")
        @JvmField val OUTCOME_UNKNOWN = CapacityRuntimeErrorCode("outcome_unknown")
        @JvmField val INTERNAL_FAILURE = CapacityRuntimeErrorCode("internal_failure")
    }
}

class CapacityRuntimeResult<T> private constructor(
    val value: T?,
    val errorCode: CapacityRuntimeErrorCode?,
    val replayed: Boolean,
    val unknownOutcomeReference: CapacityUnknownOutcomeReference?,
) {
    init {
        require((value != null) != (errorCode != null)) {
            "Capacity runtime result requires exactly one value or error code."
        }
        require(!replayed || value != null) { "Only a successful capacity result may be replayed." }
        require((errorCode == CapacityRuntimeErrorCode.OUTCOME_UNKNOWN) == (unknownOutcomeReference != null)) {
            "Only an outcome-unknown failure carries an exact mutation reference."
        }
    }

    fun isSuccess(): Boolean = value != null

    companion object {
        @JvmStatic
        @JvmOverloads
        fun <T> success(value: T, replayed: Boolean = false): CapacityRuntimeResult<T> =
            CapacityRuntimeResult(value, null, replayed, null)

        @JvmStatic
        fun <T> failure(errorCode: CapacityRuntimeErrorCode): CapacityRuntimeResult<T> {
            require(errorCode != CapacityRuntimeErrorCode.OUTCOME_UNKNOWN) {
                "Outcome-unknown failures require an exact mutation reference."
            }
            return CapacityRuntimeResult(null, errorCode, false, null)
        }

        @JvmStatic
        fun <T> outcomeUnknown(reference: CapacityUnknownOutcomeReference): CapacityRuntimeResult<T> =
            CapacityRuntimeResult(null, CapacityRuntimeErrorCode.OUTCOME_UNKNOWN, false, reference)
    }
}

/** Redacted identity plus digest-only request bindings for reconciliation; it never contains the raw key. */
class CapacityUnknownOutcomeReference(
    operation: String,
    providerId: Identifier,
    val target: ResourceScope,
    val workload: WorkloadKind,
    tenantId: Identifier,
    principalId: Identifier,
    principalType: String,
    requestBindingDigest: String,
    idempotencyScopeDigest: String,
    idempotencyBindingDigest: String,
) {
    val operation: String = requireRuntimeCode(operation, "Capacity unknown-outcome operation")
    val providerId: Identifier = requireRuntimeIdentifier(providerId, "Capacity unknown-outcome provider")
    val tenantId: Identifier = requireRuntimeIdentifier(tenantId, "Capacity unknown-outcome tenant")
    val principalId: Identifier = requireRuntimeIdentifier(principalId, "Capacity unknown-outcome principal")
    val principalType: String = requireRuntimeCode(principalType, "Capacity unknown-outcome principal type")
    val requestBindingDigest: String = requireRuntimeDigest(
        requestBindingDigest,
        "Capacity unknown-outcome request binding",
    )
    val idempotencyScopeDigest: String = requireRuntimeDigest(
        idempotencyScopeDigest,
        "Capacity unknown-outcome idempotency scope",
    )
    val idempotencyBindingDigest: String = requireRuntimeDigest(
        idempotencyBindingDigest,
        "Capacity unknown-outcome idempotency binding",
    )
    val referenceDigest: String = CapacityRuntimeDigest("flowweft.capacity.runtime.unknown-outcome.v1")
        .add(this.operation)
        .add(this.providerId.value)
        .add(target.bindingDigest)
        .add(workload.value)
        .add(this.tenantId.value)
        .add(this.principalType)
        .add(this.principalId.value)
        .add(this.requestBindingDigest)
        .add(this.idempotencyScopeDigest)
        .add(this.idempotencyBindingDigest)
        .finish()

    init {
        require(target.level != ai.icen.fw.capacity.api.CapacityScopeLevel.SYSTEM &&
            target.tenantId == this.tenantId
        ) { "Capacity unknown-outcome target is outside its trusted tenant." }
        require(operation == ADMIT || operation == RENEW || operation == RELEASE) {
            "Capacity unknown-outcome operation is unsupported."
        }
    }

    internal fun isAuthorizedReconciliationTarget(context: CapacityTrustedContext): Boolean =
        context.tenantId == tenantId && context.authorizedScope.appliesTo(target)

    override fun toString(): String = "CapacityUnknownOutcomeReference(operation=$operation, <redacted>)"

    companion object {
        internal const val ADMIT: String = "capacity.admit"
        internal const val RENEW: String = "capacity.lease.renew"
        internal const val RELEASE: String = "capacity.lease.release"
    }
}

internal fun mapProviderError(errorCode: CapacityProviderErrorCode?): CapacityRuntimeErrorCode = when (errorCode) {
    CapacityProviderErrorCode.STATE_CONFLICT -> CapacityRuntimeErrorCode.STATE_CONFLICT
    CapacityProviderErrorCode.POLICY_CHANGED -> CapacityRuntimeErrorCode.POLICY_CHANGED
    CapacityProviderErrorCode.LEASE_EXPIRED -> CapacityRuntimeErrorCode.LEASE_EXPIRED
    CapacityProviderErrorCode.NOT_FOUND -> CapacityRuntimeErrorCode.NOT_FOUND
    CapacityProviderErrorCode.UNAUTHORIZED -> CapacityRuntimeErrorCode.AUTHORIZATION_REVOKED
    CapacityProviderErrorCode.UNSUPPORTED -> CapacityRuntimeErrorCode.PROVIDER_UNSUPPORTED
    CapacityProviderErrorCode.UNAVAILABLE -> CapacityRuntimeErrorCode.PROVIDER_UNAVAILABLE
    CapacityProviderErrorCode.INTERNAL_FAILURE, null -> CapacityRuntimeErrorCode.OUTCOME_UNKNOWN
    else -> CapacityRuntimeErrorCode.OUTCOME_UNKNOWN
}

internal fun mapProviderReadError(errorCode: CapacityProviderErrorCode?): CapacityRuntimeErrorCode = when (errorCode) {
    CapacityProviderErrorCode.UNAUTHORIZED -> CapacityRuntimeErrorCode.AUTHORIZATION_REVOKED
    CapacityProviderErrorCode.UNSUPPORTED -> CapacityRuntimeErrorCode.PROVIDER_UNSUPPORTED
    CapacityProviderErrorCode.NOT_FOUND -> CapacityRuntimeErrorCode.NOT_FOUND
    CapacityProviderErrorCode.STATE_CONFLICT -> CapacityRuntimeErrorCode.STATE_CONFLICT
    CapacityProviderErrorCode.POLICY_CHANGED -> CapacityRuntimeErrorCode.POLICY_CHANGED
    CapacityProviderErrorCode.LEASE_EXPIRED -> CapacityRuntimeErrorCode.LEASE_EXPIRED
    CapacityProviderErrorCode.UNAVAILABLE,
    CapacityProviderErrorCode.INTERNAL_FAILURE,
    null,
    -> CapacityRuntimeErrorCode.PROVIDER_UNAVAILABLE

    else -> CapacityRuntimeErrorCode.PROVIDER_UNAVAILABLE
}
