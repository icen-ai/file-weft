package ai.icen.fw.web.api

/**
 * Cursor-based page payload. Cursors are opaque to callers and must be created
 * by a query adapter from stable sort keys only. A null [total] means an exact
 * count was intentionally not calculated.
 */
class ApiPage<T> @JvmOverloads constructor(
    items: List<T>,
    nextCursor: String? = null,
    total: Long? = null,
) {
    val items: List<T> = immutableList(items)
    val nextCursor: String? = optionalText(nextCursor, "Page next cursor", 512)
    val total: Long? = total

    init {
        require(total == null || total >= 0) { "Page total must not be negative." }
        require(total == null || total >= items.size.toLong()) {
            "Page total must not be smaller than the returned item count."
        }
    }
}
