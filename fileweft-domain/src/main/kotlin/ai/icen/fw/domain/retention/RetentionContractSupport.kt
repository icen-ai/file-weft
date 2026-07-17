package ai.icen.fw.domain.retention

internal const val MAX_RETENTION_EVIDENCE_ITEMS: Int = 4096

internal fun requireRetentionText(
    value: String,
    label: String,
    maximumLength: Int = 256,
) {
    require(value.isNotBlank() && value == value.trim()) { "$label must be non-blank and unpadded." }
    require(value.length <= maximumLength) { "$label must not exceed $maximumLength characters." }
    require(value.none { character -> character.isISOControl() }) {
        "$label must not contain control characters."
    }
}
