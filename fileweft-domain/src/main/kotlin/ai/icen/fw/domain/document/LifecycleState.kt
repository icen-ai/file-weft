package ai.icen.fw.domain.document

enum class LifecycleState {
    DRAFT,
    PENDING_REVIEW,
    REJECTED,
    PUBLISHING,
    PUBLISHED,
    SYNC_ERROR,
    HISTORY,
    OFFLINE,
}
