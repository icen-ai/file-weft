package ai.icen.fw.testkit.agent

import org.junit.jupiter.api.Assertions.assertNotNull
import java.time.Duration
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit

/**
 * Java-friendly asynchronous assertions shared by FlowWeft Agent provider contracts.
 *
 * These helpers deliberately form an explicit public API. Provider contract suites may call them
 * from Java or Kotlin without depending on Kotlin file-facade methods whose visibility is easy to
 * misread in a published JVM artifact.
 */
object AgentContractAssertions {
    /** Converts one positive timeout to milliseconds without overflow or sub-millisecond ambiguity. */
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

    /** Awaits a non-null Java 8 stage and asserts that it completes with a non-null value. */
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
}
