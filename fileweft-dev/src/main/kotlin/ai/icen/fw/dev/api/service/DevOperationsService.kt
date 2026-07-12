package ai.icen.fw.dev.api.service

import ai.icen.fw.application.outbox.OutboxProcessingSummary
import ai.icen.fw.application.outbox.OutboxWorker
import ai.icen.fw.application.task.TaskProcessingSummary
import ai.icen.fw.application.task.TaskWorker
import ai.icen.fw.core.id.Identifier

class DevOperationsService(
    private val access: DevAccessService,
    private val worker: OutboxWorker,
    private val taskWorker: TaskWorker,
) {
    fun processOutbox(limit: Int): OutboxProcessingSummary {
        require(limit in 1..100) { "Outbox processing limit must be between 1 and 100." }
        access.requireAction(Identifier("outbox-worker"), "SYSTEM", "system:outbox:process")
        return worker.processAvailable(limit)
    }

    fun processTasks(limit: Int): TaskProcessingSummary {
        require(limit in 1..100) { "Task processing limit must be between 1 and 100." }
        access.requireAction(Identifier("task-worker"), "SYSTEM", "system:task:process")
        return taskWorker.processAvailable(limit)
    }
}
