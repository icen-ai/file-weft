package ai.icen.fw.adapter.opentelemetry

import ai.icen.fw.spi.observability.FileWeftGauge
import ai.icen.fw.spi.observability.FileWeftGaugeRecorder
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.metrics.Meter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * OpenTelemetry projection of FileWeft gauges.
 *
 * Gauge observations are bounded to a small, fixed set of tag combinations so
 * that tenant or document identifiers cannot leak into metric labels.
 */
class OpenTelemetryFileWeftGauges(
    private val meter: Meter,
) : FileWeftGaugeRecorder {

    private val holders = ConcurrentHashMap<GaugeKey, AtomicReference<Double>>()

    override fun set(gauge: FileWeftGauge, value: Double, tags: Map<String, String>) {
        try {
            if (!value.isFinite() || value < 0) return
            val key = gaugeKey(gauge, tags) ?: return
            val holder = holders.computeIfAbsent(key) { register(it) }
            holder.set(value)
        } catch (_: Exception) {
            // Observability failures must not alter FileWeft business processing.
        }
    }

    private fun register(key: GaugeKey): AtomicReference<Double> {
        val holder = AtomicReference(0.0)
        meter.gaugeBuilder(METRIC_PREFIX + key.gauge.value)
            .setDescription("FileWeft ${key.gauge.value}")
            .buildWithCallback { measurement ->
                val current = holder.get()
                if (current.isFinite()) {
                    measurement.record(current, key.attributes())
                }
            }
        return holder
    }

    private fun gaugeKey(gauge: FileWeftGauge, tags: Map<String, String>): GaugeKey? = when (gauge) {
        FileWeftGauge.OUTBOX_BACKLOG -> tags[STATE_TAG]
            ?.takeIf { it in OUTBOX_BACKLOG_STATES }
            ?.let { GaugeKey(gauge, mapOf(STATE_TAG to it)) }

        FileWeftGauge.OUTBOX_OLDEST_READY_AGE_SECONDS,
        FileWeftGauge.OUTBOX_BACKLOG_OBSERVATION_FAILURE -> GaugeKey(gauge, emptyMap())
    }

    private data class GaugeKey(
        val gauge: FileWeftGauge,
        val tags: Map<String, String>,
    ) {
        fun attributes(): Attributes {
            val builder = Attributes.builder()
            tags.toSortedMap().forEach { (key, value) ->
                if (value.isNotBlank() && value.length <= MAX_TAG_VALUE_LENGTH) {
                    builder.put(AttributeKey.stringKey(key), value)
                }
            }
            return builder.build()
        }
    }

    private companion object {
        const val METRIC_PREFIX = "fileweft."
        const val STATE_TAG = "state"
        const val MAX_TAG_VALUE_LENGTH = 64
        val OUTBOX_BACKLOG_STATES = setOf("ready", "delayed", "running", "expired", "failed")
    }
}
