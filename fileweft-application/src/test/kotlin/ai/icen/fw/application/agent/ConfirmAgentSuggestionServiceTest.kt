package ai.icen.fw.application.agent

import ai.icen.fw.application.audit.AuditTrail
import ai.icen.fw.application.task.BackgroundTask
import ai.icen.fw.application.task.BackgroundTaskStatus
import ai.icen.fw.application.task.TaskRepository
import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.core.context.TenantContext
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.core.id.IdentifierGenerator
import ai.icen.fw.domain.audit.AuditRecord
import ai.icen.fw.domain.audit.AuditRecordRepository
import ai.icen.fw.spi.ai.AgentCapability
import ai.icen.fw.spi.ai.AgentExecutionStatus
import ai.icen.fw.spi.ai.AgentResult
import ai.icen.fw.spi.ai.AgentSuggestion
import ai.icen.fw.spi.authorization.AuthorizationDecision
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.authorization.AuthorizationRequest
import ai.icen.fw.spi.identity.UserIdentity
import ai.icen.fw.spi.identity.UserRealmProvider
import ai.icen.fw.spi.tenant.TenantProvider
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ConfirmAgentSuggestionServiceTest {
    @Test
    fun `legacy constructor retains tenant scoped confirmation and audit behavior`() {
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
        val tasks = Tasks(task(BackgroundTaskStatus.SUCCESS))
        val service = service(repository, false, mutableListOf(), tasks = tasks)

        assertFailsWith<SecurityException> {
            service.confirm(Identifier("task-1"), Identifier("suggestion-1"))
        }
        assertEquals(0, tasks.findCalls)
        assertEquals(0, repository.confirmations.size)
    }

    @Test
    fun `uses one trusted identity snapshot for authorization confirmation and audit`() {
        var identityReads = 0
        val changingUsers = object : UserRealmProvider {
            override fun currentUser(): UserIdentity {
                identityReads++
                return if (identityReads == 1) {
                    UserIdentity(Identifier("operator-1"), "Operator One")
                } else {
                    UserIdentity(Identifier("operator-2"), "Operator Two")
                }
            }

            override fun findUser(userId: Identifier): UserIdentity? = null
        }
        val requests = mutableListOf<AuthorizationRequest>()
        val audits = mutableListOf<AuditRecord>()
        val repository = Results(result())
        val service = service(
            repository = repository,
            allowed = true,
            audits = audits,
            tasks = Tasks(task(BackgroundTaskStatus.SUCCESS)),
            users = changingUsers,
            authorizationRequests = requests,
        )

        val confirmation = service.confirm(Identifier("task-1"), Identifier("suggestion-1"))

        assertEquals(1, identityReads)
        assertEquals("operator-1", requests.single().subject.id.value)
        assertEquals("operator-1", confirmation.confirmedBy.value)
        assertEquals("operator-1", audits.single().operatorId?.value)
        assertEquals("Operator One", audits.single().operatorName)
    }

    @Test
    fun `strong constructor verifies success and confirms in the same transaction`() {
        val transaction = TrackingTransaction()
        val repository = Results(result(), transaction)
        val tasks = Tasks(task(BackgroundTaskStatus.SUCCESS), transaction)
        val service = service(
            repository = repository,
            allowed = true,
            audits = mutableListOf(),
            transaction = transaction,
            tasks = tasks,
        )

        val confirmation = service.confirm(Identifier("task-1"), Identifier("suggestion-1"))

        assertEquals("operator-1", confirmation.confirmedBy.value)
        assertEquals(1, transaction.executions)
        assertEquals(1, tasks.findCalls)
        assertEquals(1, repository.findCalls)
        assertEquals(1, repository.confirmations.size)
    }

    @Test
    fun `strong constructor rejects running retry and failed task states`() {
        listOf(
            BackgroundTaskStatus.RUNNING,
            BackgroundTaskStatus.RETRY,
            BackgroundTaskStatus.FAILED,
        ).forEach { status ->
            val repository = Results(result())
            val service = service(
                repository,
                true,
                mutableListOf(),
                tasks = Tasks(task(status)),
            )

            assertFailsWith<IllegalStateException>(status.name) {
                service.confirm(Identifier("task-1"), Identifier("suggestion-1"))
            }
            assertEquals(0, repository.findCalls, status.name)
            assertTrue(repository.confirmations.isEmpty(), status.name)
        }
    }

    @Test
    fun `strong constructor rejects missing and cross tenant task state`() {
        listOf(
            "missing" to Tasks(null),
            "cross tenant" to Tasks(task(BackgroundTaskStatus.SUCCESS, tenant = "tenant-2")),
        ).forEach { (name, tasks) ->
            val repository = Results(result())
            val service = service(repository, true, mutableListOf(), tasks = tasks)

            assertFailsWith<NoSuchElementException>(name) {
                service.confirm(Identifier("task-1"), Identifier("suggestion-1"))
            }
            assertEquals(0, repository.findCalls, name)
            assertTrue(repository.confirmations.isEmpty(), name)
        }
    }

    @Test
    fun `retains legacy Kotlin default constructor ABI and exposes strong constructor`() {
        val legacyParameters = arrayOf(
            TenantProvider::class.java,
            UserRealmProvider::class.java,
            AuthorizationProvider::class.java,
            AgentResultRepository::class.java,
            IdentifierGenerator::class.java,
            ApplicationTransaction::class.java,
            Clock::class.java,
            AuditTrail::class.java,
        )
        assertTrue(ConfirmAgentSuggestionService::class.java.getConstructor(*legacyParameters) != null)
        val strongParameters = legacyParameters + TaskRepository::class.java
        assertTrue(
            ConfirmAgentSuggestionService::class.java.getConstructor(*strongParameters) != null,
        )

        val syntheticParameters = legacyParameters + arrayOf(
            Int::class.javaPrimitiveType!!,
            Class.forName("kotlin.jvm.internal.DefaultConstructorMarker"),
        )
        assertTrue(
            ConfirmAgentSuggestionService::class.java.declaredConstructors.any { constructor ->
                constructor.parameterTypes.contentEquals(syntheticParameters)
            },
            "The original Kotlin default-audit constructor ABI must remain available.",
        )
    }

    private fun service(
        repository: Results,
        allowed: Boolean,
        audits: MutableList<AuditRecord>,
        transaction: ApplicationTransaction = DirectTransaction,
        tasks: TaskRepository? = null,
        users: UserRealmProvider = fixedUsers(),
        authorizationRequests: MutableList<AuthorizationRequest>? = null,
    ): ConfirmAgentSuggestionService {
        val tenantProvider = object : TenantProvider {
            override fun currentTenant(): TenantContext = TenantContext(Identifier("tenant-1"))
        }
        val authorization = object : AuthorizationProvider {
            override fun authorize(request: AuthorizationRequest): AuthorizationDecision {
                authorizationRequests?.add(request)
                return AuthorizationDecision(allowed)
            }
        }
        val auditTrail = AuditTrail(
            object : AuditRecordRepository {
                override fun append(record: AuditRecord) {
                    audits += record
                }

                override fun findByResource(
                    tenantId: Identifier,
                    resourceType: String,
                    resourceId: Identifier,
                    limit: Int,
                ): List<AuditRecord> = emptyList()
            },
            FixedIdentifiers("audit-1"),
            fixedClock(),
        )
        return if (tasks == null) {
            ConfirmAgentSuggestionService(
                tenantProvider, users, authorization, repository, FixedIdentifiers("confirmation-1"),
                transaction, fixedClock(), auditTrail,
            )
        } else {
            ConfirmAgentSuggestionService(
                tenantProvider, users, authorization, repository, FixedIdentifiers("confirmation-1"),
                transaction, fixedClock(), auditTrail, tasks,
            )
        }
    }

    private fun fixedUsers(): UserRealmProvider = object : UserRealmProvider {
        override fun currentUser(): UserIdentity = UserIdentity(Identifier("operator-1"), "Operator 1")
        override fun findUser(userId: Identifier): UserIdentity? = null
    }

    private fun result() = PersistedAgentResult(
        Identifier("result-1"),
        Identifier("tenant-1"),
        Identifier("task-1"),
        AgentCapability.CLASSIFICATION,
        Identifier("event-1"),
        "document.created",
        AgentResult(
            Identifier("task-1"),
            AgentExecutionStatus.SUCCEEDED,
            listOf(AgentSuggestion(Identifier("suggestion-1"), "document.classification")),
            completedAt = 1,
        ),
        1,
    )

    private fun task(
        status: BackgroundTaskStatus,
        tenant: String = "tenant-1",
    ) = BackgroundTask(
        id = Identifier("task-1"),
        tenantId = Identifier(tenant),
        type = "test.agent",
        idempotencyKey = "agent:test:event-1",
        status = status,
    )

    private object DirectTransaction : ApplicationTransaction {
        override fun <T> execute(action: () -> T): T = action()
    }

    private class TrackingTransaction : ApplicationTransaction {
        var active = false
            private set
        var executions = 0
            private set

        override fun <T> execute(action: () -> T): T {
            check(!active)
            executions++
            active = true
            return try {
                action()
            } finally {
                active = false
            }
        }
    }

    private class FixedIdentifiers(private val value: String) : IdentifierGenerator {
        override fun nextId(): Identifier = Identifier(value)
    }

    private class Tasks(
        private val task: BackgroundTask?,
        private val transaction: TrackingTransaction? = null,
    ) : TaskRepository {
        var findCalls = 0
            private set

        override fun enqueue(task: BackgroundTask) = Unit

        override fun findById(tenantId: Identifier, taskId: Identifier): BackgroundTask? {
            transaction?.let { check(it.active) { "Task state must be checked in the confirmation transaction." } }
            findCalls++
            return task?.takeIf { it.tenantId == tenantId && it.id == taskId }
        }

        override fun findByBusiness(
            tenantId: Identifier,
            businessId: Identifier,
            limit: Int,
        ): List<BackgroundTask> = emptyList()
    }

    private class Results(
        private val result: PersistedAgentResult,
        private val transaction: TrackingTransaction? = null,
    ) : AgentResultRepository {
        val confirmations = mutableListOf<PersistedAgentSuggestionConfirmation>()
        var findCalls = 0
            private set

        override fun save(result: PersistedAgentResult) = Unit

        override fun findByTask(tenantId: Identifier, taskId: Identifier): PersistedAgentResult? {
            transaction?.let { check(it.active) { "Agent result must be read in the confirmation transaction." } }
            findCalls++
            return result.takeIf { it.tenantId == tenantId && it.taskId == taskId }
        }

        override fun saveConfirmation(
            confirmation: PersistedAgentSuggestionConfirmation,
        ): PersistedAgentSuggestionConfirmation {
            transaction?.let { check(it.active) { "Confirmation must be saved in the task-check transaction." } }
            return confirmations.firstOrNull {
                it.taskId == confirmation.taskId && it.suggestionId == confirmation.suggestionId
            } ?: confirmation.also { confirmations += it }
        }

        override fun findConfirmations(
            tenantId: Identifier,
            taskId: Identifier,
        ): List<PersistedAgentSuggestionConfirmation> = emptyList()
    }

    private fun fixedClock(): Clock = Clock.fixed(Instant.ofEpochMilli(10), ZoneOffset.UTC)
}
