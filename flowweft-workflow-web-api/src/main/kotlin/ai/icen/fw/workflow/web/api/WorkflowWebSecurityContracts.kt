package ai.icen.fw.workflow.web.api

/**
 * Authenticated host context. It is never deserialized from a public request body or request
 * header; an adapter obtains it from [WorkflowWebTrustedContextProvider].
 */
class WorkflowWebTrustedContext private constructor(
    tenantId: String,
    principalType: String,
    principalId: String,
    authenticationId: String,
    authorizationContextDigest: String,
    traceId: String?,
) {
    val tenantId: String = requiredText(tenantId, "Trusted tenant id", 512)
    val principalType: String = requiredText(principalType, "Trusted principal type", 64)
    val principalId: String = requiredText(principalId, "Trusted principal id", 512)
    val authenticationId: String = requiredText(authenticationId, "Trusted authentication id", 512)
    val authorizationContextDigest: String = sha256(
        authorizationContextDigest,
        "Trusted authorization context digest",
    )
    val traceId: String? = optionalText(traceId, "Trusted trace id", 256)

    override fun toString(): String = "WorkflowWebTrustedContext(<redacted>)"

    companion object {
        /** Host authentication adapters are the only intended callers of this factory. */
        @JvmStatic
        @JvmOverloads
        fun authenticated(
            tenantId: String,
            principalType: String,
            principalId: String,
            authenticationId: String,
            authorizationContextDigest: String,
            traceId: String? = null,
        ): WorkflowWebTrustedContext = WorkflowWebTrustedContext(
            tenantId,
            principalType,
            principalId,
            authenticationId,
            authorizationContextDigest,
            traceId,
        )
    }
}

fun interface WorkflowWebTrustedContextProvider {
    /** Returns null when no verified host identity is bound to the request. */
    fun currentContext(): WorkflowWebTrustedContext?
}

/** Strong optimistic-lock token used by all public Workflow mutations. */
class WorkflowWebVersionTag private constructor(val expectedVersion: Long) {
    init {
        require(expectedVersion >= 0L) { "Workflow expected version must not be negative." }
    }

    fun toHeaderValue(): String = "\"fw-$expectedVersion\""

    override fun toString(): String = toHeaderValue()

    companion object {
        private val PATTERN = Regex("\\\"fw-(0|[1-9][0-9]{0,18})\\\"")

        @JvmStatic
        fun parse(headerValue: String): WorkflowWebVersionTag {
            val match = PATTERN.matchEntire(headerValue)
                ?: throw IllegalArgumentException("If-Match must contain one strong FlowWeft version tag.")
            val version = match.groupValues[1].toLongOrNull()
                ?: throw IllegalArgumentException("If-Match version is outside the supported range.")
            return WorkflowWebVersionTag(version)
        }

        @JvmStatic
        fun of(expectedVersion: Long): WorkflowWebVersionTag = WorkflowWebVersionTag(expectedVersion)
    }
}

/**
 * Validated mutation headers. The raw idempotency key must be tenant-scoped and hashed by the
 * application boundary before persistence; [toString] never exposes it.
 */
class WorkflowWebWritePreconditions private constructor(
    idempotencyKey: String,
    val versionTag: WorkflowWebVersionTag,
) {
    val idempotencyKey: String = idempotencyKey.also {
        require(IDEMPOTENCY_PATTERN.matches(it)) {
            "Idempotency-Key must contain 1 to 128 supported ASCII characters."
        }
    }

    override fun toString(): String = "WorkflowWebWritePreconditions(<redacted>)"

    companion object {
        private val IDEMPOTENCY_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._~:-]{0,127}")

        /** Controller adapters call this only after enforcing exactly one value for each header. */
        @JvmStatic
        fun parse(idempotencyKey: String, ifMatch: String): WorkflowWebWritePreconditions =
            WorkflowWebWritePreconditions(idempotencyKey, WorkflowWebVersionTag.parse(ifMatch))
    }
}
