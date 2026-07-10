package com.fileweft.persistence.jdbc

import com.fasterxml.jackson.databind.ObjectMapper
import com.fileweft.application.doctor.DoctorReportRepository
import com.fileweft.core.id.Identifier
import com.fileweft.core.result.DoctorReport
import java.time.Clock

class JdbcDoctorReportRepository(
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) : DoctorReportRepository {
    override fun save(tenantId: Identifier, documentId: Identifier, taskId: Identifier, report: DoctorReport) {
        require(report.tenantId == tenantId) { "Doctor report tenant must match record tenant." }
        require(report.documentId == documentId) { "Doctor report document must match record document." }
        val now = clock.millis()
        JdbcConnectionContext.requireCurrent().prepareStatement(
            """
            INSERT INTO fw_doctor_record(id, tenant_id, document_id, task_id, doctor_status, report_json, created_time, updated_time)
            VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?)
            ON CONFLICT (tenant_id, task_id) DO UPDATE
            SET doctor_status = EXCLUDED.doctor_status,
                report_json = EXCLUDED.report_json,
                updated_time = EXCLUDED.updated_time
            """.trimIndent(),
        ).use { statement ->
            statement.setString(1, taskId.value)
            statement.setString(2, tenantId.value)
            statement.setString(3, documentId.value)
            statement.setString(4, taskId.value)
            statement.setString(5, report.status.name)
            statement.setString(6, objectMapper.writeValueAsString(report))
            statement.setLong(7, now)
            statement.setLong(8, now)
            statement.executeUpdate()
        }
    }
}
