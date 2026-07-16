package ai.icen.fw.governance.persistence.jdbc

import java.io.PrintWriter
import java.sql.Connection
import java.sql.SQLException
import java.util.logging.Logger
import javax.sql.DataSource
import kotlin.test.Test
import kotlin.test.assertFailsWith

class GovernanceJdbcPublicFailureBoundaryTest {
    @Test
    fun `public repository wraps checked connection failures for runtime fail-closed handling`() {
        val repository = JdbcGovernancePersistence(FailingDataSource())

        assertFailsWith<GovernanceJdbcPersistenceException> {
            repository.load("tenant-a", "plan-a")
        }
        assertFailsWith<GovernanceJdbcPersistenceException> {
            repository.findByIdempotency("tenant-a", "request-a")
        }
    }

    private class FailingDataSource : DataSource {
        override fun getConnection(): Connection = throw SQLException("simulated unavailable database")
        override fun getConnection(username: String?, password: String?): Connection = connection
        override fun getLogWriter(): PrintWriter? = null
        override fun setLogWriter(out: PrintWriter?) = Unit
        override fun setLoginTimeout(seconds: Int) = Unit
        override fun getLoginTimeout(): Int = 0
        override fun getParentLogger(): Logger = Logger.getGlobal()
        override fun <T : Any?> unwrap(iface: Class<T>?): T = throw SQLException("not a wrapper")
        override fun isWrapperFor(iface: Class<*>?): Boolean = false
    }
}
