package ai.icen.fw.adapter.authorization

import ai.icen.fw.spi.authorization.AuthorizationDecision
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.authorization.AuthorizationRequest

/**
 * Fail-closed authorization fallback. Applications must provide their own
 * [AuthorizationProvider] before any protected FileWeft operation can succeed.
 */
class DefaultAuthorizationProvider : AuthorizationProvider {
    override fun authorize(request: AuthorizationRequest): AuthorizationDecision =
        AuthorizationDecision(false, REASON)

    companion object {
        const val REASON = "No AuthorizationProvider has been configured."
    }
}
