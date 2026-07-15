package ai.icen.fw.testkit.observability

import ai.icen.fw.core.context.TraceContext
import ai.icen.fw.spi.observability.FileWeftGauge
import ai.icen.fw.spi.observability.FileWeftGaugeRecorder
import ai.icen.fw.spi.observability.FileWeftLogger
import ai.icen.fw.spi.observability.FileWeftMetric
import ai.icen.fw.spi.observability.FileWeftMetrics
import ai.icen.fw.spi.observability.LogContext
import ai.icen.fw.spi.observability.TraceContextScope

class FileWeftLoggerContractTestBehaviorTest : FileWeftLoggerContractTest() {
    override val fileWeftLogger: FileWeftLogger = object : FileWeftLogger {
        override fun info(message: String, context: LogContext) = Unit
        override fun warn(message: String, context: LogContext) = Unit
        override fun error(message: String, throwable: Throwable?, context: LogContext) = Unit
        override fun debug(message: String, context: LogContext) = Unit
    }
}

class FileWeftMetricsContractTestBehaviorTest : FileWeftMetricsContractTest() {
    override val fileWeftMetrics: FileWeftMetrics = object : FileWeftMetrics {
        override fun increment(metric: FileWeftMetric, tags: Map<String, String>) = Unit
    }
}

class FileWeftGaugeRecorderContractTestBehaviorTest : FileWeftGaugeRecorderContractTest() {
    override val gaugeRecorder: FileWeftGaugeRecorder = object : FileWeftGaugeRecorder {
        override fun set(gauge: FileWeftGauge, value: Double, tags: Map<String, String>) = Unit
    }
}

class TraceContextScopeContractTestBehaviorTest : TraceContextScopeContractTest() {
    override val traceContextScope: TraceContextScope = object : TraceContextScope {
        private val current = ThreadLocal<TraceContext?>()

        override fun currentTraceContext(): TraceContext? = current.get()

        override fun bindTraceContext(traceContext: TraceContext?) {
            if (traceContext == null) current.remove() else current.set(traceContext)
        }
    }
}
