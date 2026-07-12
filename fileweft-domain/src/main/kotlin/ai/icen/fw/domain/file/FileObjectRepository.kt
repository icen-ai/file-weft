package ai.icen.fw.domain.file

import ai.icen.fw.core.id.Identifier

interface FileObjectRepository {
    fun findById(tenantId: Identifier, fileObjectId: Identifier): FileObject?

    fun save(fileObject: FileObject)
}
