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
        requireAction(tenantId, documentId, "DOCUMENT", action)
    }

    fun requireAction(tenantId: Identifier, resourceId: Identifier, resourceType: String, action: String) {
        val user = userRealmProvider.currentUser()
            ?: throw ApplicationUnauthenticatedException()
        val decision = authorizationProvider.authorize(
            AuthorizationRequest(
                subject = AuthorizationSubject(user.id, "USER", user.attributes),
                resource = AuthorizationResource(resourceId, resourceType, tenantId),
                action = AuthorizationAction(action),
                environment = AuthorizationEnvironment(),
            ),
        )
        if (!decision.allowed) {
            throw ApplicationForbiddenException(decision.reason ?: ApplicationForbiddenException.DEFAULT_MESSAGE)
        }
    }
}

/**
 * Compatibility base type for all application-level authorization failures.
 *
 * HTTP adapters must classify the concrete child type rather than inspecting
 * a human-readable message. Existing consumers catching this base type remain
 * source and binary compatible.
 */
open class ApplicationAuthorizationException @JvmOverloads constructor(
    message: String,
    cause: Throwable? = null,
) : SecurityException(message, cause)

/** No trusted current user was available to authenticate the operation. */
class ApplicationUnauthenticatedException @JvmOverloads constructor(
    message: String = DEFAULT_MESSAGE,
    cause: Throwable? = null,
) : ApplicationAuthorizationException(message, cause) {
    companion object {
        const val DEFAULT_MESSAGE: String = "A current user is required."
    }
}

/** A trusted user was present but the authorization provider denied access. */
class ApplicationForbiddenException @JvmOverloads constructor(
    message: String = DEFAULT_MESSAGE,
    cause: Throwable? = null,
) : ApplicationAuthorizationException(message, cause) {
    companion object {
        const val DEFAULT_MESSAGE: String = "Access denied."
    }
}
