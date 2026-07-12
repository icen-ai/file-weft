package ai.icen.fw.dev.api.security

import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

class DevSessionStore {
    private val sessions = ConcurrentHashMap<String, DevPrincipal>()
    private val random = SecureRandom()

    fun create(principal: DevPrincipal): String {
        val tokenBytes = ByteArray(TOKEN_BYTES)
        random.nextBytes(tokenBytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes).also { token -> sessions[token] = principal }
    }

    fun find(token: String): DevPrincipal? = sessions[token]

    fun remove(token: String) {
        sessions.remove(token)
    }

    private companion object {
        const val TOKEN_BYTES = 32
    }
}
