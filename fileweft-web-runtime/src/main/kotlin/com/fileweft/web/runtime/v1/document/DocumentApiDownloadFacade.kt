package com.fileweft.web.runtime.v1.document

import com.fileweft.application.document.DocumentContentUnavailableException
import com.fileweft.application.document.DocumentDownload
import com.fileweft.application.document.DocumentDownloadService
import java.io.Closeable
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Pure JVM boundary for an authorized v1 document download.
 *
 * Tenant, user, storage and asset identifiers are deliberately absent. The
 * application service derives trusted context and this facade exposes only the
 * response metadata an HTTP adapter may safely forward.
 */
class DocumentApiDownloadFacade(
    private val downloads: DocumentDownloadService,
) {
    @JvmOverloads
    fun download(documentId: String, versionId: String? = null): DocumentApiDownload {
        // Validate every transport value before authorization, repositories or
        // storage can be reached through the application service.
        val validatedDocumentId = DocumentApiInputs.documentId(documentId)
        val validatedVersionId = versionId?.let(DocumentApiInputs::versionId)
        val opened = downloads.download(validatedDocumentId, validatedVersionId)
        return try {
            val contentDisposition = DocumentDownloadResponsePolicy.contentDisposition(opened.fileName)
            val contentType = DocumentDownloadResponsePolicy.contentType(opened.contentType)
            DefaultDocumentApiDownload(
                download = opened,
                safeContentDisposition = contentDisposition,
                safeContentType = contentType,
            )
        } catch (failure: Exception) {
            val closeFailure = try {
                opened.close()
                null
            } catch (closeFailure: Exception) {
                closeFailure
            }
            val unavailable = DocumentContentUnavailableException(cause = failure)
            closeFailure?.let(unavailable::addSuppressed)
            throw unavailable
        }
    }
}

/**
 * Caller-owned, Java-friendly v1 streaming handle.
 *
 * Only storage-verified length is exposed. A null value means an HTTP adapter
 * must omit Content-Length rather than substituting persisted metadata.
 */
interface DocumentApiDownload : Closeable {
    val content: InputStream
    val contentDisposition: String
    val contentType: String
    val verifiedContentLength: Long?
    override fun close()
}

private class DefaultDocumentApiDownload(
    download: DocumentDownload,
    safeContentDisposition: String,
    safeContentType: String,
) : DocumentApiDownload {
    override val content: InputStream = ApplicationDownloadInputStream(download)
    override val contentDisposition: String = safeContentDisposition
    override val contentType: String = safeContentType
    override val verifiedContentLength: Long? = download.verifiedContentLength
    private var closed: Boolean = false

    @Synchronized
    override fun close() {
        if (!closed) {
            closed = true
            content.close()
        }
    }
}

/** Closing either the exposed stream or its handle closes the application owner. */
private class ApplicationDownloadInputStream(
    private val download: DocumentDownload,
) : FilterInputStream(download.content) {
    private var closed: Boolean = false

    @Synchronized
    override fun close() {
        if (!closed) {
            closed = true
            download.close()
        }
    }
}

/** Header policy kept transport-neutral so Boot 2 and Boot 3 behave identically. */
internal object DocumentDownloadResponsePolicy {
    fun contentDisposition(sourceFileName: String): String {
        require(sourceFileName.length <= MAX_SOURCE_FILE_NAME_CHARACTERS) {
            "Persisted file name exceeds the safe response-header limit."
        }
        val fileName = safeFileName(sourceFileName)
        val fallback = asciiFallback(fileName)
        return "attachment; filename=\"$fallback\"; filename*=UTF-8''${encodeRfc5987(fileName)}"
    }

    fun contentType(sourceContentType: String?): String {
        if (
            sourceContentType == null ||
            sourceContentType.length > MAX_SOURCE_CONTENT_TYPE_CHARACTERS ||
            sourceContentType.any(::isUnsafeCodeUnit)
        ) {
            return DEFAULT_CONTENT_TYPE
        }
        val mediaType = sourceContentType
            .substringBefore(';')
            .trim()
            .lowercase(Locale.ROOT)
        if (!MIME_TYPE.matches(mediaType)) {
            return DEFAULT_CONTENT_TYPE
        }
        return mediaType.takeIf(ALLOWED_CONTENT_TYPES::contains) ?: DEFAULT_CONTENT_TYPE
    }

    private fun safeFileName(source: String): String {
        val lastSeparator = maxOf(source.lastIndexOf('/'), source.lastIndexOf('\\'))
        val legacyBaseName = source.substring(lastSeparator + 1)
        val cleaned = StringBuilder(legacyBaseName.length.coerceAtMost(MAX_SAFE_FILE_NAME_UTF8_BYTES))
        var index = 0
        while (index < legacyBaseName.length) {
            val character = legacyBaseName[index]
            val codePoint: Int
            if (Character.isHighSurrogate(character)) {
                if (index + 1 >= legacyBaseName.length || !Character.isLowSurrogate(legacyBaseName[index + 1])) {
                    index++
                    continue
                }
                codePoint = Character.toCodePoint(character, legacyBaseName[index + 1])
                index += 2
            } else if (Character.isLowSurrogate(character)) {
                index++
                continue
            } else {
                codePoint = character.code
                index++
            }
            if (!isUnsafeCodePoint(codePoint) && codePoint != '/'.code && codePoint != '\\'.code) {
                cleaned.appendCodePoint(codePoint)
            }
        }
        val candidate = cleaned.toString().trim().takeUnless(::isDotOnly) ?: DEFAULT_FILE_NAME
        return truncateUtf8PreservingExtension(candidate).takeUnless(::isDotOnly) ?: DEFAULT_FILE_NAME
    }

    private fun truncateUtf8PreservingExtension(value: String): String {
        if (utf8Length(value) <= MAX_SAFE_FILE_NAME_UTF8_BYTES) {
            return value
        }
        val dot = value.lastIndexOf('.')
        val extension = value.substring(dot.takeIf { it > 0 } ?: value.length)
            .takeIf { candidate ->
                candidate.length in 2..MAX_PRESERVED_EXTENSION_CHARACTERS + 1 &&
                    utf8Length(candidate) <= MAX_PRESERVED_EXTENSION_UTF8_BYTES
            }
            .orEmpty()
        val sourceStem = if (extension.isEmpty()) value else value.dropLast(extension.length)
        val stemBudget = MAX_SAFE_FILE_NAME_UTF8_BYTES - utf8Length(extension)
        val stem = truncateUtf8(sourceStem, stemBudget).trim().takeUnless(::isDotOnly) ?: DEFAULT_FILE_NAME
        return stem + extension
    }

    private fun truncateUtf8(value: String, maximumBytes: Int): String {
        val result = StringBuilder()
        var bytes = 0
        var index = 0
        while (index < value.length) {
            val codePoint = Character.codePointAt(value, index)
            val encodedBytes = when {
                codePoint <= 0x7f -> 1
                codePoint <= 0x7ff -> 2
                codePoint <= 0xffff -> 3
                else -> 4
            }
            if (bytes + encodedBytes > maximumBytes) {
                break
            }
            result.appendCodePoint(codePoint)
            bytes += encodedBytes
            index += Character.charCount(codePoint)
        }
        return result.toString()
    }

    private fun asciiFallback(fileName: String): String {
        val extension = asciiExtension(fileName)
        val stemSource = if (extension.isEmpty()) fileName else fileName.dropLast(extension.length)
        val mapped = StringBuilder()
        var previousUnderscore = false
        stemSource.forEach { character ->
            val safeCharacter = when {
                character in 'a'..'z' || character in 'A'..'Z' || character in '0'..'9' -> character
                character == '.' || character == '-' || character == '_' -> character
                else -> '_'
            }
            if (safeCharacter != '_' || !previousUnderscore) {
                mapped.append(safeCharacter)
            }
            previousUnderscore = safeCharacter == '_'
        }
        val maximumStemLength = MAX_ASCII_FALLBACK_CHARACTERS - extension.length
        val stem = mapped.toString()
            .trim('.', '-', '_')
            .take(maximumStemLength)
            .trimEnd('.', '-', '_')
            .ifBlank { DEFAULT_FILE_NAME }
        return stem + extension
    }

    private fun asciiExtension(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        if (dot <= 0 || dot == fileName.lastIndex) {
            return ""
        }
        val extension = fileName.substring(dot + 1)
        if (
            extension.length > MAX_ASCII_EXTENSION_CHARACTERS ||
            extension.any { character ->
                character !in 'a'..'z' && character !in 'A'..'Z' && character !in '0'..'9'
            }
        ) {
            return ""
        }
        return ".$extension"
    }

    private fun encodeRfc5987(fileName: String): String = buildString {
        fileName.toByteArray(StandardCharsets.UTF_8).forEach { byte ->
            val value = byte.toInt() and 0xff
            if (isRfc5987AttributeCharacter(value)) {
                append(value.toChar())
            } else {
                append('%')
                append(HEX[value ushr 4])
                append(HEX[value and 0x0f])
            }
        }
    }

    private fun isRfc5987AttributeCharacter(value: Int): Boolean =
        value in 'a'.code..'z'.code ||
            value in 'A'.code..'Z'.code ||
            value in '0'.code..'9'.code ||
            value == '!'.code || value == '#'.code || value == '$'.code || value == '&'.code ||
            value == '+'.code || value == '-'.code || value == '.'.code || value == '^'.code ||
            value == '_'.code || value == '`'.code || value == '|'.code || value == '~'.code

    private fun isUnsafeCodePoint(codePoint: Int): Boolean = when (Character.getType(codePoint)) {
        Character.CONTROL.toInt(),
        Character.FORMAT.toInt(),
        Character.LINE_SEPARATOR.toInt(),
        Character.PARAGRAPH_SEPARATOR.toInt(),
        Character.SURROGATE.toInt(),
        -> true
        else -> false
    }

    private fun isUnsafeCodeUnit(character: Char): Boolean =
        Character.isISOControl(character) || Character.getType(character.code) == Character.FORMAT.toInt()

    private fun isDotOnly(value: String): Boolean = value.isBlank() || value.all { character -> character == '.' }

    private fun utf8Length(value: String): Int = value.toByteArray(StandardCharsets.UTF_8).size

    internal const val MAX_SOURCE_FILE_NAME_CHARACTERS: Int = 65_536
    private const val MAX_SAFE_FILE_NAME_UTF8_BYTES: Int = 180
    private const val MAX_PRESERVED_EXTENSION_CHARACTERS: Int = 16
    private const val MAX_PRESERVED_EXTENSION_UTF8_BYTES: Int = 32
    private const val MAX_ASCII_FALLBACK_CHARACTERS: Int = 120
    private const val MAX_ASCII_EXTENSION_CHARACTERS: Int = 16
    private const val MAX_SOURCE_CONTENT_TYPE_CHARACTERS: Int = 256
    private const val DEFAULT_FILE_NAME: String = "download"
    private const val DEFAULT_CONTENT_TYPE: String = "application/octet-stream"
    private const val HEX: String = "0123456789ABCDEF"
    private val MIME_TYPE = Regex("[a-z0-9!#$&^_.+-]+/[a-z0-9!#$&^_.+-]+")
    private val ALLOWED_CONTENT_TYPES = setOf(
        "application/octet-stream",
        "application/pdf",
        "application/msword",
        "application/vnd.ms-excel",
        "application/vnd.ms-powerpoint",
        "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "application/x-7z-compressed",
        "application/zip",
        "image/gif",
        "image/jpeg",
        "image/png",
        "image/tiff",
        "image/webp",
        "text/csv",
        "text/plain",
    )
}
