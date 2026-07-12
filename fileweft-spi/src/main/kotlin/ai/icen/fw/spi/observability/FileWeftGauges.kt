package ai.icen.fw.spi.observability

/**
 * Low-cardinality gauges exposed by FileWeft. Gauge values describe the
 * current state, rather than an accumulated event count.
 */
enum class FileWeftGauge(
    val value: String,
) {
    OUTBOX_BACKLOG("outbox_backlog"),
    OUTBOX_OLDEST_READY_AGE_SECONDS("outbox_oldest_ready_age_seconds"),
    OUTBOX_BACKLOG_OBSERVATION_FAILURE("outbox_backlog_observation_failure"),
}

/**
 * Optional current-value metrics boundary.
 *
 * Implementations must treat a call as a replacement of the previous gauge
 * value for the same gauge and tag set. Implementations must not let normal
 * metrics backend failures alter FileWeft business processing.
 */
interface FileWeftGaugeRecorder {
    fun set(gauge: FileWeftGauge, value: Double, tags: Map<String, String>)
}
