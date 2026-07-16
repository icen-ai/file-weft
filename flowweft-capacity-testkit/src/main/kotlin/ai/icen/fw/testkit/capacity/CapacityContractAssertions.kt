package ai.icen.fw.testkit.capacity

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Java-friendly digest, redaction, and bounded-concurrency helpers for capacity contracts. */
object CapacityContractAssertions {
    @JvmStatic
    fun digest(character: Char): String {
        require(character in '0'..'9' || character in 'a'..'f') {
            "Capacity fixture digest character must be lowercase hexadecimal."
        }
        return character.toString().repeat(64)
    }

    @JvmStatic
    fun sha256(value: String): String {
        require(value.isNotEmpty()) { "Capacity fixture digest input must not be empty." }
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
        return buildString(bytes.size * 2) {
            bytes.forEach { byte -> append(String.format("%02x", byte.toInt() and 0xff)) }
        }
    }

    @JvmStatic
    fun assertSha256(value: String, name: String) {
        assertEquals(64, value.length, "$name must retain a SHA-256 digest.")
        assertTrue(value.all { it in '0'..'9' || it in 'a'..'f' }) {
            "$name must be lowercase hexadecimal."
        }
    }

    @JvmStatic
    fun assertRedacted(rendered: String, vararg forbiddenValues: String) {
        forbiddenValues.filter(String::isNotEmpty).forEach { forbidden ->
            assertTrue(!rendered.contains(forbidden), "Capacity rendering exposed a forbidden value.")
        }
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
    fun <T : Any> race(first: Callable<T>, second: Callable<T>, timeout: Duration): List<T> {
        val executor = Executors.newFixedThreadPool(2)
        return try {
            val futures = executor.invokeAll(
                listOf(first, second),
                timeoutMillis(timeout, "Capacity race timeout"),
                TimeUnit.MILLISECONDS,
            )
            assertEquals(2, futures.size, "Capacity race must retain both contenders.")
            futures.mapIndexed { index, future ->
                assertTrue(!future.isCancelled, "Capacity race contender $index timed out.")
                val value = future.get()
                assertNotNull(value, "Capacity race contender $index returned null.")
                requireNotNull(value)
            }
        } finally {
            executor.shutdownNow()
        }
    }
}
