package ai.icen.fw.adapter.logging

import ai.icen.fw.spi.observability.FileWeftLogger
import ai.icen.fw.spi.observability.LogContext

/**
 * Fallback logger that discards every event. It is used when a host does not
 * supply a structured logger and FileWeft must not fail because of logging.
 */
object NoOpFileWeftLogger : FileWeftLogger {
    override fun info(message: String, context: LogContext) = Unit

    override fun warn(message: String, context: LogContext) = Unit

    override fun error(message: String, throwable: Throwable?, context: LogContext) = Unit

    override fun debug(message: String, context: LogContext) = Unit
}
