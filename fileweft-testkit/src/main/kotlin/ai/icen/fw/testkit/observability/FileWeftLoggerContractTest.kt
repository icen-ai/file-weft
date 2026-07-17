package ai.icen.fw.testkit.observability

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.observability.FileWeftLogger
import ai.icen.fw.spi.observability.LogContext
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Test

/** Reusable structured-logging contract for host and adapter implementations. */
abstract class FileWeftLoggerContractTest {
    protected abstract val fileWeftLogger: FileWeftLogger

    @Test
    fun `accepts every level with the complete structured context`() {
        val context = LogContext(
            tenantId = Identifier("tenant-contract"),
            documentId = Identifier("document-contract"),
            traceId = Identifier("trace-contract"),
        )

        assertDoesNotThrow {
            fileWeftLogger.debug("contract-debug", context)
            fileWeftLogger.info("contract-info", context)
            fileWeftLogger.warn("contract-warn", context)
            fileWeftLogger.error("contract-error", ContractFailure, context)
        }
    }

    @Test
    fun `accepts the default empty context and an absent throwable`() {
        assertDoesNotThrow {
            fileWeftLogger.debug("contract-debug-empty")
            fileWeftLogger.info("contract-info-empty")
            fileWeftLogger.warn("contract-warn-empty")
            fileWeftLogger.error("contract-error-empty")
        }
    }

    private object ContractFailure : RuntimeException("testkit-contract-failure")
}
