package ai.icen.fw.persistence.jdbc

import ai.icen.fw.application.retention.SecureDeletionApplicationService
import ai.icen.fw.application.retention.SecureDeletionCompletionEvidence
import ai.icen.fw.application.retention.SecureDeletionExecution
import ai.icen.fw.application.retention.SecureDeletionExecutionStatus
import ai.icen.fw.application.retention.StoredSecureDeletionReceipt
import ai.icen.fw.core.event.OutboxEvent
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.domain.retention.DeletionAuthorizationSnapshot
import ai.icen.fw.domain.retention.LegalHoldSetSnapshot
import ai.icen.fw.domain.retention.LegalHoldSnapshot
import ai.icen.fw.domain.retention.LegalHoldStatus
import ai.icen.fw.domain.retention.RetentionDeletionDecisionEngine
import ai.icen.fw.domain.retention.RetentionPolicyMode
import ai.icen.fw.domain.retention.RetentionPolicySnapshot
import ai.icen.fw.domain.retention.SecureDeletionPlan
import ai.icen.fw.domain.retention.SecureDeletionRequest
import ai.icen.fw.domain.retention.SecureDeletionStage
import ai.icen.fw.persistence.migration.FlywayMigrationRunner
import ai.icen.fw.spi.retention.SecureDeletionProviderReceipt
import ai.icen.fw.spi.retention.SecureDeletionProviderStatus
import ai.icen.fw.spi.retention.SecureDeletionTarget
import com.fasterxml.jackson.databind.ObjectMapper
import com.mysql.cj.jdbc.MysqlDataSource
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.postgresql.ds.PGSimpleDataSource
import java.io.PrintWriter
import java.sql.Connection
import java.sql.DriverManager
import java.util.logging.Logger
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** One end-to-end contract per real dialect, keeping the external lane intentionally narrow. */
abstract class SecureDeletionRepositoryDatabaseContract {
    protected lateinit var dataSource: DataSource
    private val objectMapper = ObjectMapper()

    protected abstract fun openIsolatedDatabase(): DataSource

    protected abstract fun closeIsolatedDatabase()

    @BeforeEach
    fun migrateDatabase() {
        dataSource = openIsolatedDatabase()
        FlywayMigrationRunner(dataSource).migrate()
    }

    @AfterEach
    fun releaseDatabase() {
        if (::dataSource.isInitialized) closeIsolatedDatabase()
    }

    @Test
    fun `persists tenant fenced plans receipts reconciliation and terminal evidence`() {
        val repository = JdbcSecureDeletionRepository(objectMapper)
        val outbox = JdbcOutboxEventRepository(objectMapper)
        val transaction = JdbcApplicationTransaction(dataSource)
        val plan = allowedPlan()
        val dispatchId = SecureDeletionApplicationService.dispatchEventId(plan.tenantId, plan.id)

        assertTrue(transaction.execute {
            repository.createIfAbsent(plan, dispatchId).also { inserted ->
                if (inserted) outbox.append(dispatchEvent(plan, dispatchId))
            }
        })
        assertFalse(transaction.execute { repository.createIfAbsent(plan, dispatchId) })
        assertNull(transaction.execute { repository.findByPlanId(Identifier("tenant-foreign"), plan.id) })

        val foreignTenantPlan = allowedPlan(Identifier("tenant-foreign"))
        val foreignDispatchId = SecureDeletionApplicationService.dispatchEventId(
            foreignTenantPlan.tenantId,
            foreignTenantPlan.id,
        )
        assertFalse(dispatchId == foreignDispatchId)
        assertTrue(transaction.execute {
            repository.createIfAbsent(foreignTenantPlan, foreignDispatchId).also { inserted ->
                if (inserted) outbox.append(dispatchEvent(foreignTenantPlan, foreignDispatchId))
            }
        })
        assertEquals(
            Identifier("tenant-foreign"),
            transaction.execute { repository.findByPlanId(foreignTenantPlan.tenantId, foreignTenantPlan.id) }?.tenantId,
        )

        transaction.execute { leaseDispatch(plan.tenantId, dispatchId) }
        val initial = requireNotNull(transaction.execute { repository.findForMutation(plan.tenantId, plan.id) })
        val acceptedIndex = receipt(
            initial,
            SecureDeletionStage.PURGE_INDEX_PROJECTIONS,
            initial.indexIdempotencyKey,
            SecureDeletionProviderStatus.ACCEPTED_UNVERIFIED,
            101,
        )
        transaction.execute {
            repository.save(
                execution(
                    initial,
                    SecureDeletionStage.PURGE_INDEX_PROJECTIONS,
                    SecureDeletionExecutionStatus.RECONCILING,
                    listOf(acceptedIndex),
                    101,
                ),
            )
        }

        val verifiedIndex = receipt(
            initial,
            SecureDeletionStage.PURGE_INDEX_PROJECTIONS,
            initial.indexIdempotencyKey,
            SecureDeletionProviderStatus.VERIFIED_ABSENT,
            102,
        )
        transaction.execute {
            repository.save(
                execution(
                    initial,
                    SecureDeletionStage.PURGE_OBJECT_STORAGE,
                    SecureDeletionExecutionStatus.PENDING,
                    listOf(verifiedIndex),
                    102,
                ),
            )
        }

        val acceptedObject = receipt(
            initial,
            SecureDeletionStage.PURGE_OBJECT_STORAGE,
            initial.objectIdempotencyKey,
            SecureDeletionProviderStatus.ACCEPTED_UNVERIFIED,
            103,
        )
        transaction.execute {
            repository.save(
                execution(
                    initial,
                    SecureDeletionStage.PURGE_OBJECT_STORAGE,
                    SecureDeletionExecutionStatus.RECONCILING,
                    listOf(verifiedIndex, acceptedObject),
                    103,
                ),
            )
        }

        val verifiedObject = receipt(
            initial,
            SecureDeletionStage.PURGE_OBJECT_STORAGE,
            initial.objectIdempotencyKey,
            SecureDeletionProviderStatus.VERIFIED_ABSENT,
            104,
        )
        val completed = execution(
            initial,
            SecureDeletionStage.APPEND_COMPLETION_AUDIT,
            SecureDeletionExecutionStatus.SUCCEEDED,
            listOf(verifiedIndex, verifiedObject),
            104,
        )
        transaction.execute { repository.save(completed) }
        val completionEvidence = SecureDeletionCompletionEvidence(
            id = completed.planId,
            tenantId = completed.tenantId,
            planId = completed.planId,
            tombstoneId = completed.tombstoneId,
            resourceType = completed.resourceType,
            resourceId = completed.resourceId,
            resourceRevision = completed.resourceRevision,
            completedAt = 104,
            receipts = completed.receipts,
        )
        assertTrue(transaction.execute { repository.appendCompletionEvidenceIfAbsent(completionEvidence) })
        assertFalse(transaction.execute { repository.appendCompletionEvidenceIfAbsent(completionEvidence) })

        val restored = requireNotNull(transaction.execute { repository.findByPlanId(plan.tenantId, plan.id) })
        assertEquals(SecureDeletionExecutionStatus.SUCCEEDED, restored.status)
        assertEquals(SecureDeletionStage.APPEND_COMPLETION_AUDIT, restored.currentStage)
        assertEquals(2, restored.receipts.size)
        assertTrue(restored.receipts.all { it.providerReceipt.isVerifiedAbsent() })

        val denied = deniedDecision()
        assertTrue(transaction.execute { repository.appendDecisionAuditIfAbsent(denied.auditEvidence) })
        assertFalse(transaction.execute { repository.appendDecisionAuditIfAbsent(denied.auditEvidence) })
        assertEquals(2, countRows("fw_secure_deletion_plan"))
        assertEquals(2, countRows("fw_secure_deletion_tombstone"))
        assertEquals(2, countRows("fw_secure_deletion_receipt"))
        assertEquals(4, countRows("fw_secure_deletion_audit"))

        transaction.execute {
            JdbcConnectionContext.requireCurrent().prepareStatement(
                """
                UPDATE fw_secure_deletion_receipt
                   SET request_binding_digest = ?
                 WHERE tenant_id = ? AND plan_id = ? AND deletion_stage = 'PURGE_INDEX_PROJECTIONS'
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, "0".repeat(64))
                statement.setString(2, plan.tenantId.value)
                statement.setString(3, plan.id.value)
                assertEquals(1, statement.executeUpdate())
            }
        }
        assertFailsWith<SecureDeletionPersistenceException> {
            transaction.execute { repository.findByPlanId(plan.tenantId, plan.id) }
        }
    }

    private fun execution(
        base: SecureDeletionExecution,
        stage: SecureDeletionStage,
        status: SecureDeletionExecutionStatus,
        receipts: List<StoredSecureDeletionReceipt>,
        updatedAt: Long,
    ): SecureDeletionExecution = SecureDeletionExecution(
        planId = base.planId,
        tenantId = base.tenantId,
        dispatchEventId = base.dispatchEventId,
        tombstoneId = base.tombstoneId,
        decisionEvidenceId = base.decisionEvidenceId,
        resourceType = base.resourceType,
        resourceId = base.resourceId,
        resourceRevision = base.resourceRevision,
        requestedBy = base.requestedBy,
        indexIdempotencyKey = base.indexIdempotencyKey,
        objectIdempotencyKey = base.objectIdempotencyKey,
        currentStage = stage,
        status = status,
        receipts = receipts,
        failureCount = base.failureCount,
        lastError = null,
        createdAt = base.createdAt,
        updatedAt = updatedAt,
    )

    private fun receipt(
        execution: SecureDeletionExecution,
        stage: SecureDeletionStage,
        idempotencyKey: String,
        status: SecureDeletionProviderStatus,
        recordedAt: Long,
    ): StoredSecureDeletionReceipt {
        val target = StoredSecureDeletionReceipt.targetFor(stage)
        return StoredSecureDeletionReceipt(
            stage = stage,
            idempotencyKey = idempotencyKey,
            providerReceipt = SecureDeletionProviderReceipt(
                providerId = if (target == SecureDeletionTarget.INDEX) "index-provider" else "object-provider",
                target = target,
                status = status,
                requestBindingDigest = execution.requestBindingDigestFor(stage),
                receiptReference = "opaque-${target.name.lowercase()}-$recordedAt",
                evidence = mapOf("state" to if (status == SecureDeletionProviderStatus.VERIFIED_ABSENT) "absent" else "queued"),
            ),
            recordedAt = recordedAt,
        )
    }

    private fun leaseDispatch(tenantId: Identifier, dispatchId: Identifier) {
        JdbcConnectionContext.requireCurrent().prepareStatement(
            """
            UPDATE fw_outbox_event
               SET event_status = 'RUNNING', lease_owner = 'secure-deletion-test',
                   lease_token = 'lease-token', lease_expire_time = 1000, updated_time = 100
             WHERE tenant_id = ? AND id = ?
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, tenantId.value)
            statement.setString(2, dispatchId.value)
            assertEquals(1, statement.executeUpdate())
        }
    }

    private fun dispatchEvent(plan: SecureDeletionPlan, dispatchId: Identifier) = OutboxEvent(
        id = dispatchId,
        tenantId = plan.tenantId,
        type = SecureDeletionApplicationService.SECURE_DELETION_REQUESTED_EVENT_TYPE,
        payload = mapOf(SecureDeletionApplicationService.PLAN_ID_PAYLOAD_KEY to plan.id.value),
        timestamp = plan.createdAt,
    )

    private fun allowedPlan(tenantId: Identifier = TENANT_ID): SecureDeletionPlan {
        val request = request(
            suffix = "allowed",
            holds = emptyList(),
            tenantId = tenantId,
        )
        return requireNotNull(RetentionDeletionDecisionEngine(TEST_CLOCK).evaluate(request).plan)
    }

    private fun deniedDecision() = RetentionDeletionDecisionEngine(TEST_CLOCK).evaluate(
        request(
            suffix = "denied",
            holds = listOf(
                LegalHoldSnapshot(
                    id = Identifier("hold-denied"),
                    tenantId = TENANT_ID,
                    resourceType = RESOURCE_TYPE,
                    resourceId = Identifier("resource-denied"),
                    revision = "hold-revision-1",
                    status = LegalHoldStatus.ACTIVE,
                    appliedAt = 25,
                    releasedAt = null,
                ),
            ),
        ),
    )

    private fun request(
        suffix: String,
        holds: List<LegalHoldSnapshot>,
        tenantId: Identifier = TENANT_ID,
    ): SecureDeletionRequest {
        val resourceId = Identifier("resource-$suffix")
        return SecureDeletionRequest(
            decisionEvidenceId = Identifier("decision-$suffix"),
            planId = Identifier("plan-$suffix"),
            tombstoneId = Identifier("tombstone-$suffix"),
            tenantId = tenantId,
            resourceType = RESOURCE_TYPE,
            resourceId = resourceId,
            resourceRevision = 7,
            requestedBy = Identifier("operator-1"),
            policy = RetentionPolicySnapshot(
                tenantId = tenantId,
                resourceType = RESOURCE_TYPE,
                resourceId = resourceId,
                policyId = "policy-1",
                policyRevision = "policy-revision-1",
                mode = RetentionPolicyMode.RETAIN_UNTIL,
                effectiveAt = 10,
                capturedAt = 50,
                expiresAt = 200,
                retainUntil = 75,
            ),
            legalHolds = LegalHoldSetSnapshot(
                tenantId = tenantId,
                resourceType = RESOURCE_TYPE,
                resourceId = resourceId,
                snapshotRevision = "holds-revision-1",
                observedAt = 50,
                expiresAt = 200,
                complete = true,
                holds = holds,
            ),
            authorization = DeletionAuthorizationSnapshot(
                tenantId = tenantId,
                resourceType = RESOURCE_TYPE,
                resourceId = resourceId,
                principalId = Identifier("operator-1"),
                authorizationRevision = "authorization-revision-1",
                evaluatedAt = 50,
                expiresAt = 200,
                complete = true,
                authorized = true,
            ),
        )
    }

    private fun countRows(table: String): Int = dataSource.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM $table").use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }
    }

    private companion object {
        val TENANT_ID = Identifier("tenant-1")
        const val RESOURCE_TYPE = "DOCUMENT"
        val TEST_CLOCK = java.time.Clock.fixed(
            java.time.Instant.ofEpochMilli(100),
            java.time.ZoneOffset.UTC,
        )
    }
}

class JdbcSecureDeletionRepositoryIntegrationTest : SecureDeletionRepositoryDatabaseContract() {
    override fun openIsolatedDatabase(): DataSource {
        assumeTrue(System.getenv("FILEWEFT_RUN_POSTGRES_TESTS") == "true")
        return PGSimpleDataSource().apply {
            setURL(System.getenv("FILEWEFT_POSTGRES_URL") ?: "jdbc:postgresql://localhost:5432/fileweft")
            user = System.getenv("FILEWEFT_POSTGRES_USER") ?: "fileweft"
            password = System.getenv("FILEWEFT_POSTGRES_PASSWORD") ?: "fileweft-dev"
        }.also { dataSource -> resetPostgres(dataSource.connection) }
    }

    override fun closeIsolatedDatabase() {
        resetPostgres(dataSource.connection)
    }

    private fun resetPostgres(connection: Connection) = connection.use {
        it.createStatement().use { statement ->
            statement.execute("DROP SCHEMA public CASCADE")
            statement.execute("CREATE SCHEMA public")
        }
    }
}

class JdbcSecureDeletionRepositoryMySQLIntegrationTest : SecureDeletionRepositoryDatabaseContract() {
    private lateinit var databaseName: String

    override fun openIsolatedDatabase(): DataSource {
        check(System.getenv("FILEWEFT_RUN_MYSQL_TESTS") == "true") {
            "MySQL integration tests must run only through the fail-closed Gradle task"
        }
        databaseName = System.getenv("FILEWEFT_MYSQL_DATABASE") ?: "fileweft"
        require(databaseName.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")))
        mysqlAdmin().connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("DROP DATABASE IF EXISTS `$databaseName`")
                statement.execute(
                    "CREATE DATABASE `$databaseName` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci",
                )
            }
        }
        return MysqlDataSource().apply {
            setURL(System.getenv("FILEWEFT_MYSQL_URL") ?: "jdbc:mysql://localhost:3306/$databaseName")
            user = System.getenv("FILEWEFT_MYSQL_USER") ?: "root"
            password = System.getenv("FILEWEFT_MYSQL_PASSWORD") ?: ""
        }
    }

    override fun closeIsolatedDatabase() {
        mysqlAdmin().connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("DROP DATABASE IF EXISTS `$databaseName`")
            }
        }
    }

    private fun mysqlAdmin() = MysqlDataSource().apply {
        setURL(System.getenv("FILEWEFT_MYSQL_ADMIN_URL") ?: "jdbc:mysql://localhost:3306")
        user = System.getenv("FILEWEFT_MYSQL_ADMIN_USER") ?: "root"
        password = System.getenv("FILEWEFT_MYSQL_ADMIN_PASSWORD") ?: ""
    }
}

class JdbcSecureDeletionRepositoryKingbaseIntegrationTest : SecureDeletionRepositoryDatabaseContract() {
    private lateinit var settings: KingbaseSettings

    override fun openIsolatedDatabase(): DataSource {
        check(System.getenv("FILEWEFT_RUN_KINGBASE_TESTS") == "true") {
            "Kingbase integration tests must run only through the fail-closed Gradle task"
        }
        Class.forName(System.getenv("FILEWEFT_KINGBASE_DRIVER") ?: "com.kingbase8.Driver")
        settings = KingbaseSettings(
            url = System.getenv("FILEWEFT_KINGBASE_URL") ?: "jdbc:kingbase8://localhost:54321/fileweft",
            user = System.getenv("FILEWEFT_KINGBASE_USER") ?: "system",
            password = System.getenv("FILEWEFT_KINGBASE_PASSWORD") ?: "kingbase",
            schema = System.getenv("FILEWEFT_KINGBASE_SCHEMA") ?: "public",
        )
        require(settings.schema.matches(Regex("[A-Za-z_][A-Za-z0-9_]*")))
        settings.rawConnection().use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("DROP SCHEMA IF EXISTS ${settings.schema} CASCADE")
                statement.execute("CREATE SCHEMA ${settings.schema}")
            }
        }
        return KingbaseDataSource(settings)
    }

    override fun closeIsolatedDatabase() {
        settings.rawConnection().use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("DROP SCHEMA IF EXISTS ${settings.schema} CASCADE")
                statement.execute("CREATE SCHEMA ${settings.schema}")
            }
        }
    }

    private data class KingbaseSettings(
        val url: String,
        val user: String,
        val password: String,
        val schema: String,
    ) {
        fun rawConnection(): Connection = DriverManager.getConnection(url, user, password)
    }

    private class KingbaseDataSource(private val settings: KingbaseSettings) : DataSource {
        override fun getConnection(): Connection = configure(settings.rawConnection())

        override fun getConnection(username: String, password: String): Connection =
            configure(DriverManager.getConnection(settings.url, username, password))

        private fun configure(connection: Connection): Connection = connection.apply {
            createStatement().use { it.execute("SET search_path TO ${settings.schema}") }
        }

        override fun getLogWriter(): PrintWriter? = DriverManager.getLogWriter()
        override fun setLogWriter(out: PrintWriter?) = DriverManager.setLogWriter(out)
        override fun setLoginTimeout(seconds: Int) = DriverManager.setLoginTimeout(seconds)
        override fun getLoginTimeout(): Int = DriverManager.getLoginTimeout()
        override fun getParentLogger(): Logger = Logger.getLogger("ai.icen.fw.persistence.jdbc.kingbase.retention")
        override fun <T : Any> unwrap(iface: Class<T>): T {
            if (iface.isInstance(this)) return iface.cast(this)
            throw java.sql.SQLException("Not a wrapper for ${iface.name}")
        }
        override fun isWrapperFor(iface: Class<*>): Boolean = iface.isInstance(this)
    }
}
