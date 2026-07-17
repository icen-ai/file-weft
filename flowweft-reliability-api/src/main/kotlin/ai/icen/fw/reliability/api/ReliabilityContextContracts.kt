package ai.icen.fw.reliability.api

class ReliabilityPrincipalRef private constructor(type: String, id: String) {
    val type: String = ReliabilityContractSupport.code(type, "Reliability principal type is invalid.")
    val id: String = ReliabilityContractSupport.text(
        id, ReliabilityContractSupport.MAX_ID_BYTES, "Reliability principal id is invalid.",
    )

    override fun equals(other: Any?): Boolean =
        this === other || other is ReliabilityPrincipalRef && type == other.type && id == other.id
    override fun hashCode(): Int = 31 * type.hashCode() + id.hashCode()
    override fun toString(): String = "ReliabilityPrincipalRef(<redacted>)"

    companion object {
        @JvmStatic fun of(type: String, id: String): ReliabilityPrincipalRef = ReliabilityPrincipalRef(type, id)
    }
}

class ReliabilityResourceRef private constructor(
    type: String,
    id: String,
    revision: String,
    digest: String,
) {
    val type: String = ReliabilityContractSupport.code(type, "Reliability resource type is invalid.")
    val id: String = ReliabilityContractSupport.text(
        id, ReliabilityContractSupport.MAX_ID_BYTES, "Reliability resource id is invalid.",
    )
    val revision: String = ReliabilityContractSupport.text(
        revision, ReliabilityContractSupport.MAX_REVISION_BYTES, "Reliability resource revision is invalid.",
    )
    val digest: String = ReliabilityContractSupport.sha256(digest, "Reliability resource digest is invalid.")
    val referenceDigest: String = ReliabilityContractSupport.digest("flowweft-reliability-api-resource-v1")
        .text(this.type)
        .text(this.id)
        .text(this.revision)
        .text(this.digest)
        .finish()

    override fun equals(other: Any?): Boolean = this === other ||
        other is ReliabilityResourceRef && type == other.type && id == other.id &&
        revision == other.revision && digest == other.digest

    override fun hashCode(): Int {
        var result = type.hashCode()
        result = 31 * result + id.hashCode()
        result = 31 * result + revision.hashCode()
        result = 31 * result + digest.hashCode()
        return result
    }

    override fun toString(): String = "ReliabilityResourceRef(<redacted>)"

    companion object {
        @JvmStatic
        fun of(type: String, id: String, revision: String, digest: String): ReliabilityResourceRef =
            ReliabilityResourceRef(type, id, revision, digest)
    }
}

enum class ReliabilityPurpose {
    EVALUATE_SLO,
    CREATE_BACKUP,
    VERIFY_BACKUP,
    RESTORE,
    RECONCILE,
    RUN_DRILL,
    DISCOVER_CAPABILITIES,
    INSPECT_DOCTOR,
}

enum class ReliabilityAction {
    EVALUATE_SLO,
    CREATE_BACKUP,
    VERIFY_BACKUP,
    RESTORE_CLEAN_TARGET,
    RECONCILE_OPERATION,
    RUN_DRILL,
    DISCOVER_CAPABILITIES,
    INSPECT_DOCTOR,
}

/** Fresh host decision for one exact tenant, principal, purpose, action and resource revision. */
class ReliabilityAuthorizationSnapshot private constructor(
    authorizationId: String,
    tenantId: String,
    val principal: ReliabilityPrincipalRef,
    val purpose: ReliabilityPurpose,
    val action: ReliabilityAction,
    val resource: ReliabilityResourceRef,
    authorityId: String,
    authorityRevision: String,
    authorizationRevision: String,
    decisionDigest: String,
    val issuedAtEpochMilli: Long,
    val expiresAtEpochMilli: Long,
) {
    val authorizationId: String = ReliabilityContractSupport.opaque(
        authorizationId, "Reliability authorization id is invalid.",
    )
    val tenantId: String = ReliabilityContractSupport.text(
        tenantId, ReliabilityContractSupport.MAX_ID_BYTES, "Reliability authorization tenant is invalid.",
    )
    val authorityId: String = ReliabilityContractSupport.code(
        authorityId, "Reliability authorization authority is invalid.",
    )
    val authorityRevision: String = ReliabilityContractSupport.text(
        authorityRevision,
        ReliabilityContractSupport.MAX_REVISION_BYTES,
        "Reliability authorization authority revision is invalid.",
    )
    val authorizationRevision: String = ReliabilityContractSupport.text(
        authorizationRevision,
        ReliabilityContractSupport.MAX_REVISION_BYTES,
        "Reliability authorization revision is invalid.",
    )
    val decisionDigest: String = ReliabilityContractSupport.sha256(
        decisionDigest, "Reliability authorization decision digest is invalid.",
    )
    val snapshotDigest: String

    init {
        require(actionAllowed(purpose, action)) { "Reliability authorization purpose and action are inconsistent." }
        require(issuedAtEpochMilli >= 0L && expiresAtEpochMilli > issuedAtEpochMilli &&
            expiresAtEpochMilli - issuedAtEpochMilli <= MAX_AUTHORIZATION_TTL_MILLIS
        ) { "Reliability authorization validity window is invalid." }
        snapshotDigest = ReliabilityContractSupport.digest("flowweft-reliability-api-authorization-v1")
            .text(this.authorizationId)
            .text(this.tenantId)
            .text(principal.type)
            .text(principal.id)
            .text(purpose.name)
            .text(action.name)
            .text(resource.referenceDigest)
            .text(this.authorityId)
            .text(this.authorityRevision)
            .text(this.authorizationRevision)
            .text(this.decisionDigest)
            .longValue(issuedAtEpochMilli)
            .longValue(expiresAtEpochMilli)
            .finish()
    }

    override fun toString(): String = "ReliabilityAuthorizationSnapshot(<redacted>)"

    companion object {
        const val MAX_AUTHORIZATION_TTL_MILLIS: Long = 10L * 60L * 1000L

        @JvmStatic
        fun of(
            authorizationId: String,
            tenantId: String,
            principal: ReliabilityPrincipalRef,
            purpose: ReliabilityPurpose,
            action: ReliabilityAction,
            resource: ReliabilityResourceRef,
            authorityId: String,
            authorityRevision: String,
            authorizationRevision: String,
            decisionDigest: String,
            issuedAtEpochMilli: Long,
            expiresAtEpochMilli: Long,
        ): ReliabilityAuthorizationSnapshot = ReliabilityAuthorizationSnapshot(
            authorizationId,
            tenantId,
            principal,
            purpose,
            action,
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

/** Trusted call context. Only a SHA-256 idempotency digest crosses this public boundary. */
class ReliabilityCallContext private constructor(
    requestId: String,
    tenantId: String,
    val principal: ReliabilityPrincipalRef,
    val purpose: ReliabilityPurpose,
    val action: ReliabilityAction,
    val resource: ReliabilityResourceRef,
    val authorization: ReliabilityAuthorizationSnapshot,
    idempotencyDigest: String,
    val requestedAtEpochMilli: Long,
    val deadlineEpochMilli: Long,
) {
    val requestId: String = ReliabilityContractSupport.opaque(requestId, "Reliability request id is invalid.")
    val tenantId: String = ReliabilityContractSupport.text(
        tenantId, ReliabilityContractSupport.MAX_ID_BYTES, "Reliability tenant is invalid.",
    )
    val idempotencyDigest: String = ReliabilityContractSupport.sha256(
        idempotencyDigest, "Reliability idempotency digest is invalid.",
    )
    val contextDigest: String

    init {
        require(actionAllowed(purpose, action)) { "Reliability call purpose and action are inconsistent." }
        require(requestedAtEpochMilli >= 0L && deadlineEpochMilli > requestedAtEpochMilli &&
            deadlineEpochMilli - requestedAtEpochMilli <= MAX_CALL_WINDOW_MILLIS
        ) { "Reliability call window is invalid." }
        require(authorization.tenantId == this.tenantId && authorization.principal == principal &&
            authorization.purpose == purpose && authorization.action == action && authorization.resource == resource
        ) { "Reliability authorization does not match the exact call binding." }
        require(authorization.issuedAtEpochMilli <= requestedAtEpochMilli &&
            requestedAtEpochMilli - authorization.issuedAtEpochMilli <= MAX_AUTHORIZATION_AGE_MILLIS &&
            deadlineEpochMilli <= authorization.expiresAtEpochMilli
        ) { "Reliability authorization is not fresh for the complete call window." }
        contextDigest = ReliabilityContractSupport.digest("flowweft-reliability-api-call-context-v1")
            .text(this.requestId)
            .text(this.tenantId)
            .text(principal.type)
            .text(principal.id)
            .text(purpose.name)
            .text(action.name)
            .text(resource.referenceDigest)
            .text(authorization.snapshotDigest)
            .text(this.idempotencyDigest)
            .longValue(requestedAtEpochMilli)
            .longValue(deadlineEpochMilli)
            .finish()
    }

    override fun toString(): String = "ReliabilityCallContext(<redacted>)"

    /** Fresh authorization is a short dispatch capability; it does not have to span async execution. */
    fun isFreshAt(atEpochMilli: Long): Boolean =
        atEpochMilli >= requestedAtEpochMilli && atEpochMilli < deadlineEpochMilli &&
            atEpochMilli >= authorization.issuedAtEpochMilli && atEpochMilli < authorization.expiresAtEpochMilli

    fun requireFresh(atEpochMilli: Long) {
        require(isFreshAt(atEpochMilli)) { "Reliability call context is not fresh at dispatch time." }
    }

    companion object {
        const val MAX_CALL_WINDOW_MILLIS: Long = 5L * 60L * 1000L
        const val MAX_AUTHORIZATION_AGE_MILLIS: Long = 60_000L

        @JvmStatic
        fun of(
            requestId: String,
            tenantId: String,
            principal: ReliabilityPrincipalRef,
            purpose: ReliabilityPurpose,
            action: ReliabilityAction,
            resource: ReliabilityResourceRef,
            authorization: ReliabilityAuthorizationSnapshot,
            idempotencyDigest: String,
            requestedAtEpochMilli: Long,
            deadlineEpochMilli: Long,
        ): ReliabilityCallContext = ReliabilityCallContext(
            requestId,
            tenantId,
            principal,
            purpose,
            action,
            resource,
            authorization,
            idempotencyDigest,
            requestedAtEpochMilli,
            deadlineEpochMilli,
        )
    }
}

/** Compare-and-set fence for the exact resource state observed before an operation. */
class ReliabilityVersionFence private constructor(
    val resource: ReliabilityResourceRef,
    val expectedVersion: Long,
    expectedStateDigest: String,
) {
    val expectedStateDigest: String = ReliabilityContractSupport.sha256(
        expectedStateDigest, "Reliability CAS state digest is invalid.",
    )
    val fenceDigest: String

    init {
        require(expectedVersion >= 0L) { "Reliability CAS version is invalid." }
        fenceDigest = ReliabilityContractSupport.digest("flowweft-reliability-api-version-fence-v1")
            .text(resource.referenceDigest)
            .longValue(expectedVersion)
            .text(this.expectedStateDigest)
            .finish()
    }

    override fun toString(): String = "ReliabilityVersionFence(<redacted>)"

    companion object {
        @JvmStatic
        fun of(
            resource: ReliabilityResourceRef,
            expectedVersion: Long,
            expectedStateDigest: String,
        ): ReliabilityVersionFence = ReliabilityVersionFence(resource, expectedVersion, expectedStateDigest)
    }
}

enum class ReliabilityFailureClass {
    DENIED,
    STALE_EVIDENCE,
    UNSUPPORTED,
    TEMPORARY_UNAVAILABLE,
    PERMANENT_FAILURE,
    OUTCOME_UNKNOWN,
}

enum class ReliabilityFailureCode {
    AUTHORIZATION_DENIED,
    DATA_MISSING,
    DATA_STALE,
    DATA_INSUFFICIENT,
    DATA_MISMATCHED,
    CAPABILITY_UNSUPPORTED,
    PROVIDER_UNAVAILABLE,
    PROVIDER_FAILED,
    CAS_CONFLICT,
    CLEAN_TARGET_PROOF_REQUIRED,
    TARGET_BINDING_MISMATCH,
    MANIFEST_INVALID,
    OPERATION_OUTCOME_UNKNOWN,
}

/** Value-free failure classification; no provider message, URL, path, secret or exception field exists. */
class ReliabilityFailure private constructor(
    val classification: ReliabilityFailureClass,
    val code: ReliabilityFailureCode,
    val retryable: Boolean,
    val reconciliationRequired: Boolean,
) {
    val failureDigest: String

    init {
        require(retryable == (classification == ReliabilityFailureClass.TEMPORARY_UNAVAILABLE)) {
            "Reliability retryability must be explicit and classification-safe."
        }
        require(reconciliationRequired == (classification == ReliabilityFailureClass.OUTCOME_UNKNOWN) &&
            (!reconciliationRequired || code == ReliabilityFailureCode.OPERATION_OUTCOME_UNKNOWN)
        ) { "Reliability reconciliation is reserved for outcome-unknown operations." }
        failureDigest = ReliabilityContractSupport.digest("flowweft-reliability-api-failure-v1")
            .text(classification.name)
            .text(code.name)
            .bool(retryable)
            .bool(reconciliationRequired)
            .finish()
    }

    override fun toString(): String = "ReliabilityFailure(classification=$classification, code=$code)"

    companion object {
        @JvmStatic
        @JvmOverloads
        fun of(
            classification: ReliabilityFailureClass,
            code: ReliabilityFailureCode,
            retryable: Boolean = false,
            reconciliationRequired: Boolean = false,
        ): ReliabilityFailure = ReliabilityFailure(classification, code, retryable, reconciliationRequired)
    }
}

internal fun actionAllowed(purpose: ReliabilityPurpose, action: ReliabilityAction): Boolean = when (purpose) {
    ReliabilityPurpose.EVALUATE_SLO -> action == ReliabilityAction.EVALUATE_SLO
    ReliabilityPurpose.CREATE_BACKUP -> action == ReliabilityAction.CREATE_BACKUP
    ReliabilityPurpose.VERIFY_BACKUP -> action == ReliabilityAction.VERIFY_BACKUP
    ReliabilityPurpose.RESTORE -> action == ReliabilityAction.RESTORE_CLEAN_TARGET
    ReliabilityPurpose.RECONCILE -> action == ReliabilityAction.RECONCILE_OPERATION
    ReliabilityPurpose.RUN_DRILL -> action == ReliabilityAction.RUN_DRILL
    ReliabilityPurpose.DISCOVER_CAPABILITIES -> action == ReliabilityAction.DISCOVER_CAPABILITIES
    ReliabilityPurpose.INSPECT_DOCTOR -> action == ReliabilityAction.INSPECT_DOCTOR
}
