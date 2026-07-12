package ai.icen.fw.application.doctor

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.core.result.DoctorReport

/** Durable history for a completed asynchronous diagnostic request. */
interface DoctorReportRepository {
    fun save(tenantId: Identifier, documentId: Identifier, taskId: Identifier, report: DoctorReport)
}
