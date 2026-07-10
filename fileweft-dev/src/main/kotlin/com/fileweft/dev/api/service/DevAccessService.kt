package com.fileweft.dev.api.service

import com.fileweft.core.id.Identifier
import com.fileweft.spi.authorization.AuthorizationAction
import com.fileweft.spi.authorization.AuthorizationEnvironment
import com.fileweft.spi.authorization.AuthorizationProvider
import com.fileweft.spi.authorization.AuthorizationRequest
import com.fileweft.spi.authorization.AuthorizationResource
import com.fileweft.spi.authorization.AuthorizationSubject
import com.fileweft.spi.identity.UserRealmProvider
import com.fileweft.spi.tenant.TenantProvider

/** Applies the same authorization SPI to development-only read and operations endpoints. */
class DevAccessService(
    private val tenants: TenantProvider,
    private val users: UserRealmProvider,
    private val authorization: AuthorizationProvider,
) {
    fun requireDocumentAction(documentId: Identifier, action: String) = requireAction(documentId, "DOCUMENT", action)

    fun requireAction(resourceId: Identifier, resourceType: String, action: String) {
        val tenant = tenants.currentTenant()
        val user = users.currentUser() ?: throw SecurityException("An authenticated development user is required.")
        val decision = authorization.authorize(
            AuthorizationRequest(
                subject = AuthorizationSubject(user.id, "USER", user.attributes),
                resource = AuthorizationResource(resourceId, resourceType, tenant.tenantId),
                action = AuthorizationAction(action),
                environment = AuthorizationEnvironment(),
            ),
        )
        if (!decision.allowed) throw SecurityException(decision.reason ?: "Access denied for $action.")
    }
}
