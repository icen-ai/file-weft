package ai.icen.fw.application.document

import ai.icen.fw.core.id.Identifier
import java.util.Collections
import java.util.LinkedHashSet

/**
 * Optional host-catalog boundary used to constrain all document reads.
 *
 * Implementations derive tenant and user identity from trusted FlowWeft
 * providers. The public query request carries only an opaque folder id;
 * [readableFolderIds] is always derived from the trusted host catalog rather
 * than from caller input.
 */
interface DocumentFolderReadAccess {
    fun requireFolderForDocumentRead(folderId: String)

    /**
     * Returns the complete, user-visible folder scope for the current trusted
     * tenant and user. An empty result is a valid deny-all scope, not a signal
     * to fall back to an unfiltered document query.
     */
    fun readableFolderIds(): Set<String>
}

/**
 * Additive download-specific catalog capability.
 *
 * Keeping this as a child interface leaves the existing
 * [DocumentFolderReadAccess] JVM contract unchanged for previously compiled
 * Java and Kotlin hosts. Download visibility safely falls back to
 * [DocumentFolderReadAccess.readableFolderIds] for legacy implementations.
 */
interface DocumentFolderDownloadAccess : DocumentFolderReadAccess {
    fun readableFolderIdsForDocumentDownload(documentId: Identifier): Set<String>
}

/**
 * Immutable folder visibility constraint passed only from the application
 * service to its persistence read port. A null scope means no catalog
 * integration is configured; an empty scope means the trusted catalog grants
 * no folder visibility.
 */
class DocumentFolderReadScope(folderIds: Collection<String>) {
    val folderIds: List<String> = Collections.unmodifiableList(LinkedHashSet(folderIds).toList())

    val isEmpty: Boolean
        get() = folderIds.isEmpty()

    init {
        folderIds.forEach { folderId ->
            require(folderId.isNotBlank()) { "Readable document folder id must not be blank." }
            require(folderId.length <= MAX_FOLDER_ID_LENGTH) {
                "Readable document folder id must not exceed $MAX_FOLDER_ID_LENGTH characters."
            }
            require(folderId.none { character -> Character.isISOControl(character) }) {
                "Readable document folder id must not contain control characters."
            }
        }
    }

    companion object {
        private const val MAX_FOLDER_ID_LENGTH = 256
    }
}

/**
 * A folder-filtered query is intentionally unavailable until a host catalog
 * access service is configured. Callers must not fall back to an unscoped
 * database filter, because that would bypass host folder ACLs.
 */
class DocumentFolderReadAccessUnavailableException : IllegalStateException(DEFAULT_MESSAGE) {
    companion object {
        const val DEFAULT_MESSAGE: String = "Document folder filtering is unavailable."
    }
}
