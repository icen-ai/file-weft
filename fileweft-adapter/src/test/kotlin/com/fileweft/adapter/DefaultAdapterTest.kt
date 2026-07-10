package com.fileweft.adapter

import com.fileweft.adapter.authorization.DefaultAuthorizationProvider
import com.fileweft.adapter.id.UuidIdentifierGenerator
import com.fileweft.adapter.identity.DefaultUserRealmProvider
import com.fileweft.core.id.Identifier
import com.fileweft.spi.authorization.AuthorizationAction
import com.fileweft.spi.authorization.AuthorizationEnvironment
import com.fileweft.spi.authorization.AuthorizationRequest
import com.fileweft.spi.authorization.AuthorizationResource
import com.fileweft.spi.authorization.AuthorizationSubject
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class DefaultAdapterTest {
    @Test
    fun `default user provider never invents an identity`() {
        val provider = DefaultUserRealmProvider()

        assertNull(provider.currentUser())
        assertNull(provider.findUser(Identifier("user-1")))
    }

    @Test
    fun `default authorization provider fails closed with an actionable reason`() {
        val decision = DefaultAuthorizationProvider().authorize(
            AuthorizationRequest(
                subject = AuthorizationSubject(Identifier("user-1"), "USER"),
                resource = AuthorizationResource(Identifier("document-1"), "DOCUMENT", Identifier("tenant-1")),
                action = AuthorizationAction("document:read"),
                environment = AuthorizationEnvironment(),
            ),
        )

        assertFalse(decision.allowed)
        assertEquals(DefaultAuthorizationProvider.REASON, decision.reason)
    }

    @Test
    fun `default identifier generator returns distinct non blank identifiers`() {
        val generator = UuidIdentifierGenerator()

        val first = generator.nextId()
        val second = generator.nextId()

        assertEquals(32, first.value.length)
        assertEquals(32, second.value.length)
        kotlin.test.assertNotEquals(first, second)
    }
}
