package com.fileweft.application.catalog

import com.fileweft.application.document.CreateDocumentDraftCommand
import com.fileweft.application.document.DocumentDraftService
import com.fileweft.domain.document.Document
import com.fileweft.spi.catalog.DocumentCatalogBinding
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.LinkedHashMap
import java.util.Locale

/**
 * Creates a draft that is atomically bound to a host-owned catalog folder.
 *
 * Folder resolution and its host ACL happen before [DocumentDraftService]
 * opens object storage or its persistence transaction. The returned canonical
 * folder id is the only catalog binding that may enter asset metadata; callers
 * cannot write FileWeft or catalog-reserved metadata namespaces themselves.
 */
class DocumentCatalogDraftService(
    private val drafts: DocumentDraftService,
    private val catalogAccess: DocumentCatalogAccessService,
) {
    /**
     * Resolves [folderId] from trusted tenant and user context, validates all
     * caller-owned metadata, and delegates the actual upload plus asset and
     * document persistence to [DocumentDraftService].
     *
     * The folder lookup intentionally happens before any storage call. The
     * binding reaches the asset through the same draft creation transaction as
     * the uploaded file metadata; no follow-up folder move is required.
     */
    fun createInFolder(
        command: CreateDocumentDraftCommand,
        folderId: String,
        content: InputStream,
    ): Document {
        val folder = catalogAccess.requireFolderForDocumentCreation(folderId)
        val metadata = sanitizeCallerMetadata(command.metadata)
        metadata[DocumentCatalogBinding.METADATA_KEY] = folder.id
        validateStoredMetadata(metadata)
        return drafts.create(command.copy(metadata = metadata), content)
    }

    private fun sanitizeCallerMetadata(source: Map<String, String>): LinkedHashMap<String, String> {
        require(source.size <= MAX_CALLER_METADATA_ENTRIES) {
            "Document metadata must contain at most $MAX_CALLER_METADATA_ENTRIES caller entries."
        }
        val sanitized = LinkedHashMap<String, String>(source.size + 1)
        source.entries.forEach { entry ->
            require(sanitized.size < MAX_CALLER_METADATA_ENTRIES) {
                "Document metadata must contain at most $MAX_CALLER_METADATA_ENTRIES caller entries."
            }
            val key: String? = entry.key
            val value: String? = entry.value
            require(key != null) { "Document metadata keys must not be null." }
            require(value != null) { "Document metadata values must not be null." }
            validateMetadataEntry(key, value)
            require(!isReservedMetadataKey(key)) {
                "Caller metadata must not use a FileWeft-reserved namespace."
            }
            sanitized[key] = value
        }
        return sanitized
    }

    private fun validateStoredMetadata(metadata: Map<String, String>) {
        require(metadata.size <= MAX_STORED_METADATA_ENTRIES) {
            "Document metadata must contain at most $MAX_STORED_METADATA_ENTRIES stored entries."
        }
        var totalUtf8Bytes = 0L
        metadata.entries.forEach { entry ->
            val key: String? = entry.key
            val value: String? = entry.value
            require(key != null) { "Document metadata keys must not be null." }
            require(value != null) { "Document metadata values must not be null." }
            validateMetadataEntry(key, value)
            totalUtf8Bytes += utf8Length(key).toLong() + utf8Length(value).toLong()
            require(totalUtf8Bytes <= MAX_TOTAL_METADATA_UTF8_BYTES) {
                "Document metadata must not exceed $MAX_TOTAL_METADATA_UTF8_BYTES UTF-8 bytes."
            }
        }
    }

    private fun validateMetadataEntry(key: String, value: String) {
        require(key.isNotBlank()) { "Document metadata keys must not be blank." }
        require(value.isNotBlank()) { "Document metadata values must not be blank." }
        require(!containsControlCharacter(key)) { "Document metadata keys must not contain control characters." }
        require(!containsControlCharacter(value)) { "Document metadata values must not contain control characters." }
        require(codePointCount(key) <= MAX_METADATA_KEY_CHARACTERS) {
            "Document metadata keys must not exceed $MAX_METADATA_KEY_CHARACTERS characters."
        }
        require(codePointCount(value) <= MAX_METADATA_VALUE_CHARACTERS) {
            "Document metadata values must not exceed $MAX_METADATA_VALUE_CHARACTERS characters."
        }
        require(utf8Length(key) <= MAX_METADATA_KEY_UTF8_BYTES) {
            "Document metadata keys must not exceed $MAX_METADATA_KEY_UTF8_BYTES UTF-8 bytes."
        }
        require(utf8Length(value) <= MAX_METADATA_VALUE_UTF8_BYTES) {
            "Document metadata values must not exceed $MAX_METADATA_VALUE_UTF8_BYTES UTF-8 bytes."
        }
    }

    private fun isReservedMetadataKey(key: String): Boolean {
        val normalized = key.lowercase(Locale.ROOT)
        return normalized.startsWith(CATALOG_NAMESPACE) || normalized.startsWith(FILEWEFT_NAMESPACE)
    }

    private fun containsControlCharacter(value: String): Boolean =
        value.any { character -> Character.isISOControl(character) }

    private fun codePointCount(value: String): Int = value.codePointCount(0, value.length)

    private fun utf8Length(value: String): Int = value.toByteArray(StandardCharsets.UTF_8).size

    companion object {
        /** Maximum asset metadata entries after FileWeft adds the folder binding. */
        const val MAX_STORED_METADATA_ENTRIES: Int = 32
        /** Maximum caller-owned entries; one stored entry is reserved for the verified folder binding. */
        const val MAX_CALLER_METADATA_ENTRIES: Int = MAX_STORED_METADATA_ENTRIES - 1
        const val MAX_METADATA_KEY_CHARACTERS: Int = 128
        const val MAX_METADATA_VALUE_CHARACTERS: Int = 1_024
        const val MAX_METADATA_KEY_UTF8_BYTES: Int = 256
        const val MAX_METADATA_VALUE_UTF8_BYTES: Int = 2_048
        const val MAX_TOTAL_METADATA_UTF8_BYTES: Int = 16_384

        private const val CATALOG_NAMESPACE = "catalog."
        private const val FILEWEFT_NAMESPACE = "fileweft."
    }
}
