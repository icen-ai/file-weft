package ai.icen.fw.workflow.persistence.jdbc

import ai.icen.fw.workflow.api.WorkflowDefinition
import ai.icen.fw.workflow.api.WorkflowDefinitionStatus
import ai.icen.fw.workflow.api.WorkflowNodeDefinition
import ai.icen.fw.workflow.api.WorkflowNodeKind
import ai.icen.fw.workflow.api.WorkflowPrincipalRef
import ai.icen.fw.workflow.api.WorkflowSubjectRef
import ai.icen.fw.workflow.api.WorkflowSubjectSnapshot
import ai.icen.fw.workflow.api.WorkflowTransitionDefinition
import ai.icen.fw.workflow.domain.WorkflowCommandContext
import ai.icen.fw.workflow.domain.WorkflowDefinitionExecutionReceipt
import ai.icen.fw.workflow.domain.WorkflowDefinitionIndex
import ai.icen.fw.workflow.domain.WorkflowDomainEngine
import ai.icen.fw.workflow.domain.WorkflowEffectCode
import ai.icen.fw.workflow.domain.WorkflowExecutionIds
import ai.icen.fw.workflow.domain.WorkflowIdempotencyReceipt
import ai.icen.fw.workflow.domain.WorkflowStartCommand
import ai.icen.fw.workflow.persistence.migration.WorkflowFlywayMigrationRunner
import ai.icen.fw.workflow.runtime.WorkflowRuntimeAtomicCommit
import ai.icen.fw.workflow.runtime.WorkflowRuntimeCommitCode
import ai.icen.fw.workflow.runtime.WorkflowRuntimeDefinitionRecord
import ai.icen.fw.workflow.runtime.WorkflowReadyEffectJobClaimRequest
import ai.icen.fw.workflow.runtime.WorkflowEffectCoordinator
import ai.icen.fw.workflow.runtime.WorkflowEffectExecutionPhase
import ai.icen.fw.workflow.runtime.WorkflowEffectJobExecutionMode
import ai.icen.fw.workflow.runtime.WorkflowEffectJobResultCheckpoint
import ai.icen.fw.workflow.runtime.WorkflowEffectJobStoredResult
import ai.icen.fw.workflow.runtime.WorkflowEffectObservedOutcome
import ai.icen.fw.workflow.runtime.WorkflowRuntimeAuthorizationDecision
import ai.icen.fw.workflow.runtime.WorkflowRuntimeAuthorizationPort
import ai.icen.fw.workflow.runtime.WorkflowRuntimeAuthorizationRequest
import ai.icen.fw.workflow.runtime.WorkflowRuntimeAuthorizationStatus
import ai.icen.fw.workflow.runtime.WorkflowRuntimeHumanDecisionReceiptRequest
import ai.icen.fw.workflow.runtime.WorkflowTrustedCallContext
import java.io.PrintWriter
import java.sql.Connection
import java.sql.DriverManager
import java.util.logging.Logger
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

abstract class WorkflowRealDatabaseIntegrationContract {
    protected abstract val enabledEnvironmentVariable: String
    protected abstract val driverClassName: String
    protected abstract val dialect: WorkflowJdbcDialect
    protected abstract fun createIsolatedDatabase(namespace: String): IsolatedDatabase

    @Test
    fun `migrates a clean workflow namespace and persists one atomic runtime transition`() {
        check(System.getenv(enabledEnvironmentVariable) == "true") {
            "Workflow database integration tests run only through their fail-closed Gradle task."
        }
        Class.forName(driverClassName)
        val namespace = "fw_wf_it_${System.nanoTime().toString().replace('-', '0')}"
        val isolated = createIsolatedDatabase(namespace)
        try {
            val runner = WorkflowFlywayMigrationRunner(isolated.dataSource, namespace)
            assertEquals(4, runner.migrate())
            runner.validate()
            isolated.dataSource.connection.use { connection ->
                assertTrue(tableExists(connection, "fw_wf_instance"))
                assertTrue(tableExists(connection, "fw_wf_effect"))
                assertTrue(tableExists(connection, "fw_wf_form_submission"))
                assertTrue(tableExists(connection, WorkflowFlywayMigrationRunner.HISTORY_TABLE))
            }
            exerciseAtomicRuntime(isolated.dataSource, dialect)
        } finally {
            isolated.close()
        }
    }

    private fun exerciseAtomicRuntime(dataSource: DataSource, dialect: WorkflowJdbcDialect) {
        val definition = definition()
        val index = WorkflowDefinitionIndex.compile(definition)
        val executionReceipt = WorkflowDefinitionExecutionReceipt.of(
            "receipt-real-database",
            TENANT,
            DEFINITION_ID,
            definition.ref,
            definition.schemaVersion,
            DIGEST_B,
            1L,
            10_000L,
        )
        val definitionRecord = WorkflowRuntimeDefinitionRecord.of(index, executionReceipt)
        val definitionStore = JdbcWorkflowDefinitionStore(dataSource, dialect)
        definitionStore.install(definitionRecord, 1L)
        definitionStore.install(definitionRecord, 2L)
        assertNotNull(definitionStore.load(TENANT, DEFINITION_ID, definition.ref))

        val started = WorkflowDomainEngine.start(
            index,
            WorkflowStartCommand.of(
                commandContext(),
                TENANT,
                INSTANCE_ID,
                DEFINITION_ID,
                definition.ref,
                SUBJECT,
                WorkflowPrincipalRef.of("user", "starter"),
                executionReceipt,
            ),
        )
        val commit = WorkflowRuntimeAtomicCommit.fromDomain(started, DIGEST_C, 0L, null, null, 10L)
        val persistence = JdbcWorkflowRuntimePersistence(dataSource, dialect)
        assertSame(WorkflowRuntimeCommitCode.COMMITTED, persistence.commit(commit).code)

        val snapshot = persistence.loadCommandSnapshot(TENANT, INSTANCE_ID, IDEMPOTENCY_KEY, 11L)
        assertEquals(started.state!!.stateDigest, snapshot.state!!.stateDigest)
        assertEquals(DIGEST_C, snapshot.idempotency!!.logicalRequestDigest)
        assertNotNull(persistence.loadEffect(TENANT, started.effects.single().effectId, 11L))
        val queue = JdbcWorkflowReadyEffectJobQueue(dataSource, dialect)
        val claimed = queue.claimReady(
            WorkflowReadyEffectJobClaimRequest.of(
                TENANT,
                WorkflowEffectCode.SERVICE_TASK,
                "real-database-worker",
                "real-database-claim",
                11L,
                30L,
                1,
            ),
        ).single()
        assertEquals(started.effects.single().effectId, claimed.effectId)
        assertEquals(claimed.claimReceiptDigest, queue.loadClaim(TENANT, claimed.jobId, 12L)!!.claimReceiptDigest)
        val workerContext = WorkflowTrustedCallContext.of(
            TENANT,
            WorkflowPrincipalRef.of("service", "real-database-worker"),
            "real-database-worker-authentication",
            DIGEST_A,
        )
        val coordinator = WorkflowEffectCoordinator(
            object : WorkflowRuntimeAuthorizationPort {
                override fun authorize(request: WorkflowRuntimeAuthorizationRequest) =
                    WorkflowRuntimeAuthorizationDecision.of(
                        "authorization-${request.action.code}",
                        request.callContext.tenantId,
                        request.callContext.actor,
                        request.action,
                        request.instanceId,
                        request.requestDigest,
                        WorkflowRuntimeAuthorizationStatus.AUTHORIZED,
                        "real-database-authority-revision",
                        DIGEST_A,
                        request.evaluatedAt,
                        100L,
                    )

                override fun issueHumanDecisionReceipt(request: WorkflowRuntimeHumanDecisionReceiptRequest) =
                    throw UnsupportedOperationException()
            },
            persistence,
        )
        val effectClaim = coordinator.claim(
            workerContext,
            claimed.effectId,
            claimed.lease.workerId,
            claimed.lease.leaseId,
            claimed.expectedEffectVersion,
            claimed.lease.fencingToken,
            12L,
            claimed.lease.expiresAt,
        )
        val checkpoint = coordinator.checkpoint(
            workerContext,
            claimed.effectId,
            requireNotNull(effectClaim.record).version,
            claimed.lease.leaseId,
            claimed.lease.fencingToken,
            1L,
            WorkflowEffectExecutionPhase.PROVIDER_CALL_STARTED,
            DIGEST_B,
            13L,
        )
        val opaquePayload = byteArrayOf(0, 1, 0x7f, 0x80.toByte(), 0xff.toByte(), 0)
        val storedResult = WorkflowEffectJobStoredResult.of(
            WorkflowEffectObservedOutcome.SUCCEEDED,
            "real-database-result-v1",
            DIGEST_C,
            opaquePayload,
            null,
            14L,
        )
        queue.storeResult(
            WorkflowEffectJobResultCheckpoint.of(
                claimed,
                requireNotNull(checkpoint.record).version,
                storedResult,
                14L,
            ),
        )
        val recovered = queue.claimReady(
            WorkflowReadyEffectJobClaimRequest.of(
                TENANT,
                WorkflowEffectCode.SERVICE_TASK,
                "real-database-recovery-worker",
                "real-database-recovery-claim",
                31L,
                40L,
                1,
            ),
        ).single()
        assertSame(WorkflowEffectJobExecutionMode.APPLY_SUCCEEDED_RESULT, recovered.mode)
        assertContentEquals(opaquePayload, requireNotNull(recovered.storedResult).bytes())
        val otherTenant = persistence.loadCommandSnapshot("tenant-other", INSTANCE_ID, IDEMPOTENCY_KEY, 11L)
        assertNull(otherTenant.state)
        assertNull(otherTenant.idempotency)
    }

    private fun commandContext(): WorkflowCommandContext = WorkflowCommandContext.of(
        "command-start-real-database",
        IDEMPOTENCY_KEY,
        0L,
        10L,
        64,
        WorkflowExecutionIds.of(
            (0 until 8).map { "token-real-$it" },
            (0 until 8).map { "execution-real-$it" },
            (0 until 4).map { "work-real-$it" },
            (0 until 8).map { "effect-real-$it" },
            (0 until 32).map { "event-real-$it" },
            (0 until 4).map { "scope-real-$it" },
        ),
        WorkflowIdempotencyReceipt.fresh(TENANT, INSTANCE_ID, IDEMPOTENCY_KEY, 10L),
    )

    private fun definition(): WorkflowDefinition = WorkflowDefinition.of(
        TENANT,
        DEFINITION_ID,
        "real-database-flow",
        "v1",
        1,
        WorkflowDefinitionStatus.PUBLISHED,
        "真实数据库流程",
        null,
        listOf(
            WorkflowNodeDefinition.of("start", WorkflowNodeKind.START, "开始", null),
            WorkflowNodeDefinition.serviceTask("service", "服务", null, DIGEST_A, DIGEST_B),
            WorkflowNodeDefinition.of("end", WorkflowNodeKind.END, "结束", null),
        ),
        listOf(
            WorkflowTransitionDefinition.unconditional("start-service", "start", "service"),
            WorkflowTransitionDefinition.unconditional("service-end", "service", "end"),
        ),
    )

    private fun tableExists(connection: Connection, tableName: String): Boolean =
        connection.metaData.getTables(null, null, tableName, arrayOf("TABLE")).use { result -> result.next() }

    protected fun environment(name: String, fallback: String): String =
        System.getenv(name)?.takeIf(String::isNotBlank) ?: fallback

    protected fun driverManagerDataSource(url: String, user: String, password: String): DataSource =
        DriverManagerDataSource(url, user, password)

    protected fun schemaDataSource(delegate: DataSource, schema: String): DataSource =
        SchemaSelectingDataSource(delegate, schema)

    protected fun createSchemaDatabase(
        url: String,
        user: String,
        password: String,
        namespace: String,
    ): IsolatedDatabase {
        val admin = driverManagerDataSource(url, user, password)
        admin.connection.use { connection ->
            connection.createStatement().use { statement -> statement.execute("CREATE SCHEMA $namespace") }
        }
        return IsolatedDatabase(schemaDataSource(admin, namespace)) {
            admin.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.execute("DROP SCHEMA IF EXISTS $namespace CASCADE")
                }
            }
        }
    }

    protected class IsolatedDatabase(
        val dataSource: DataSource,
        private val cleanup: () -> Unit,
    ) : AutoCloseable {
        override fun close() = cleanup()
    }

    private companion object {
        const val TENANT = "tenant-real-database"
        const val INSTANCE_ID = "instance-real-database"
        const val DEFINITION_ID = "definition-real-database"
        const val IDEMPOTENCY_KEY = "idempotency-real-database"
        const val DIGEST_A = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
        const val DIGEST_B = "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"
        const val DIGEST_C = "1111111111111111111111111111111111111111111111111111111111111111"
        val SUBJECT = WorkflowSubjectSnapshot.of(
            WorkflowSubjectRef.of("business-record", "record-real-database"),
            "revision-1",
            DIGEST_A,
        )
    }
}

class WorkflowPostgresIntegrationTest : WorkflowRealDatabaseIntegrationContract() {
    override val enabledEnvironmentVariable: String = "FILEWEFT_RUN_POSTGRES_TESTS"
    override val driverClassName: String = "org.postgresql.Driver"
    override val dialect: WorkflowJdbcDialect = WorkflowJdbcDialect.POSTGRESQL

    override fun createIsolatedDatabase(namespace: String): IsolatedDatabase = createSchemaDatabase(
        environment("FILEWEFT_POSTGRES_URL", "jdbc:postgresql://localhost:5432/fileweft"),
        environment("FILEWEFT_POSTGRES_USER", "fileweft"),
        environment("FILEWEFT_POSTGRES_PASSWORD", "fileweft-dev"),
        namespace,
    )
}

class WorkflowKingbaseIntegrationTest : WorkflowRealDatabaseIntegrationContract() {
    override val enabledEnvironmentVariable: String = "FILEWEFT_RUN_KINGBASE_TESTS"
    override val driverClassName: String = environment("FILEWEFT_KINGBASE_DRIVER", "com.kingbase8.Driver")
    override val dialect: WorkflowJdbcDialect = WorkflowJdbcDialect.KINGBASE

    override fun createIsolatedDatabase(namespace: String): IsolatedDatabase = createSchemaDatabase(
        environment("FILEWEFT_KINGBASE_URL", "jdbc:kingbase8://localhost:54321/test"),
        environment("FILEWEFT_KINGBASE_USER", "system"),
        environment("FILEWEFT_KINGBASE_PASSWORD", "kingbase"),
        namespace,
    )
}

class WorkflowMySQLIntegrationTest : WorkflowRealDatabaseIntegrationContract() {
    override val enabledEnvironmentVariable: String = "FILEWEFT_RUN_MYSQL_TESTS"
    override val driverClassName: String = "com.mysql.cj.jdbc.Driver"
    override val dialect: WorkflowJdbcDialect = WorkflowJdbcDialect.MYSQL

    override fun createIsolatedDatabase(namespace: String): IsolatedDatabase {
        val adminUrl = environment(
            "FILEWEFT_MYSQL_ADMIN_URL",
            "jdbc:mysql://localhost:3306?useSSL=false&allowPublicKeyRetrieval=true",
        )
        val user = environment("FILEWEFT_MYSQL_ADMIN_USER", "root")
        val password = environment("FILEWEFT_MYSQL_ADMIN_PASSWORD", "fileweft-dev")
        val admin = driverManagerDataSource(adminUrl, user, password)
        admin.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute(
                    "CREATE DATABASE $namespace CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin",
                )
            }
        }
        val databaseUrl = mysqlDatabaseUrl(adminUrl, namespace)
        return IsolatedDatabase(driverManagerDataSource(databaseUrl, user, password)) {
            admin.connection.use { connection ->
                connection.createStatement().use { statement -> statement.execute("DROP DATABASE IF EXISTS $namespace") }
            }
        }
    }

    private fun mysqlDatabaseUrl(adminUrl: String, database: String): String {
        val queryIndex = adminUrl.indexOf('?')
        val base = if (queryIndex >= 0) adminUrl.substring(0, queryIndex) else adminUrl
        val query = if (queryIndex >= 0) adminUrl.substring(queryIndex) else ""
        val hostEnd = base.indexOf('/', "jdbc:mysql://".length)
        val server = if (hostEnd >= 0) base.substring(0, hostEnd) else base
        return "${server.trimEnd('/')}/$database$query"
    }
}

private class DriverManagerDataSource(
    private val url: String,
    private val user: String,
    private val password: String,
) : DataSource {
    override fun getConnection(): Connection = DriverManager.getConnection(url, user, password)
    override fun getConnection(username: String, password: String): Connection =
        DriverManager.getConnection(url, username, password)
    override fun getLogWriter(): PrintWriter? = DriverManager.getLogWriter()
    override fun setLogWriter(out: PrintWriter?) = DriverManager.setLogWriter(out)
    override fun setLoginTimeout(seconds: Int) = DriverManager.setLoginTimeout(seconds)
    override fun getLoginTimeout(): Int = DriverManager.getLoginTimeout()
    override fun getParentLogger(): Logger = Logger.getLogger("flowweft.workflow.jdbc")
    override fun <T : Any?> unwrap(iface: Class<T>): T {
        if (iface.isInstance(this)) return iface.cast(this)
        throw java.sql.SQLException("FlowWeft test DataSource does not wrap ${iface.name}.")
    }
    override fun isWrapperFor(iface: Class<*>): Boolean = iface.isInstance(this)
}

private class SchemaSelectingDataSource(
    private val delegate: DataSource,
    private val schema: String,
) : DataSource by delegate {
    override fun getConnection(): Connection = select(delegate.connection)
    override fun getConnection(username: String, password: String): Connection =
        select(delegate.getConnection(username, password))

    private fun select(connection: Connection): Connection {
        try {
            connection.createStatement().use { statement -> statement.execute("SET search_path TO $schema") }
            return connection
        } catch (failure: Throwable) {
            connection.close()
            throw failure
        }
    }
}
