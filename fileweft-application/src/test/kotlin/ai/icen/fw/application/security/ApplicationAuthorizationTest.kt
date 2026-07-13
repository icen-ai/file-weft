package ai.icen.fw.application.security

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.authorization.AuthorizationDecision
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.authorization.AuthorizationRequest
import ai.icen.fw.spi.identity.UserIdentity
import ai.icen.fw.spi.identity.UserRealmProvider
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class ApplicationAuthorizationTest {
    @Test
    fun `throws unauthenticated when no trusted current user exists without consulting policy`() {
        val policy = RecordingAuthorization(AuthorizationDecision(true))
        val authorization = ApplicationAuthorization(noCurrentUser(), policy)

        val failure = assertThrows<ApplicationAuthorizationException> {
            authorization.requireDocumentAction(Identifier("tenant-1"), Identifier("document-1"), "document:read")
        }

        assertIs<ApplicationUnauthenticatedException>(failure)
        assertNull(policy.lastRequest)
    }

    @Test
    fun `throws forbidden for a trusted user regardless of the denial message`() {
        listOf(null, "missing document grant").forEach { reason ->
            val policy = RecordingAuthorization(AuthorizationDecision(false, reason))
            val authorization = ApplicationAuthorization(currentUser(), policy)

            val failure = assertThrows<ApplicationAuthorizationException> {
                authorization.requireDocumentAction(Identifier("tenant-1"), Identifier("document-1"), "document:read")
            }

            assertIs<ApplicationForbiddenException>(failure)
            assertEquals(Identifier("user-1"), policy.lastRequest?.subject?.id)
            assertEquals(Identifier("tenant-1"), policy.lastRequest?.resource?.tenantId)
            assertEquals("document:read", policy.lastRequest?.action?.name)
        }
    }

    @Test
    fun `allows a trusted user when policy grants access`() {
        val policy = RecordingAuthorization(AuthorizationDecision(true))
        val authorization = ApplicationAuthorization(currentUser(), policy)

        authorization.requireAction(
            tenantId = Identifier("tenant-1"),
            resourceId = Identifier("catalog-1"),
            resourceType = "DOCUMENT_CATALOG",
            action = "document:read",
        )

        assertEquals("DOCUMENT_CATALOG", policy.lastRequest?.resource?.type)
    }

    @Test
    fun `accepts the full persisted host identity width without normalizing it`() {
        val userId = "用户/" + "x".repeat(253)
        val policy = RecordingAuthorization(AuthorizationDecision(true))
        val authorization = ApplicationAuthorization(users(UserIdentity(Identifier(userId), "外部审批者")), policy)

        val snapshot = authorization.requireDocumentAction(
            Identifier("tenant-1"), Identifier("document-1"), "document:audit",
        )

        assertEquals(256, snapshot.id.value.length)
        assertEquals(userId, snapshot.id.value)
        assertEquals("外部审批者", snapshot.displayName)
        assertEquals(userId, policy.lastRequest?.subject?.id?.value)
    }

    @Test
    fun `rejects invalid host identities before consulting authorization policy`() {
        val invalidIds = listOf(
            "x".repeat(257),
            "\u00a0leading",
            "trailing\u3000",
            "control\u0001",
            "format\u200b",
            "broken\ud800",
        )

        invalidIds.forEach { userId ->
            val policy = RecordingAuthorization(AuthorizationDecision(true))
            val authorization = ApplicationAuthorization(users(UserIdentity(Identifier(userId))), policy)

            val failure = assertThrows<ApplicationUnauthenticatedException>(userId) {
                authorization.requireCurrentUser()
            }

            assertEquals("The trusted current-user identity is invalid.", failure.message)
            assertNull(policy.lastRequest)
        }
    }

    @Test
    fun `omits unsafe optional display names while preserving the trusted user id`() {
        listOf(" ", "x".repeat(257), "unsafe\nname", "format\u200bname", "broken\ud800").forEach { displayName ->
            val authorization = ApplicationAuthorization(
                users(UserIdentity(Identifier("user-safe"), displayName)),
                RecordingAuthorization(AuthorizationDecision(true)),
            )

            assertNull(authorization.requireCurrentUser().displayName, displayName)
        }
    }

    private fun currentUser(): UserRealmProvider = object : UserRealmProvider {
        override fun currentUser(): UserIdentity = UserIdentity(Identifier("user-1"), "User One")
        override fun findUser(userId: Identifier): UserIdentity? = null
    }

    private fun noCurrentUser(): UserRealmProvider = object : UserRealmProvider {
        override fun currentUser(): UserIdentity? = null
        override fun findUser(userId: Identifier): UserIdentity? = null
    }

    private fun users(user: UserIdentity): UserRealmProvider = object : UserRealmProvider {
        override fun currentUser(): UserIdentity = user
        override fun findUser(userId: Identifier): UserIdentity? = null
    }

    private class RecordingAuthorization(
        private val decision: AuthorizationDecision,
    ) : AuthorizationProvider {
        var lastRequest: AuthorizationRequest? = null

        override fun authorize(request: AuthorizationRequest): AuthorizationDecision {
            lastRequest = request
            return decision
        }
    }
}
