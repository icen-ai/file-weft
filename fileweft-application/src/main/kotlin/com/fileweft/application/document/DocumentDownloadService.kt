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
    private var downloadVisibility: DocumentDownloadVisibility? = null

    constructor(
        tenantProvider: TenantProvider,
        userRealmProvider: UserRealmProvider,
        authorizationProvider: AuthorizationProvider,
        documentRepository: DocumentRepository,
        fileObjectRepository: FileObjectRepository,
        storageAdapter: StorageAdapter,
        transaction: ApplicationTransaction,
        auditTrail: AuditTrail?,
        downloadVisibility: DocumentDownloadVisibility,
    ) : this(
        tenantProvider,
        userRealmProvider,
        authorizationProvider,
        documentRepository,
        fileObjectRepository,
        storageAdapter,
        transaction,
        auditTrail,
    ) {
        this.downloadVisibility = downloadVisibility
    }

    @JvmOverloads
    fun download(documentId: Identifier, versionId: Identifier? = null): DocumentDownload {
        val tenant = tenantProvider.currentTenant()
        authorization.requireDocumentAction(tenant.tenantId, documentId, DOWNLOAD_ACTION)
        val visibility = downloadVisibility
        val visibilityPermit = visibility?.prepare(tenant.tenantId, documentId)
        // A user realm can be remote. Freeze its audit identity before opening
        // the short repository transaction so the database transaction never
        // waits on identity infrastructure.
        val operator = userRealmProvider.currentUser()
        val target = transaction.execute {
            val document = documentRepository.findById(tenant.tenantId, documentId) ?: throw DocumentNotFoundException(documentId)
            if (visibility != null) {
                visibility.verify(tenant.tenantId, document, checkNotNull(visibilityPermit))
            }
            val version = resolveVersion(document, versionId)
            val fileObject = fileObjectRepository.findById(tenant.tenantId, version.fileObjectId)
                ?: throw DocumentDownloadNotFoundException("File ${version.fileObjectId.value} for document ${document.id.value} was not found.")
            recordDownloadIntent(tenant.tenantId, document, version, fileObject, operator)
            DownloadTarget(document.id, version, fileObject)
        }
        val location = StorageObjectLocation(target.fileObject.storageType, target.fileObject.storagePath)
        val storageDownload = try {
            storageAdapter.download(location)
        } catch (failure: Exception) {
            throw DocumentContentUnavailableException(cause = failure)
        }
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
        operator: com.fileweft.spi.identity.UserIdentity?,
    ) {
        val audit = auditTrail ?: return
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
    /** Length persisted with the tenant-scoped file object during upload. */
    val expectedContentLength: Long = fileObject.contentLength

    /**
     * Storage-reported length after it has been checked against [expectedContentLength].
     *
     * A null value means the storage adapter provides a stream without response
     * metadata. HTTP or other transports must not treat the persisted fallback
     * as a storage-verified response length.
     */
    val verifiedContentLength: Long? = storageDownload.contentLength

    /**
     * Compatibility view retained for existing callers. New transports should
     * use [verifiedContentLength] when deciding whether to advertise a length.
     */
    val contentLength: Long = verifiedContentLength ?: expectedContentLength
    val content: InputStream = storageDownload.content

    init {
        if (verifiedContentLength != null && verifiedContentLength != expectedContentLength) {
            val failure = DocumentContentUnavailableException()
            try {
                content.close()
            } catch (closeFailure: Exception) {
                failure.addSuppressed(closeFailure)
            }
            throw failure
        }
    }

    override fun close() {
        content.close()
    }
}

class DocumentDownloadNotFoundException(message: String) : NoSuchElementException(message)

/**
 * The requested document and version exist, but their physical content cannot
 * currently be opened or does not match its persisted metadata.
 *
 * The default message is intentionally free of storage locations and vendor
 * details. Transport adapters must classify this type rather than exposing its
 * [cause] or using exception-message inspection.
 */
class DocumentContentUnavailableException @JvmOverloads constructor(
    message: String = DEFAULT_MESSAGE,
    cause: Throwable? = null,
) : RuntimeException(message, cause) {
    constructor(cause: Throwable) : this(DEFAULT_MESSAGE, cause)

    companion object {
        const val DEFAULT_MESSAGE: String = "Document content is unavailable."
    }
}
