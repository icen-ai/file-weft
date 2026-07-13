package ai.icen.fw.testkit.task

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.task.FileWeftTaskHandler
import ai.icen.fw.spi.task.TaskExecution
import ai.icen.fw.spi.task.TaskHandlingStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

abstract class FileWeftTaskHandlerContractTest {
    protected abstract val taskHandler: FileWeftTaskHandler

    protected abstract fun supportedTask(): TaskExecution

    protected abstract fun unsupportedTask(): TaskExecution

    @Test
    fun `supports the configured task type`() {
        assertTrue(taskHandler.supports(supportedTask()), "Handler must support its configured task type.")
        assertFalse(taskHandler.supports(unsupportedTask()), "Handler must not support an unrelated task type.")
    }

    @Test
    fun `handles a supported task idempotently`() {
        val task = supportedTask()
        val first = taskHandler.handle(task)
        val second = taskHandler.handle(task)

        assertEquals(TaskHandlingStatus.SUCCEEDED, first.status, first.message)
        assertEquals(TaskHandlingStatus.SUCCEEDED, second.status, second.message)
    }
}
