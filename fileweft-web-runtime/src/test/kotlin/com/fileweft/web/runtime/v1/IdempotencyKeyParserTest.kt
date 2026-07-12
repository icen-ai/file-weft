package com.fileweft.web.runtime.v1

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame

class IdempotencyKeyParserTest {
    @Test
    fun `returns the exact single valid caller value`() {
        val key = String(charArrayOf('R', 'e', 'q', '-', '1', '.', '_', '~', ':'))

        val parsed = IdempotencyKeyParser.parse(listOf(key))

        assertSame(key, parsed)
        assertEquals("a", IdempotencyKeyParser.parse(listOf("a")))
        assertEquals("a".repeat(128), IdempotencyKeyParser.parse(listOf("a".repeat(128))))
    }

    @Test
    fun `rejects missing empty and repeated header values`() {
        listOf<List<String>?>(null, emptyList(), listOf("request-1", "request-2"), listOf("same", "same"))
            .forEach { values ->
                val failure = assertFailsWith<IllegalArgumentException> {
                    IdempotencyKeyParser.parse(values)
                }
                assertEquals("Idempotency-Key header must be supplied exactly once.", failure.message)
            }
    }

    @Test
    fun `rejects blank non ascii control malformed and oversized values without echoing them`() {
        val invalidValues = listOf(
            "",
            " ",
            "secret key",
            "密钥",
            "request\rkey",
            "request\nkey",
            "request\u007fkey",
            "-starts-with-punctuation",
            "request/key",
            "request,key",
            "request=key",
            "a".repeat(129),
        )

        invalidValues.forEach { value ->
            val failure = assertFailsWith<IllegalArgumentException> {
                IdempotencyKeyParser.parse(listOf(value))
            }
            assertEquals("Idempotency-Key header is invalid.", failure.message)
            if (value.length > 1) {
                assertFalse(failure.message.orEmpty().contains(value))
            }
        }
    }
}
