package ai.icen.fw.observability.jdbc

import ai.icen.fw.observability.SystemDoctorCapability
import ai.icen.fw.observability.SystemDoctorCode
import ai.icen.fw.observability.SystemDoctorReadiness
import ai.icen.fw.observability.SystemDoctorScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JdbcQueueAndLeaseProbeTest {
    @Test
    fun `tenant queue snapshot is fenced while authorized system scope can aggregate all tenants`() {
        val fixture = JdbcProbeTestFixture()
        fixture.execute(
            """CREATE TABLE fw_outbox_event(
                tenant_id varchar(64) NOT NULL,
                event_status varchar(32) NOT NULL,
                next_attempt_time bigint,
                created_time bigint NOT NULL
            )""".trimIndent(),
            "INSERT INTO fw_outbox_event VALUES ('tenant-a', 'PENDING', 9000, 9000)",
            "INSERT INTO fw_outbox_event VALUES ('tenant-b', 'PENDING', 1000, 1000)",
            "INSERT INTO fw_outbox_event VALUES ('tenant-b', 'PENDING', 2000, 2000)",
        )
        var boundTenant: String? = null
        val binding = JdbcTenantBindingPort { tenantId ->
            boundTenant = tenantId.value
            JdbcTenantBinding.of(tenantId.value)
        }
        val probe = JdbcQueueSystemDoctorProbe(
            fixture.access(),
            "jdbc-outbox",
            listOf(outboxDefinition(binding)),
        )

        val tenantRequest = fixture.request(SystemDoctorScope.TENANT, "tenant-a")
        val tenantReport = fixture.doctor(
            tenantRequest,
            listOf(probe),
            SystemDoctorCapability.OUTBOX_QUEUE,
        ).inspectTenant(tenantRequest)
        val systemRequest = fixture.request(SystemDoctorScope.SYSTEM)
        val systemReport = fixture.doctor(
            systemRequest,
            listOf(probe),
            SystemDoctorCapability.OUTBOX_QUEUE,
        ).inspectSystem(systemRequest)

        assertEquals(SystemDoctorReadiness.READY, tenantReport.readiness)
        assertEquals("tenant-a", boundTenant)
        assertTrue(
            tenantReport.findings.any { finding ->
                finding.code == SystemDoctorCode.QUEUE_BACKLOG_WITHIN_LIMIT && finding.count == 1L
            },
        )
        assertEquals(SystemDoctorReadiness.NOT_READY, systemReport.readiness)
        assertTrue(
            systemReport.findings.any { finding ->
                finding.code == SystemDoctorCode.QUEUE_BACKLOG_HIGH && finding.count == 3L
            },
        )
        val rendered = tenantReport.toString() + tenantReport.findings.joinToString()
        assertFalse(rendered.contains("tenant-a"))
        assertFalse(rendered.contains("tenant-b"))
    }

    @Test
    fun `expired or stuck leases are tenant fenced and block system readiness`() {
        val fixture = JdbcProbeTestFixture()
        fixture.execute(
            """CREATE TABLE fw_task(
                tenant_id varchar(64) NOT NULL,
                task_status varchar(32) NOT NULL,
                lease_expire_time bigint,
                updated_time bigint NOT NULL
            )""".trimIndent(),
            "INSERT INTO fw_task VALUES ('tenant-a', 'RUNNING', 20000, 9500)",
            "INSERT INTO fw_task VALUES ('tenant-b', 'RUNNING', 9000, 9500)",
            "INSERT INTO fw_task VALUES ('tenant-b', 'RUNNING', 20000, 1000)",
        )
        val definition = JdbcWorkerLeaseDefinition(
            JdbcTrustedTable.of("fw_task"),
            JdbcTrustedSqlIdentifier.of("tenant_id"),
            JdbcTrustedSqlIdentifier.of("task_status"),
            JdbcTrustedSqlIdentifier.of("lease_expire_time"),
            JdbcTrustedSqlIdentifier.of("updated_time"),
            listOf(JdbcTrustedValue.of("RUNNING")),
            5_000L,
            "plain-tenant-v1",
            JdbcTenantBindingPort.IDENTIFIER_VALUE,
        )
        val probe = JdbcWorkerLeaseSystemDoctorProbe(fixture.access(), "jdbc-leases", listOf(definition))
        val tenantRequest = fixture.request(SystemDoctorScope.TENANT, "tenant-a")
        val tenantReport = fixture.doctor(
            tenantRequest,
            listOf(probe),
            SystemDoctorCapability.WORKER_LEASE,
        ).inspectTenant(tenantRequest)
        val systemRequest = fixture.request(SystemDoctorScope.SYSTEM)
        val systemReport = fixture.doctor(
            systemRequest,
            listOf(probe),
            SystemDoctorCapability.WORKER_LEASE,
        ).inspectSystem(systemRequest)

        assertEquals(SystemDoctorReadiness.READY, tenantReport.readiness)
        assertTrue(tenantReport.findings.any { it.code == SystemDoctorCode.WORKER_LEASE_CURRENT && it.count == 1L })
        assertEquals(SystemDoctorReadiness.NOT_READY, systemReport.readiness)
        assertTrue(systemReport.findings.any { it.code == SystemDoctorCode.WORKER_LEASE_EXPIRED && it.count == 2L })
    }

    @Test
    fun `effect probe can aggregate explicit effect SLA and workflow queue definitions`() {
        val fixture = JdbcProbeTestFixture()
        fixture.execute(
            "CREATE TABLE fw_effect_queue(tenant_id varchar(64), queue_status varchar(32), ready_time bigint, created_time bigint)",
            "CREATE TABLE fw_sla_queue(tenant_id varchar(64), queue_status varchar(32), ready_time bigint, created_time bigint)",
            "CREATE TABLE fw_workflow_queue(tenant_id varchar(64), queue_status varchar(32), ready_time bigint, created_time bigint)",
            "INSERT INTO fw_effect_queue VALUES ('tenant-a', 'READY', 1, 9000)",
            "INSERT INTO fw_sla_queue VALUES ('tenant-a', 'READY', 1, 9000)",
            "INSERT INTO fw_workflow_queue VALUES ('tenant-a', 'READY', 1, 9000)",
        )
        val definitions = listOf(
            genericQueueDefinition(JdbcQueueWorkload.EFFECT, "fw_effect_queue"),
            genericQueueDefinition(JdbcQueueWorkload.SLA, "fw_sla_queue"),
            genericQueueDefinition(JdbcQueueWorkload.WORKFLOW, "fw_workflow_queue"),
        )
        val probe = JdbcQueueSystemDoctorProbe(fixture.access(), "jdbc-effect-family", definitions)
        val request = fixture.request(SystemDoctorScope.TENANT)

        val report = fixture.doctor(
            request,
            listOf(probe),
            SystemDoctorCapability.EFFECT_QUEUE,
        ).inspectTenant(request)

        assertEquals(SystemDoctorReadiness.READY, report.readiness)
        assertTrue(
            report.findings.any { finding ->
                finding.code == SystemDoctorCode.QUEUE_BACKLOG_WITHIN_LIMIT && finding.count == 3L
            },
        )
    }

    @Test
    fun `missing queue table and hostile identifiers cannot manufacture a healthy result`() {
        assertFailsWith<IllegalArgumentException> {
            JdbcTrustedSqlIdentifier.of("fw_task;drop_table")
        }
        val fixture = JdbcProbeTestFixture()
        val probe = JdbcQueueSystemDoctorProbe(fixture.access(), "missing-queue", listOf(outboxDefinition()))
        val request = fixture.request(SystemDoctorScope.TENANT)

        val report = fixture.doctor(
            request,
            listOf(probe),
            SystemDoctorCapability.OUTBOX_QUEUE,
        ).inspectTenant(request)

        assertEquals(SystemDoctorReadiness.NOT_READY, report.readiness)
        assertTrue(report.findings.any { finding -> finding.code == SystemDoctorCode.PROBE_UNSUPPORTED })
    }

    private fun outboxDefinition(
        binding: JdbcTenantBindingPort = JdbcTenantBindingPort.IDENTIFIER_VALUE,
    ): JdbcQueueDefinition = JdbcQueueDefinition(
        JdbcQueueWorkload.OUTBOX,
        JdbcTrustedTable.of("fw_outbox_event"),
        JdbcTrustedSqlIdentifier.of("tenant_id"),
        JdbcTrustedSqlIdentifier.of("event_status"),
        JdbcTrustedSqlIdentifier.of("created_time"),
        JdbcTrustedSqlIdentifier.of("next_attempt_time"),
        listOf(JdbcTrustedValue.of("PENDING")),
        listOf(JdbcTrustedValue.of("FAILED")),
        listOf(JdbcTrustedValue.of("OUTCOME_UNKNOWN")),
        listOf(JdbcTrustedValue.of("RECONCILIATION_PENDING")),
        1L,
        5_000L,
        "plain-tenant-v1",
        binding,
    )

    private fun genericQueueDefinition(workload: JdbcQueueWorkload, table: String): JdbcQueueDefinition =
        JdbcQueueDefinition(
            workload,
            JdbcTrustedTable.of(table),
            JdbcTrustedSqlIdentifier.of("tenant_id"),
            JdbcTrustedSqlIdentifier.of("queue_status"),
            JdbcTrustedSqlIdentifier.of("created_time"),
            JdbcTrustedSqlIdentifier.of("ready_time"),
            listOf(JdbcTrustedValue.of("READY")),
            emptyList(),
            emptyList(),
            emptyList(),
            1L,
            5_000L,
            "plain-tenant-v1",
            JdbcTenantBindingPort.IDENTIFIER_VALUE,
        )
}
