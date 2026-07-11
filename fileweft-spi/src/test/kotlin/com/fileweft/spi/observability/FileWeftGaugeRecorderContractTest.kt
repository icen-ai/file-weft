package com.fileweft.spi.observability

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class FileWeftGaugeRecorderContractTest {
    @Test
    fun `exposes documented outbox gauge names through a single Java friendly setter boundary`() {
        val recorded = mutableListOf<RecordedGauge>()
        val recorder = object : FileWeftGaugeRecorder {
            override fun set(gauge: FileWeftGauge, value: Double, tags: Map<String, String>) {
                recorded += RecordedGauge(gauge, value, tags)
            }
        }

        recorder.set(FileWeftGauge.OUTBOX_BACKLOG, 3.0, mapOf("state" to "ready"))
        recorder.set(FileWeftGauge.OUTBOX_OLDEST_READY_AGE_SECONDS, 12.5, emptyMap())
        recorder.set(FileWeftGauge.OUTBOX_BACKLOG_OBSERVATION_FAILURE, 1.0, emptyMap())

        assertEquals("outbox_backlog", recorded[0].gauge.value)
        assertEquals(3.0, recorded[0].value)
        assertEquals(mapOf("state" to "ready"), recorded[0].tags)
        assertEquals("outbox_oldest_ready_age_seconds", recorded[1].gauge.value)
        assertEquals(emptyMap(), recorded[1].tags)
        assertEquals("outbox_backlog_observation_failure", recorded[2].gauge.value)
        assertEquals(1.0, recorded[2].value)
        assertEquals(emptyMap(), recorded[2].tags)
    }

    private class RecordedGauge(
        val gauge: FileWeftGauge,
        val value: Double,
        val tags: Map<String, String>,
    )
}
