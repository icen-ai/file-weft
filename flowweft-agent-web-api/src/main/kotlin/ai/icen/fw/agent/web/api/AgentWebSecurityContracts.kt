package ai.icen.fw.agent.web.api

import ai.icen.fw.agent.api.AgentRunContext
import ai.icen.fw.core.id.Identifier

/**
 * Verified host identity plus fresh authorization evidence. This object is supplied by a trusted
 * adapter and is never deserialized from request parameters, headers or JSON.
 */
class AgentWebTrustedContext private constructor(
    val runContext: AgentRunContext,
    authenticationId: Identifier,
    authorizationRevision: String,
    val authorizationExpiresAt: Long,
    authorizationContextDigest: String,
) {
    val tenantId: Identifier = runContext.tenantId
    val principalId: Identifier = runContext.principalId
    val principalType: String = runContext.principalType
    val requestId: Identifier = runContext.requestId
    val authenticationId: Identifier = agentWebIdentifier(authenticationId, "Agent Web authentication identifier")
    val authorizationRevision: String = agentWebText(
        authorizationRevision,
        AGENT_WEB_MAX_ID_BYTES,
        "Agent Web authorization revision",
    )
    val authorizationContextDigest: String = agentWebSha256(
        authorizationContextDigest,
        "Agent Web authorization context",
    )
    val trustedContextDigest: String

    init {
        require(authorizationExpiresAt > runContext.initiatedAt) {
            "Agent Web authorization must outlive its trusted request context."
        }
        trustedContextDigest = AgentWebDigest("flowweft.agent.web.trusted-context.v1")
            .add(tenantId.value)
            .add(principalType)
            .add(principalId.value)
            .add(requestId.value)
            .add(runContext.initiatedAt)
            .add(runContext.locale ?: "-")
            .add(this.authenticationId.value)
            .add(this.authorizationRevision)
            .add(authorizationExpiresAt)
            .add(this.authorizationContextDigest)
            .finish()
    }

    fun requireFresh(atTime: Long) {
        require(atTime >= runContext.initiatedAt && atTime < authorizationExpiresAt) {
            "Agent Web authorization is not current."
        }
    }

    override fun toString(): String = "AgentWebTrustedContext(<redacted>)"

    companion object {
        @JvmStatic
        fun authenticated(
            runContext: AgentRunContext,
            authenticationId: Identifier,
            authorizationRevision: String,
            authorizationExpiresAt: Long,
            authorizationContextDigest: String,
        ): AgentWebTrustedContext = AgentWebTrustedContext(
            runContext,
            authenticationId,
            authorizationRevision,
            authorizationExpiresAt,
            authorizationContextDigest,
        )
    }
}

fun interface AgentWebTrustedContextProvider {
    /** Returns null when no verified host identity is bound to the request. */
    fun currentContext(): AgentWebTrustedContext?
}

/** Strong optimistic-lock token shared by every Agent Web mutation. */
class AgentWebVersionTag private constructor(val expectedVersion: Long) {
    init {
        require(expectedVersion >= 0L) { "Agent Web expected version must not be negative." }
    }

    fun toHeaderValue(): String = "\"fw-agent-$expectedVersion\""

    override fun toString(): String = toHeaderValue()

    companion object {
        private val PATTERN = Regex("\\\"fw-agent-(0|[1-9][0-9]{0,18})\\\"")

        @JvmStatic
        fun parse(headerValue: String): AgentWebVersionTag {
            val match = PATTERN.matchEntire(headerValue)
                ?: throw IllegalArgumentException("If-Match must contain one strong Agent version tag.")
            val version = match.groupValues[1].toLongOrNull()
                ?: throw IllegalArgumentException("Agent Web version is outside the supported range.")
            return AgentWebVersionTag(version)
        }

        @JvmStatic
        fun of(expectedVersion: Long): AgentWebVersionTag = AgentWebVersionTag(expectedVersion)
    }
}

/** Raw keys are tenant/principal scoped and hashed by the application boundary before storage. */
class AgentWebWritePreconditions private constructor(
    idempotencyKey: String,
    val versionTag: AgentWebVersionTag,
) {
    val idempotencyKey: String = idempotencyKey.also { value ->
        require(IDEMPOTENCY_PATTERN.matches(value)) {
            "Idempotency-Key must contain 1 to 128 supported ASCII characters."
        }
    }

    override fun toString(): String = "AgentWebWritePreconditions(<redacted>)"

    companion object {
        private val IDEMPOTENCY_PATTERN = Regex("[A-Za-z0-9][A-Za-z0-9._~:-]{0,127}")

        @JvmStatic
        fun parse(idempotencyKey: String, ifMatch: String): AgentWebWritePreconditions =
            AgentWebWritePreconditions(idempotencyKey, AgentWebVersionTag.parse(ifMatch))
    }
}
