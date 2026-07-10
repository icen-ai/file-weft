package com.fileweft.dev.api.security

import com.fileweft.core.id.Identifier
import com.fileweft.dev.api.config.DevRole
import com.fileweft.dev.api.config.FileWeftDevProperties
import com.fileweft.spi.identity.UserIdentity

data class DevPrincipal(
    val id: Identifier,
    val username: String,
    val displayName: String,
    val tenantId: Identifier,
    val role: DevRole,
) {
    fun toUserIdentity(): UserIdentity = UserIdentity(
        id = id,
        displayName = displayName,
        attributes = mapOf("username" to username, "tenantId" to tenantId.value, "role" to role.name),
    )
}

class DevUserDirectory(properties: FileWeftDevProperties) {
    private val users: List<DevPrincipal>
    private val passwordsByUserId: Map<Identifier, String>
    private val usersByUsername: Map<String, DevPrincipal>
    private val usersById: Map<Identifier, DevPrincipal>

    init {
        require(properties.users.isNotEmpty()) { "fileweft.dev.users must contain at least one development user." }
        users = properties.users.map { configured ->
            require(configured.id.isNotBlank()) { "Development user id must not be blank." }
            require(configured.username.isNotBlank()) { "Development username must not be blank." }
            require(configured.password.isNotBlank()) { "Development user password must not be blank." }
            require(configured.displayName.isNotBlank()) { "Development user display name must not be blank." }
            require(configured.tenantId.isNotBlank()) { "Development user tenant id must not be blank." }
            DevPrincipal(
                id = Identifier(configured.id),
                username = configured.username,
                displayName = configured.displayName,
                tenantId = Identifier(configured.tenantId),
                role = configured.role,
            )
        }
        require(users.map { it.id }.distinct().size == users.size) { "Development user ids must be unique." }
        require(users.map { it.username }.distinct().size == users.size) { "Development usernames must be unique." }
        passwordsByUserId = properties.users.associate { configured -> Identifier(configured.id) to configured.password }
        usersByUsername = users.associateBy { it.username }
        usersById = users.associateBy { it.id }
    }

    fun authenticate(username: String, password: String): DevPrincipal? {
        val principal = usersByUsername[username] ?: return null
        return principal.takeIf { passwordsByUserId[principal.id] == password }
    }

    fun findById(userId: Identifier): DevPrincipal? = usersById[userId]

    fun all(): List<DevPrincipal> = users.toList()
}

object DevRequestIdentityContext {
    private val current = ThreadLocal<DevPrincipal?>()

    fun current(): DevPrincipal? = current.get()

    fun bind(principal: DevPrincipal) {
        current.set(principal)
    }

    fun clear() {
        current.remove()
    }
}
