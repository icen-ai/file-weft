package ai.icen.fw.adapter.observability

import ai.icen.fw.spi.observability.FileWeftMetric
import ai.icen.fw.spi.observability.FileWeftMetrics

/** Safe default for deployments that have not installed a metrics backend. */
class NoOpFileWeftMetrics : FileWeftMetrics {
    override fun increment(metric: FileWeftMetric, tags: Map<String, String>) = Unit
}
