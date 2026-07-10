package com.fileweft.adapter.authorization

import com.fileweft.spi.authorization.AuthorizationDecision
import com.fileweft.spi.authorization.AuthorizationProvider
import com.fileweft.spi.authorization.AuthorizationRequest

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
