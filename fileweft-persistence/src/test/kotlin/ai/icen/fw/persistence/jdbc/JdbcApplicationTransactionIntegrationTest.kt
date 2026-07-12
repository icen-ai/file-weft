package ai.icen.fw.persistence.jdbc

import ai.icen.fw.application.transaction.ApplicationTransactionOutcomeUnknownException
import ai.icen.fw.persistence.migration.FlywayMigrationRunner
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.postgresql.ds.PGSimpleDataSource
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class JdbcApplicationTransactionIntegrationTest {
    private lateinit var dataSource: DataSource

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
    fun `commits successful work and rolls back failed work`() {
        val transaction = JdbcApplicationTransaction(dataSource)
        transaction.execute { insertOutboxEvent("event-1") }
        assertEquals(1, countOutboxEvents())

        val actionFailure = IllegalStateException("fail after write")
        val observedFailure = assertThrows<IllegalStateException> {
            transaction.execute {
                insertOutboxEvent("event-2")
                throw actionFailure
            }
        }
        assertSame(actionFailure, observedFailure)
        assertEquals(1, countOutboxEvents())
    }

    @Test
    fun `different data sources commit and roll back independently when nested`() {
        val firstTransaction = JdbcApplicationTransaction(dataSource)
        val secondDataSource = postgresDataSource()
        val secondTransaction = JdbcApplicationTransaction(secondDataSource)

        assertThrows<IllegalStateException> {
            firstTransaction.execute {
                val firstConnection = JdbcConnectionContext.requireCurrent()
                insertOutboxEvent("outer-rollback")

                assertThrows<IllegalArgumentException> {
                    secondTransaction.execute {
                        assertNotSame(firstConnection, JdbcConnectionContext.requireCurrent())
                        insertOutboxEvent("inner-rollback")
                        throw IllegalArgumentException("roll back only the inner transaction")
                    }
                }
                assertSame(firstConnection, JdbcConnectionContext.requireCurrent())

                secondTransaction.execute {
                    assertNotSame(firstConnection, JdbcConnectionContext.requireCurrent())
                    insertOutboxEvent("inner-commit")
                }
                assertSame(firstConnection, JdbcConnectionContext.requireCurrent())
                throw IllegalStateException("roll back only the outer transaction")
            }
        }

        assertEquals(0, countOutboxEvent("outer-rollback"))
        assertEquals(0, countOutboxEvent("inner-rollback"))
        assertEquals(1, countOutboxEvent("inner-commit"))
    }

    @Test
    fun `classifies a lost commit acknowledgement as unknown without rolling back committed work`() {
        val acknowledgementLost = FaultInjectingDataSource(
            delegate = dataSource,
            loseCommitAcknowledgement = true,
            failClose = true,
        )
        val transaction = JdbcApplicationTransaction(acknowledgementLost)

        val failure = assertThrows<ApplicationTransactionOutcomeUnknownException> {
            transaction.execute { insertOutboxEvent("event-commit-ack-lost") }
        }

        assertEquals(ApplicationTransactionOutcomeUnknownException.DEFAULT_MESSAGE, failure.message)
        assertIs<SQLException>(failure.cause)
        assertEquals("commit acknowledgement lost", failure.cause?.message)
        assertEquals(
            listOf("connection close failed"),
            failure.suppressed.map { it.message },
        )
        assertEquals(0, acknowledgementLost.rollbackCalls.get())
        assertEquals(0, acknowledgementLost.autoCommitRestoreCalls.get())
        assertEquals(1, acknowledgementLost.closeCalls.get())
        assertEquals(1, countOutboxEvents())
    }

    @Test
    fun `classifies a lost rollback acknowledgement as unknown after the database rolled back`() {
        val rollbackAcknowledgementLost = FaultInjectingDataSource(
            delegate = dataSource,
            loseRollbackAcknowledgement = true,
        )
        val transaction = JdbcApplicationTransaction(rollbackAcknowledgementLost)
        val actionFailure = IllegalArgumentException("action failed before rollback acknowledgement loss")

        val failure = assertThrows<ApplicationTransactionOutcomeUnknownException> {
            transaction.execute {
                insertOutboxEvent("event-rollback-ack-lost")
                throw actionFailure
            }
        }

        assertSame(actionFailure, failure.cause)
        assertEquals(listOf("rollback acknowledgement lost"), failure.suppressed.map { it.message })
        assertEquals(1, rollbackAcknowledgementLost.rollbackCalls.get())
        assertEquals(1, rollbackAcknowledgementLost.delegateRollbackCalls.get())
        assertEquals(0, rollbackAcknowledgementLost.autoCommitRestoreCalls.get())
        assertEquals(1, rollbackAcknowledgementLost.closeCalls.get())
        assertEquals(0, countOutboxEvents())
    }

    @Test
    fun `classifies a pre-delegate rollback failure as unknown and closes without auto-commit restore`() {
        val rollbackFailsBeforeDelegate = FaultInjectingDataSource(
            delegate = dataSource,
            failRollbackBeforeDelegate = true,
        )
        val transaction = JdbcApplicationTransaction(rollbackFailsBeforeDelegate)
        val actionFailure = IllegalStateException("action failed before rollback")

        val failure = assertThrows<ApplicationTransactionOutcomeUnknownException> {
            transaction.execute {
                insertOutboxEvent("event-rollback-before-delegate-fail")
                throw actionFailure
            }
        }

        assertSame(actionFailure, failure.cause)
        assertEquals(listOf("rollback failed before delegate"), failure.suppressed.map { it.message })
        assertEquals(1, rollbackFailsBeforeDelegate.rollbackCalls.get())
        assertEquals(0, rollbackFailsBeforeDelegate.delegateRollbackCalls.get())
        assertEquals(0, rollbackFailsBeforeDelegate.autoCommitRestoreCalls.get())
        assertEquals(1, rollbackFailsBeforeDelegate.closeCalls.get())
        assertEquals(0, countOutboxEvents())
    }

    @Test
    fun `reuses an action outcome-unknown exception when rollback also fails`() {
        val rollbackFails = FaultInjectingDataSource(
            delegate = dataSource,
            failRollbackBeforeDelegate = true,
        )
        val transaction = JdbcApplicationTransaction(rollbackFails)
        val actionFailure = ApplicationTransactionOutcomeUnknownException(SQLException("nested outcome unknown"))

        val failure = assertThrows<ApplicationTransactionOutcomeUnknownException> {
            transaction.execute { throw actionFailure }
        }

        assertSame(actionFailure, failure)
        assertEquals(listOf("rollback failed before delegate"), failure.suppressed.map { it.message })
        assertEquals(0, rollbackFails.autoCommitRestoreCalls.get())
        assertEquals(1, rollbackFails.closeCalls.get())
    }

    @Test
    fun `preserves action failure while attaching restore and close failures as suppressed`() {
        val cleanupFails = FaultInjectingDataSource(
            delegate = dataSource,
            failAutoCommitRestore = true,
            failClose = true,
        )
        val transaction = JdbcApplicationTransaction(cleanupFails)
        val actionFailure = IllegalArgumentException("action failed")

        val observedFailure = assertThrows<IllegalArgumentException> {
            transaction.execute {
                insertOutboxEvent("event-action-and-cleanup-fail")
                throw actionFailure
            }
        }

        assertSame(actionFailure, observedFailure)
        assertEquals(
            listOf("auto-commit restore failed", "connection close failed"),
            observedFailure.suppressed.map { it.message },
        )
        assertEquals(1, cleanupFails.rollbackCalls.get())
        assertEquals(1, cleanupFails.delegateRollbackCalls.get())
        assertEquals(1, cleanupFails.autoCommitRestoreCalls.get())
        assertEquals(1, cleanupFails.closeCalls.get())
        assertEquals(0, countOutboxEvents())
    }

    @Test
    fun `does not turn confirmed commit into a business failure when connection cleanup fails`() {
        val cleanupFails = FaultInjectingDataSource(
            delegate = dataSource,
            failAutoCommitRestore = true,
            failClose = true,
        )
        val transaction = JdbcApplicationTransaction(cleanupFails)

        val result = transaction.execute {
            insertOutboxEvent("event-confirmed-commit-cleanup-fail")
            "committed"
        }

        assertEquals("committed", result)
        assertEquals(0, cleanupFails.rollbackCalls.get())
        assertEquals(1, cleanupFails.autoCommitRestoreCalls.get())
        assertEquals(1, cleanupFails.closeCalls.get())
        assertEquals(1, countOutboxEvents())
    }

    private fun insertOutboxEvent(id: String) {
        JdbcConnectionContext.requireCurrent().prepareStatement(
            "INSERT INTO fw_outbox_event(id, tenant_id, event_type, payload_json, event_status, retry_count, created_time, updated_time) VALUES (?, 'tenant-1', 'test', '{}'::jsonb, 'PENDING', 0, 1, 1)",
        ).use { statement ->
            statement.setString(1, id)
            statement.executeUpdate()
        }
    }

    private fun countOutboxEvents(): Int = dataSource.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM fw_outbox_event").use { result ->
                result.next()
                result.getInt(1)
            }
        }
    }

    private fun countOutboxEvent(id: String): Int = dataSource.connection.use { connection ->
        connection.prepareStatement("SELECT COUNT(*) FROM fw_outbox_event WHERE id = ?").use { statement ->
            statement.setString(1, id)
            statement.executeQuery().use { result ->
                result.next()
                result.getInt(1)
            }
        }
    }

    private fun postgresDataSource(): DataSource = PGSimpleDataSource().apply {
        setURL(System.getenv("FILEWEFT_POSTGRES_URL") ?: "jdbc:postgresql://localhost:5432/fileweft")
        user = System.getenv("FILEWEFT_POSTGRES_USER") ?: "fileweft"
        password = System.getenv("FILEWEFT_POSTGRES_PASSWORD") ?: "fileweft-dev"
    }

    private fun reset(connection: Connection) = connection.use {
        it.createStatement().use { statement ->
            statement.execute("DROP SCHEMA public CASCADE")
            statement.execute("CREATE SCHEMA public")
        }
    }

    private class FaultInjectingDataSource(
        private val delegate: DataSource,
        loseCommitAcknowledgement: Boolean = false,
        loseRollbackAcknowledgement: Boolean = false,
        failRollbackBeforeDelegate: Boolean = false,
        failAutoCommitRestore: Boolean = false,
        failClose: Boolean = false,
    ) : DataSource by delegate {
        val rollbackCalls = AtomicInteger()
        val delegateRollbackCalls = AtomicInteger()
        val autoCommitRestoreCalls = AtomicInteger()
        val closeCalls = AtomicInteger()
        private val failNextCommit = AtomicBoolean(loseCommitAcknowledgement)
        private val failNextRollbackAfterDelegate = AtomicBoolean(loseRollbackAcknowledgement)
        private val failNextRollbackBeforeDelegate = AtomicBoolean(failRollbackBeforeDelegate)
        private val failNextAutoCommitRestore = AtomicBoolean(failAutoCommitRestore)
        private val failNextClose = AtomicBoolean(failClose)

        override fun getConnection(): Connection = wrap(delegate.connection)

        override fun getConnection(username: String?, password: String?): Connection =
            wrap(delegate.getConnection(username, password))

        private fun wrap(connection: Connection): Connection = Proxy.newProxyInstance(
            Connection::class.java.classLoader,
            arrayOf(Connection::class.java),
        ) { _, method, arguments ->
            when (method.name) {
                "commit" -> invoke(connection, method, arguments).also {
                    if (failNextCommit.compareAndSet(true, false)) {
                        throw SQLException("commit acknowledgement lost")
                    }
                }

                "setAutoCommit" -> {
                    val restoring = arguments?.singleOrNull() == true
                    if (restoring) autoCommitRestoreCalls.incrementAndGet()
                    invoke(connection, method, arguments).also {
                        if (restoring && failNextAutoCommitRestore.compareAndSet(true, false)) {
                            throw SQLException("auto-commit restore failed")
                        }
                    }
                }

                "close" -> {
                    closeCalls.incrementAndGet()
                    invoke(connection, method, arguments).also {
                        if (failNextClose.compareAndSet(true, false)) {
                            throw SQLException("connection close failed")
                        }
                    }
                }

                "rollback" -> {
                    rollbackCalls.incrementAndGet()
                    if (failNextRollbackBeforeDelegate.compareAndSet(true, false)) {
                        throw SQLException("rollback failed before delegate")
                    }
                    delegateRollbackCalls.incrementAndGet()
                    invoke(connection, method, arguments).also {
                        if (failNextRollbackAfterDelegate.compareAndSet(true, false)) {
                            throw SQLException("rollback acknowledgement lost")
                        }
                    }
                }

                else -> invoke(connection, method, arguments)
            }
        } as Connection

        private fun invoke(connection: Connection, method: Method, arguments: Array<out Any?>?): Any? = try {
            method.invoke(connection, *(arguments ?: emptyArray()))
        } catch (failure: InvocationTargetException) {
            throw failure.targetException
        }
    }
}
