package com.fileweft.adapter.micrometer

import com.fileweft.spi.observability.FileWeftMetric
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class MicrometerFileWeftMetricsTest {
    @Test
    fun `exports stable low cardinality tags and drops tenant identifiers`() {
        val registry = SimpleMeterRegistry()
        val metrics = MicrometerFileWeftMetrics(registry)

        metrics.increment(FileWeftMetric.TASK_SUCCESS, mapOf("tenantId" to "tenant-a", "taskType" to "agent.execute"))

        val counter = registry.find("fileweft.task_success").tag("taskType", "agent.execute").counter()
        assertEquals(1.0, counter?.count())
        assertEquals(null, registry.find("fileweft.task_success").tag("tenantId", "tenant-a").counter())

        metrics.increment(FileWeftMetric.DELIVERY_REMOVAL_FAILURE, mapOf("tenantId" to "tenant-a", "connector" to "archive"))
        val removalCounter = registry.find("fileweft.delivery_removal_failure").tag("connector", "archive").counter()
        assertEquals(1.0, removalCounter?.count())
        assertEquals(null, registry.find("fileweft.delivery_removal_failure").tag("tenantId", "tenant-a").counter())
    }

    @Test
    fun `contains registry failures so metric export cannot break business work`() {
        val metrics = MicrometerFileWeftMetrics(object : SimpleMeterRegistry() {
            override fun counter(name: String, tags: Iterable<io.micrometer.core.instrument.Tag>): io.micrometer.core.instrument.Counter {
                throw IllegalStateException("metrics unavailable")
            }
        })

        metrics.increment(FileWeftMetric.UPLOAD_COUNT)
    }
}
