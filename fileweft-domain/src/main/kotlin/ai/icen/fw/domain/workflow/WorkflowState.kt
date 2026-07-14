package ai.icen.fw.domain.workflow

enum class WorkflowState {
    PENDING,
    APPROVED,
    REJECTED,
    WITHDRAWN,
}

enum class WorkflowTaskState {
    PENDING,
    APPROVED,
    REJECTED,
}
