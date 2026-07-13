package ai.icen.fw.adapter.opentelemetry

import ai.icen.fw.spi.observability.FileWeftLogger
import ai.icen.fw.spi.observability.LogContext
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.logs.Logger
import io.opentelemetry.api.logs.Severity

/**
 * OpenTelemetry bridge for FileWeft structured logs.
 *
 * Tenant, document and trace identifiers from [LogContext] are attached as
 * attributes so a downstream collector can correlate logs with traces and
 * metrics. The logger itself does not perform network I/O.
 */
class OpenTelemetryFileWeftLogger(
    private val logger: Logger,
) : FileWeftLogger {

    override fun info(message: String, context: LogContext) {
        emit(Severity.INFO, message, null, context)
    }

    override fun warn(message: String, context: LogContext) {
        emit(Severity.WARN, message, null, context)
    }

    override fun error(message: String, throwable: Throwable?, context: LogContext) {
        emit(Severity.ERROR, message, throwable, context)
    }

    override fun debug(message: String, context: LogContext) {
        emit(Severity.DEBUG, message, null, context)
    }

    private fun emit(severity: Severity, message: String, throwable: Throwable?, context: LogContext) {
        try {
            val builder = logger.logRecordBuilder()
                .setSeverity(severity)
                .setBody(message)

            context.tenantId?.value?.let { builder.setAttribute(TENANT_ID_KEY, it) }
            context.documentId?.value?.let { builder.setAttribute(DOCUMENT_ID_KEY, it) }
            context.traceId?.value?.let { builder.setAttribute(TRACE_ID_KEY, it) }

            throwable?.let {
                builder.setAttribute(EXCEPTION_TYPE_KEY, it.javaClass.name)
                it.message?.let { msg -> builder.setAttribute(EXCEPTION_MESSAGE_KEY, msg) }
            }

            builder.emit()
        } catch (_: Exception) {
            // Logging must never alter FileWeft business acknowledgement semantics.
        }
    }

    private companion object {
        val TENANT_ID_KEY = AttributeKey.stringKey("fileweft.tenant_id")
        val DOCUMENT_ID_KEY = AttributeKey.stringKey("fileweft.document_id")
        val TRACE_ID_KEY = AttributeKey.stringKey("fileweft.trace_id")
        val EXCEPTION_TYPE_KEY = AttributeKey.stringKey("exception.type")
        val EXCEPTION_MESSAGE_KEY = AttributeKey.stringKey("exception.message")
    }
}
