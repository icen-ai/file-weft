package ai.icen.fw.application.catalog

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.domain.document.DocumentConflictException

/**
 * The document's catalog binding changed after its external ACL decision was
 * made. Callers may retry so the new source folder is authorized from scratch.
 */
class DocumentCatalogBindingChangedException(
    val documentId: Identifier,
) : DocumentConflictException(
    "Document ${documentId.value} changed its catalog binding while the mutation was being prepared.",
)
