package com.fileweft.spi.observability

enum class FileWeftMetric(
    val value: String,
) {
    UPLOAD_COUNT("upload_count"),
    UPLOAD_FAILURE("upload_failure"),
    SYNC_SUCCESS("sync_success"),
    SYNC_FAILURE("sync_failure"),
    DOCTOR_FAILURE("doctor_failure"),
    TASK_SUCCESS("task_success"),
    TASK_FAILURE("task_failure"),
}

/**
 * Optional metrics boundary. Implementations must not throw for normal metric
 * emission failures, because observability cannot compromise business work.
 */
interface FileWeftMetrics {
    fun increment(metric: FileWeftMetric, tags: Map<String, String>)

    fun increment(metric: FileWeftMetric) {
        increment(metric, emptyMap())
    }
}
