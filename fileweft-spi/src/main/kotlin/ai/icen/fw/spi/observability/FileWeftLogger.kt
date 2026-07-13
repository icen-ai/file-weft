package ai.icen.fw.spi.observability

import ai.icen.fw.core.id.Identifier

/**
 * Structured logging boundary for FileWeft operations.
 *
 * Implementations must include the provided [LogContext] fields (tenant id,
 * document id, trace id) in every emitted event without throwing for normal
 * logging failures.
 */
interface FileWeftLogger {
    fun info(message: String, context: LogContext = LogContext.EMPTY)

    fun warn(message: String, context: LogContext = LogContext.EMPTY)

    fun error(message: String, throwable: Throwable? = null, context: LogContext = LogContext.EMPTY)

    fun debug(message: String, context: LogContext = LogContext.EMPTY)
}

/**
 * Context fields that must accompany every structured log event.
 */
class LogContext @JvmOverloads constructor(
    val tenantId: Identifier? = null,
    val documentId: Identifier? = null,
    val traceId: Identifier? = null,
) {
    companion object {
        @JvmField
        val EMPTY = LogContext()
    }
}
