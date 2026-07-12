package ai.icen.fw.dev.api.security

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.dev.api.config.DevRole
import ai.icen.fw.dev.api.config.FileWeftDevProperties
import ai.icen.fw.dev.api.service.DevAuthService
import ai.icen.fw.spi.authorization.AuthorizationAction
import ai.icen.fw.spi.authorization.AuthorizationEnvironment
import ai.icen.fw.spi.authorization.AuthorizationRequest
import ai.icen.fw.spi.authorization.AuthorizationResource
import ai.icen.fw.spi.authorization.AuthorizationSubject
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DevSecurityTest {
    @AfterEach
    fun clearContext() {
        DevRequestIdentityContext.clear()
    }

    @Test
    fun `authenticates configured users with opaque session tokens`() {
        val sessions = DevSessionStore()
        val authentication = DevAuthService(directory(), sessions)

        val result = authentication.login("editor@alpha", "dev-editor")

        assertEquals("alpha-editor", result.principal.id.value)
        assertNotNull(sessions.find(result.token))
        authentication.logout(result.token)
        assertNull(sessions.find(result.token))
    }

    @Test
    fun `rejects invalid development credentials`() {
        val authentication = DevAuthService(directory(), DevSessionStore())

        assertThrows(SecurityException::class.java) {
            authentication.login("editor@alpha", "incorrect")
        }
    }

    @Test
    fun `editor can change documents only in their own tenant`() {
        val editor = directory().authenticate("editor@alpha", "dev-editor")!!
        DevRequestIdentityContext.bind(editor)
        val provider = DevAuthorizationProvider()

        assertTrue(provider.authorize(request("alpha", "document:create")).allowed)
        assertFalse(provider.authorize(request("beta", "document:create")).allowed)
    }

    @Test
    fun `equivalent roles remain isolated when their tenant ids differ`() {
        val betaEditor = directory().authenticate("editor@beta", "dev-editor")!!
        DevRequestIdentityContext.bind(betaEditor)
        val provider = DevAuthorizationProvider()

        assertTrue(provider.authorize(request("beta", "document:create")).allowed)
        assertFalse(provider.authorize(request("alpha", "document:create")).allowed)
    }

    @Test
    fun `reviewer cannot edit a document`() {
        val reviewer = directory().authenticate("reviewer@alpha", "dev-reviewer")!!
        DevRequestIdentityContext.bind(reviewer)

        assertFalse(DevAuthorizationProvider().authorize(request("alpha", "document:edit")).allowed)
        assertTrue(DevAuthorizationProvider().authorize(request("alpha", "document:audit")).allowed)
    }

    @Test
    fun `exposes only proof lab actions granted to each role`() {
        assertTrue(DevRolePolicy.proofLabPermissions(DevRole.EDITOR).contains("document:create"))
        assertTrue(DevRolePolicy.proofLabPermissions(DevRole.EDITOR).contains("document:edit"))
        assertTrue(DevRolePolicy.proofLabPermissions(DevRole.EDITOR).contains("document:doctor"))
        assertFalse(DevRolePolicy.proofLabPermissions(DevRole.EDITOR).contains("document:audit"))
        assertTrue(DevRolePolicy.proofLabPermissions(DevRole.REVIEWER).contains("document:audit"))
        assertTrue(DevRolePolicy.proofLabPermissions(DevRole.REVIEWER).contains("document:doctor"))
        assertTrue(DevRolePolicy.proofLabPermissions(DevRole.REVIEWER).contains("agent:suggestion:read"))
        assertFalse(DevRolePolicy.proofLabPermissions(DevRole.REVIEWER).contains("document:create"))
        assertEquals(listOf("document:read", "document:download"), DevRolePolicy.proofLabPermissions(DevRole.VIEWER))
        assertTrue(DevRolePolicy.proofLabPermissions(DevRole.ADMIN).contains("system:outbox:process"))
        assertTrue(DevRolePolicy.proofLabPermissions(DevRole.ADMIN).contains("system:doctor:read"))
        assertTrue(DevRolePolicy.proofLabPermissions(DevRole.ADMIN).contains(DevRolePolicy.DOCUMENT_DELIVERY_READ_ACTION))
        assertFalse(DevRolePolicy.proofLabPermissions(DevRole.EDITOR).contains(DevRolePolicy.DOCUMENT_DELIVERY_READ_ACTION))
        assertFalse(DevRolePolicy.proofLabPermissions(DevRole.REVIEWER).contains(DevRolePolicy.DOCUMENT_DELIVERY_READ_ACTION))
        assertFalse(DevRolePolicy.proofLabPermissions(DevRole.VIEWER).contains(DevRolePolicy.DOCUMENT_DELIVERY_READ_ACTION))
        assertFalse(DevRolePolicy.proofLabPermissions(DevRole.EDITOR).contains("system:doctor:read"))
        assertFalse(DevRolePolicy.proofLabPermissions(DevRole.REVIEWER).contains("system:doctor:read"))
    }

    private fun request(tenantId: String, action: String) = AuthorizationRequest(
        subject = AuthorizationSubject(DevRequestIdentityContext.current()!!.id, "USER"),
        resource = AuthorizationResource(Identifier("document-1"), "DOCUMENT", Identifier(tenantId)),
        action = AuthorizationAction(action),
        environment = AuthorizationEnvironment(),
    )

    private fun directory(): DevUserDirectory = DevUserDirectory(FileWeftDevProperties().apply {
        users += user("alpha-admin", "admin@alpha", "dev-admin", "alpha", DevRole.ADMIN)
        users += user("alpha-editor", "editor@alpha", "dev-editor", "alpha", DevRole.EDITOR)
        users += user("alpha-reviewer", "reviewer@alpha", "dev-reviewer", "alpha", DevRole.REVIEWER)
        users += user("beta-editor", "editor@beta", "dev-editor", "beta", DevRole.EDITOR)
    })

    private fun user(id: String, username: String, password: String, tenantId: String, role: DevRole) = FileWeftDevProperties.User().apply {
        this.id = id
        this.username = username
        this.password = password
        this.displayName = username
        this.tenantId = tenantId
        this.role = role
    }

    private fun assertEquals(expected: Any?, actual: Any?) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual)
    }
}
