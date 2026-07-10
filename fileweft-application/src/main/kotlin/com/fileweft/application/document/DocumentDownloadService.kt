package com.fileweft.application.document

import com.fileweft.application.audit.AuditTrail
import com.fileweft.application.security.ApplicationAuthorization
import com.fileweft.application.transaction.ApplicationTransaction
import com.fileweft.core.id.Identifier
import com.fileweft.domain.document.Document
import com.fileweft.domain.document.DocumentRepository
import com.fileweft.domain.document.DocumentVersion
import com.fileweft.domain.file.FileObject
import com.fileweft.domain.file.FileObjectRepository
import com.fileweft.spi.authorization.AuthorizationProvider
import com.fileweft.spi.identity.UserRealmProvider
import com.fileweft.spi.storage.StorageAdapter
import com.fileweft.spi.storage.StorageDownload
import com.fileweft.spi.storage.StorageObjectLocation
import com.fileweft.spi.tenant.TenantProvider
import java.io.Closeable
import java.io.InputStream

/** Authorizes a document version before opening its tenant-scoped storage object. */
class DocumentDownloadService(
    private val tenantProvider: TenantProvider,
    private val userRealmProvider: UserRealmProvider,
    authorizationProvider: AuthorizationProvider,
    private val documentRepository: DocumentRepository,
    private val fileObjectRepository: FileObjectRepository,
    private val storageAdapter: StorageAdapter,
    private val transaction: ApplicationTransaction,
    private val auditTrail: AuditTrail? = null,
) {
    private val authorization = ApplicationAuthorization(userRealmProvider, authorizationProvider)

    @JvmOverloads
    fun download(documentId: Identifier, versionId: Identifier? = null): DocumentDownload {
        val tenant = tenantProvider.currentTenant()
        authorization.requireDocumentAction(tenant.tenantId, documentId, DOWNLOAD_ACTION)
        val target = transaction.execute {
            val document = documentRepository.findById(tenant.tenantId, documentId) ?: throw DocumentNotFoundException(documentId)
            val version = resolveVersion(document, versionId)
            val fileObject = fileObjectRepository.findById(tenant.tenantId, version.fileObjectId)
                ?: throw DocumentDownloadNotFoundException("File ${version.fileObjectId.value} for document ${document.id.value} was not found.")
            recordDownloadIntent(tenant.tenantId, document, version, fileObject)
            DownloadTarget(document.id, version, fileObject)
        }
        val storageDownload = storageAdapter.download(StorageObjectLocation(target.fileObject.storageType, target.fileObject.storagePath))
        return DocumentDownload(target.documentId, target.version.id, target.version.versionNumber, target.fileObject, storageDownload)
    }

    private fun resolveVersion(document: Document, requestedVersionId: Identifier?): DocumentVersion {
        val versionId = requestedVersionId ?: document.currentVersionId
            ?: throw DocumentDownloadNotFoundException("Document ${document.id.value} has no downloadable version.")
        return document.versions.firstOrNull { it.id == versionId }
            ?: throw DocumentDownloadNotFoundException("Version ${versionId.value} does not belong to document ${document.id.value}.")
    }

    private fun recordDownloadIntent(
        tenantId: Identifier,
        document: Document,
        version: DocumentVersion,
        fileObject: FileObject,
    ) {
        val audit = auditTrail ?: return
        val operator = userRealmProvider.currentUser()
        audit.record(
            tenantId = tenantId,
            resourceType = DOCUMENT_RESOURCE_TYPE,
            resourceId = document.id,
            action = DOWNLOAD_ACTION,
            operatorId = operator?.id,
            operatorName = operator?.displayName,
            details = mapOf("versionId" to version.id.value, "fileObjectId" to fileObject.id.value),
        )
    }

    private companion object {
        const val DOCUMENT_RESOURCE_TYPE = "DOCUMENT"
        const val DOWNLOAD_ACTION = "document:download"
    }

    private class DownloadTarget(
        val documentId: Identifier,
        val version: DocumentVersion,
        val fileObject: FileObject,
    )
}

/** A caller-owned streaming response; it must be closed after copying its content. */
class DocumentDownload(
    val documentId: Identifier,
    val versionId: Identifier,
    val versionNumber: String,
    fileObject: FileObject,
    storageDownload: StorageDownload,
) : Closeable {
    val fileName: String = fileObject.fileName
    val contentType: String? = storageDownload.contentType ?: fileObject.contentType
    val contentLength: Long = storageDownload.contentLength ?: fileObject.contentLength
    val content: InputStream = storageDownload.content

    override fun close() {
        content.close()
    }
}

class DocumentDownloadNotFoundException(message: String) : NoSuchElementException(message)
