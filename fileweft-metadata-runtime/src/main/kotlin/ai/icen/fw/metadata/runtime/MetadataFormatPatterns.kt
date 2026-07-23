package ai.icen.fw.metadata.runtime

import com.google.re2j.Pattern as LinearPattern
import java.util.concurrent.ConcurrentHashMap

/**
 * Process-wide cache of compiled RE2/J patterns keyed by their format source.
 *
 * Schemas are immutable and the same format strings repeat across tenants and
 * evaluations, so one compiled pattern can be shared process-wide: RE2/J
 * `Pattern` instances are immutable and thread-safe after compilation.
 *
 * The cache is bounded. Once [MAX_CACHE_ENTRIES] distinct formats are held,
 * additional formats compile directly without being stored, which degrades to
 * the pre-cache behavior for pathological hosts instead of growing the heap
 * without limit. No eviction policy is needed at this size.
 *
 * Compilation failures are never cached: [compile] throws
 * [com.google.re2j.PatternSyntaxException] for an invalid format on every
 * call, exactly like a direct `Pattern.compile`, so callers keep wrapping
 * failures at their own layer.
 */
internal object MetadataFormatPatterns {
    private const val MAX_CACHE_ENTRIES = 512

    private val compiled = ConcurrentHashMap<String, LinearPattern>()

    fun compile(format: String): LinearPattern {
        compiled[format]?.let { return it }
        // Throws PatternSyntaxException for an invalid format; nothing is stored.
        val pattern = LinearPattern.compile(format)
        // The size check races with concurrent puts, so the map may briefly
        // exceed the bound by the number of in-flight compilations; it stays
        // effectively bounded without locking. putIfAbsent returns the
        // canonical cached instance so racing callers share one pattern.
        return if (compiled.size < MAX_CACHE_ENTRIES) {
            compiled.putIfAbsent(format, pattern) ?: pattern
        } else {
            pattern
        }
    }

    /** Test hook: current number of cached patterns. Production code must not depend on it. */
    internal fun cachedPatternCount(): Int = compiled.size

    /** Test hook: drops every cached pattern. Production code must not call this. */
    internal fun evictAll() {
        compiled.clear()
    }
}
