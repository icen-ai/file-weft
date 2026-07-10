package com.fileweft.application.security

import com.fileweft.core.id.Identifier
import com.fileweft.spi.authorization.AuthorizationAction
import com.fileweft.spi.authorization.AuthorizationEnvironment
import com.fileweft.spi.authorization.AuthorizationProvider
import com.fileweft.spi.authorization.AuthorizationRequest
import com.fileweft.spi.authorization.AuthorizationResource
import com.fileweft.spi.authorization.AuthorizationSubject
import com.fileweft.spi.identity.UserRealmProvider

internal class ApplicationAuthorization(
    private val userRealmProvider: UserRealmProvider,
    private val authorizationProvider: AuthorizationProvider,
) {
    fun requireDocumentAction(tenantId: Identifier, documentId: Identifier, action: String) {
        val user = userRealmProvider.currentUser()
            ?: throw ApplicationAuthorizationException("A current user is required.")
        val decision = authorizationProvider.authorize(
            AuthorizationRequest(
                subject = AuthorizationSubject(user.id, "USER", user.attributes),
                resource = AuthorizationResource(documentId, "DOCUMENT", tenantId),
                action = AuthorizationAction(action),
                environment = AuthorizationEnvironment(),
            ),
        )
        if (!decision.allowed) {
            throw ApplicationAuthorizationException(decision.reason ?: "Access denied for $action.")
        }
    }
}

class ApplicationAuthorizationException(message: String) : SecurityException(message)
