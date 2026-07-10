package com.fileweft.adapter.observability

import com.fileweft.spi.observability.FileWeftMetric
import com.fileweft.spi.observability.FileWeftMetrics

/** Safe default for deployments that have not installed a metrics backend. */
class NoOpFileWeftMetrics : FileWeftMetrics {
    override fun increment(metric: FileWeftMetric, tags: Map<String, String>) = Unit
}
