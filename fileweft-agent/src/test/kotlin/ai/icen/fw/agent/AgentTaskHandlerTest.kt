package ai.icen.fw.agent

import ai.icen.fw.application.agent.AgentResultRepository
import ai.icen.fw.application.agent.PersistedAgentResult
import ai.icen.fw.application.agent.PersistedAgentSuggestionConfirmation
import ai.icen.fw.application.task.BackgroundTask
import ai.icen.fw.application.task.BackgroundTaskLease
import ai.icen.fw.application.task.BackgroundTaskStatus
import ai.icen.fw.application.task.LeasedTaskProcessingRepository
import ai.icen.fw.application.task.TaskLeaseClaim
import ai.icen.fw.application.task.TaskLeaseLostException
import ai.icen.fw.application.task.TaskMutationRepository
import ai.icen.fw.application.task.TaskState
import ai.icen.fw.application.task.TaskWorker
import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.ai.AgentCapability
import ai.icen.fw.spi.ai.AgentExecutionStatus
import ai.icen.fw.spi.ai.AgentResult
import ai.icen.fw.spi.ai.AgentTask
import ai.icen.fw.spi.ai.FileWeftAgent
import ai.icen.fw.spi.task.TaskExecution
import ai.icen.fw.spi.task.TaskHandlingStatus
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentTaskHandlerTest {
    @Test
    fun `retains legacy direct evidence projection`() {
        val results = InMemoryResults()
        val handler = legacyHandler(results) { task ->
            AgentResult(task.id, AgentExecutionStatus.SUCCEEDED, completedAt = 3)
        }

        val outcome = handler.handle(execution())

        assertEquals(TaskHandlingStatus.SUCCEEDED, outcome.status)
        assertEquals(1, results.saved.size)
        assertEquals("event-1", results.saved.single().sourceEventId.value)
        assertEquals(AgentExecutionStatus.SUCCEEDED, results.saved.single().result.status)
    }

    @Test
    fun `retains legacy lease and retry exhaustion behavior`() {
        val results = InMemoryResults()
        val handler = legacyHandler(results) { task ->
            AgentResult(task.id, AgentExecutionStatus.FAILED, message = "model offline", completedAt = 3)
        }

        assertEquals(TaskHandlingStatus.RETRYABLE_FAILURE, handler.handle(lease()).status)
        handler.onExhausted(execution(), "retry budget exhausted")

        assertEquals(1, results.saved.size)
        assertEquals("retry budget exhausted", results.saved.single().result.message)
    }

    @Test
    fun `rejects malformed task payload without invoking an agent`() {
        val results = InMemoryResults()
        var agentCalls = 0
        val handler = legacyHandler(results) { task ->
            agentCalls++
            AgentResult(task.id, AgentExecutionStatus.SUCCEEDED, completedAt = 3)
        }
        val malformed = TaskExecution(
            Identifier("task-1"),
            Identifier("tenant-1"),
            AgentTaskHandler.TASK_TYPE,
            payload = emptyMap(),
        )

        assertEquals(TaskHandlingStatus.PERMANENT_FAILURE, handler.handle(malformed).status)
        assertEquals(0, agentCalls)
        assertTrue(results.saved.isEmpty())
    }

    @Test
    fun `strong handler rejects direct execution without invoking an agent`() {
        val transaction = TrackingTransaction()
        val results = InMemoryResults(transaction)
        val mutations = RecordingMutations(transaction, runningState())
        var agentCalls = 0
        val handler = strongHandler(results, transaction, mutations) { task ->
            agentCalls++
            AgentResult(task.id, AgentExecutionStatus.SUCCEEDED, completedAt = 3)
        }

        val outcome = handler.handle(execution())
        handler.onExhausted(execution(), "must not project")

        assertEquals(TaskHandlingStatus.PERMANENT_FAILURE, outcome.status)
        assertEquals(0, agentCalls)
        assertTrue(mutations.calls.isEmpty())
        assertTrue(results.saved.isEmpty())
    }

    @Test
    fun `strong handler rejects tokenless lease before invoking an agent`() {
        val transaction = TrackingTransaction()
        val results = InMemoryResults(transaction)
        val mutations = RecordingMutations(transaction, runningState())
        var agentCalls = 0
        val handler = strongHandler(results, transaction, mutations) { task ->
            agentCalls++
            AgentResult(task.id, AgentExecutionStatus.SUCCEEDED, completedAt = 3)
        }

        assertFailsWith<TaskLeaseLostException> { handler.handle(lease(token = null)) }

        assertEquals(0, agentCalls)
        assertTrue(mutations.calls.isEmpty())
        assertTrue(results.saved.isEmpty())
    }

    @Test
    fun `executes remote agent outside transaction then locks exact lease before saving`() {
        val events = mutableListOf<String>()
        val transaction = TrackingTransaction(events)
        val results = InMemoryResults(transaction, events)
        val mutations = RecordingMutations(transaction, runningState(), events)
        val handler = strongHandler(results, transaction, mutations) { task ->
            assertFalse(transaction.active)
            events += "agent"
            AgentResult(task.id, AgentExecutionStatus.SUCCEEDED, completedAt = 3)
        }

        val outcome = handler.handle(lease())

        assertEquals(TaskHandlingStatus.SUCCEEDED, outcome.status)
        assertEquals(listOf("agent", "transaction:start", "lock", "save", "transaction:end"), events)
        assertEquals(listOf(Identifier("tenant-1") to Identifier("task-1")), mutations.calls)
        assertEquals(1, results.saved.size)
    }

    @Test
    fun `stale or forged lease cannot overwrite agent evidence`() {
        val cases = listOf(
            "missing" to null,
            "task id" to runningState(id = "task-other"),
            "tenant" to runningState(tenant = "tenant-other"),
            "type" to runningState(type = "document.doctor"),
            "business" to runningState(businessId = "document-other"),
            "owner" to runningState(owner = "worker-other"),
            "token" to runningState(token = "token-other"),
            "status" to TaskState(
                Identifier("task-1"), Identifier("tenant-1"), AgentTaskHandler.TASK_TYPE,
                BackgroundTaskStatus.SUCCESS, Identifier("document-1"),
            ),
        )

        cases.forEach { (name, state) ->
            val transaction = TrackingTransaction()
            val results = InMemoryResults(transaction)
            val mutations = RecordingMutations(transaction, state)
            var agentCalls = 0
            val handler = strongHandler(results, transaction, mutations) { task ->
                agentCalls++
                AgentResult(task.id, AgentExecutionStatus.SUCCEEDED, completedAt = 3)
            }

            assertFailsWith<TaskLeaseLostException>(name) { handler.handle(lease()) }
            assertEquals(1, agentCalls, name)
            assertTrue(results.saved.isEmpty(), name)
        }
    }

    @Test
    fun `terminal failure projection requires the exact failed task identity`() {
        val transaction = TrackingTransaction()
        val results = InMemoryResults(transaction)
        val mutations = RecordingMutations(transaction, failedState())
        val handler = strongHandler(results, transaction, mutations) { task ->
            AgentResult(task.id, AgentExecutionStatus.FAILED, message = "offline", completedAt = 3)
        }

        handler.onExhausted(lease(), "retry budget exhausted")
        assertEquals("retry budget exhausted", results.saved.single().result.message)

        mutations.state = failedState(businessId = "document-other")
        handler.onExhausted(lease(), "must not overwrite")
        mutations.state = runningState()
        handler.onExhausted(lease(), "must not overwrite")
        handler.onExhausted(lease(token = null), "must not overwrite")

        assertEquals(1, results.saved.size)
    }

    @Test
    fun `task worker passes its token lease through fenced agent projection and acknowledgement`() {
        val transaction = TrackingTransaction()
        val repository = WorkerRepository(transaction, backgroundTask(BackgroundTaskStatus.PENDING))
        val results = InMemoryResults(transaction)
        var agentCalls = 0
        val handler = strongHandler(results, transaction, repository) { task ->
            assertFalse(transaction.active)
            agentCalls++
            AgentResult(task.id, AgentExecutionStatus.SUCCEEDED, completedAt = 3)
        }
        val worker = TaskWorker(
            repository = repository,
            transaction = transaction,
            handlers = listOf(handler),
            clock = fixedClock(),
            workerId = "worker-a",
            maxAttempts = 2,
            initialRetryDelay = Duration.ofMillis(10),
            maxRetryDelay = Duration.ofMillis(20),
            leaseDuration = Duration.ofSeconds(1),
            legacyRunningGrace = Duration.ZERO,
        )

        val summary = worker.processAvailable(1)

        assertEquals(1, summary.succeeded)
        assertEquals(0, summary.lost)
        assertEquals(1, agentCalls)
        assertEquals(BackgroundTaskStatus.SUCCESS, repository.status)
        assertEquals(repository.claimedToken, repository.acknowledgedToken)
        assertEquals(1, results.saved.size)
    }

    private fun legacyHandler(
        results: InMemoryResults,
        execute: (AgentTask) -> AgentResult,
    ): AgentTaskHandler = AgentTaskHandler(
        AgentTaskOrchestrator(listOf(agent(execute)), fixedClock()),
        results,
        DirectTransaction,
        fixedClock(),
    )

    private fun strongHandler(
        results: InMemoryResults,
        transaction: ApplicationTransaction,
        mutations: TaskMutationRepository,
        execute: (AgentTask) -> AgentResult,
    ): AgentTaskHandler = AgentTaskHandler(
        AgentTaskOrchestrator(listOf(agent(execute)), fixedClock()),
        results,
        transaction,
        fixedClock(),
        mutations,
    )

    private fun execution(
        id: String = "task-1",
        tenant: String = "tenant-1",
        type: String = AgentTaskHandler.TASK_TYPE,
        businessId: String? = "document-1",
    ) = TaskExecution(
        Identifier(id),
        Identifier(tenant),
        type,
        businessId?.let(::Identifier),
        mapOf(
            AgentTaskHandler.CAPABILITY_KEY to "METADATA",
            AgentTaskHandler.SOURCE_EVENT_ID_KEY to "event-1",
            AgentTaskHandler.SOURCE_EVENT_TYPE_KEY to "document.created",
            AgentTaskHandler.CONTEXT_PREFIX + "documentId" to "document-1",
        ),
    )

    private fun backgroundTask(status: BackgroundTaskStatus = BackgroundTaskStatus.RUNNING) = BackgroundTask(
        id = Identifier("task-1"),
        tenantId = Identifier("tenant-1"),
        type = AgentTaskHandler.TASK_TYPE,
        idempotencyKey = "agent:METADATA:event-1",
        businessId = Identifier("document-1"),
        payload = execution().payload,
        status = status,
        nextAttemptTime = 0,
    )

    private fun lease(
        owner: String = "worker-a",
        token: String? = "token-a",
    ) = BackgroundTaskLease(backgroundTask(), owner, token)

    private fun runningState(
        id: String = "task-1",
        tenant: String = "tenant-1",
        type: String = AgentTaskHandler.TASK_TYPE,
        businessId: String? = "document-1",
        owner: String = "worker-a",
        token: String = "token-a",
    ) = TaskState(
        Identifier(id),
        Identifier(tenant),
        type,
        BackgroundTaskStatus.RUNNING,
        businessId?.let(::Identifier),
        owner,
        token,
    )

    private fun failedState(
        businessId: String? = "document-1",
    ) = TaskState(
        Identifier("task-1"),
        Identifier("tenant-1"),
        AgentTaskHandler.TASK_TYPE,
        BackgroundTaskStatus.FAILED,
        businessId?.let(::Identifier),
    )

    private fun agent(execute: (AgentTask) -> AgentResult): FileWeftAgent = object : FileWeftAgent {
        override fun capability(): AgentCapability = AgentCapability.METADATA
        override fun execute(task: AgentTask): AgentResult = execute(task)
    }

    private object DirectTransaction : ApplicationTransaction {
        override fun <T> execute(action: () -> T): T = action()
    }

    private class TrackingTransaction(
        private val events: MutableList<String>? = null,
    ) : ApplicationTransaction {
        var active = false
            private set

        override fun <T> execute(action: () -> T): T {
            check(!active) { "Nested test transaction is not expected." }
            events?.add("transaction:start")
            active = true
            return try {
                action()
            } finally {
                active = false
                events?.add("transaction:end")
            }
        }
    }

    private class RecordingMutations(
        private val transaction: TrackingTransaction,
        var state: TaskState?,
        private val events: MutableList<String>? = null,
    ) : TaskMutationRepository {
        val calls = mutableListOf<Pair<Identifier, Identifier>>()

        override fun findForMutation(tenantId: Identifier, taskId: Identifier): TaskState? {
            check(transaction.active) { "Task mutation lookup must run inside the local transaction." }
            events?.add("lock")
            calls += tenantId to taskId
            return state
        }
    }

    private class InMemoryResults(
        private val transaction: TrackingTransaction? = null,
        private val events: MutableList<String>? = null,
    ) : AgentResultRepository {
        val saved = mutableListOf<PersistedAgentResult>()

        override fun save(result: PersistedAgentResult) {
            transaction?.let { check(it.active) { "Agent result must be saved inside the local transaction." } }
            events?.add("save")
            saved.removeAll { existing ->
                existing.tenantId == result.tenantId && existing.taskId == result.taskId
            }
            saved += result
        }

        override fun findByTask(tenantId: Identifier, taskId: Identifier): PersistedAgentResult? =
            saved.lastOrNull { it.tenantId == tenantId && it.taskId == taskId }

        override fun saveConfirmation(
            confirmation: PersistedAgentSuggestionConfirmation,
        ): PersistedAgentSuggestionConfirmation = throw UnsupportedOperationException()

        override fun findConfirmations(
            tenantId: Identifier,
            taskId: Identifier,
        ): List<PersistedAgentSuggestionConfirmation> = emptyList()
    }

    private inner class WorkerRepository(
        private val transaction: TrackingTransaction,
        private var task: BackgroundTask,
    ) : LeasedTaskProcessingRepository, TaskMutationRepository {
        var status: BackgroundTaskStatus = task.status
            private set
        var claimedToken: String? = null
            private set
        var acknowledgedToken: String? = null
            private set
        private var owner: String? = null
        private var activeToken: String? = null

        override fun claimAvailable(
            limit: Int,
            now: Long,
            leaseOwner: String,
            leaseExpiresAt: Long,
        ): List<BackgroundTaskLease> = error("The token-aware claim path is required.")

        override fun claimAvailable(limit: Int, now: Long, claim: TaskLeaseClaim): List<BackgroundTaskLease> {
            check(transaction.active)
            if (status != BackgroundTaskStatus.PENDING) return emptyList()
            status = BackgroundTaskStatus.RUNNING
            owner = claim.leaseOwner
            claimedToken = claim.leaseToken
            activeToken = claim.leaseToken
            task = backgroundTask(status)
            return listOf(BackgroundTaskLease(task, claim.leaseOwner, claim.leaseToken))
        }

        override fun findForMutation(tenantId: Identifier, taskId: Identifier): TaskState? {
            check(transaction.active)
            return TaskState(
                task.id,
                task.tenantId,
                task.type,
                status,
                task.businessId,
                owner,
                activeToken,
            )
        }

        override fun markSucceeded(lease: BackgroundTaskLease, completedAt: Long) {
            check(transaction.active)
            check(status == BackgroundTaskStatus.RUNNING)
            check(owner == lease.leaseOwner && activeToken == lease.leaseToken)
            acknowledgedToken = lease.leaseToken
            status = BackgroundTaskStatus.SUCCESS
            owner = null
            activeToken = null
        }

        override fun markForRetry(
            lease: BackgroundTaskLease,
            nextAttemptAt: Long,
            message: String,
            updatedAt: Long,
        ) = error("Retry is not expected.")

        override fun markFailed(lease: BackgroundTaskLease, message: String, updatedAt: Long) =
            error("Failure is not expected.")
    }

    private fun fixedClock(): Clock = Clock.fixed(Instant.ofEpochMilli(10), ZoneOffset.UTC)
}
