package ai.icen.fw.adapter.opentelemetry

import ai.icen.fw.spi.observability.FileWeftMetric
import ai.icen.fw.spi.observability.FileWeftMetrics
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.Meter

/**
 * OpenTelemetry projection of FlowWeft counters.
 *
 * Only a bounded, low-cardinality allow-list of tags is emitted as OTel
 * attributes. Tenant, document and any other resource identifiers are
 * deliberately redacted to prevent cardinality explosions and information
 * leakage in metric backends.
 */
class OpenTelemetryFileWeftMetrics(
    private val meter: Meter,
) : FileWeftMetrics {

    override fun increment(metric: FileWeftMetric, tags: Map<String, String>) {
        try {
            val counter = meter.counterBuilder(METRIC_PREFIX + metric.value)
                .setDescription("FlowWeft ${metric.value}")
                .build()
            counter.add(1, attributes(tags))
        } catch (_: Exception) {
            // Metrics must never alter business acknowledgement semantics.
        }
    }

    override fun increment(metric: FileWeftMetric) {
        increment(metric, emptyMap())
    }

    private fun attributes(tags: Map<String, String>): Attributes {
        val builder = Attributes.builder()
        tags
            .filter { (key, value) -> key in ALLOWED_TAGS && value.isNotBlank() && value.length <= MAX_TAG_VALUE_LENGTH }
            .toSortedMap()
            .forEach { (key, value) -> builder.put(AttributeKey.stringKey(key), value) }
        return builder.build()
    }

    private companion object {
        const val METRIC_PREFIX = "fileweft."
        const val MAX_TAG_VALUE_LENGTH = 64
        val ALLOWED_TAGS = setOf("taskType", "connector", "outcome")
    }
}
