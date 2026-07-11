package com.fileweft.application.document

import com.fileweft.application.transaction.ApplicationTransaction
import com.fileweft.domain.document.DocumentRepository
import com.fileweft.domain.file.FileAssetRepository

/** The exact persistence components owned by one [DocumentDraftService]. */
internal class DocumentMutationComponents(
    val documents: DocumentRepository,
    val assets: FileAssetRepository,
    val transaction: ApplicationTransaction,
)
