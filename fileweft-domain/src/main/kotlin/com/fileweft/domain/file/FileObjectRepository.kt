package com.fileweft.domain.file

import com.fileweft.core.id.Identifier

interface FileObjectRepository {
    fun findById(tenantId: Identifier, fileObjectId: Identifier): FileObject?

    fun save(fileObject: FileObject)
}
