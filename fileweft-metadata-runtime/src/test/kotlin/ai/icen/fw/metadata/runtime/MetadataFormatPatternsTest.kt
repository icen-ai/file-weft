package ai.icen.fw.metadata.runtime

import ai.icen.fw.metadata.api.MetadataField
import ai.icen.fw.metadata.api.MetadataFieldType
import ai.icen.fw.metadata.api.MetadataSchema
import com.google.re2j.Pattern as LinearPattern
import com.google.re2j.PatternSyntaxException as LinearPatternSyntaxException
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MetadataFormatPatternsTest {
    @BeforeEach
    fun evictSharedCache() = MetadataFormatPatterns.evictAll()

    @Test
    fun `compiles each format once and reuses the immutable pattern`() {
        val first = MetadataFormatPatterns.compile("cache-probe-[0-9]+")
        val second = MetadataFormatPatterns.compile("cache-probe-[0-9]+")

        assertSame(first, second, "A repeated format must reuse the cached Pattern instance.")
        assertEquals(1, MetadataFormatPatterns.cachedPatternCount())
    }

    @Test
    fun `validator evaluations share one compiled pattern per format`() {
        val schema = MetadataSchema(
            "cache-behavior",
            "1",
            listOf(
                MetadataField("code", MetadataFieldType.STRING, format = "cacheshared[0-9]+"),
                MetadataField("label", MetadataFieldType.STRING, format = "cachelabel[a-z]+"),
            ),
        )
        val validator = MetadataValidator()
        val metadata = mapOf("code" to "cacheshared123", "label" to "cachelabelabc")

        repeat(3) {
            assertTrue(validator.validate(schema, metadata).valid, "Cached patterns must keep evaluations valid.")
        }

        assertEquals(
            2,
            MetadataFormatPatterns.cachedPatternCount(),
            "Schema validation plus per-field normalization must compile each format only once.",
        )
        assertSame(
            MetadataFormatPatterns.compile("cacheshared[0-9]+"),
            MetadataFormatPatterns.compile("cacheshared[0-9]+"),
        )
    }

    @Test
    fun `invalid formats fail on every call and are never cached`() {
        repeat(2) {
            assertFailsWith<LinearPatternSyntaxException> {
                MetadataFormatPatterns.compile("cache-invalid-[")
            }
        }
        assertEquals(0, MetadataFormatPatterns.cachedPatternCount())

        val schema = MetadataSchema(
            "cache-invalid",
            "1",
            listOf(MetadataField("code", MetadataFieldType.STRING, format = "cache-invalid-[")),
        )
        val failure = assertFailsWith<MetadataSchemaConfigurationException> {
            MetadataValidator().validate(schema, mapOf("code" to "x"))
        }
        assertEquals(MetadataSchemaConfigurationException.MESSAGE, failure.message)
        assertTrue(
            failure.cause is LinearPatternSyntaxException,
            "The RE2/J syntax failure must be retained as the cause.",
        )
    }

    @Test
    fun `stays bounded and degrades to direct compilation beyond the entry limit`() {
        // 512 must match MetadataFormatPatterns.MAX_CACHE_ENTRIES.
        repeat(600) { MetadataFormatPatterns.compile("cache-limit-$it") }

        assertEquals(512, MetadataFormatPatterns.cachedPatternCount())

        val overflow = MetadataFormatPatterns.compile("cache-limit-overflow")
        assertEquals(512, MetadataFormatPatterns.cachedPatternCount(), "A full cache must not grow further.")
        assertNotSame(
            overflow,
            MetadataFormatPatterns.compile("cache-limit-overflow"),
            "Formats beyond the bound compile directly without being cached.",
        )
    }

    @Test
    fun `shares patterns safely across concurrent compilations`() {
        val formats = (0 until 8).map { "cache-concurrent-$it-[0-9]+" }
        val threadCount = 8
        val iterations = 200
        val executor = Executors.newFixedThreadPool(threadCount)
        val start = CountDownLatch(1)
        val observed = ConcurrentHashMap<String, MutableList<LinearPattern>>()
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        try {
            val tasks = (0 until threadCount).map {
                executor.submit {
                    start.await()
                    repeat(iterations) {
                        formats.forEach { format ->
                            try {
                                observed
                                    .computeIfAbsent(format) { Collections.synchronizedList(mutableListOf()) }
                                    .add(MetadataFormatPatterns.compile(format))
                            } catch (throwable: Throwable) {
                                failures += throwable
                            }
                        }
                    }
                }
            }
            start.countDown()
            tasks.forEach { it.get(30, TimeUnit.SECONDS) }
        } finally {
            executor.shutdownNow()
        }

        assertTrue(failures.isEmpty(), "Concurrent compilation must not throw: $failures")
        assertEquals(formats.size, observed.size)
        observed.forEach { (format, patterns) ->
            val canonical = patterns.first()
            patterns.forEach { pattern ->
                assertSame(canonical, pattern, "Concurrent callers must share one cached pattern for $format.")
            }
        }
    }
}
