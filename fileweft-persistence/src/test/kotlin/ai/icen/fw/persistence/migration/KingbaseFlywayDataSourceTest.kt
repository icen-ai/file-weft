package ai.icen.fw.persistence.migration

import org.junit.jupiter.api.Test
import java.io.PrintWriter
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.SQLException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Logger
import javax.sql.DataSource
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class KingbaseFlywayDataSourceTest {
    @Test
    fun `adapts only Kingbase product name and delegates connection lifecycle`() {
        val closed = AtomicBoolean(false)
        val metadata = proxy<DatabaseMetaData> { method ->
            when (method.name) {
                "getDatabaseProductName" -> "KingbaseES"
                "getURL" -> KINGBASE_URL
                "getDatabaseProductVersion" -> "V008R006C009B0014"
                "getDatabaseMajorVersion" -> 8
                "getDatabaseMinorVersion" -> 6
                "getDriverName" -> "Kingbase JDBC Driver"
                "getDriverVersion" -> "8.6.1"
                else -> defaultValue(method.returnType)
            }
        }
        val connection = proxy<Connection> { method ->
            when (method.name) {
                "getMetaData" -> metadata
                "close" -> {
                    closed.set(true)
                    null
                }
                "isClosed" -> closed.get()
                else -> defaultValue(method.returnType)
            }
        }

        KingbaseFlywayDataSource(FixedConnectionDataSource(connection)).connection.use { adapted ->
            assertEquals("PostgreSQL", adapted.metaData.databaseProductName)
            assertEquals(KINGBASE_URL, adapted.metaData.url)
            assertEquals("V008R006C009B0014", adapted.metaData.databaseProductVersion)
            assertEquals(8, adapted.metaData.databaseMajorVersion)
            assertEquals(6, adapted.metaData.databaseMinorVersion)
            assertEquals("Kingbase JDBC Driver", adapted.metaData.driverName)
            assertEquals("8.6.1", adapted.metaData.driverVersion)
        }

        assertTrue(closed.get())
    }

    @Test
    fun `credential connection overload is adapted and delegated`() {
        val metadata = proxy<DatabaseMetaData> { method ->
            when (method.name) {
                "getDatabaseProductName" -> "KingbaseES"
                else -> defaultValue(method.returnType)
            }
        }
        val connection = proxy<Connection> { method ->
            when (method.name) {
                "getMetaData" -> metadata
                else -> defaultValue(method.returnType)
            }
        }
        val delegate = FixedConnectionDataSource(connection)

        KingbaseFlywayDataSource(delegate).getConnection("host-user", "host-password").use { adapted ->
            assertEquals("PostgreSQL", adapted.metaData.databaseProductName)
        }

        assertEquals("host-user" to "host-password", delegate.lastCredentials)
    }

    @Test
    fun `non Kingbase connections retain their original identity`() {
        val metadata = proxy<DatabaseMetaData> { method ->
            when (method.name) {
                "getDatabaseProductName" -> "PostgreSQL"
                else -> defaultValue(method.returnType)
            }
        }
        val connection = proxy<Connection> { method ->
            when (method.name) {
                "getMetaData" -> metadata
                else -> defaultValue(method.returnType)
            }
        }

        assertSame(connection, KingbaseFlywayDataSource(FixedConnectionDataSource(connection)).connection)
    }

    @Test
    fun `delegated SQLExceptions are not hidden by reflection`() {
        val expected = SQLException("connection failed", "08006")
        val metadata = proxy<DatabaseMetaData> { method ->
            when (method.name) {
                "getDatabaseProductName" -> "KingbaseES"
                else -> defaultValue(method.returnType)
            }
        }
        val connection = proxy<Connection> { method ->
            when (method.name) {
                "getMetaData" -> metadata
                "commit" -> throw expected
                else -> defaultValue(method.returnType)
            }
        }
        val adapted = KingbaseFlywayDataSource(FixedConnectionDataSource(connection)).connection

        val actual = assertFailsWith<SQLException> { adapted.commit() }

        assertSame(expected, actual)
    }

    @Suppress("UNCHECKED_CAST")
    private inline fun <reified T> proxy(crossinline answer: (java.lang.reflect.Method) -> Any?): T =
        Proxy.newProxyInstance(javaClass.classLoader, arrayOf(T::class.java)) { _, method, _ ->
            answer(method)
        } as T

    private fun defaultValue(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0F
        java.lang.Double.TYPE -> 0.0
        java.lang.Character.TYPE -> 0.toChar()
        else -> null
    }

    private class FixedConnectionDataSource(
        private val connection: Connection,
    ) : DataSource {
        var lastCredentials: Pair<String?, String?>? = null
            private set

        override fun getConnection(): Connection = connection
        override fun getConnection(username: String?, password: String?): Connection {
            lastCredentials = username to password
            return connection
        }
        override fun getLogWriter(): PrintWriter? = null
        override fun setLogWriter(out: PrintWriter?) = Unit
        override fun setLoginTimeout(seconds: Int) = Unit
        override fun getLoginTimeout(): Int = 0
        override fun getParentLogger(): Logger = Logger.getAnonymousLogger()
        override fun <T : Any?> unwrap(iface: Class<T>?): T = throw UnsupportedOperationException()
        override fun isWrapperFor(iface: Class<*>?): Boolean = false
    }

    private companion object {
        const val KINGBASE_URL = "jdbc:kingbase8://localhost:54321/fileweft"
    }
}
