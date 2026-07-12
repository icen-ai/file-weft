package ai.icen.fw.persistence.jdbc

import java.lang.reflect.Proxy
import java.sql.Connection
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class JdbcConnectionContextTest {
    @Test
    fun `different data sources own independent nested transaction connections`() {
        val first = trackingDataSource("first")
        val second = trackingDataSource("second")
        val firstTransaction = JdbcApplicationTransaction(first.dataSource)
        val secondTransaction = JdbcApplicationTransaction(second.dataSource)

        firstTransaction.execute {
            val firstConnection = JdbcConnectionContext.requireCurrent()
            assertTrue(firstTransaction.isTransactionActive())
            assertFalse(secondTransaction.isTransactionActive())

            secondTransaction.execute {
                val secondConnection = JdbcConnectionContext.requireCurrent()
                assertNotSame(firstConnection, secondConnection)
                assertTrue(firstTransaction.isTransactionActive())
                assertTrue(secondTransaction.isTransactionActive())
            }

            assertSame(firstConnection, JdbcConnectionContext.requireCurrent())
            assertTrue(firstTransaction.isTransactionActive())
            assertFalse(secondTransaction.isTransactionActive())
        }

        assertFalse(firstTransaction.isTransactionActive())
        assertFalse(secondTransaction.isTransactionActive())
        assertEquals(1, first.acquisitions.get())
        assertEquals(1, second.acquisitions.get())
        assertEquals(1, first.commits.get())
        assertEquals(1, second.commits.get())
    }

    @Test
    fun `reselects a lower matching binding across A B A nesting`() {
        val first = trackingDataSource("first")
        val second = trackingDataSource("second")
        val firstTransaction = JdbcApplicationTransaction(first.dataSource)
        val secondTransaction = JdbcApplicationTransaction(second.dataSource)

        firstTransaction.execute {
            val firstConnection = JdbcConnectionContext.requireCurrent()
            secondTransaction.execute {
                val secondConnection = JdbcConnectionContext.requireCurrent()
                firstTransaction.execute {
                    assertSame(firstConnection, JdbcConnectionContext.requireCurrent())
                }
                assertSame(secondConnection, JdbcConnectionContext.requireCurrent())
            }
            assertSame(firstConnection, JdbcConnectionContext.requireCurrent())
        }

        assertEquals(1, first.acquisitions.get())
        assertEquals(1, first.commits.get())
        assertEquals(1, second.acquisitions.get())
        assertEquals(1, second.commits.get())
    }

    @Test
    fun `anonymous compatibility binding does not make every data source active`() {
        val anonymousConnection = trackingConnection("anonymous")
        val named = trackingDataSource("named")
        val transaction = JdbcApplicationTransaction(named.dataSource)

        JdbcConnectionContext.withConnection(anonymousConnection.connection) {
            assertSame(anonymousConnection.connection, JdbcConnectionContext.requireCurrent())
            assertFalse(transaction.isTransactionActive())
            transaction.execute {
                assertNotSame(anonymousConnection.connection, JdbcConnectionContext.requireCurrent())
                assertTrue(transaction.isTransactionActive())
            }
            assertSame(anonymousConnection.connection, JdbcConnectionContext.requireCurrent())
        }

        assertEquals(1, named.acquisitions.get())
        assertEquals(1, named.commits.get())
    }

    private data class TrackingDataSource(
        val dataSource: DataSource,
        val acquisitions: AtomicInteger,
        val commits: AtomicInteger,
    )

    private data class TrackingConnection(
        val connection: Connection,
        val commits: AtomicInteger,
    )

    private fun trackingDataSource(name: String): TrackingDataSource {
        val acquisitions = AtomicInteger()
        val commits = AtomicInteger()
        val dataSource = Proxy.newProxyInstance(
            DataSource::class.java.classLoader,
            arrayOf(DataSource::class.java),
        ) { proxy, method, arguments ->
            when (method.name) {
                "getConnection" -> {
                    acquisitions.incrementAndGet()
                    trackingConnection("$name-${acquisitions.get()}", commits).connection
                }
                "toString" -> "TrackingDataSource($name)"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments?.singleOrNull()
                else -> throw UnsupportedOperationException("Unexpected DataSource method: ${method.name}")
            }
        } as DataSource
        return TrackingDataSource(dataSource, acquisitions, commits)
    }

    private fun trackingConnection(
        name: String,
        commits: AtomicInteger = AtomicInteger(),
    ): TrackingConnection {
        var autoCommit = true
        var closed = false
        val connection = Proxy.newProxyInstance(
            Connection::class.java.classLoader,
            arrayOf(Connection::class.java),
        ) { proxy, method, arguments ->
            when (method.name) {
                "getAutoCommit" -> autoCommit
                "setAutoCommit" -> {
                    autoCommit = arguments?.singleOrNull() as Boolean
                    null
                }
                "commit" -> {
                    commits.incrementAndGet()
                    null
                }
                "rollback" -> null
                "close" -> {
                    closed = true
                    null
                }
                "isClosed" -> closed
                "toString" -> "TrackingConnection($name)"
                "hashCode" -> System.identityHashCode(proxy)
                "equals" -> proxy === arguments?.singleOrNull()
                else -> throw UnsupportedOperationException("Unexpected Connection method: ${method.name}")
            }
        } as Connection
        return TrackingConnection(connection, commits)
    }
}
