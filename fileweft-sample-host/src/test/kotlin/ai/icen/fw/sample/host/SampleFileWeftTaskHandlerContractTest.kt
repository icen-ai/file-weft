package ai.icen.fw.sample.host

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.task.TaskExecution
import ai.icen.fw.testkit.task.FileWeftTaskHandlerContractTest

class SampleFileWeftTaskHandlerContractTest : FileWeftTaskHandlerContractTest() {

    override val taskHandler = SampleFileWeftTaskHandler(supportedType = "sample.task")

    override fun supportedTask(): TaskExecution {
        return TaskExecution(
            id = Identifier("task-1"),
            tenantId = Identifier("sample-tenant"),
            type = "sample.task",
        )
    }

    override fun unsupportedTask(): TaskExecution {
        return TaskExecution(
            id = Identifier("task-2"),
            tenantId = Identifier("sample-tenant"),
            type = "other.task",
        )
    }
}
