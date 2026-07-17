package ai.icen.fw.agent.persistence.migration

import java.io.PrintWriter
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.util.logging.Logger
import javax.sql.DataSource

/** Flyway-only PostgreSQL metadata view for the PostgreSQL-compatible KingbaseES dialect. */
internal class AgentKingbaseFlywayDataSource(
    private val delegate: DataSource,
) : DataSource {
    override fun getConnection(): Connection = delegate.connection.asCompatible()
    override fun getConnection(username: String, password: String): Connection =
        delegate.getConnection(username, password).asCompatible()
    override fun getLogWriter(): PrintWriter? = delegate.logWriter
    override fun setLogWriter(out: PrintWriter?) { delegate.logWriter = out }
    override fun setLoginTimeout(seconds: Int) { delegate.loginTimeout = seconds }
    override fun getLoginTimeout(): Int = delegate.loginTimeout
    override fun getParentLogger(): Logger = delegate.parentLogger
    override fun <T : Any?> unwrap(iface: Class<T>): T = delegate.unwrap(iface)
    override fun isWrapperFor(iface: Class<*>): Boolean = delegate.isWrapperFor(iface)

    private fun Connection.asCompatible(): Connection {
        val connection = this
        if (!connection.metaData.databaseProductName.startsWith("Kingbase", ignoreCase = true)) return connection
        return Proxy.newProxyInstance(
            AgentKingbaseFlywayDataSource::class.java.classLoader,
            arrayOf(Connection::class.java),
        ) { _, method, arguments ->
            if (method.name == "getMetaData" && method.parameterCount == 0) {
                connection.metaData.asPostgreSql()
            } else {
                method.invokeUnwrapping(connection, arguments)
            }
        } as Connection
    }

    private fun DatabaseMetaData.asPostgreSql(): DatabaseMetaData {
        val metadata = this
        return Proxy.newProxyInstance(
            AgentKingbaseFlywayDataSource::class.java.classLoader,
            arrayOf(DatabaseMetaData::class.java),
        ) { _, method, arguments ->
            if (method.name == "getDatabaseProductName" && method.parameterCount == 0) {
                "PostgreSQL"
            } else {
                method.invokeUnwrapping(metadata, arguments)
            }
        } as DatabaseMetaData
    }

    private fun Method.invokeUnwrapping(target: Any, arguments: Array<out Any?>?): Any? = try {
        invoke(target, *(arguments ?: emptyArray()))
    } catch (failure: InvocationTargetException) {
        throw failure.targetException
    }
}
