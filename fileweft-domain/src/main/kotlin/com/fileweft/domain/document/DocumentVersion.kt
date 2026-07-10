package com.fileweft.domain.document

import com.fileweft.core.id.Identifier

class DocumentVersion(
    val id: Identifier,
    val tenantId: Identifier,
    val documentId: Identifier,
    val versionNumber: String,
    val fileObjectId: Identifier,
) {
    init {
        require(versionNumber.isNotBlank()) { "Version number must not be blank." }
    }
}
