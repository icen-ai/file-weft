package ai.icen.fw.observability.jdbc

import ai.icen.fw.observability.SystemDoctorCapability
import ai.icen.fw.observability.SystemDoctorCode
import ai.icen.fw.observability.SystemDoctorReadiness
import ai.icen.fw.observability.SystemDoctorScope
import org.h2.jdbcx.JdbcDataSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JdbcDatabaseAndHistoryProbeTest {
    @Test
    fun `database and complete migration inventory return bounded healthy evidence`() {
        val fixture = JdbcProbeTestFixture()
        fixture.execute(
            "CREATE TABLE fw_schema_history(migration_version varchar(32) NOT NULL, success boolean NOT NULL)",
            "INSERT INTO fw_schema_history(migration_version, success) VALUES ('1', true), ('2', true)",
        )
        val access = fixture.access()
        val database = JdbcDatabaseSystemDoctorProbe(access, "jdbc-database")
        val history = JdbcMigrationHistorySystemDoctorProbe(access, "jdbc-history", historyDefinition())
        val request = fixture.request(SystemDoctorScope.TENANT)

        val databaseReport = fixture.doctor(
            request,
            listOf(database, history),
            SystemDoctorCapability.DATABASE,
        ).inspectTenant(request)
        val historyReport = fixture.doctor(
            request,
            listOf(database, history),
            SystemDoctorCapability.HISTORY,
        ).inspectTenant(request)

        assertEquals(SystemDoctorReadiness.READY, databaseReport.readiness)
        assertTrue(databaseReport.findings.any { finding -> finding.code == SystemDoctorCode.DATABASE_AVAILABLE })
        assertEquals(SystemDoctorReadiness.READY, historyReport.readiness)
        assertTrue(
            historyReport.findings.any { finding ->
                finding.code == SystemDoctorCode.HISTORY_COMPLETE && finding.count == 2L
            },
        )
        assertFalse(historyReport.toString().contains("fw_schema_history"))
    }

    @Test
    fun `failed or missing migrations never report healthy history`() {
        val fixture = JdbcProbeTestFixture()
        fixture.execute(
            "CREATE TABLE fw_schema_history(migration_version varchar(32) NOT NULL, success boolean NOT NULL)",
            "INSERT INTO fw_schema_history(migration_version, success) VALUES ('1', true), ('3', false)",
        )
        val probe = JdbcMigrationHistorySystemDoctorProbe(fixture.access(), "jdbc-history", historyDefinition())
        val request = fixture.request(SystemDoctorScope.SYSTEM)

        val report = fixture.doctor(
            request,
            listOf(probe),
            SystemDoctorCapability.HISTORY,
        ).inspectSystem(request)

        assertEquals(SystemDoctorReadiness.NOT_READY, report.readiness)
        assertTrue(
            report.findings.any { finding ->
                finding.code == SystemDoctorCode.HISTORY_INCOMPLETE && finding.count == 2L
            },
        )
        assertFalse(report.findings.any { finding -> finding.code == SystemDoctorCode.HISTORY_COMPLETE })
    }

    @Test
    fun `unknown dialect and unreadable credentials become stable non-healthy states`() {
        val fixture = JdbcProbeTestFixture()
        fixture.dataSource.connection.use { }
        val wrongDialect = JdbcDatabaseSystemDoctorProbe(
            fixture.access(dialect = JdbcSystemDoctorDialect.POSTGRESQL),
            "wrong-dialect",
        )
        val wrongDialectRequest = fixture.request(SystemDoctorScope.SYSTEM)

        val unsupported = fixture.doctor(
            wrongDialectRequest,
            listOf(wrongDialect),
            SystemDoctorCapability.DATABASE,
        ).inspectSystem(wrongDialectRequest)

        val deniedSource = JdbcDataSource().also { source ->
            source.setURL(fixture.dataSource.getURL())
            source.user = "sa"
            source.password = "wrong-password"
        }
        val denied = JdbcDatabaseSystemDoctorProbe(fixture.access(deniedSource), "denied-database")
        val deniedRequest = fixture.request(SystemDoctorScope.SYSTEM)
        val unavailable = fixture.doctor(
            deniedRequest,
            listOf(denied),
            SystemDoctorCapability.DATABASE,
        ).inspectSystem(deniedRequest)

        assertEquals(SystemDoctorReadiness.NOT_READY, unsupported.readiness)
        assertTrue(unsupported.findings.any { finding -> finding.code == SystemDoctorCode.PROBE_UNSUPPORTED })
        assertEquals(SystemDoctorReadiness.NOT_READY, unavailable.readiness)
        assertTrue(unavailable.findings.any { finding -> finding.code == SystemDoctorCode.PROBE_UNAVAILABLE })
        val rendered = unavailable.toString() + unavailable.findings.joinToString()
        assertFalse(rendered.contains("wrong-password"))
        assertFalse(rendered.contains("jdbc:h2"))
    }

    @Test
    fun `missing history table is explicit unsupported rather than empty healthy history`() {
        val fixture = JdbcProbeTestFixture()
        val probe = JdbcMigrationHistorySystemDoctorProbe(fixture.access(), "jdbc-history", historyDefinition())
        val request = fixture.request(SystemDoctorScope.SYSTEM)

        val report = fixture.doctor(
            request,
            listOf(probe),
            SystemDoctorCapability.HISTORY,
        ).inspectSystem(request)

        assertEquals(SystemDoctorReadiness.NOT_READY, report.readiness)
        assertTrue(report.findings.any { finding -> finding.code == SystemDoctorCode.PROBE_UNSUPPORTED })
    }

    private fun historyDefinition(): JdbcMigrationHistoryDefinition = JdbcMigrationHistoryDefinition(
        JdbcTrustedTable.of("fw_schema_history"),
        JdbcTrustedSqlIdentifier.of("migration_version"),
        JdbcTrustedSqlIdentifier.of("success"),
        listOf(JdbcTrustedValue.of("1"), JdbcTrustedValue.of("2")),
    )
}
