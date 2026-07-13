package ai.icen.fw.dev.api.service

import ai.icen.fw.core.context.TenantContext
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.authorization.AuthorizationDecision
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.authorization.AuthorizationRequest
import ai.icen.fw.spi.identity.UserIdentity
import ai.icen.fw.spi.identity.UserRealmProvider
import ai.icen.fw.spi.tenant.TenantProvider
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DevDocumentQueryServiceAccessTest {
    @Test
    fun `rejects sync status when document read is denied before querying the database`() {
        val failure = assertFailsWith<SecurityException> {
            deniedQueries().syncStatus(Identifier("document-1"))
        }

        assertEquals("Test policy denied document:read.", failure.message)
    }

    private fun deniedQueries(): DevDocumentQueryService {
        val tenants = object : TenantProvider {
            override fun currentTenant(): TenantContext = TenantContext(Identifier("alpha"))
        }
        val users = object : UserRealmProvider {
            override fun currentUser(): UserIdentity = UserIdentity(Identifier("alpha-denied"), "Denied user")

            override fun findUser(userId: Identifier): UserIdentity? = null
        }
        val denyRead = object : AuthorizationProvider {
            override fun authorize(request: AuthorizationRequest): AuthorizationDecision =
                AuthorizationDecision(false, "Test policy denied document:read.")
        }
        return DevDocumentQueryService(JdbcTemplate(), DevAccessService(tenants, users, denyRead), tenants)
    }
}
