package com.fileweft.dev.api.service

import com.fileweft.core.context.TenantContext
import com.fileweft.core.id.Identifier
import com.fileweft.spi.authorization.AuthorizationDecision
import com.fileweft.spi.authorization.AuthorizationProvider
import com.fileweft.spi.authorization.AuthorizationRequest
import com.fileweft.spi.identity.UserIdentity
import com.fileweft.spi.identity.UserRealmProvider
import com.fileweft.spi.tenant.TenantProvider
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DevDocumentQueryServiceAccessTest {
    @Test
    fun `rejects sync status when document read is denied before querying the database`() {
        val failure = assertFailsWith<SecurityException> {
            deniedQueries().syncStatus(Identifier("document-1"))
        }

        assertEquals("Test policy denied document:read.", failure.message)
    }

    @Test
    fun `rejects logs when document read is denied before querying the database`() {
        val failure = assertFailsWith<SecurityException> {
            deniedQueries().logs(Identifier("document-1"), 20)
        }

        assertEquals("Test policy denied document:read.", failure.message)
    }

    @Test
    fun `rejects document log limits outside the documented bounds`() {
        assertFailsWith<IllegalArgumentException> {
            deniedQueries().logs(Identifier("document-1"), 0)
        }
        assertFailsWith<IllegalArgumentException> {
            deniedQueries().logs(Identifier("document-1"), 101)
        }
    }

    @Test
    fun `projects Agent results only through the matching successful Agent task`() {
        val normalizedSql = DEV_AGENT_RESULTS_SQL.trimIndent().replace(Regex("\\s+"), " ")

        assertTrue(normalizedSql.contains("JOIN fw_task task ON task.id = result.task_id AND task.tenant_id = result.tenant_id"))
        assertTrue(normalizedSql.contains("result.tenant_id = ? AND task.business_id = ?"))
        assertTrue(normalizedSql.contains("task.task_type = 'agent.execute' AND task.task_status = 'SUCCESS'"))
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
