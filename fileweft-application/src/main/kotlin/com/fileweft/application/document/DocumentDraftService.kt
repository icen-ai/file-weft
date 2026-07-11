package com.fileweft.application.document

import com.fileweft.application.audit.AuditTrail
import com.fileweft.application.security.ApplicationAuthorization
import com.fileweft.application.transaction.ApplicationTransaction
import com.fileweft.application.upload.StoredObjectIntegrity
import com.fileweft.core.id.Identifier
import com.fileweft.core.id.IdentifierGenerator
import com.fileweft.domain.document.Document
import com.fileweft.domain.document.DocumentNumberAlreadyExistsException
import com.fileweft.domain.document.DocumentRepository
import com.fileweft.domain.document.DocumentVersion
import com.fileweft.domain.file.FileAsset
import com.fileweft.domain.file.FileAssetRepository
import com.fileweft.domain.file.FileObject
import com.fileweft.domain.file.FileObjectRepository
import com.fileweft.spi.authorization.AuthorizationProvider
import com.fileweft.spi.identity.UserRealmProvider
import com.fileweft.spi.observability.FileWeftMetric
import com.fileweft.spi.observability.FileWeftMetrics
import com.fileweft.spi.storage.StorageAdapter
import com.fileweft.spi.storage.StorageObjectLocation
import com.fileweft.spi.storage.StorageUploadRequest
import com.fileweft.spi.storage.StoredObject
import com.fileweft.spi.tenant.TenantProvider
import java.io.InputStream

/**
 * Creates and edits draft documents while keeping object storage outside the
 * database transaction. If persistence cannot commit, the uploaded object is
 * removed as compensation.
 */
class DocumentDraftService(
    private val tenantProvider: TenantProvider,
    private val userRealmProvider: UserRealmProvider,
    authorizationProvider: AuthorizationProvider,
    private val storageAdapter: StorageAdapter,
    private val documentRepository: DocumentRepository,
    private val fileObjectRepository: FileObjectRepository,
    private val fileAssetRepository: FileAssetRepository,
    private val identifierGenerator: IdentifierGenerator,
    private val transaction: ApplicationTransaction,
    private val auditTrail: AuditTrail? = null,
    private val metrics: FileWeftMetrics? = null,
) {
    private val authorization = ApplicationAuthorization(userRealmProvider, authorizationProvider)

    fun create(command: CreateDocumentDraftCommand, content: InputStream): Document {
        val tenant = tenantProvider.currentTenant()
        val operator = userRealmProvider.currentUser()
        val documentId = identifierGenerator.nextId()
        authorization.requireDocumentAction(tenant.tenantId, documentId, CREATE_ACTION)
        transaction.execute {
            if (documentRepository.findByDocumentNumber(tenant.tenantId, command.documentNumber) != null) {
                throw DocumentNumberAlreadyExistsException(command.documentNumber)
            }
        }
        val fileObjectId = identifierGenerator.nextId()
        val assetId = identifierGenerator.nextId()
        val versionId = identifierGenerator.nextId()
        var stored: StoredObject? = null
        try {
            val attempted = upload(tenant.tenantId, command.fileName, command.contentLength, command.contentType, command.metadata, content)
            stored = attempted.stored
            StoredObjectIntegrity.requireMatches(attempted.request, attempted.stored)
            val uploaded = stored ?: error("Stored object is unavailable after upload.")
            val document = transaction.execute {
                val fileObject = uploaded.toFileObject(fileObjectId, tenant.tenantId, command.fileName)
                val asset = FileAsset(assetId, tenant.tenantId, fileObject.id, DOCUMENT_ASSET_TYPE, command.metadata)
                val version = DocumentVersion(versionId, tenant.tenantId, documentId, INITIAL_VERSION, fileObject.id)
                val created = Document(
                    id = documentId,
                    tenantId = tenant.tenantId,
                    assetId = asset.id,
                    documentNumber = command.documentNumber,
                    title = command.title,
                    versions = listOf(version),
                    currentVersionId = version.id,
                )
                fileObjectRepository.save(fileObject)
                fileAssetRepository.save(asset)
                documentRepository.save(created)
                auditTrail?.record(
                    tenantId = tenant.tenantId,
                    resourceType = DOCUMENT_RESOURCE_TYPE,
                    resourceId = created.id,
                    action = CREATE_ACTION,
                    operatorId = operator?.id,
                    operatorName = operator?.displayName,
                    details = mapOf("documentNumber" to created.documentNumber, "version" to INITIAL_VERSION),
                )
                created
            }
            recordMetric(FileWeftMetric.UPLOAD_COUNT, tenant.tenantId.value)
            return document
        } catch (failure: Throwable) {
            stored?.let { compensate(it.location, failure) }
            recordMetric(FileWeftMetric.UPLOAD_FAILURE, tenant.tenantId.value)
            throw failure
        }
    }

    fun addVersion(documentId: Identifier, command: AddDocumentVersionCommand, content: InputStream): Document {
        val tenant = tenantProvider.currentTenant()
        val operator = userRealmProvider.currentUser()
        val fileObjectId = identifierGenerator.nextId()
        val versionId = identifierGenerator.nextId()
        authorization.requireDocumentAction(tenant.tenantId, documentId, EDIT_ACTION)
        var stored: StoredObject? = null
        try {
            val attempted = upload(tenant.tenantId, command.fileName, command.contentLength, command.contentType, command.metadata, content)
            stored = attempted.stored
            StoredObjectIntegrity.requireMatches(attempted.request, attempted.stored)
            val uploaded = stored ?: error("Stored object is unavailable after upload.")
            val document = transaction.execute {
                val existing = documentRepository.findForMutation(tenant.tenantId, documentId)
                    ?: throw DocumentNotFoundException(documentId)
                val fileObject = uploaded.toFileObject(fileObjectId, tenant.tenantId, command.fileName)
                existing.addVersion(
                    DocumentVersion(versionId, tenant.tenantId, existing.id, command.versionNumber, fileObject.id),
                )
                fileObjectRepository.save(fileObject)
                documentRepository.save(existing)
                auditTrail?.record(
                    tenantId = tenant.tenantId,
                    resourceType = DOCUMENT_RESOURCE_TYPE,
                    resourceId = existing.id,
                    action = ADD_VERSION_ACTION,
                    operatorId = operator?.id,
                    operatorName = operator?.displayName,
                    details = mapOf("version" to command.versionNumber, "fileObjectId" to fileObject.id.value),
                )
                existing
            }
            recordMetric(FileWeftMetric.UPLOAD_COUNT, tenant.tenantId.value)
            return document
        } catch (failure: Throwable) {
            stored?.let { compensate(it.location, failure) }
            recordMetric(FileWeftMetric.UPLOAD_FAILURE, tenant.tenantId.value)
            throw failure
        }
    }

    fun rename(documentId: Identifier, title: String): Document {
        val tenant = tenantProvider.currentTenant()
        val operator = userRealmProvider.currentUser()
        authorization.requireDocumentAction(tenant.tenantId, documentId, EDIT_ACTION)
        return transaction.execute {
            val document = documentRepository.findForMutation(tenant.tenantId, documentId)
                ?: throw DocumentNotFoundException(documentId)
            document.rename(title)
            documentRepository.save(document)
            auditTrail?.record(
                tenantId = tenant.tenantId,
                resourceType = DOCUMENT_RESOURCE_TYPE,
                resourceId = document.id,
                action = RENAME_ACTION,
                operatorId = operator?.id,
                operatorName = operator?.displayName,
                details = mapOf("title" to document.title),
            )
            document
        }
    }

    private fun upload(
        tenantId: Identifier,
        fileName: String,
        contentLength: Long,
        contentType: String?,
        metadata: Map<String, String>,
        content: InputStream,
    ): StorageUploadAttempt {
        val request = StorageUploadRequest(tenantId, fileName, contentLength, contentType, metadata = metadata)
        return StorageUploadAttempt(request, storageAdapter.upload(request, content))
    }

    private fun StoredObject.toFileObject(fileObjectId: Identifier, tenantId: Identifier, fileName: String): FileObject = FileObject(
        id = fileObjectId,
        tenantId = tenantId,
        fileName = fileName,
        contentLength = contentLength,
        storageType = location.storageType,
        storagePath = location.path,
        contentType = contentType,
        contentHash = contentHash,
    )

    private fun compensate(location: StorageObjectLocation, failure: Throwable) {
        try {
            storageAdapter.delete(location)
        } catch (cleanupFailure: Throwable) {
            failure.addSuppressed(cleanupFailure)
        }
    }

    private fun recordMetric(metric: FileWeftMetric, tenantId: String) {
        try {
            metrics?.increment(metric, mapOf("tenantId" to tenantId))
        } catch (_: Exception) {
            // Observability must not affect draft persistence or compensation.
        }
    }

    private data class StorageUploadAttempt(
        val request: StorageUploadRequest,
        val stored: StoredObject,
    )

    companion object {
        const val DOCUMENT_RESOURCE_TYPE = "DOCUMENT"
        const val DOCUMENT_ASSET_TYPE = "DOCUMENT"
        const val INITIAL_VERSION = "1.0"
        const val CREATE_ACTION = "document:create"
        const val EDIT_ACTION = "document:edit"
        const val ADD_VERSION_ACTION = "document:version:add"
        const val RENAME_ACTION = "document:rename"
    }
}
