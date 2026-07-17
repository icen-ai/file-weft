package ai.icen.fw.application.catalog

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.domain.document.Document

/** Application command boundary for changing only the host-owned folder binding. */
interface DocumentCatalogBindingCommand {
    fun move(documentId: Identifier, folderId: String): Document
}
