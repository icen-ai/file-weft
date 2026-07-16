package ai.icen.fw.testkit.reliability

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/** Java-friendly asynchronous, digest, and redaction assertions for reliability contracts. */
object ReliabilityContractAssertions {
    @JvmStatic
    fun digest(character: Char): String {
        require(character in '0'..'9' || character in 'a'..'f') {
            "Reliability fixture digest character must be lowercase hexadecimal."
        }
        return character.toString().repeat(64)
    }

    @JvmStatic
    fun sha256(value: String): String {
        require(value.isNotEmpty()) { "Reliability fixture digest input must not be empty." }
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(StandardCharsets.UTF_8))
        val result = StringBuilder(bytes.size * 2)
        bytes.forEach { byte -> result.append(String.format("%02x", byte.toInt() and 0xff)) }
        return result.toString()
    }

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
    fun <T : Any> awaitStage(stage: CompletionStage<T>?, timeout: Duration, stageName: String): T {
        assertNotNull(stage, "$stageName must return a non-null CompletionStage.")
        val value = requireNotNull(stage).toCompletableFuture()
            .get(timeoutMillis(timeout, "$stageName timeout"), TimeUnit.MILLISECONDS)
        assertNotNull(value, "$stageName must complete with a non-null value.")
        return requireNotNull(value)
    }

    @JvmStatic
    fun awaitFailure(stage: CompletionStage<*>?, timeout: Duration, stageName: String): Throwable {
        assertNotNull(stage, "$stageName must return a non-null CompletionStage.")
        var observed: Throwable? = null
        try {
            requireNotNull(stage).toCompletableFuture()
                .get(timeoutMillis(timeout, "$stageName timeout"), TimeUnit.MILLISECONDS)
        } catch (failure: Throwable) {
            observed = unwrap(failure)
        }
        assertNotNull(observed, "$stageName must complete exceptionally.")
        return requireNotNull(observed)
    }

    @JvmStatic
    fun assertSha256(value: String, name: String) {
        assertEquals(64, value.length, "$name must retain a SHA-256 digest.")
        assertTrue(value.all { it in '0'..'9' || it in 'a'..'f' }, "$name must be lowercase hexadecimal.")
    }

    @JvmStatic
    fun assertRedacted(rendered: String, vararg forbiddenValues: String) {
        forbiddenValues.filter { it.isNotEmpty() }.forEach { forbidden ->
            assertTrue(!rendered.contains(forbidden), "Reliability rendering exposed a forbidden value.")
        }
    }

    private fun unwrap(failure: Throwable): Throwable {
        var current = failure
        while ((current is CompletionException || current is ExecutionException) && current.cause != null) {
            current = requireNotNull(current.cause)
        }
        return current
    }
}
