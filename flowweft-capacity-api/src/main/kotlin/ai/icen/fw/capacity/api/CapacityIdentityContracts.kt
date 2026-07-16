package ai.icen.fw.capacity.api

import ai.icen.fw.core.id.Identifier

/** Open, provider-neutral purpose bound into trusted authorization evidence. */
class CapacityPurpose(value: String) {
    val value: String = requireCapacityCode(value, "Capacity purpose")

    override fun equals(other: Any?): Boolean = other is CapacityPurpose && value == other.value
    override fun hashCode(): Int = value.hashCode()
    override fun toString(): String = value

    companion object {
        @JvmField val ADMISSION = CapacityPurpose("capacity.admission")
        @JvmField val LEASE = CapacityPurpose("capacity.lease")
        @JvmField val OBSERVE = CapacityPurpose("capacity.observe")
        @JvmField val DOCTOR = CapacityPurpose("capacity.doctor")
    }
}

/**
 * Host-authenticated identity and current authorization evidence. Request bodies and provider
 * adapters must never manufacture this context.
 */
class CapacityTrustedContext private constructor(
    tenantId: Identifier,
    principalId: Identifier,
    principalType: String,
    requestId: Identifier,
    val purpose: CapacityPurpose,
    val authorizedScope: ResourceScope,
    authenticationId: Identifier,
    authorizationDecisionId: Identifier,
    authorizationRevision: String,
    authorizationEvidenceDigest: String,
    val initiatedAt: Long,
    val authorizationExpiresAt: Long,
) {
    val tenantId: Identifier = requireCapacityIdentifier(tenantId, "Capacity tenant identifier")
    val principalId: Identifier = requireCapacityIdentifier(principalId, "Capacity principal identifier")
    val principalType: String = requireCapacityCode(principalType, "Capacity principal type")
    val requestId: Identifier = requireCapacityIdentifier(requestId, "Capacity request identifier")
    val authenticationId: Identifier = requireCapacityIdentifier(
        authenticationId,
        "Capacity authentication identifier",
    )
    val authorizationDecisionId: Identifier = requireCapacityIdentifier(
        authorizationDecisionId,
        "Capacity authorization decision identifier",
    )
    val authorizationRevision: String = requireCapacityToken(
        authorizationRevision,
        "Capacity authorization revision",
    )
    val authorizationEvidenceDigest: String = requireCapacityDigest(
        authorizationEvidenceDigest,
        "Capacity authorization evidence",
    )
    val bindingDigest: String

    init {
        require(initiatedAt >= 0L && authorizationExpiresAt > initiatedAt) {
            "Capacity trusted-context lifetime is invalid."
        }
        require(authorizedScope.level == CapacityScopeLevel.SYSTEM || authorizedScope.tenantId == this.tenantId) {
            "Capacity authorization scope is outside the trusted tenant."
        }
        bindingDigest = CapacityDigest("flowweft.capacity.trusted-context.v1")
            .add(this.tenantId.value)
            .add(this.principalType)
            .add(this.principalId.value)
            .add(this.requestId.value)
            .add(purpose.value)
            .add(authorizedScope.bindingDigest)
            .add(this.authenticationId.value)
            .add(this.authorizationDecisionId.value)
            .add(this.authorizationRevision)
            .add(this.authorizationEvidenceDigest)
            .add(initiatedAt)
            .add(authorizationExpiresAt)
            .finish()
    }

    fun isFresh(atTime: Long): Boolean = atTime >= initiatedAt && atTime < authorizationExpiresAt

    fun requireFresh(atTime: Long) {
        require(isFresh(atTime)) { "Capacity authorization evidence is stale or expired." }
    }

    fun requirePurpose(expected: CapacityPurpose) {
        require(purpose == expected) { "Capacity trusted-context purpose does not match the operation." }
    }

    override fun toString(): String = "CapacityTrustedContext(purpose=$purpose, <redacted>)"

    companion object {
        @JvmStatic
        fun authenticated(
            tenantId: Identifier,
            principalId: Identifier,
            principalType: String,
            requestId: Identifier,
            purpose: CapacityPurpose,
            authorizedScope: ResourceScope,
            authenticationId: Identifier,
            authorizationDecisionId: Identifier,
            authorizationRevision: String,
            authorizationEvidenceDigest: String,
            initiatedAt: Long,
            authorizationExpiresAt: Long,
        ): CapacityTrustedContext = CapacityTrustedContext(
            tenantId,
            principalId,
            principalType,
            requestId,
            purpose,
            authorizedScope,
            authenticationId,
            authorizationDecisionId,
            authorizationRevision,
            authorizationEvidenceDigest,
            initiatedAt,
            authorizationExpiresAt,
        )
    }
}

fun interface CapacityTrustedContextProvider {
    fun currentContext(purpose: CapacityPurpose): CapacityTrustedContext?
}
