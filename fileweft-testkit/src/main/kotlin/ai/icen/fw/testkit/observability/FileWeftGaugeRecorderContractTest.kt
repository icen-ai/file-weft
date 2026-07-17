package ai.icen.fw.testkit.observability

import ai.icen.fw.spi.observability.FileWeftGauge
import ai.icen.fw.spi.observability.FileWeftGaugeRecorder
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Collections

/** Reusable current-value gauge contract for metrics bridges. */
abstract class FileWeftGaugeRecorderContractTest {
    protected abstract val gaugeRecorder: FileWeftGaugeRecorder

    @Test
    fun `accepts replacement values for every documented gauge`() {
        val mutableTags = linkedMapOf("component" to "testkit")
        val tags = Collections.unmodifiableMap(mutableTags)

        assertDoesNotThrow {
            FileWeftGauge.values().forEach { gauge ->
                gaugeRecorder.set(gauge, 1.0, tags)
                gaugeRecorder.set(gauge, 0.0, tags)
            }
        }
        assertEquals(
            mapOf("component" to "testkit"),
            mutableTags,
            "A gauge implementation must treat caller-owned tags as read-only.",
        )
    }
}
