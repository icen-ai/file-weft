package ai.icen.fw.workflow.runtime

import ai.icen.fw.workflow.domain.WorkflowInstanceState
import ai.icen.fw.workflow.domain.WorkflowResultCode

class WorkflowRuntimeResult private constructor(
    val code: WorkflowRuntimeResultCode,
    val state: WorkflowInstanceState?,
    val domainResultCode: WorkflowResultCode?,
    failureCode: String?,
    val committed: Boolean,
) {
    val failureCode: String? = failureCode?.let { value ->
        WorkflowRuntimeSupport.code(value, "Workflow runtime failure code is invalid.")
    }

    init {
        when (code) {
            WorkflowRuntimeResultCode.COMMITTED,
            WorkflowRuntimeResultCode.COMMITTED_DISPATCH_DEFERRED,
            WorkflowRuntimeResultCode.REPLAYED -> require(state != null && committed) {
                "Committed and replayed workflow results require durable state."
            }
            else -> require(!committed) { "Non-durable workflow results cannot be marked committed." }
        }
    }

    override fun toString(): String = "WorkflowRuntimeResult(<redacted>)"

    companion object {
        @JvmStatic fun durable(
            code: WorkflowRuntimeResultCode,
            state: WorkflowInstanceState,
            domainResultCode: WorkflowResultCode?,
            failureCode: String?,
        ): WorkflowRuntimeResult = WorkflowRuntimeResult(
            code,
            state,
            domainResultCode,
            failureCode,
            true,
        )

        @JvmStatic fun failed(
            code: WorkflowRuntimeResultCode,
            state: WorkflowInstanceState?,
            domainResultCode: WorkflowResultCode?,
            failureCode: String,
        ): WorkflowRuntimeResult = WorkflowRuntimeResult(
            code,
            state,
            domainResultCode,
            failureCode,
            false,
        )
    }
}
