package ai.icen.fw.testkit.retrieval

import ai.icen.fw.retrieval.api.RetrievalCancellationOutcome
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import java.time.Duration
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit

/**
 * Intentional Java-friendly assertion surface shared by FlowWeft retrieval provider contracts.
 *
 * Unlike a Kotlin top-level `internal` helper, this type has a deliberate public JVM name and can
 * be treated as part of the retrieval TestKit ABI.
 */
object RetrievalContractAssertions {
    @JvmStatic
    fun timeoutMillis(duration: Duration, name: String): Long {
        require(!duration.isNegative && !duration.isZero) { "$name must be positive." }
        val millis = try {
            duration.toMillis()
        } catch (failure: ArithmeticException) {
            throw IllegalArgumentException("$name is too large.", failure)
        }
        require(millis > 0L) { "$name must be at least one millisecond." }
        return millis
    }

    @JvmStatic
    fun <T : Any> awaitStage(
        stage: CompletionStage<T>?,
        timeout: Duration,
        stageName: String,
    ): T {
        assertNotNull(stage, "$stageName must return a non-null CompletionStage.")
        val value = requireNotNull(stage)
            .toCompletableFuture()
            .get(timeoutMillis(timeout, "$stageName timeout"), TimeUnit.MILLISECONDS)
        assertNotNull(value, "$stageName must complete with a non-null value.")
        return requireNotNull(value)
    }

    @JvmStatic
    fun assertCancellationDeclaration(
        supportsCancellation: Boolean,
        outcome: RetrievalCancellationOutcome,
    ) {
        if (supportsCancellation) {
            assertNotEquals(
                RetrievalCancellationOutcome.UNSUPPORTED,
                outcome,
                "A provider advertising cancellation must not report it as unsupported.",
            )
        } else {
            assertNotEquals(
                RetrievalCancellationOutcome.ACCEPTED,
                outcome,
                "A provider that does not advertise cancellation must not claim to have accepted it.",
            )
        }
    }
}
