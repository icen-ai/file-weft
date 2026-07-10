package com.fileweft.application.agent

import com.fileweft.application.audit.AuditTrail
import com.fileweft.application.transaction.ApplicationTransaction
import com.fileweft.core.context.TenantContext
import com.fileweft.core.id.Identifier
import com.fileweft.core.id.IdentifierGenerator
import com.fileweft.domain.audit.AuditRecord
import com.fileweft.domain.audit.AuditRecordRepository
import com.fileweft.spi.ai.AgentCapability
import com.fileweft.spi.ai.AgentExecutionStatus
import com.fileweft.spi.ai.AgentResult
import com.fileweft.spi.ai.AgentSuggestion
import com.fileweft.spi.authorization.AuthorizationDecision
import com.fileweft.spi.authorization.AuthorizationProvider
import com.fileweft.spi.authorization.AuthorizationRequest
import com.fileweft.spi.identity.UserIdentity
import com.fileweft.spi.identity.UserRealmProvider
import com.fileweft.spi.tenant.TenantProvider
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ConfirmAgentSuggestionServiceTest {
    @Test
    fun `requires authorization then persists tenant scoped consent and audit evidence`() {
        val repository = Results(result())
        val audits = mutableListOf<AuditRecord>()
        val service = service(repository, true, audits)

        val confirmation = service.confirm(Identifier("task-1"), Identifier("suggestion-1"))

        assertEquals("operator-1", confirmation.confirmedBy.value)
        assertEquals(1, repository.confirmations.size)
        assertEquals(ConfirmAgentSuggestionService.CONFIRM_ACTION, audits.single().action)
        assertEquals("suggestion-1", audits.single().details["suggestionId"])
    }

    @Test
    fun `does not reveal or confirm an unavailable result when authorization is denied`() {
        val repository = Results(result())
        val service = service(repository, false, mutableListOf())

        assertFailsWith<SecurityException> { service.confirm(Identifier("task-1"), Identifier("suggestion-1")) }
        assertEquals(0, repository.confirmations.size)
    }

    private fun service(repository: Results, allowed: Boolean, audits: MutableList<AuditRecord>): ConfirmAgentSuggestionService =
        ConfirmAgentSuggestionService(
            tenantProvider = object : TenantProvider {
                override fun currentTenant(): TenantContext = TenantContext(Identifier("tenant-1"))
            },
            userRealmProvider = object : UserRealmProvider {
                override fun currentUser(): UserIdentity = UserIdentity(Identifier("operator-1"), "Operator 1")
                override fun findUser(userId: Identifier): UserIdentity? = null
            },
            authorizationProvider = object : AuthorizationProvider {
                override fun authorize(request: AuthorizationRequest): AuthorizationDecision = AuthorizationDecision(allowed)
            },
            results = repository,
            identifiers = object : IdentifierGenerator {
                override fun nextId(): Identifier = Identifier("confirmation-1")
            },
            transaction = DirectTransaction,
            clock = fixedClock(),
            auditTrail = AuditTrail(object : AuditRecordRepository {
                override fun append(record: AuditRecord) { audits += record }
                override fun findByResource(tenantId: Identifier, resourceType: String, resourceId: Identifier, limit: Int): List<AuditRecord> = emptyList()
            }, object : IdentifierGenerator {
                override fun nextId(): Identifier = Identifier("audit-1")
            }, fixedClock()),
        )

    private fun result() = PersistedAgentResult(
        Identifier("result-1"), Identifier("tenant-1"), Identifier("task-1"), AgentCapability.CLASSIFICATION,
        Identifier("event-1"), "document.created",
        AgentResult(Identifier("task-1"), AgentExecutionStatus.SUCCEEDED, listOf(AgentSuggestion(Identifier("suggestion-1"), "document.classification")), completedAt = 1),
        1,
    )

    private object DirectTransaction : ApplicationTransaction {
        override fun <T> execute(action: () -> T): T = action()
    }

    private class Results(private val result: PersistedAgentResult) : AgentResultRepository {
        val confirmations = mutableListOf<PersistedAgentSuggestionConfirmation>()
        override fun save(result: PersistedAgentResult) = Unit
        override fun findByTask(tenantId: Identifier, taskId: Identifier): PersistedAgentResult? = result.takeIf {
            it.tenantId == tenantId && it.taskId == taskId
        }
        override fun saveConfirmation(confirmation: PersistedAgentSuggestionConfirmation): PersistedAgentSuggestionConfirmation =
            confirmations.firstOrNull { it.taskId == confirmation.taskId && it.suggestionId == confirmation.suggestionId } ?: confirmation.also { confirmations += it }
        override fun findConfirmations(tenantId: Identifier, taskId: Identifier): List<PersistedAgentSuggestionConfirmation> = emptyList()
    }

    private fun fixedClock(): Clock = Clock.fixed(Instant.ofEpochMilli(10), ZoneOffset.UTC)
}
