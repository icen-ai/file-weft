package ai.icen.fw.persistence.jdbc

import ai.icen.fw.application.delivery.DocumentDeliveryStatus
import ai.icen.fw.application.delivery.DocumentDeliveryTarget
import ai.icen.fw.application.delivery.DeliveryDispatchOperation
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.persistence.migration.FlywayMigrationRunner
import ai.icen.fw.spi.delivery.DeliveryRequirement
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.postgresql.ds.PGSimpleDataSource
import java.sql.Connection
import java.sql.SQLException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JdbcDocumentDeliveryTargetRepositoryIntegrationTest {
    private lateinit var dataSource: DataSource
    private val repository = JdbcDocumentDeliveryTargetRepository(
        Clock.fixed(Instant.ofEpochMilli(100), ZoneOffset.UTC),
    )

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
    fun `round trips and advances a tenant scoped delivery dispatch fence`() {
        val transaction = JdbcApplicationTransaction(dataSource)
        val target = target("delivery-1", "target-1").also {
            it.bindInitialDelivery(Identifier("event-1"))
        }
        transaction.execute { repository.save(target) }

        val loaded = transaction.execute { repository.findById(TENANT, target.id) }
        assertEquals("event-1", loaded?.currentDispatchFence?.eventId?.value)
        assertEquals(DeliveryDispatchOperation.DELIVERY, loaded?.currentDispatchFence?.operation)
        assertEquals(1, loaded?.currentDispatchFence?.sequence)
        assertNull(transaction.execute { repository.findById(Identifier("tenant-2"), target.id) })

        val failed = requireNotNull(loaded)
        failed.markFailed("downstream rejected")
        failed.retryManually(Identifier("event-2"))
        transaction.execute { repository.save(failed) }

        val retried = transaction.execute { repository.findForMutation(TENANT, target.id) }
        assertEquals(DocumentDeliveryStatus.PENDING, retried?.status)
        assertEquals("event-2", retried?.currentDispatchFence?.eventId?.value)
        assertEquals(2, retried?.currentDispatchFence?.sequence)
        assertTrue(retried?.matchesDispatch(Identifier("event-2"), DeliveryDispatchOperation.DELIVERY, 2) == true)
    }

    @Test
    fun `refuses to persist a target before its initial event is bound`() {
        val transaction = JdbcApplicationTransaction(dataSource)

        assertFailsWith<IllegalArgumentException> {
            transaction.execute { repository.save(target("delivery-unfenced", "target-unfenced")) }
        }
    }

    @Test
    fun `rejects a new id colliding with an existing target business identity`() {
        val transaction = JdbcApplicationTransaction(dataSource)
        val original = target("delivery-original", "shared-target").also {
            it.bindInitialDelivery(Identifier("event-original"))
        }
        transaction.execute { repository.save(original) }
        val conflicting = target("delivery-conflicting", "shared-target").also {
            it.bindInitialDelivery(Identifier("event-conflicting"))
        }

        assertFailsWith<IllegalStateException> {
            transaction.execute { repository.save(conflicting) }
        }

        val persisted = transaction.execute { repository.findById(TENANT, original.id) }
        assertEquals(original.id, persisted?.id)
        assertEquals("event-original", persisted?.currentDispatchFence?.eventId?.value)
        assertNull(transaction.execute { repository.findById(TENANT, conflicting.id) })
    }

    @Test
    fun `holds the mutation row lock until the caller transaction completes`() {
        val transaction = JdbcApplicationTransaction(dataSource)
        val target = target("delivery-lock", "target-lock").also {
            it.bindInitialDelivery(Identifier("event-lock"))
        }
        transaction.execute { repository.save(target) }

        dataSource.connection.use { firstConnection ->
            firstConnection.autoCommit = false
            JdbcConnectionContext.withConnection(firstConnection) {
                assertEquals(target.id, repository.findForMutation(TENANT, target.id)?.id)
            }
            val started = CountDownLatch(1)
            val executor = Executors.newSingleThreadExecutor()
            try {
                val second = executor.submit<DocumentDeliveryTarget?> {
                    dataSource.connection.use { secondConnection ->
                        secondConnection.autoCommit = false
                        secondConnection.createStatement().use { statement ->
                            statement.execute("SET LOCAL statement_timeout = '5s'")
                        }
                        try {
                            started.countDown()
                            val locked = JdbcConnectionContext.withConnection(secondConnection) {
                                repository.findForMutation(TENANT, target.id)
                            }
                            secondConnection.commit()
                            locked
                        } catch (failure: Throwable) {
                            secondConnection.rollback()
                            throw failure
                        }
                    }
                }
                assertTrue(started.await(5, TimeUnit.SECONDS))
                assertFailsWith<TimeoutException> { second.get(250, TimeUnit.MILLISECONDS) }

                firstConnection.commit()
                assertEquals(target.id, second.get(5, TimeUnit.SECONDS)?.id)
            } finally {
                executor.shutdownNow()
                firstConnection.rollback()
            }
        }
    }

    @Test
    fun `database constraints reject duplicate events invalid operations and impossible removal state`() {
        val transaction = JdbcApplicationTransaction(dataSource)
        transaction.execute {
            repository.save(target("delivery-a", "target-a").also { it.bindInitialDelivery(Identifier("event-a")) })
            repository.save(target("delivery-b", "target-b").also { it.bindInitialDelivery(Identifier("event-b")) })
        }

        assertSqlRejected(
            "UPDATE fw_document_delivery_target SET current_event_id = 'event-a' WHERE tenant_id = 'tenant-1' AND id = 'delivery-b'",
        )
        assertSqlRejected(
            "UPDATE fw_document_delivery_target SET current_operation = 'UNKNOWN' WHERE tenant_id = 'tenant-1' AND id = 'delivery-a'",
        )
        assertSqlRejected(
            "UPDATE fw_document_delivery_target SET dispatch_sequence = 0 WHERE tenant_id = 'tenant-1' AND id = 'delivery-a'",
        )
        assertSqlRejected(
            "UPDATE fw_document_delivery_target SET removal_status = 'PENDING' WHERE tenant_id = 'tenant-1' AND id = 'delivery-a'",
        )
    }

    private fun assertSqlRejected(sql: String) {
        assertFailsWith<SQLException> {
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement -> statement.executeUpdate(sql) }
            }
        }
    }

    private fun target(id: String, targetId: String) = DocumentDeliveryTarget(
        id = Identifier(id),
        tenantId = TENANT,
        documentId = Identifier("document-1"),
        profileId = "profile-1",
        targetId = targetId,
        displayName = targetId,
        connectorId = "connector-$targetId",
        requirement = DeliveryRequirement.REQUIRED,
    )

    private fun reset(connection: Connection) = connection.use {
        it.createStatement().use { statement ->
            statement.execute("DROP SCHEMA public CASCADE")
            statement.execute("CREATE SCHEMA public")
        }
    }

    private companion object {
        val TENANT = Identifier("tenant-1")
    }
}
