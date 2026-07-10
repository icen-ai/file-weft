package com.fileweft.dev.api.service

import com.fileweft.application.doctor.DoctorApplicationService
import com.fileweft.application.outbox.OutboxProcessingSummary
import com.fileweft.application.outbox.OutboxWorker
import com.fileweft.application.task.TaskProcessingSummary
import com.fileweft.application.task.TaskWorker
import com.fileweft.core.id.Identifier
import com.fileweft.core.result.DoctorReport

class DevOperationsService(
    private val access: DevAccessService,
    private val worker: OutboxWorker,
    private val taskWorker: TaskWorker,
    private val doctor: DoctorApplicationService,
) {
    fun processOutbox(limit: Int): OutboxProcessingSummary {
        require(limit in 1..100) { "Outbox processing limit must be between 1 and 100." }
        access.requireAction(Identifier("outbox-worker"), "SYSTEM", "system:outbox:process")
        return worker.processAvailable(limit)
    }

    fun inspectDocument(documentId: Identifier): DoctorReport = doctor.inspectDocument(documentId)

    fun processTasks(limit: Int): TaskProcessingSummary {
        require(limit in 1..100) { "Task processing limit must be between 1 and 100." }
        access.requireAction(Identifier("task-worker"), "SYSTEM", "system:task:process")
        return taskWorker.processAvailable(limit)
    }
}
