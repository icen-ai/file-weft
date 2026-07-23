package ai.icen.fw.persistence.jdbc

import ai.icen.fw.application.idempotency.IdempotencyResult
import ai.icen.fw.application.idempotency.IdempotencyStoreException
import ai.icen.fw.application.idempotency.RequestFingerprint
import ai.icen.fw.application.idempotency.RequestIdempotency
import ai.icen.fw.application.idempotency.RequestIdempotencyClaim
import ai.icen.fw.application.idempotency.RequestIdempotencyStatus
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.persistence.migration.FlywayMigrationRunner
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.postgresql.ds.PGSimpleDataSource
import org.postgresql.util.PSQLException
import java.sql.Connection
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JdbcRequestIdempotencyRepositoryIntegrationTest {
    private lateinit var dataSource: DataSource
    private val repository = JdbcRequestIdempotencyRepository()

    @BeforeEach
    fun prepareSchema() {
        assumeTrue(System.getenv("FILEWEFT_RUN_POSTGRES_TESTS") == "true")
        dataSource = PGSimpleDataSource().apply {
            setURL(System.getenv("FILEWEFT_POSTGRES_URL") ?: "jdbc:postgresql://localhost:5432/fileweft")
            user = System.getenv("FILEWEFT_POSTGRES_USER") ?: "fileweft"
            password = System.getenv("FILEWEFT_POSTGRES_PASSWORD") ?: "fileweft-dev"
        }
        reset(dataSource.connection)
        FlywayMigrationRunner(dataSource).migrate()
    }

    @AfterEach
    fun cleanSchema() {
        if (::dataSource.isInitialized) reset(dataSource.connection)
    }

    @Test
    fun `round trips a completed request result and replays the tenant scoped record`() {
        val transaction = JdbcApplicationTransaction(dataSource)
        val request = request("client-key-roundtrip")
        val completed = transaction.execute {
            val claim = repository.claim(request, Identifier("idempotency-1"), 100)
            assertTrue(claim.acquired)
            assertEquals(RequestIdempotencyStatus.IN_PROGRESS, claim.record.status)
            assertNull(claim.record.result)
            repository.complete(
                claim.record.id,
                request.tenantId,
                request.keyDigest,
                IdempotencyResult(
                    "DOCUMENT",
                    Identifier("document-1"),
                    "WORKFLOW",
                    Identifier("workflow-1"),
                    Identifier("task-1"),
                ),
                110,
            )
        }

        assertEquals(RequestIdempotencyStatus.COMPLETED, completed.status)
        assertEquals(110, completed.completedTime)
        assertEquals("DOCUMENT", completed.result?.resourceType)
        assertEquals(Identifier("document-1"), completed.result?.resourceId)
        assertEquals("WORKFLOW", completed.result?.relatedResourceType)
        assertEquals(Identifier("workflow-1"), completed.result?.relatedResourceId)
        assertEquals(Identifier("task-1"), completed.result?.subresourceId)

        val replay = transaction.execute {
            repository.claim(request, Identifier("idempotency-replay"), 120)
        }
        assertFalse(replay.acquired)
        assertEquals(completed.id, replay.record.id)
        assertEquals(completed.result?.resourceId, replay.record.result?.resourceId)
        assertEquals(Identifier("task-1"), replay.record.result?.subresourceId)
        assertNull(transaction.execute { repository.findByKeyDigest(Identifier("tenant-foreign"), request.keyDigest) })
    }

    @Test
    fun `allows the same digest in separate tenants without exposing either record`() {
        val transaction = JdbcApplicationTransaction(dataSource)
        val first = request("shared-client-key", Identifier("tenant-1"))
        val second = request("shared-client-key", Identifier("tenant-2"))
        assertNotEquals(first.keyDigest, second.keyDigest)

        transaction.execute {
            completeClaim(first, "idempotency-tenant-1", "document-1", 100)
            completeClaim(second, "idempotency-tenant-2", "document-2", 101)
        }

        val firstRecord = transaction.execute { repository.findByKeyDigest(first.tenantId, first.keyDigest) }
        val secondRecord = transaction.execute { repository.findByKeyDigest(second.tenantId, second.keyDigest) }
        assertEquals(Identifier("document-1"), firstRecord?.result?.resourceId)
        assertEquals(Identifier("document-2"), secondRecord?.result?.resourceId)
        // Results without a subresource slot round trip as null.
        assertNull(firstRecord?.result?.subresourceId)
        assertNull(secondRecord?.result?.subresourceId)
        assertEquals(2, countRows("fw_idempotency_record"))
    }

    @Test
    fun `blocks a concurrent claim until the first transaction commits then returns its result`() {
        val request = request("concurrent-commit")
        dataSource.connection.use { firstConnection ->
            firstConnection.autoCommit = false
            val firstCompleted = JdbcConnectionContext.withConnection(firstConnection) {
                val claim = repository.claim(request, Identifier("idempotency-first"), 100)
                assertTrue(claim.acquired)
                repository.complete(
                    claim.record.id,
                    request.tenantId,
                    request.keyDigest,
                    IdempotencyResult("DOCUMENT", Identifier("document-first")),
                    110,
                )
            }
            val started = CountDownLatch(1)
            val executor = Executors.newSingleThreadExecutor()
            try {
                val second = executor.submit<RequestIdempotencyClaim> {
                    dataSource.connection.use { secondConnection ->
                        secondConnection.autoCommit = false
                        setStatementTimeout(secondConnection)
                        try {
                            started.countDown()
                            val claim = JdbcConnectionContext.withConnection(secondConnection) {
                                repository.claim(request, Identifier("idempotency-second"), 120)
                            }
                            secondConnection.commit()
                            claim
                        } catch (failure: Throwable) {
                            secondConnection.rollback()
                            throw failure
                        }
                    }
                }
                assertTrue(started.await(5, TimeUnit.SECONDS))
                assertFailsWith<TimeoutException> { second.get(250, TimeUnit.MILLISECONDS) }

                firstConnection.commit()
                val replay = second.get(5, TimeUnit.SECONDS)
                assertFalse(replay.acquired)
                assertEquals(firstCompleted.id, replay.record.id)
                assertEquals(Identifier("document-first"), replay.record.result?.resourceId)
                assertEquals(1, countRows("fw_idempotency_record"))
            } finally {
                executor.shutdownNow()
                firstConnection.rollback()
            }
        }
    }

    @Test
    fun `lets a blocked claimant acquire the key after the first transaction rolls back`() {
        val request = request("concurrent-rollback")
        dataSource.connection.use { firstConnection ->
            firstConnection.autoCommit = false
            JdbcConnectionContext.withConnection(firstConnection) {
                val claim = repository.claim(request, Identifier("idempotency-abandoned"), 100)
                assertTrue(claim.acquired)
            }
            val started = CountDownLatch(1)
            val executor = Executors.newSingleThreadExecutor()
            try {
                val second = executor.submit<Pair<RequestIdempotencyClaim, Identifier>> {
                    dataSource.connection.use { secondConnection ->
                        secondConnection.autoCommit = false
                        setStatementTimeout(secondConnection)
                        try {
                            started.countDown()
                            val outcome = JdbcConnectionContext.withConnection(secondConnection) {
                                val claim = repository.claim(request, Identifier("idempotency-owner"), 120)
                                val completed = repository.complete(
                                    claim.record.id,
                                    request.tenantId,
                                    request.keyDigest,
                                    IdempotencyResult("DOCUMENT", Identifier("document-owner")),
                                    130,
                                )
                                claim to completed.id
                            }
                            secondConnection.commit()
                            outcome
                        } catch (failure: Throwable) {
                            secondConnection.rollback()
                            throw failure
                        }
                    }
                }
                assertTrue(started.await(5, TimeUnit.SECONDS))
                assertFailsWith<TimeoutException> { second.get(250, TimeUnit.MILLISECONDS) }

                firstConnection.rollback()
                val (claim, completedId) = second.get(5, TimeUnit.SECONDS)
                assertTrue(claim.acquired)
                assertEquals(Identifier("idempotency-owner"), completedId)
                assertEquals(Identifier("idempotency-owner"), claim.record.id)
                assertEquals(1, countRows("fw_idempotency_record"))
            } finally {
                executor.shutdownNow()
                firstConnection.rollback()
            }
        }
    }

    @Test
    fun `rolls back the claim with business writes and permits a clean retry`() {
        val request = request("business-rollback")
        val transaction = JdbcApplicationTransaction(dataSource)

        assertFailsWith<ExpectedBusinessFailure> {
            transaction.execute {
                val claim = repository.claim(request, Identifier("idempotency-failed"), 100)
                assertTrue(claim.acquired)
                appendBusinessMarker("operation-failed")
                throw ExpectedBusinessFailure()
            }
        }

        assertNull(transaction.execute { repository.findByKeyDigest(request.tenantId, request.keyDigest) })
        assertEquals(0, countRows("fw_operation_log"))

        val retried = transaction.execute {
            val claim = repository.claim(request, Identifier("idempotency-retry"), 120)
            assertTrue(claim.acquired)
            appendBusinessMarker("operation-succeeded")
            val completed = repository.complete(
                claim.record.id,
                request.tenantId,
                request.keyDigest,
                IdempotencyResult("DOCUMENT", Identifier("document-retry")),
                121,
            )
            RequestIdempotencyClaim(completed, true)
        }
        assertEquals(RequestIdempotencyStatus.COMPLETED, retried.record.status)
        assertEquals(1, countRows("fw_operation_log"))
        assertEquals(1, countRows("fw_idempotency_record"))
    }

    @Test
    fun `stores only digests and rejects a raw key in digest columns`() {
        val rawKey = "customer-visible-secret-key"
        val request = request(rawKey)
        JdbcApplicationTransaction(dataSource).execute {
            completeClaim(request, "idempotency-digest", "document-digest", 100)
        }

        dataSource.connection.use { connection ->
            val columns = connection.metaData.getColumns(null, "public", "fw_idempotency_record", null).use { result ->
                buildList {
                    while (result.next()) add(result.getString("COLUMN_NAME"))
                }
            }
            assertFalse(columns.contains("idempotency_key"))
            connection.prepareStatement(
                "SELECT to_jsonb(idem)::text FROM fw_idempotency_record AS idem WHERE tenant_id = ? AND key_digest = ?",
            ).use { statement ->
                statement.setString(1, request.tenantId.value)
                statement.setString(2, request.keyDigest)
                statement.executeQuery().use { result ->
                    assertTrue(result.next())
                    val persisted = result.getString(1)
                    assertFalse(persisted.contains(rawKey))
                    assertTrue(persisted.contains(request.keyDigest))
                }
            }
            val invalid = assertFailsWith<PSQLException> {
                connection.prepareStatement(
                    """
                    INSERT INTO fw_idempotency_record(
                        id, tenant_id, key_digest, operator_id, action, resource_type, resource_id,
                        request_fingerprint, record_status, created_time, updated_time
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'IN_PROGRESS', 1, 1)
                    """.trimIndent(),
                ).use { statement ->
                    statement.setString(1, "idempotency-raw")
                    statement.setString(2, "tenant-1")
                    statement.setString(3, rawKey)
                    statement.setString(4, "operator-1")
                    statement.setString(5, "document:submit")
                    statement.setString(6, "DOCUMENT")
                    statement.setString(7, "document-raw")
                    statement.setString(8, request.requestFingerprint)
                    statement.executeUpdate()
                }
            }
            assertEquals("23514", invalid.sqlState)
        }
    }

    @Test
    fun `rejects malformed binding values at the database boundary`() {
        val request = request("database-binding")
        listOf(
            "1invalid" to "operator-1",
            "document:submit" to "\toperator-1",
        ).forEachIndexed { index, (action, operatorId) ->
            val failure = assertFailsWith<PSQLException> {
                dataSource.connection.use { connection ->
                    connection.prepareStatement(
                        """
                        INSERT INTO fw_idempotency_record(
                            id, tenant_id, key_digest, operator_id, action, resource_type, resource_id,
                            request_fingerprint, record_status, created_time, updated_time
                        ) VALUES (?, ?, ?, ?, ?, 'DOCUMENT', 'document-1', ?, 'IN_PROGRESS', 1, 1)
                        """.trimIndent(),
                    ).use { statement ->
                        statement.setString(1, "idempotency-invalid-$index")
                        statement.setString(2, request.tenantId.value)
                        statement.setString(3, RequestFingerprint.sha256("invalid-$index"))
                        statement.setString(4, operatorId)
                        statement.setString(5, action)
                        statement.setString(6, request.requestFingerprint)
                        statement.executeUpdate()
                    }
                }
            }
            assertEquals("23514", failure.sqlState)
        }
    }

    @Test
    fun `classifies malformed persisted rows and impossible completion as store failures`() {
        val request = request("corrupt-row")
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("ALTER TABLE fw_idempotency_record DROP CONSTRAINT ck_fw_idempotency_binding")
            }
            connection.prepareStatement(
                """
                INSERT INTO fw_idempotency_record(
                    id, tenant_id, key_digest, operator_id, action, resource_type, resource_id,
                    request_fingerprint, record_status, created_time, updated_time
                ) VALUES ('idempotency-corrupt', ?, ?, 'operator-1', '1invalid', 'DOCUMENT', 'document-1', ?, 'IN_PROGRESS', 1, 1)
                """.trimIndent(),
            ).use { statement ->
                statement.setString(1, request.tenantId.value)
                statement.setString(2, request.keyDigest)
                statement.setString(3, request.requestFingerprint)
                statement.executeUpdate()
            }
        }

        val malformed = assertFailsWith<IdempotencyStoreException> {
            JdbcApplicationTransaction(dataSource).execute {
                repository.findByKeyDigest(request.tenantId, request.keyDigest)
            }
        }
        assertTrue(malformed.cause is IllegalArgumentException)

        assertFailsWith<IdempotencyStoreException> {
            JdbcApplicationTransaction(dataSource).execute {
                repository.complete(
                    Identifier("missing-record"),
                    Identifier("tenant-1"),
                    RequestFingerprint.sha256("missing-key"),
                    IdempotencyResult("DOCUMENT", Identifier("document-1")),
                    10,
                )
            }
        }
    }

    private fun completeClaim(
        request: RequestIdempotency,
        recordId: String,
        resultResourceId: String,
        now: Long,
    ): RequestIdempotencyClaim {
        val claim = repository.claim(request, Identifier(recordId), now)
        assertTrue(claim.acquired)
        val completed = repository.complete(
            claim.record.id,
            request.tenantId,
            request.keyDigest,
            IdempotencyResult("DOCUMENT", Identifier(resultResourceId)),
            now + 1,
        )
        return RequestIdempotencyClaim(completed, true)
    }

    private fun request(rawKey: String, tenantId: Identifier = Identifier("tenant-1")) = RequestIdempotency.create(
        tenantId = tenantId,
        operatorId = Identifier("operator-1"),
        idempotencyKey = rawKey,
        action = "document:submit",
        resourceType = "DOCUMENT",
        resourceId = Identifier("document-1"),
        requestFingerprint = RequestFingerprint.sha256("document:submit", "document-1"),
        subresourceId = null,
    )

    private fun appendBusinessMarker(id: String) {
        JdbcConnectionContext.requireCurrent().prepareStatement(
            """
            INSERT INTO fw_operation_log(
                id, tenant_id, resource_type, resource_id, action, detail_json, created_time
            ) VALUES (?, 'tenant-1', 'DOCUMENT', 'document-1', 'document:test', '{}'::jsonb, 100)
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, id)
            statement.executeUpdate()
        }
    }

    private fun setStatementTimeout(connection: Connection) {
        connection.createStatement().use { statement -> statement.execute("SET LOCAL statement_timeout = '5s'") }
    }

    private fun countRows(table: String): Int = dataSource.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM $table").use { result ->
                result.next()
                result.getInt(1)
            }
        }
    }

    private fun reset(connection: Connection) = connection.use {
        it.createStatement().use { statement ->
            statement.execute("DROP SCHEMA public CASCADE")
            statement.execute("CREATE SCHEMA public")
        }
    }

    private class ExpectedBusinessFailure : RuntimeException()
}
