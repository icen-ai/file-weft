package ai.icen.fw.workflow.sla.persistence.jdbc

import ai.icen.fw.workflow.api.WorkflowDefinitionRef
import ai.icen.fw.workflow.api.WorkflowPrincipalRef
import ai.icen.fw.workflow.api.WorkflowSubjectRef
import ai.icen.fw.workflow.api.WorkflowSubjectSnapshot
import ai.icen.fw.workflow.runtime.WorkflowBusinessCalendarProfile
import ai.icen.fw.workflow.runtime.WorkflowTrustedCallContext
import ai.icen.fw.workflow.sla.WorkflowSlaActionCheckpoint
import ai.icen.fw.workflow.sla.WorkflowSlaActionCompletion
import ai.icen.fw.workflow.sla.WorkflowSlaActionOutcome
import ai.icen.fw.workflow.sla.WorkflowSlaActionProfile
import ai.icen.fw.workflow.sla.WorkflowSlaActionReceipt
import ai.icen.fw.workflow.sla.WorkflowSlaActionRequest
import ai.icen.fw.workflow.sla.WorkflowSlaCalendarBinding
import ai.icen.fw.workflow.sla.WorkflowSlaClaimMutation
import ai.icen.fw.workflow.sla.WorkflowSlaCreateMutation
import ai.icen.fw.workflow.sla.WorkflowSlaLease
import ai.icen.fw.workflow.sla.WorkflowSlaMilestoneKind
import ai.icen.fw.workflow.sla.WorkflowSlaMilestoneRecord
import ai.icen.fw.workflow.sla.WorkflowSlaMilestoneStatus
import ai.icen.fw.workflow.sla.WorkflowSlaPolicy
import ai.icen.fw.workflow.sla.WorkflowSlaReconciliation
import ai.icen.fw.workflow.sla.WorkflowSlaReconciliationResolution
import ai.icen.fw.workflow.sla.WorkflowSlaSchedule
import ai.icen.fw.workflow.sla.WorkflowSlaStoreCode
import ai.icen.fw.workflow.sla.WorkflowSlaTaskSnapshot
import ai.icen.fw.workflow.sla.WorkflowSlaTaskStatus
import ai.icen.fw.workflow.spi.WorkflowBusinessCalendarRef
import org.h2.jdbcx.JdbcDataSource
import org.h2.tools.RunScript
import java.io.InputStreamReader
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.atomic.AtomicBoolean
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class JdbcWorkflowSlaDurableStoreTest {
    @Test
    fun `guarded create is tenant scoped replay safe and canonical after reload`() {
        val dataSource = database("create")
        insertHostTask(dataSource, TENANT)
        val store = store(dataSource)
        val candidate = schedule(TENANT)

        val created = store.createSchedule(WorkflowSlaCreateMutation.of(candidate))
        val replayed = store.createSchedule(WorkflowSlaCreateMutation.of(candidate))
        val duplicateTask = store.createSchedule(
            WorkflowSlaCreateMutation.of(schedule(TENANT, "schedule-2", "idempotency-2")),
        )
        val loaded = assertNotNull(store.loadSchedule(TENANT, candidate.scheduleId))

        assertSame(WorkflowSlaStoreCode.APPLIED, created.code)
        assertSame(WorkflowSlaStoreCode.REPLAYED, replayed.code)
        assertSame(WorkflowSlaStoreCode.CONFLICT, duplicateTask.code)
        assertEquals(candidate.contentDigest, loaded.contentDigest)
        assertEquals(
            candidate.contentDigest,
            store.loadScheduleByIdempotency(TENANT, candidate.idempotencyKey)?.contentDigest,
        )
        assertNull(store.loadSchedule(OTHER_TENANT, candidate.scheduleId))
    }

    @Test
    fun `task drift rejects schedule in the same transaction`() {
        val dataSource = database("drift")
        insertHostTask(dataSource, TENANT, revision = 2L, taskDigest = sha('2'))
        val result = store(dataSource).createSchedule(WorkflowSlaCreateMutation.of(schedule(TENANT)))

        assertSame(WorkflowSlaStoreCode.NOT_ELIGIBLE, result.code)
    }

    @Test
    fun `commit exception is reconciled only by exact create evidence`() {
        val delegate = database("commit-unknown")
        insertHostTask(delegate, TENANT)
        val store = JdbcWorkflowSlaDurableStore(
            CommitFailsOnceDataSource(delegate),
            WorkflowSlaJdbcDialect.POSTGRESQL,
        )

        val result = store.createSchedule(WorkflowSlaCreateMutation.of(schedule(TENANT)))

        assertSame(WorkflowSlaStoreCode.REPLAYED, result.code)
        assertNotNull(result.schedule)
    }

    @Test
    fun `expired uncheckpointed lease is reclaimed with a higher fence`() {
        val dataSource = database("fence")
        insertHostTask(dataSource, TENANT)
        val store = store(dataSource)
        val created = create(store)
        val first = claim(store, created, "lease-1", 200L, 100L)
        val firstLease = assertNotNull(first.milestone(WorkflowSlaMilestoneKind.REMINDER).lease)
        val due = store.findDue(TENANT, 301L, 10)
        assertEquals(2, due.size)
        assertTrue(due.any { it.milestoneKind == WorkflowSlaMilestoneKind.REMINDER })

        val reclaimedResult = store.claim(
            WorkflowSlaClaimMutation.of(
                TENANT,
                created.scheduleId,
                WorkflowSlaMilestoneKind.REMINDER,
                first.version,
                "worker-2",
                "lease-2",
                sha('b'),
                301L,
                100L,
            ),
        )
        val reclaimed = assertNotNull(reclaimedResult.schedule)
        val secondLease = assertNotNull(reclaimed.milestone(WorkflowSlaMilestoneKind.REMINDER).lease)

        assertSame(WorkflowSlaStoreCode.APPLIED, reclaimedResult.code)
        assertEquals(firstLease.fencingToken + 1L, secondLease.fencingToken)
        val stale = store.completeAction(
            WorkflowSlaActionCompletion.of(
                TENANT,
                created.scheduleId,
                WorkflowSlaMilestoneKind.REMINDER,
                reclaimed.version,
                firstLease,
                WorkflowSlaMilestoneStatus.SUPPRESSED,
                null,
                sha('c'),
                null,
                310L,
            ),
        )
        assertSame(WorkflowSlaStoreCode.LEASE_MISMATCH, stale.code)
    }

    @Test
    fun `checkpointed unknown outcome and receipt survive restart and reconcile`() {
        val dataSource = database("unknown")
        insertHostTask(dataSource, TENANT)
        val store = store(dataSource)
        val created = create(store)
        val claimed = claim(store, created, "lease-1", 200L, 1_000L)
        val lease = assertNotNull(claimed.milestone(WorkflowSlaMilestoneKind.REMINDER).lease)
        val task = assertNotNull(store.loadTask(TENANT, INSTANCE, TASK))
        val request = actionRequest(claimed, task, 210L, 1_000L)
        val checkpointed = assertNotNull(store.checkpointAction(
            WorkflowSlaActionCheckpoint.of(
                TENANT,
                created.scheduleId,
                WorkflowSlaMilestoneKind.REMINDER,
                claimed.version,
                lease,
                task,
                request.requestDigest,
                sha('c'),
                210L,
            ),
        ).schedule)
        val unknownReceipt = WorkflowSlaActionReceipt.failure(
            request,
            WorkflowSlaActionOutcome.OUTCOME_UNKNOWN,
            sha('d'),
            "transport-unknown",
            215L,
            1_000L,
        )
        val unknown = assertNotNull(store.completeAction(
            WorkflowSlaActionCompletion.of(
                TENANT,
                created.scheduleId,
                WorkflowSlaMilestoneKind.REMINDER,
                checkpointed.version,
                lease,
                WorkflowSlaMilestoneStatus.OUTCOME_UNKNOWN,
                unknownReceipt,
                sha('e'),
                null,
                220L,
            ),
        ).schedule)

        val restarted = store(dataSource)
        val loaded = assertNotNull(restarted.loadSchedule(TENANT, created.scheduleId))
        val restoredReceipt = assertNotNull(loaded.milestone(WorkflowSlaMilestoneKind.REMINDER).actionReceipt)
        assertEquals(unknownReceipt.receiptDigest, restoredReceipt.receiptDigest)
        val diagnostic = restarted.diagnosticSnapshot(TENANT, 230L)
        assertEquals(1L, diagnostic.outcomeUnknown)

        val successReceipt = WorkflowSlaActionReceipt.success(request, sha('f'), 216L, 1_000L)
        val reconciled = restarted.reconcile(
            WorkflowSlaReconciliation.of(
                TENANT,
                created.scheduleId,
                WorkflowSlaMilestoneKind.REMINDER,
                unknown.version,
                WorkflowSlaReconciliationResolution.APPLIED,
                successReceipt,
                sha('a'),
                sha('b'),
                null,
                230L,
            ),
        )
        assertSame(WorkflowSlaStoreCode.APPLIED, reconciled.code)
        assertSame(
            WorkflowSlaMilestoneStatus.SUCCEEDED,
            reconciled.schedule?.milestone(WorkflowSlaMilestoneKind.REMINDER)?.status,
        )
        val reloadedSuccess = assertNotNull(
            restarted.loadSchedule(TENANT, created.scheduleId)
                ?.milestone(WorkflowSlaMilestoneKind.REMINDER)
                ?.actionReceipt,
        )
        assertEquals(successReceipt.receiptDigest, reloadedSuccess.receiptDigest)
    }

    @Test
    fun `due query is bounded ordered and tenant isolated`() {
        val dataSource = database("due")
        insertHostTask(dataSource, TENANT)
        create(store(dataSource))

        val due = store(dataSource).findDue(TENANT, 250L, 10)

        assertEquals(1, due.size)
        assertSame(WorkflowSlaMilestoneKind.REMINDER, due.single().milestoneKind)
        assertTrue(store(dataSource).findDue(OTHER_TENANT, 1_000L, 10).isEmpty())
    }

    private fun store(dataSource: DataSource): JdbcWorkflowSlaDurableStore = JdbcWorkflowSlaDurableStore(
        dataSource,
        WorkflowSlaJdbcDialect.POSTGRESQL,
    )

    private fun create(store: JdbcWorkflowSlaDurableStore): WorkflowSlaSchedule = assertNotNull(
        store.createSchedule(WorkflowSlaCreateMutation.of(schedule(TENANT))).schedule,
    )

    private fun claim(
        store: JdbcWorkflowSlaDurableStore,
        schedule: WorkflowSlaSchedule,
        leaseId: String,
        now: Long,
        duration: Long,
    ): WorkflowSlaSchedule = assertNotNull(store.claim(
        WorkflowSlaClaimMutation.of(
            schedule.tenantId,
            schedule.scheduleId,
            WorkflowSlaMilestoneKind.REMINDER,
            schedule.version,
            "worker-1",
            leaseId,
            sha('b'),
            now,
            duration,
        ),
    ).schedule)

    private fun actionRequest(
        schedule: WorkflowSlaSchedule,
        task: WorkflowSlaTaskSnapshot,
        requestedAt: Long,
        deadline: Long,
    ): WorkflowSlaActionRequest = WorkflowSlaActionRequest.of(
        WorkflowTrustedCallContext.of(
            TENANT,
            WorkflowPrincipalRef.of("user", "alice"),
            "authentication-1",
            sha('f'),
        ),
        schedule.scheduleId,
        schedule.policy.definitionRef,
        task.instanceId,
        task.workItemId,
        task.nodeId,
        task.subject,
        task.revision,
        task.taskDigest,
        schedule.policy.policyDigest,
        WorkflowSlaMilestoneKind.REMINDER,
        schedule.milestone(WorkflowSlaMilestoneKind.REMINDER).policy.action,
        schedule.policy.actionProfile,
        schedule.milestone(WorkflowSlaMilestoneKind.REMINDER).attempt,
        sha('c'),
        requestedAt,
        deadline,
    )

    private fun schedule(
        tenantId: String,
        scheduleId: String = "schedule-1",
        idempotencyKey: String = "idempotency-1",
    ): WorkflowSlaSchedule {
        val task = task(tenantId)
        val milestones = POLICY.milestones.mapIndexed { index, policy ->
            WorkflowSlaMilestoneRecord.scheduled(
                policy,
                200L + index * 100L,
                sha(listOf('8', '9', '0')[index]),
                150L,
            )
        }
        return WorkflowSlaSchedule.create(
            tenantId,
            scheduleId,
            idempotencyKey,
            sha('7'),
            POLICY,
            task,
            milestones,
            sha('6'),
            sha('5'),
            150L,
        )
    }

    private fun task(tenantId: String): WorkflowSlaTaskSnapshot = WorkflowSlaTaskSnapshot.of(
        tenantId,
        INSTANCE,
        TASK,
        DEFINITION_ID,
        DEFINITION_REF,
        NODE,
        SUBJECT,
        WorkflowSlaTaskStatus.ACTIVE,
        1L,
        sha('1'),
        100L,
        100L,
    )

    private fun database(name: String): DataSource {
        val dataSource = JdbcDataSource().apply {
            setURL(
                "jdbc:h2:mem:sla-$name-${System.nanoTime()};" +
                    "MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
            )
            user = "sa"
            password = ""
        }
        dataSource.connection.use { connection ->
            HOST_SCHEMA.split(';').map(String::trim).filter(String::isNotEmpty).forEach { sql ->
                connection.createStatement().use { statement -> statement.execute(sql) }
            }
            val resource = requireNotNull(javaClass.getResourceAsStream(
                WorkflowSlaJdbcMigrationDialect.POSTGRESQL.resourcePath,
            ))
            InputStreamReader(resource, Charsets.UTF_8).use { reader -> RunScript.execute(connection, reader) }
        }
        return dataSource
    }

    private fun insertHostTask(
        dataSource: DataSource,
        tenantId: String,
        revision: Long = 1L,
        taskDigest: String = sha('1'),
    ) {
        dataSource.connection.use { connection ->
            connection.prepareStatement(
                "INSERT INTO fw_wf_instance VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            ).use { statement ->
                statement.setString(1, INSTANCE)
                statement.setString(2, tenantId)
                statement.setString(3, DEFINITION_ID)
                statement.setString(4, DEFINITION_REF.key)
                statement.setString(5, DEFINITION_REF.version)
                statement.setString(6, DEFINITION_REF.digest)
                statement.setString(7, SUBJECT.ref.type)
                statement.setString(8, SUBJECT.ref.id)
                statement.setString(9, SUBJECT.revision)
                statement.setString(10, SUBJECT.digest)
                statement.setString(11, "waiting")
                statement.setLong(12, 100L)
                statement.setLong(13, 100L)
                statement.executeUpdate()
            }
            connection.prepareStatement(
                "INSERT INTO fw_wf_human_task VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            ).use { statement ->
                statement.setString(1, TASK)
                statement.setString(2, tenantId)
                statement.setString(3, INSTANCE)
                statement.setString(4, NODE)
                statement.setString(5, "active")
                statement.setLong(6, revision)
                statement.setString(7, taskDigest)
                statement.setLong(8, 100L)
                statement.setLong(9, 100L)
                statement.executeUpdate()
            }
        }
    }

    private class CommitFailsOnceDataSource(private val delegate: DataSource) : DataSource by delegate {
        private val fail = AtomicBoolean(true)

        override fun getConnection(): Connection = wrap(delegate.connection)
        override fun getConnection(username: String?, password: String?): Connection =
            wrap(delegate.getConnection(username, password))

        private fun wrap(connection: Connection): Connection = Proxy.newProxyInstance(
            Connection::class.java.classLoader,
            arrayOf(Connection::class.java),
        ) { _, method, arguments ->
            try {
                if (method.name == "commit" && fail.compareAndSet(true, false)) {
                    method.invoke(connection, *(arguments ?: emptyArray()))
                    throw SQLException("simulated ambiguous commit", "08007")
                }
                method.invoke(connection, *(arguments ?: emptyArray()))
            } catch (failure: InvocationTargetException) {
                throw failure.targetException
            }
        } as Connection
    }

    companion object {
        private const val TENANT = "tenant-1"
        private const val OTHER_TENANT = "tenant-2"
        private const val INSTANCE = "instance-1"
        private const val TASK = "task-1"
        private const val DEFINITION_ID = "definition-1"
        private const val NODE = "approve"

        private val DEFINITION_REF = WorkflowDefinitionRef.of("approval", "1", sha('3'))
        private val SUBJECT = WorkflowSubjectSnapshot.of(
            WorkflowSubjectRef.of("case", "case-1"),
            "subject-r1",
            sha('4'),
        )
        private val POLICY = WorkflowSlaPolicy.standard(
            "standard-sla",
            "1",
            sha('d'),
            DEFINITION_REF,
            NODE,
            WorkflowSlaCalendarBinding.of(
                "calendar-profile",
                "1",
                sha('a'),
                sha('b'),
                WorkflowBusinessCalendarRef.of("calendar-provider", "calendar-cn", "calendar-r1", sha('c')),
                WorkflowBusinessCalendarProfile.of("calendar-provider", "provider-r1", 10_000L, 1_024, 1_024),
            ),
            WorkflowSlaActionProfile.of(
                "sla-actions",
                "1",
                sha('e'),
                "action-provider",
                "provider-r1",
                10_000L,
                1_024,
                1_024,
                3,
                100L,
            ),
            100L,
            200L,
            300L,
        )

        private const val HOST_SCHEMA = """
            CREATE TABLE fw_wf_instance (
                id varchar(512) NOT NULL,
                tenant_id varchar(512) NOT NULL,
                definition_id varchar(512) NOT NULL,
                definition_key varchar(256) NOT NULL,
                definition_version varchar(128) NOT NULL,
                definition_digest varchar(64) NOT NULL,
                subject_type varchar(64) NOT NULL,
                subject_id varchar(512) NOT NULL,
                subject_revision varchar(256) NOT NULL,
                subject_digest varchar(64) NOT NULL,
                status varchar(64) NOT NULL,
                created_time bigint NOT NULL,
                updated_time bigint NOT NULL,
                PRIMARY KEY (tenant_id, id)
            );
            CREATE TABLE fw_wf_human_task (
                id varchar(512) NOT NULL,
                tenant_id varchar(512) NOT NULL,
                instance_id varchar(512) NOT NULL,
                node_id varchar(128) NOT NULL,
                task_status varchar(64) NOT NULL,
                task_revision bigint NOT NULL,
                content_digest varchar(64) NOT NULL,
                created_time bigint NOT NULL,
                updated_time bigint NOT NULL,
                PRIMARY KEY (tenant_id, id)
            )
        """

        private fun sha(character: Char): String = character.toString().repeat(64)
    }
}
