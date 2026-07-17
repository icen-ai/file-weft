package ai.icen.fw.starter.boot2

import ai.icen.fw.application.outbox.OutboxBacklogMetricsPublisher
import ai.icen.fw.application.outbox.OutboxWorker
import ai.icen.fw.application.task.TaskWorker
import ai.icen.fw.application.upload.PresignedUploadCleanupService
import ai.icen.fw.application.upload.PresignedUploadRecoveryService
import ai.icen.fw.application.upload.ResumableUploadService
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.support.StaticListableBeanFactory
import java.util.concurrent.Executor

class PresignedUploadWorkerSchedulingTest {
    @Test
    fun `continues cleanup and observation when recovery fails`() {
        val recovery = Mockito.mock(PresignedUploadRecoveryService::class.java)
        val cleanup = Mockito.mock(PresignedUploadCleanupService::class.java)
        val metrics = Mockito.mock(FlowWeftPresignedUploadMetricsPublisher::class.java)
        Mockito.`when`(recovery.recover(17)).thenThrow(IllegalStateException("temporary recovery outage"))
        val beans = StaticListableBeanFactory().apply {
            addBean("presignedRecovery", recovery)
            addBean("presignedCleanup", cleanup)
            addBean("presignedMetrics", metrics)
        }
        val properties = FileWeftProperties().apply {
            worker.enabled = true
            worker.processOutbox = false
            worker.processTasks = false
            worker.processUploadCleanup = false
            worker.processPresignedUploadMaintenance = true
            upload.presignedMaintenanceBatchSize = 17
        }
        val scheduler = FileWeftWorkerSchedulingConfiguration().configuredFlowWeftWorkerScheduler(
            properties = properties,
            outbox = beans.getBeanProvider(OutboxWorker::class.java),
            tasks = beans.getBeanProvider(TaskWorker::class.java),
            uploads = beans.getBeanProvider(ResumableUploadService::class.java),
            presignedRecovery = beans.getBeanProvider(PresignedUploadRecoveryService::class.java),
            presignedCleanup = beans.getBeanProvider(PresignedUploadCleanupService::class.java),
            presignedMetrics = beans.getBeanProvider(FlowWeftPresignedUploadMetricsPublisher::class.java),
            outboxBacklogMetrics = beans.getBeanProvider(OutboxBacklogMetricsPublisher::class.java),
            outboxBacklogMetricsExecutor = beans.getBeanProvider(Executor::class.java),
        )

        scheduler.processAvailable()

        Mockito.verify(recovery).recover(17)
        Mockito.verify(cleanup).cleanup(17)
        Mockito.verify(metrics).publish()
    }
}
