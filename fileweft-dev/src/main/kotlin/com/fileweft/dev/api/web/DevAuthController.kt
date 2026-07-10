package com.fileweft.dev.api.web

import com.fileweft.dev.api.security.DevRequestIdentityContext
import com.fileweft.dev.api.security.DevRolePolicy
import com.fileweft.dev.api.service.DevAuthService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

data class DevLoginRequest(val username: String, val password: String)
data class DevLoginResponse(
    val token: String,
    val userId: String,
    val username: String,
    val displayName: String,
    val tenantId: String,
    val role: String,
    val permissions: List<String>,
)
data class DevCurrentUserResponse(
    val userId: String,
    val username: String,
    val displayName: String,
    val tenantId: String,
    val role: String,
    val permissions: List<String>,
)

@RestController
@RequestMapping("/api/auth")
class DevAuthController(
    private val authentication: DevAuthService,
) {
    @PostMapping("/login")
    fun login(@RequestBody request: DevLoginRequest): DevLoginResponse {
        val result = authentication.login(request.username, request.password)
        return result.principal.toResponse(result.token)
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun logout(@RequestHeader("Authorization", required = false) authorization: String?) {
        authentication.logout(authorization?.removePrefix(BEARER_PREFIX).orEmpty())
    }

    @GetMapping("/me")
    fun currentUser(): DevCurrentUserResponse {
        val principal = DevRequestIdentityContext.current() ?: throw SecurityException("请先登录开发测试平台。")
        return DevCurrentUserResponse(
            principal.id.value,
            principal.username,
            principal.displayName,
            principal.tenantId.value,
            principal.role.name,
            DevRolePolicy.proofLabPermissions(principal.role),
        )
    }

    private fun com.fileweft.dev.api.security.DevPrincipal.toResponse(token: String) = DevLoginResponse(
        token,
        id.value,
        username,
        displayName,
        tenantId.value,
        role.name,
        DevRolePolicy.proofLabPermissions(role),
    )

    private companion object {
        const val BEARER_PREFIX = "Bearer "
    }
}
