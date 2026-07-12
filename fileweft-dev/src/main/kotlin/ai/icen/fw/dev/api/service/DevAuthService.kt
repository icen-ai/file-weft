package ai.icen.fw.dev.api.service

import ai.icen.fw.dev.api.security.DevPrincipal
import ai.icen.fw.dev.api.security.DevSessionStore
import ai.icen.fw.dev.api.security.DevUserDirectory

data class DevLoginResult(
    val token: String,
    val principal: DevPrincipal,
)

class DevAuthService(
    private val users: DevUserDirectory,
    private val sessions: DevSessionStore,
) {
    fun login(username: String, password: String): DevLoginResult {
        require(username.isNotBlank()) { "Username must not be blank." }
        require(password.isNotBlank()) { "Password must not be blank." }
        val principal = users.authenticate(username, password) ?: throw SecurityException("用户名或密码不正确。")
        return DevLoginResult(sessions.create(principal), principal)
    }

    fun logout(token: String) {
        if (token.isNotBlank()) sessions.remove(token)
    }
}
