package ai.icen.fw.reliability.persistence.jdbc

import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReliabilityJdbcDialectTest {
    @Test
    fun `only exact vendor unique violations are reconciled`() {
        assertTrue(ReliabilityJdbcDialect.POSTGRESQL.isUniqueViolation(SQLException("duplicate", "23505")))
        assertTrue(ReliabilityJdbcDialect.KINGBASE.isUniqueViolation(SQLException("duplicate", "23505")))
        assertTrue(ReliabilityJdbcDialect.MYSQL.isUniqueViolation(SQLException("duplicate", "23000", 1062)))

        assertFalse(ReliabilityJdbcDialect.POSTGRESQL.isUniqueViolation(SQLException("check", "23514")))
        assertFalse(ReliabilityJdbcDialect.KINGBASE.isUniqueViolation(SQLException("foreign key", "23503")))
        assertFalse(ReliabilityJdbcDialect.MYSQL.isUniqueViolation(SQLException("not null", "23000", 1048)))
    }

    @Test
    fun `only transaction-wide rollback signals are retried`() {
        assertTrue(ReliabilityJdbcFailures.isRetryableRollback(SQLException("serialization", "40001")))
        assertTrue(ReliabilityJdbcFailures.isRetryableRollback(SQLException("deadlock", "40P01")))
        assertTrue(ReliabilityJdbcFailures.isRetryableRollback(SQLException("deadlock", "23000", 1213)))
        assertFalse(ReliabilityJdbcFailures.isRetryableRollback(SQLException("lock timeout", "HY000", 1205)))
        assertFalse(ReliabilityJdbcFailures.isRetryableRollback(SQLException("connection", "08006")))
    }
}
