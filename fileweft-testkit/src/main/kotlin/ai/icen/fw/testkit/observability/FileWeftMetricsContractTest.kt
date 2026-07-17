package ai.icen.fw.testkit.observability

import ai.icen.fw.spi.observability.FileWeftMetric
import ai.icen.fw.spi.observability.FileWeftMetrics
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.util.Collections

/** Reusable counter-emission contract for metrics bridges. */
abstract class FileWeftMetricsContractTest {
    protected abstract val fileWeftMetrics: FileWeftMetrics

    @Test
    fun `accepts every documented metric with low-cardinality tags`() {
        val mutableTags = linkedMapOf("result" to "contract", "component" to "testkit")
        val tags = Collections.unmodifiableMap(mutableTags)

        assertDoesNotThrow {
            FileWeftMetric.values().forEach { metric -> fileWeftMetrics.increment(metric, tags) }
        }
        assertEquals(
            mapOf("result" to "contract", "component" to "testkit"),
            mutableTags,
            "A metrics implementation must treat caller-owned tags as read-only.",
        )
    }

    @Test
    fun `accepts the no-tag convenience operation`() {
        assertDoesNotThrow {
            FileWeftMetric.values().forEach { metric -> fileWeftMetrics.increment(metric) }
        }
    }
}
