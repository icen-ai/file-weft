package ai.icen.fw.application.security

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.authorization.AuthorizationAction
import ai.icen.fw.spi.authorization.AuthorizationEnvironment
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.authorization.AuthorizationRequest
import ai.icen.fw.spi.authorization.AuthorizationResource
import ai.icen.fw.spi.authorization.AuthorizationSubject
import ai.icen.fw.spi.identity.UserIdentity
import ai.icen.fw.spi.identity.UserRealmProvider
import java.util.Collections
import java.util.LinkedHashMap

internal class ApplicationAuthorization(
    private val userRealmProvider: UserRealmProvider,
    private val authorizationProvider: AuthorizationProvider,
) {
    fun requireDocumentAction(tenantId: Identifier, documentId: Identifier, action: String): UserIdentity =
        requireAction(tenantId, documentId, "DOCUMENT", action)

    fun requireDocumentActionAs(
        tenantId: Identifier,
        documentId: Identifier,
        action: String,
        user: UserIdentity,
    ): UserIdentity = requireActionAs(tenantId, documentId, "DOCUMENT", action, user)

    fun requireCurrentUser(): UserIdentity = snapshot(
        userRealmProvider.currentUser() ?: throw ApplicationUnauthenticatedException(),
    )

    fun requireAction(
        tenantId: Identifier,
        resourceId: Identifier,
        resourceType: String,
        action: String,
    ): UserIdentity {
        val user = requireCurrentUser()
        return requireActionAs(tenantId, resourceId, resourceType, action, user)
    }

    /**
     * Reuses the trusted identity snapshot captured at the application boundary.
     * Callers must never construct [UserIdentity] from request parameters.
     */
    fun requireActionAs(
        tenantId: Identifier,
        resourceId: Identifier,
        resourceType: String,
        action: String,
        user: UserIdentity,
    ): UserIdentity {
        val snapshot = snapshot(user)
        val decision = authorizationProvider.authorize(
            AuthorizationRequest(
                subject = AuthorizationSubject(snapshot.id, "USER", snapshot.attributes),
                resource = AuthorizationResource(resourceId, resourceType, tenantId),
                action = AuthorizationAction(action),
                environment = AuthorizationEnvironment(),
            ),
        )
        if (!decision.allowed) {
            throw ApplicationForbiddenException(decision.reason ?: ApplicationForbiddenException.DEFAULT_MESSAGE)
        }
        return snapshot
    }

    private fun snapshot(user: UserIdentity): UserIdentity = UserIdentity(
        id = try {
            Identifier(validatedTrustedUserId(user.id.value))
        } catch (failure: IllegalArgumentException) {
            throw ApplicationUnauthenticatedException(INVALID_IDENTITY_MESSAGE, failure)
        },
        displayName = safeTrustedDisplayName(user.displayName),
        attributes = Collections.unmodifiableMap(LinkedHashMap(user.attributes)),
    )

    private companion object {
        const val INVALID_IDENTITY_MESSAGE: String = "The trusted current-user identity is invalid."
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
