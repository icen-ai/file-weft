package com.fileweft.application.doctor

import com.fileweft.core.id.Identifier
import com.fileweft.core.result.DoctorReport

/** Durable history for a completed asynchronous diagnostic request. */
interface DoctorReportRepository {
    fun save(tenantId: Identifier, documentId: Identifier, taskId: Identifier, report: DoctorReport)
}
