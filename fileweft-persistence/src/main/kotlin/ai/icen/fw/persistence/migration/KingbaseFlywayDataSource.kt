package ai.icen.fw.persistence.migration

import java.io.PrintWriter
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.util.logging.Logger
import javax.sql.DataSource

/**
 * Presents a Kingbase connection to Flyway as PostgreSQL-compatible without
 * depending on Flyway's internal database-type classes.
 *
 * Only the product name is adapted. The real URL, driver/version metadata,
 * connection, SQL execution, lifecycle, and diagnostics remain untouched.
 */
internal class KingbaseFlywayDataSource(
    private val delegate: DataSource,
) : DataSource {
    override fun getConnection(): Connection = delegate.connection.asFlywayCompatibleConnection()

    override fun getConnection(username: String, password: String): Connection =
        delegate.getConnection(username, password).asFlywayCompatibleConnection()

    override fun getLogWriter(): PrintWriter? = delegate.logWriter

    override fun setLogWriter(out: PrintWriter?) {
        delegate.logWriter = out
    }

    override fun setLoginTimeout(seconds: Int) {
        delegate.loginTimeout = seconds
    }

    override fun getLoginTimeout(): Int = delegate.loginTimeout

    // java.sql.DataSource mandates java.util.logging for this method; it is a
    // pass-through to the driver, not a FileWeft logging path.
    override fun getParentLogger(): Logger = delegate.parentLogger

    override fun <T : Any?> unwrap(iface: Class<T>): T = delegate.unwrap(iface)

    override fun isWrapperFor(iface: Class<*>): Boolean = delegate.isWrapperFor(iface)

    private fun Connection.asFlywayCompatibleConnection(): Connection {
        val connection = this
        try {
            if (!connection.metaData.databaseProductName.startsWith("Kingbase", ignoreCase = true)) {
                return connection
            }
        } catch (failure: Exception) {
            try {
                connection.close()
            } catch (closeFailure: Exception) {
                failure.addSuppressed(closeFailure)
            }
            throw failure
        }
        return Proxy.newProxyInstance(
            KingbaseFlywayDataSource::class.java.classLoader,
            arrayOf(Connection::class.java),
        ) { _, method, args ->
            if (method.name == "getMetaData" && method.parameterCount == 0) {
                connection.metaData.asFlywayPostgreSqlMetadata()
            } else {
                method.invokeUnwrapping(connection, args)
            }
        } as Connection
    }

    private fun DatabaseMetaData.asFlywayPostgreSqlMetadata(): DatabaseMetaData {
        val metadata = this
        return Proxy.newProxyInstance(
            KingbaseFlywayDataSource::class.java.classLoader,
            arrayOf(DatabaseMetaData::class.java),
        ) { _, method, args ->
            if (method.name == "getDatabaseProductName" && method.parameterCount == 0) {
                POSTGRESQL_PRODUCT_NAME
            } else {
                method.invokeUnwrapping(metadata, args)
            }
        } as DatabaseMetaData
    }

    private fun Method.invokeUnwrapping(target: Any, arguments: Array<out Any?>?): Any? = try {
        invoke(target, *(arguments ?: emptyArray()))
    } catch (failure: InvocationTargetException) {
        throw failure.targetException
    }

    private companion object {
        const val POSTGRESQL_PRODUCT_NAME: String = "PostgreSQL"
    }
}
