package com.fileweft.adapter.micrometer

import com.fileweft.spi.observability.FileWeftGauge
import io.micrometer.core.instrument.Gauge
import io.micrometer.core.instrument.Meter
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.ToDoubleFunction
import kotlin.test.assertEquals

class MicrometerFileWeftGaugesTest {
    @Test
    fun `exports the five fixed backlog states and one independent oldest ready age gauge`() {
        val registry = SimpleMeterRegistry()
        val gauges = MicrometerFileWeftGauges(registry)
        val expectedBacklogs = linkedMapOf(
            "ready" to 5.0,
            "delayed" to 4.0,
            "running" to 3.0,
            "expired" to 2.0,
            "failed" to 1.0,
        )

        expectedBacklogs.forEach { (state, value) ->
            gauges.set(FileWeftGauge.OUTBOX_BACKLOG, value, mapOf("state" to state))
        }
        gauges.set(FileWeftGauge.OUTBOX_OLDEST_READY_AGE_SECONDS, 42.0, emptyMap())
        gauges.set(FileWeftGauge.OUTBOX_BACKLOG_OBSERVATION_FAILURE, 0.0, emptyMap())

        expectedBacklogs.forEach { (state, value) ->
            assertEquals(value, registry.find(BACKLOG_NAME).tag("state", state).gauge()?.value())
        }
        assertEquals(42.0, registry.find(OLDEST_READY_AGE_NAME).gauge()?.value())
        assertEquals(0.0, registry.find(OBSERVATION_FAILURE_NAME).gauge()?.value())
        assertEquals(null, registry.find(OLDEST_READY_AGE_NAME).tag("state", "ready").gauge())
        assertEquals(7, registry.meters.size)
    }

    @Test
    fun `updates existing gauges without adding meters`() {
        val registry = SimpleMeterRegistry()
        val gauges = MicrometerFileWeftGauges(registry)

        gauges.set(FileWeftGauge.OUTBOX_BACKLOG, 1.0, mapOf("state" to "ready"))
        gauges.set(FileWeftGauge.OUTBOX_OLDEST_READY_AGE_SECONDS, 2.0, emptyMap())
        gauges.set(FileWeftGauge.OUTBOX_BACKLOG_OBSERVATION_FAILURE, 1.0, emptyMap())
        gauges.set(FileWeftGauge.OUTBOX_BACKLOG, 9.0, mapOf("state" to "ready"))
        gauges.set(FileWeftGauge.OUTBOX_OLDEST_READY_AGE_SECONDS, 12.0, emptyMap())
        gauges.set(FileWeftGauge.OUTBOX_BACKLOG_OBSERVATION_FAILURE, 0.0, emptyMap())

        assertEquals(9.0, registry.find(BACKLOG_NAME).tag("state", "ready").gauge()?.value())
        assertEquals(12.0, registry.find(OLDEST_READY_AGE_NAME).gauge()?.value())
        assertEquals(0.0, registry.find(OBSERVATION_FAILURE_NAME).gauge()?.value())
        assertEquals(1, registry.meters.count { it.id.name == BACKLOG_NAME && it.id.getTag("state") == "ready" })
        assertEquals(1, registry.meters.count { it.id.name == OLDEST_READY_AGE_NAME })
    }

    @Test
    fun `retains only approved fixed state tags`() {
        val registry = SimpleMeterRegistry()
        val gauges = MicrometerFileWeftGauges(registry)

        gauges.set(
            FileWeftGauge.OUTBOX_BACKLOG,
            7.0,
            mapOf(
                "state" to "ready",
                "tenantId" to "tenant-secret",
                "resourceId" to "document-secret",
                "unapproved" to "discard-me",
            ),
        )
        gauges.set(
            FileWeftGauge.OUTBOX_OLDEST_READY_AGE_SECONDS,
            11.0,
            mapOf("state" to "ready", "tenantId" to "tenant-secret"),
        )
        gauges.set(
            FileWeftGauge.OUTBOX_BACKLOG_OBSERVATION_FAILURE,
            1.0,
            mapOf("state" to "ready", "tenantId" to "tenant-secret"),
        )
        gauges.set(FileWeftGauge.OUTBOX_BACKLOG, 13.0, mapOf("state" to "arbitrary-user-value"))

        assertEquals(7.0, registry.find(BACKLOG_NAME).tag("state", "ready").gauge()?.value())
        assertEquals(null, registry.find(BACKLOG_NAME).tag("tenantId", "tenant-secret").gauge())
        assertEquals(null, registry.find(BACKLOG_NAME).tag("resourceId", "document-secret").gauge())
        assertEquals(null, registry.find(BACKLOG_NAME).tag("unapproved", "discard-me").gauge())
        assertEquals(null, registry.find(BACKLOG_NAME).tag("state", "arbitrary-user-value").gauge())
        assertEquals(11.0, registry.find(OLDEST_READY_AGE_NAME).gauge()?.value())
        assertEquals(1.0, registry.find(OBSERVATION_FAILURE_NAME).gauge()?.value())
        assertEquals(null, registry.find(OLDEST_READY_AGE_NAME).tag("state", "ready").gauge())
        assertEquals(null, registry.find(OLDEST_READY_AGE_NAME).tag("tenantId", "tenant-secret").gauge())
        assertEquals(null, registry.find(OBSERVATION_FAILURE_NAME).tag("state", "ready").gauge())
        assertEquals(null, registry.find(OBSERVATION_FAILURE_NAME).tag("tenantId", "tenant-secret").gauge())
    }

    @Test
    fun `ignores non finite and negative values without replacing a valid value`() {
        val registry = SimpleMeterRegistry()
        val gauges = MicrometerFileWeftGauges(registry)

        gauges.set(FileWeftGauge.OUTBOX_BACKLOG, 3.0, mapOf("state" to "ready"))
        gauges.set(FileWeftGauge.OUTBOX_BACKLOG, -1.0, mapOf("state" to "ready"))
        gauges.set(FileWeftGauge.OUTBOX_BACKLOG, Double.NaN, mapOf("state" to "ready"))
        gauges.set(FileWeftGauge.OUTBOX_BACKLOG, Double.POSITIVE_INFINITY, mapOf("state" to "ready"))
        gauges.set(FileWeftGauge.OUTBOX_BACKLOG, Double.NEGATIVE_INFINITY, mapOf("state" to "ready"))
        gauges.set(FileWeftGauge.OUTBOX_OLDEST_READY_AGE_SECONDS, Double.NaN, emptyMap())

        assertEquals(3.0, registry.find(BACKLOG_NAME).tag("state", "ready").gauge()?.value())
        assertEquals(null, registry.find(OLDEST_READY_AGE_NAME).gauge())
    }

    @Test
    fun `contains registry failures so gauge export cannot break business work`() {
        val registry = ThrowingGaugeRegistry()
        val gauges = MicrometerFileWeftGauges(registry)

        gauges.set(FileWeftGauge.OUTBOX_BACKLOG, 3.0, mapOf("state" to "ready"))

        assertEquals(1, registry.gaugeAttempts.get())
    }

    private class ThrowingGaugeRegistry : SimpleMeterRegistry() {
        val gaugeAttempts = AtomicInteger()

        override fun <T : Any?> newGauge(
            id: Meter.Id,
            obj: T,
            valueFunction: ToDoubleFunction<T>,
        ): Gauge {
            gaugeAttempts.incrementAndGet()
            throw IllegalStateException("metrics unavailable")
        }
    }

    private companion object {
        const val BACKLOG_NAME = "fileweft.outbox_backlog"
        const val OLDEST_READY_AGE_NAME = "fileweft.outbox_oldest_ready_age_seconds"
        const val OBSERVATION_FAILURE_NAME = "fileweft.outbox_backlog_observation_failure"
    }
}
