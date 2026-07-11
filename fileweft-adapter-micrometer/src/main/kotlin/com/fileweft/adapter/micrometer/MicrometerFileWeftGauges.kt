package com.fileweft.adapter.micrometer

import com.fileweft.spi.observability.FileWeftGauge
import com.fileweft.spi.observability.FileWeftGaugeRecorder
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tag
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * Micrometer projection for FileWeft's current-value observations.
 *
 * Holders are retained strongly because Micrometer gauges otherwise retain
 * their observed objects weakly. The bounded [GaugeKey] prevents tenant,
 * document, resource, or arbitrary caller tags from becoming metric labels.
 */
class MicrometerFileWeftGauges(
    private val meterRegistry: MeterRegistry,
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
        Gauge.builder(METRIC_PREFIX + key.gauge.value, holder) { current -> current.get() }
            .tags(key.tags())
            .register(meterRegistry)
        return holder
    }

    private fun gaugeKey(gauge: FileWeftGauge, tags: Map<String, String>): GaugeKey? = when (gauge) {
        FileWeftGauge.OUTBOX_BACKLOG -> tags[STATE_TAG]
            ?.takeIf { it in OUTBOX_BACKLOG_STATES }
            ?.let { GaugeKey(gauge, it) }

        FileWeftGauge.OUTBOX_OLDEST_READY_AGE_SECONDS,
        FileWeftGauge.OUTBOX_BACKLOG_OBSERVATION_FAILURE -> GaugeKey(gauge, null)
    }

    private data class GaugeKey(
        val gauge: FileWeftGauge,
        val state: String?,
    ) {
        fun tags(): Iterable<Tag> = state?.let { listOf(Tag.of(STATE_TAG, it)) } ?: emptyList()
    }

    private companion object {
        const val METRIC_PREFIX = "fileweft."
        const val STATE_TAG = "state"
        val OUTBOX_BACKLOG_STATES = setOf("ready", "delayed", "running", "expired", "failed")
    }
}
