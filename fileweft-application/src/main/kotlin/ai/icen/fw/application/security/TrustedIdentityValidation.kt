package ai.icen.fw.application.security

/** Maximum persisted width for opaque identifiers owned by a host user realm. */
internal const val MAX_TRUSTED_USER_ID_LENGTH: Int = 256

internal fun validatedTrustedUserId(value: String, label: String = "Trusted user id"): String = value.also {
    require(it.isNotEmpty()) { "$label must not be blank." }
    require(it.length <= MAX_TRUSTED_USER_ID_LENGTH) {
        "$label must not exceed $MAX_TRUSTED_USER_ID_LENGTH UTF-16 code units."
    }
    require(!it.hasUnpairedSurrogate()) { "$label must be well-formed Unicode." }
    require(!it.firstCodePoint().isIdentityBoundaryWhitespace() && !it.lastCodePoint().isIdentityBoundaryWhitespace()) {
        "$label must not have leading or trailing Unicode whitespace."
    }
    require(!it.hasForbiddenIdentityCodePoint()) {
        "$label must not contain ISO control or forbidden Unicode format characters."
    }
}

/**
 * Display names are optional evidence, not authorization keys. Unsafe values
 * are omitted instead of preventing an otherwise valid opaque identity from
 * performing work.
 */
internal fun safeTrustedDisplayName(value: String?): String? = value
    ?.takeIf { name ->
        name.isNotBlank() &&
            name.length <= MAX_TRUSTED_DISPLAY_NAME_LENGTH &&
            !name.hasUnpairedSurrogate() &&
            !name.hasForbiddenIdentityCodePoint()
    }

/*
 * Keep this table explicit. Character.getType() follows the Unicode data in
 * each JDK, while FileWeft supports JDK 8 through 25 and installs a fixed
 * PostgreSQL constraint with the same code-point set.
 */
private fun String.hasForbiddenIdentityCodePoint(): Boolean {
    var offset = 0
    while (offset < length) {
        val codePoint = Character.codePointAt(this, offset)
        if (codePoint.isIsoControlCodePoint() || codePoint.isForbiddenIdentityFormatCodePoint()) return true
        offset += Character.charCount(codePoint)
    }
    return false
}

private fun String.hasUnpairedSurrogate(): Boolean {
    var offset = 0
    while (offset < length) {
        val current = this[offset]
        when {
            Character.isHighSurrogate(current) -> {
                if (offset + 1 >= length || !Character.isLowSurrogate(this[offset + 1])) return true
                offset += 2
            }
            Character.isLowSurrogate(current) -> return true
            else -> offset++
        }
    }
    return false
}

private fun String.firstCodePoint(): Int = Character.codePointAt(this, 0)

private fun String.lastCodePoint(): Int = Character.codePointBefore(this, length)

private fun Int.isIsoControlCodePoint(): Boolean = this in 0x0000..0x001F || this in 0x007F..0x009F

private fun Int.isIdentityBoundaryWhitespace(): Boolean =
    this == 0x0020 ||
        this == 0x00A0 ||
        this == 0x1680 ||
        this in 0x2000..0x200A ||
        this in 0x2028..0x2029 ||
        this == 0x202F ||
        this == 0x205F ||
        this == 0x3000

private fun Int.isForbiddenIdentityFormatCodePoint(): Boolean =
    this == 0x00AD ||
        this in 0x0600..0x0605 ||
        this == 0x061C ||
        this == 0x06DD ||
        this == 0x070F ||
        this in 0x0890..0x0891 ||
        this == 0x08E2 ||
        this == 0x180E ||
        this in 0x200B..0x200F ||
        this in 0x202A..0x202E ||
        this in 0x2060..0x2064 ||
        this in 0x2066..0x206F ||
        this == 0xFEFF ||
        this in 0xFFF9..0xFFFB ||
        this == 0x110BD ||
        this == 0x110CD ||
        this in 0x13430..0x1343F ||
        this in 0x1BCA0..0x1BCA3 ||
        this in 0x1D173..0x1D17A ||
        this == 0xE0001 ||
        this in 0xE0020..0xE007F

private const val MAX_TRUSTED_DISPLAY_NAME_LENGTH = 256
