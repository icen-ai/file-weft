package ai.icen.fw.governance.api

/** Stable tenant-relative principal reference. It is an identity claim, never authentication proof. */
class GovernancePrincipalRef private constructor(type: String, id: String) {
    val type: String = GovernanceContractSupport.requireMachineCode(
        type, "Governance principal type is invalid.",
    )
    val id: String = GovernanceContractSupport.requireText(
        id, GovernanceContractSupport.MAX_ID_UTF8_BYTES, "Governance principal identifier is invalid.",
    )

    override fun equals(other: Any?): Boolean =
        this === other || other is GovernancePrincipalRef && type == other.type && id == other.id

    override fun hashCode(): Int = 31 * type.hashCode() + id.hashCode()
    override fun toString(): String = "GovernancePrincipalRef(<redacted>)"

    companion object {
        @JvmStatic
        fun of(type: String, id: String): GovernancePrincipalRef = GovernancePrincipalRef(type, id)
    }
}

/** Exact host-owned resource snapshot. Governance never owns its catalog CRUD. */
class GovernanceResourceRef private constructor(
    type: String,
    id: String,
    revision: String,
    digest: String,
) {
    val type: String = GovernanceContractSupport.requireMachineCode(type, "Governance resource type is invalid.")
    val id: String = GovernanceContractSupport.requireText(
        id, GovernanceContractSupport.MAX_ID_UTF8_BYTES, "Governance resource identifier is invalid.",
    )
    val revision: String = GovernanceContractSupport.requireText(
        revision, GovernanceContractSupport.MAX_REVISION_UTF8_BYTES, "Governance resource revision is invalid.",
    )
    val digest: String = GovernanceContractSupport.requireSha256(
        digest, "Governance resource digest is invalid.",
    )
    val referenceDigest: String = GovernanceContractSupport.digest("flowweft-governance-api-resource-ref-v1")
        .text(this.type)
        .text(this.id)
        .text(this.revision)
        .text(this.digest)
        .finish()

    override fun equals(other: Any?): Boolean = this === other ||
        other is GovernanceResourceRef && type == other.type && id == other.id &&
        revision == other.revision && digest == other.digest

    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + id.hashCode()
        result = 31 * result + revision.hashCode()
        result = 31 * result + digest.hashCode()
        return result
    }

    override fun toString(): String = "GovernanceResourceRef(<redacted>)"

    companion object {
        @JvmStatic
        fun of(type: String, id: String, revision: String, digest: String): GovernanceResourceRef =
            GovernanceResourceRef(type, id, revision, digest)
    }
}

/** Exact high-risk purpose. Matching a purpose never grants authority by itself. */
class GovernancePurpose private constructor(code: String) {
    val code: String = GovernanceContractSupport.requireMachineCode(code, "Governance purpose is invalid.")

    override fun equals(other: Any?): Boolean =
        this === other || other is GovernancePurpose && code == other.code

    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "GovernancePurpose(<redacted>)"

    companion object {
        @JvmField val EVALUATE_RETENTION = GovernancePurpose("evaluate-retention")
        @JvmField val RESOLVE_LEGAL_HOLD = GovernancePurpose("resolve-legal-hold")
        @JvmField val PLAN_SECURE_DELETION = GovernancePurpose("plan-secure-deletion")
        @JvmField val EXECUTE_SECURE_DELETION = GovernancePurpose("execute-secure-deletion")
        @JvmField val RECONCILE_SECURE_DELETION = GovernancePurpose("reconcile-secure-deletion")
        @JvmField val DISCOVER_CAPABILITIES = GovernancePurpose("discover-capabilities")
        @JvmField val INSPECT_DOCTOR = GovernancePurpose("inspect-doctor")

        @JvmStatic
        fun of(code: String): GovernancePurpose = when (code) {
            EVALUATE_RETENTION.code -> EVALUATE_RETENTION
            RESOLVE_LEGAL_HOLD.code -> RESOLVE_LEGAL_HOLD
            PLAN_SECURE_DELETION.code -> PLAN_SECURE_DELETION
            EXECUTE_SECURE_DELETION.code -> EXECUTE_SECURE_DELETION
            RECONCILE_SECURE_DELETION.code -> RECONCILE_SECURE_DELETION
            DISCOVER_CAPABILITIES.code -> DISCOVER_CAPABILITIES
            INSPECT_DOCTOR.code -> INSPECT_DOCTOR
            else -> GovernancePurpose(code)
        }
    }
}

/**
 * Fresh authorization evidence for one exact tenant, principal, purpose and resource snapshot.
 * It is consistency evidence, not a bearer token, session, or reusable superuser permission.
 */
class GovernanceAuthorizationSnapshot private constructor(
    authorizationId: String,
    tenantId: String,
    val principal: GovernancePrincipalRef,
    val purpose: GovernancePurpose,
    val resource: GovernanceResourceRef,
    authorityId: String,
    authorityRevision: String,
    authorizationRevision: String,
    decisionDigest: String,
    val issuedAtEpochMilli: Long,
    val expiresAtEpochMilli: Long,
) {
    val authorizationId: String = GovernanceContractSupport.requireOpaqueReference(
        authorizationId, "Governance authorization identifier is invalid.",
    )
    val tenantId: String = GovernanceContractSupport.requireText(
        tenantId, GovernanceContractSupport.MAX_ID_UTF8_BYTES, "Governance authorization tenant is invalid.",
    )
    val authorityId: String = GovernanceContractSupport.requireMachineCode(
        authorityId, "Governance authorization authority is invalid.",
    )
    val authorityRevision: String = GovernanceContractSupport.requireText(
        authorityRevision, GovernanceContractSupport.MAX_REVISION_UTF8_BYTES,
        "Governance authorization authority revision is invalid.",
    )
    val authorizationRevision: String = GovernanceContractSupport.requireText(
        authorizationRevision, GovernanceContractSupport.MAX_REVISION_UTF8_BYTES,
        "Governance authorization revision is invalid.",
    )
    val decisionDigest: String = GovernanceContractSupport.requireSha256(
        decisionDigest, "Governance authorization decision digest is invalid.",
    )
    val snapshotDigest: String

    init {
        require(issuedAtEpochMilli >= 0L && expiresAtEpochMilli > issuedAtEpochMilli) {
            "Governance authorization validity window is invalid."
        }
        require(expiresAtEpochMilli - issuedAtEpochMilli <= GovernanceContractSupport.MAX_AUTHORIZATION_TTL_MILLIS) {
            "Governance authorization lifetime exceeds the freshness limit."
        }
        snapshotDigest = GovernanceContractSupport.digest("flowweft-governance-api-authorization-v1")
            .text(this.authorizationId)
            .text(this.tenantId)
            .text(principal.type)
            .text(principal.id)
            .text(purpose.code)
            .text(resource.referenceDigest)
            .text(this.authorityId)
            .text(this.authorityRevision)
            .text(this.authorizationRevision)
            .text(this.decisionDigest)
            .longValue(issuedAtEpochMilli)
            .longValue(expiresAtEpochMilli)
            .finish()
    }

    override fun toString(): String = "GovernanceAuthorizationSnapshot(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            authorizationId: String,
            tenantId: String,
            principal: GovernancePrincipalRef,
            purpose: GovernancePurpose,
            resource: GovernanceResourceRef,
            authorityId: String,
            authorityRevision: String,
            authorizationRevision: String,
            decisionDigest: String,
            issuedAtEpochMilli: Long,
            expiresAtEpochMilli: Long,
        ): GovernanceAuthorizationSnapshot = GovernanceAuthorizationSnapshot(
            authorizationId,
            tenantId,
            principal,
            purpose,
            resource,
            authorityId,
            authorityRevision,
            authorizationRevision,
            decisionDigest,
            issuedAtEpochMilli,
            expiresAtEpochMilli,
        )
    }
}

/** Trusted invocation context. Every command receives a distinct idempotency key and fresh authorization. */
class GovernanceCallContext private constructor(
    requestId: String,
    tenantId: String,
    val principal: GovernancePrincipalRef,
    val purpose: GovernancePurpose,
    val authorization: GovernanceAuthorizationSnapshot,
    idempotencyKey: String,
    val requestedAtEpochMilli: Long,
    val deadlineEpochMilli: Long,
) {
    val requestId: String = GovernanceContractSupport.requireOpaqueReference(
        requestId, "Governance request identifier is invalid.",
    )
    val tenantId: String = GovernanceContractSupport.requireText(
        tenantId, GovernanceContractSupport.MAX_ID_UTF8_BYTES, "Governance tenant is invalid.",
    )
    val idempotencyKey: String = GovernanceContractSupport.requireText(
        idempotencyKey, GovernanceContractSupport.MAX_ID_UTF8_BYTES,
        "Governance idempotency key is invalid.",
    )
    val contextDigest: String

    init {
        require(requestedAtEpochMilli >= 0L && deadlineEpochMilli > requestedAtEpochMilli) {
            "Governance deadline must follow its request time."
        }
        require(deadlineEpochMilli - requestedAtEpochMilli <= GovernanceContractSupport.MAX_CALL_WINDOW_MILLIS) {
            "Governance call window exceeds the limit."
        }
        require(authorization.tenantId == this.tenantId && authorization.principal == principal &&
            authorization.purpose == purpose) {
            "Governance authorization does not match the exact tenant, principal and purpose."
        }
        require(requestedAtEpochMilli >= authorization.issuedAtEpochMilli &&
            deadlineEpochMilli <= authorization.expiresAtEpochMilli) {
            "Governance authorization is not fresh for the complete call window."
        }
        contextDigest = GovernanceContractSupport.digest("flowweft-governance-api-call-context-v1")
            .text(this.requestId)
            .text(this.tenantId)
            .text(principal.type)
            .text(principal.id)
            .text(purpose.code)
            .text(authorization.snapshotDigest)
            .text(this.idempotencyKey)
            .longValue(requestedAtEpochMilli)
            .longValue(deadlineEpochMilli)
            .finish()
    }

    override fun toString(): String = "GovernanceCallContext(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            requestId: String,
            tenantId: String,
            principal: GovernancePrincipalRef,
            purpose: GovernancePurpose,
            authorization: GovernanceAuthorizationSnapshot,
            idempotencyKey: String,
            requestedAtEpochMilli: Long,
            deadlineEpochMilli: Long,
        ): GovernanceCallContext = GovernanceCallContext(
            requestId,
            tenantId,
            principal,
            purpose,
            authorization,
            idempotencyKey,
            requestedAtEpochMilli,
            deadlineEpochMilli,
        )
    }
}

/** Compare-and-set fence for the exact resource snapshot and governance aggregate version. */
class GovernanceVersionFence private constructor(
    val resource: GovernanceResourceRef,
    val expectedGovernanceVersion: Long,
) {
    val fenceDigest: String

    init {
        require(expectedGovernanceVersion >= 0L) { "Governance expected version is invalid." }
        fenceDigest = GovernanceContractSupport.digest("flowweft-governance-api-version-fence-v1")
            .text(resource.referenceDigest)
            .longValue(expectedGovernanceVersion)
            .finish()
    }

    override fun toString(): String = "GovernanceVersionFence(<redacted>)"

    companion object {
        @JvmStatic
        fun of(resource: GovernanceResourceRef, expectedGovernanceVersion: Long): GovernanceVersionFence =
            GovernanceVersionFence(resource, expectedGovernanceVersion)
    }
}

/** Explicit controllable clock snapshot; contract objects never read ambient system time. */
class GovernanceEffectiveClock private constructor(
    clockId: String,
    authorityId: String,
    authorityRevision: String,
    val observedAtEpochMilli: Long,
    val effectiveAtEpochMilli: Long,
    val expiresAtEpochMilli: Long,
) {
    val clockId: String = GovernanceContractSupport.requireOpaqueReference(
        clockId, "Governance clock identifier is invalid.",
    )
    val authorityId: String = GovernanceContractSupport.requireMachineCode(
        authorityId, "Governance clock authority is invalid.",
    )
    val authorityRevision: String = GovernanceContractSupport.requireText(
        authorityRevision, GovernanceContractSupport.MAX_REVISION_UTF8_BYTES,
        "Governance clock authority revision is invalid.",
    )
    val clockDigest: String

    init {
        require(observedAtEpochMilli >= 0L && effectiveAtEpochMilli >= 0L &&
            expiresAtEpochMilli > observedAtEpochMilli) {
            "Governance clock values are invalid."
        }
        clockDigest = GovernanceContractSupport.digest("flowweft-governance-api-effective-clock-v1")
            .text(this.clockId)
            .text(this.authorityId)
            .text(this.authorityRevision)
            .longValue(observedAtEpochMilli)
            .longValue(effectiveAtEpochMilli)
            .longValue(expiresAtEpochMilli)
            .finish()
    }

    override fun toString(): String = "GovernanceEffectiveClock(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            clockId: String,
            authorityId: String,
            authorityRevision: String,
            observedAtEpochMilli: Long,
            effectiveAtEpochMilli: Long,
            expiresAtEpochMilli: Long,
        ): GovernanceEffectiveClock = GovernanceEffectiveClock(
            clockId,
            authorityId,
            authorityRevision,
            observedAtEpochMilli,
            effectiveAtEpochMilli,
            expiresAtEpochMilli,
        )
    }
}

class GovernanceFailureClass private constructor(code: String) {
    val code: String = GovernanceContractSupport.requireMachineCode(code, "Governance failure class is invalid.")

    override fun equals(other: Any?): Boolean =
        this === other || other is GovernanceFailureClass && code == other.code

    override fun hashCode(): Int = code.hashCode()
    override fun toString(): String = "GovernanceFailureClass(<redacted>)"

    companion object {
        @JvmField val INVALID_REQUEST = GovernanceFailureClass("invalid-request")
        @JvmField val DENIED = GovernanceFailureClass("denied")
        @JvmField val CONFLICT = GovernanceFailureClass("conflict")
        @JvmField val STALE_EVIDENCE = GovernanceFailureClass("stale-evidence")
        @JvmField val LEGAL_HOLD_ACTIVE = GovernanceFailureClass("legal-hold-active")
        @JvmField val UNSUPPORTED = GovernanceFailureClass("unsupported")
        @JvmField val NOT_FOUND = GovernanceFailureClass("not-found")
        @JvmField val TEMPORARY_UNAVAILABLE = GovernanceFailureClass("temporary-unavailable")
        @JvmField val PERMANENT_FAILURE = GovernanceFailureClass("permanent-failure")
        @JvmField val OUTCOME_UNKNOWN = GovernanceFailureClass("outcome-unknown")

        @JvmStatic
        fun of(code: String): GovernanceFailureClass = when (code) {
            INVALID_REQUEST.code -> INVALID_REQUEST
            DENIED.code -> DENIED
            CONFLICT.code -> CONFLICT
            STALE_EVIDENCE.code -> STALE_EVIDENCE
            LEGAL_HOLD_ACTIVE.code -> LEGAL_HOLD_ACTIVE
            UNSUPPORTED.code -> UNSUPPORTED
            NOT_FOUND.code -> NOT_FOUND
            TEMPORARY_UNAVAILABLE.code -> TEMPORARY_UNAVAILABLE
            PERMANENT_FAILURE.code -> PERMANENT_FAILURE
            OUTCOME_UNKNOWN.code -> OUTCOME_UNKNOWN
            else -> GovernanceFailureClass(code)
        }
    }
}

/** Value-free error metadata. Provider exception messages must not cross the API. */
class GovernanceFailure private constructor(
    val classification: GovernanceFailureClass,
    reasonCode: String,
    val retryable: Boolean,
    val reconciliationRequired: Boolean,
) {
    val reasonCode: String = GovernanceContractSupport.requireMachineCode(
        reasonCode, "Governance failure reason is invalid.",
    )
    val failureDigest: String

    init {
        when (classification) {
            GovernanceFailureClass.TEMPORARY_UNAVAILABLE -> require(retryable && !reconciliationRequired) {
                "Temporary governance failures must be retryable without reconciliation."
            }
            GovernanceFailureClass.OUTCOME_UNKNOWN -> require(!retryable && reconciliationRequired) {
                "Unknown governance outcomes require reconciliation and cannot be retried."
            }
            GovernanceFailureClass.INVALID_REQUEST,
            GovernanceFailureClass.DENIED,
            GovernanceFailureClass.CONFLICT,
            GovernanceFailureClass.STALE_EVIDENCE,
            GovernanceFailureClass.LEGAL_HOLD_ACTIVE,
            GovernanceFailureClass.UNSUPPORTED,
            GovernanceFailureClass.NOT_FOUND,
            GovernanceFailureClass.PERMANENT_FAILURE,
            -> require(!retryable && !reconciliationRequired) {
                "Terminal governance failures cannot be retried or reconciled."
            }
        }
        failureDigest = GovernanceContractSupport.digest("flowweft-governance-api-failure-v1")
            .text(classification.code)
            .text(this.reasonCode)
            .booleanValue(retryable)
            .booleanValue(reconciliationRequired)
            .finish()
    }

    override fun toString(): String = "GovernanceFailure(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            classification: GovernanceFailureClass,
            reasonCode: String,
            retryable: Boolean,
            reconciliationRequired: Boolean,
        ): GovernanceFailure = GovernanceFailure(
            classification, reasonCode, retryable, reconciliationRequired,
        )
    }
}
