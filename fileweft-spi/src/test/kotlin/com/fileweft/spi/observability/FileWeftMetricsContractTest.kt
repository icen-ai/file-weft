package com.fileweft.spi.observability

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class FileWeftMetricsContractTest {
    @Test
    fun `exposes the documented metric names through a Java friendly boundary`() {
        val recorded = mutableListOf<Pair<FileWeftMetric, Map<String, String>>>()
        val metrics = object : FileWeftMetrics {
            override fun increment(metric: FileWeftMetric, tags: Map<String, String>) {
                recorded += metric to tags
            }
        }

        metrics.increment(FileWeftMetric.UPLOAD_COUNT, mapOf("tenantId" to "tenant-1"))
        metrics.increment(FileWeftMetric.DOCTOR_FAILURE)
        metrics.increment(FileWeftMetric.DELIVERY_REMOVAL_SUCCESS)
        metrics.increment(FileWeftMetric.TASK_LEASE_LOST)

        assertEquals("upload_count", recorded[0].first.value)
        assertEquals("tenant-1", recorded[0].second["tenantId"])
        assertEquals(emptyMap(), recorded[1].second)
        assertEquals("delivery_removal_success", recorded[2].first.value)
        assertEquals("task_lease_lost", recorded[3].first.value)
    }
}
