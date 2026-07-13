package ai.icen.fw.sample.host

import ai.icen.fw.spi.task.FileWeftTaskHandler
import ai.icen.fw.spi.task.TaskExecution
import ai.icen.fw.spi.task.TaskHandlingResult
import ai.icen.fw.spi.task.TaskHandlingStatus

/**
 * Sample host task handler that succeeds for its supported task type.
 */
class SampleFileWeftTaskHandler(
    private val supportedType: String = "sample-task",
) : FileWeftTaskHandler {

    override fun supports(task: TaskExecution): Boolean = task.type == supportedType

    override fun handle(task: TaskExecution): TaskHandlingResult {
        require(supports(task)) { "Unsupported task type: ${task.type}" }
        return TaskHandlingResult(status = TaskHandlingStatus.SUCCEEDED, message = "Sample task handled.")
    }
}
