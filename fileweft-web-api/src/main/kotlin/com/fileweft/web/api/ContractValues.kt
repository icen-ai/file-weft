package com.fileweft.web.api

import java.util.ArrayList
import java.util.Collections

internal fun requiredText(value: String, field: String, maximumLength: Int = DEFAULT_MAX_TEXT_LENGTH): String = value.also {
    require(maximumLength > 0) { "$field maximum length must be greater than zero." }
    require(it.isNotBlank()) { "$field must not be blank." }
    require(it.length <= maximumLength) { "$field must not exceed $maximumLength characters." }
    require(it.none(::isUnsafeControlCharacter)) { "$field must not contain control characters." }
}

internal fun optionalText(
    value: String?,
    field: String,
    maximumLength: Int = DEFAULT_MAX_TEXT_LENGTH,
): String? = value?.also {
    require(maximumLength > 0) { "$field maximum length must be greater than zero." }
    require(it.isNotBlank()) { "$field must not be blank when provided." }
    require(it.length <= maximumLength) { "$field must not exceed $maximumLength characters." }
    require(it.none(::isUnsafeControlCharacter)) { "$field must not contain control characters." }
}

internal fun <T> immutableList(values: List<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))

private fun isUnsafeControlCharacter(character: Char): Boolean =
    character.code in 0..31 || character.code in 127..159

private const val DEFAULT_MAX_TEXT_LENGTH = 512
