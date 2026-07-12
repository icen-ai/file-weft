package ai.icen.fw.domain.document

enum class LifecycleCommand {
    SUBMIT,
    APPROVE,
    REJECT,
    REVISE,
    PUBLISH_SUCCEEDED,
    SYNC_FAILED,
    RETRY_SYNC,
    ARCHIVE,
    OFFLINE,
    RESTORE_DRAFT,
}
