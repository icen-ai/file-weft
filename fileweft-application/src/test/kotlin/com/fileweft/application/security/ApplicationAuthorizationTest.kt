package com.fileweft.application.security

import com.fileweft.core.id.Identifier
import com.fileweft.spi.authorization.AuthorizationDecision
import com.fileweft.spi.authorization.AuthorizationProvider
import com.fileweft.spi.authorization.AuthorizationRequest
import com.fileweft.spi.identity.UserIdentity
import com.fileweft.spi.identity.UserRealmProvider
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

    private fun currentUser(): UserRealmProvider = object : UserRealmProvider {
        override fun currentUser(): UserIdentity = UserIdentity(Identifier("user-1"), "User One")
        override fun findUser(userId: Identifier): UserIdentity? = null
    }

    private fun noCurrentUser(): UserRealmProvider = object : UserRealmProvider {
        override fun currentUser(): UserIdentity? = null
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
