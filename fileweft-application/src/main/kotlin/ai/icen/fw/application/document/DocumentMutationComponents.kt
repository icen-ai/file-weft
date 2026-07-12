package ai.icen.fw.application.document

import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.domain.document.DocumentRepository
import ai.icen.fw.domain.file.FileAssetRepository

/** The exact persistence components owned by one [DocumentDraftService]. */
internal class DocumentMutationComponents(
    val documents: DocumentRepository,
    val assets: FileAssetRepository,
    val transaction: ApplicationTransaction,
)
