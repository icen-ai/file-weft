package ai.icen.fw.adapter.micrometer

import ai.icen.fw.spi.observability.FileWeftMetric
import ai.icen.fw.spi.observability.FileWeftMetrics
import io.micrometer.core.instrument.MeterRegistry

/**
 * Micrometer projection that intentionally excludes tenant and resource tags,
 * preventing sensitive high-cardinality identities from becoming metric labels.
 */
class MicrometerFileWeftMetrics(
    private val meterRegistry: MeterRegistry,
) : FileWeftMetrics {
    override fun increment(metric: FileWeftMetric, tags: Map<String, String>) {
        try {
            val counter = meterRegistry.counter(METRIC_PREFIX + metric.value, safeTags(tags))
            counter.increment()
        } catch (_: Exception) {
            // Metrics must never alter business acknowledgement semantics.
        }
    }

    private fun safeTags(tags: Map<String, String>): Iterable<io.micrometer.core.instrument.Tag> = tags
        .filter { (key, value) -> key in ALLOWED_TAGS && value.isNotBlank() && value.length <= MAX_TAG_VALUE_LENGTH }
        .toSortedMap()
        .map { (key, value) -> io.micrometer.core.instrument.Tag.of(key, value) }

    private companion object {
        const val METRIC_PREFIX = "fileweft."
        const val MAX_TAG_VALUE_LENGTH = 64
        val ALLOWED_TAGS = setOf("taskType", "connector", "outcome")
    }
}
