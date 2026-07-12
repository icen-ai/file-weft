package ai.icen.fw.dev.api.service

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.authorization.AuthorizationAction
import ai.icen.fw.spi.authorization.AuthorizationDecision
import ai.icen.fw.spi.authorization.AuthorizationEnvironment
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.authorization.AuthorizationRequest
import ai.icen.fw.spi.authorization.AuthorizationResource
import ai.icen.fw.spi.authorization.AuthorizationSubject
import ai.icen.fw.spi.identity.UserRealmProvider
import ai.icen.fw.spi.tenant.TenantProvider

/** Applies the same authorization SPI to development-only read and operations endpoints. */
class DevAccessService(
    private val tenants: TenantProvider,
    private val users: UserRealmProvider,
    private val authorization: AuthorizationProvider,
) {
    fun requireDocumentAction(documentId: Identifier, action: String) = requireAction(documentId, "DOCUMENT", action)

    fun allowsDocumentAction(documentId: Identifier, action: String): Boolean = allowsAction(documentId, "DOCUMENT", action)

    fun requireAction(resourceId: Identifier, resourceType: String, action: String) {
        val decision = authorize(resourceId, resourceType, action)
        if (!decision.allowed) throw SecurityException(decision.reason ?: "Access denied for $action.")
    }

    private fun allowsAction(resourceId: Identifier, resourceType: String, action: String): Boolean =
        authorize(resourceId, resourceType, action).allowed

    private fun authorize(resourceId: Identifier, resourceType: String, action: String): AuthorizationDecision {
        val tenant = tenants.currentTenant()
        val user = users.currentUser() ?: throw SecurityException("An authenticated development user is required.")
        return authorization.authorize(
            AuthorizationRequest(
                subject = AuthorizationSubject(user.id, "USER", user.attributes),
                resource = AuthorizationResource(resourceId, resourceType, tenant.tenantId),
                action = AuthorizationAction(action),
                environment = AuthorizationEnvironment(),
            ),
        )
    }
}
