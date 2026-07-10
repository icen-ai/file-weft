package com.fileweft.dev.api.security

import com.fileweft.core.context.TenantContext
import com.fileweft.spi.authorization.AuthorizationDecision
import com.fileweft.spi.authorization.AuthorizationProvider
import com.fileweft.spi.authorization.AuthorizationRequest
import com.fileweft.spi.identity.UserIdentity
import com.fileweft.spi.identity.UserRealmProvider
import com.fileweft.spi.tenant.TenantProvider

class DevTenantProvider : TenantProvider {
    override fun currentTenant(): TenantContext = DevRequestIdentityContext.current()
        ?.let { principal -> TenantContext(principal.tenantId) }
        ?: throw SecurityException("An authenticated development user is required.")
}

class DevUserRealmProvider(
    private val users: DevUserDirectory,
) : UserRealmProvider {
    override fun currentUser(): UserIdentity? = DevRequestIdentityContext.current()?.toUserIdentity()

    override fun findUser(userId: com.fileweft.core.id.Identifier): UserIdentity? = users.findById(userId)?.toUserIdentity()
}

class DevAuthorizationProvider : AuthorizationProvider {
    override fun authorize(request: AuthorizationRequest): AuthorizationDecision {
        val principal = DevRequestIdentityContext.current()
            ?: return AuthorizationDecision(false, "An authenticated development user is required.")
        if (request.subject.id != principal.id || request.resource.tenantId != principal.tenantId) {
            return AuthorizationDecision(false, "Cross-user or cross-tenant access is forbidden.")
        }
        return if (principal.role.allows(request.action.name)) {
            AuthorizationDecision(true)
        } else {
            AuthorizationDecision(false, "Role ${principal.role.name} cannot perform ${request.action.name}.")
        }
    }
}

private fun com.fileweft.dev.api.config.DevRole.allows(action: String): Boolean = when (this) {
    com.fileweft.dev.api.config.DevRole.ADMIN -> true
    com.fileweft.dev.api.config.DevRole.EDITOR -> action in EDITOR_ACTIONS
    com.fileweft.dev.api.config.DevRole.REVIEWER -> action in REVIEWER_ACTIONS
    com.fileweft.dev.api.config.DevRole.VIEWER -> action in VIEWER_ACTIONS
}

private val EDITOR_ACTIONS = setOf(
    "document:read", "document:create", "document:edit", "document:rename", "document:version:add",
    "document:submit", "document:revise", "file:upload", "document:doctor",
)
private val REVIEWER_ACTIONS = setOf("document:read", "document:audit", "document:doctor")
private val VIEWER_ACTIONS = setOf("document:read")
