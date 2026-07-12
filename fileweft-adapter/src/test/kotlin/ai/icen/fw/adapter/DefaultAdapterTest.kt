package ai.icen.fw.adapter

import ai.icen.fw.adapter.authorization.DefaultAuthorizationProvider
import ai.icen.fw.adapter.id.UuidIdentifierGenerator
import ai.icen.fw.adapter.observability.NoOpFileWeftMetrics
import ai.icen.fw.adapter.identity.DefaultUserRealmProvider
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.authorization.AuthorizationAction
import ai.icen.fw.spi.authorization.AuthorizationEnvironment
import ai.icen.fw.spi.authorization.AuthorizationRequest
import ai.icen.fw.spi.observability.FileWeftMetric
import ai.icen.fw.spi.authorization.AuthorizationResource
import ai.icen.fw.spi.authorization.AuthorizationSubject
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

    @Test
    fun `default metrics implementation has no business side effects`() {
        NoOpFileWeftMetrics().increment(FileWeftMetric.UPLOAD_COUNT, mapOf("tenantId" to "tenant-1"))
    }
}
