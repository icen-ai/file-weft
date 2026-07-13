package ai.icen.fw.sample.host

import ai.icen.fw.spi.authorization.AuthorizationDecision
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.authorization.AuthorizationRequest

/**
 * Sample host authorization provider that permits a small set of safe actions
 * and denies everything else.
 */
class SampleAuthorizationProvider(
    private val allowedActions: Set<String> = setOf("document:read", "document:write"),
) : AuthorizationProvider {

    override fun authorize(request: AuthorizationRequest): AuthorizationDecision {
        val allowed = request.action.name in allowedActions
        return AuthorizationDecision(
            allowed = allowed,
            reason = if (allowed) "Action is in the sample allow-list." else "Action is not in the sample allow-list.",
        )
    }
}
