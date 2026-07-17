package ai.icen.fw.adapter.logging

import ai.icen.fw.spi.observability.FileWeftLogger
import ai.icen.fw.spi.observability.LogContext
import org.slf4j.LoggerFactory
import org.slf4j.MDC

/**
 * Structured logger that writes through SLF4J and populates MDC with the
 * FlowWeft context fields required for observability: tenant id, document id
 * and trace id. Hosts can then render the MDC map as JSON or key-value pairs
 * using their chosen logback/log4j2 encoder.
 */
class Slf4jFileWeftLogger(name: String) : FileWeftLogger {
    private val delegate = LoggerFactory.getLogger(name)

    override fun info(message: String, context: LogContext) {
        withMdc(context) { delegate.info(message) }
    }

    override fun warn(message: String, context: LogContext) {
        withMdc(context) { delegate.warn(message) }
    }

    override fun error(message: String, throwable: Throwable?, context: LogContext) {
        withMdc(context) {
            if (throwable == null) delegate.error(message) else delegate.error(message, throwable)
        }
    }

    override fun debug(message: String, context: LogContext) {
        withMdc(context) { delegate.debug(message) }
    }

    private fun withMdc(context: LogContext, action: () -> Unit) {
        val previous = MDC.getCopyOfContextMap()
        try {
            context.tenantId?.let { MDC.put(TENANT_ID_KEY, it.value) }
            context.documentId?.let { MDC.put(DOCUMENT_ID_KEY, it.value) }
            context.traceId?.let { MDC.put(TRACE_ID_KEY, it.value) }
            action()
        } finally {
            restore(previous)
        }
    }

    private fun restore(previous: Map<String, String>?) {
        if (previous == null) {
            MDC.clear()
        } else {
            MDC.setContextMap(previous)
        }
    }

    private companion object {
        const val TENANT_ID_KEY = "tenantId"
        const val DOCUMENT_ID_KEY = "documentId"
        const val TRACE_ID_KEY = "traceId"
    }
}
