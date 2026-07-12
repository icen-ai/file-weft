package ai.icen.fw.application.document

import ai.icen.fw.application.audit.AuditTrail
import ai.icen.fw.application.security.ApplicationAuthorization
import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.application.upload.StoredObjectIntegrity
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.core.id.IdentifierGenerator
import ai.icen.fw.domain.document.Document
import ai.icen.fw.domain.document.DocumentNumberAlreadyExistsException
import ai.icen.fw.domain.document.DocumentRepository
import ai.icen.fw.domain.document.DocumentVersion
import ai.icen.fw.domain.file.FileAsset
import ai.icen.fw.domain.file.FileAssetRepository
import ai.icen.fw.domain.file.FileObject
import ai.icen.fw.domain.file.FileObjectRepository
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.catalog.DocumentCatalogBinding
import ai.icen.fw.spi.identity.UserRealmProvider
import ai.icen.fw.spi.observability.FileWeftMetric
import ai.icen.fw.spi.observability.FileWeftMetrics
import ai.icen.fw.spi.storage.StorageAdapter
import ai.icen.fw.spi.storage.StorageObjectLocation
import ai.icen.fw.spi.storage.StorageUploadRequest
import ai.icen.fw.spi.storage.StoredObject
import ai.icen.fw.spi.tenant.TenantProvider
import java.io.InputStream
import java.util.Collections
import java.util.LinkedHashMap

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
    internal val mutationComponents = DocumentMutationComponents(
        documentRepository,
        fileAssetRepository,
        transaction,
    )

    fun create(command: CreateDocumentDraftCommand, content: InputStream): Document {
        val tenant = tenantProvider.currentTenant()
        val operator = userRealmProvider.currentUser()
        val metadata = immutableMetadata(command.metadata)
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
            val attempted = upload(tenant.tenantId, command.fileName, command.contentLength, command.contentType, metadata, content)
            stored = attempted.stored
            StoredObjectIntegrity.requireMatches(attempted.request, attempted.stored)
            val uploaded = stored ?: error("Stored object is unavailable after upload.")
            val document = transaction.execute {
                val fileObject = uploaded.toFileObject(fileObjectId, tenant.tenantId, command.fileName)
                val asset = FileAsset(assetId, tenant.tenantId, fileObject.id, DOCUMENT_ASSET_TYPE, metadata)
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
                    details = createAuditDetails(created, metadata),
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

    fun addVersion(documentId: Identifier, command: AddDocumentVersionCommand, content: InputStream): Document =
        addVersion(documentId, command, content, null)

    internal fun addVersion(
        documentId: Identifier,
        command: AddDocumentVersionCommand,
        content: InputStream,
        mutationGuard: DocumentMutationGuard?,
    ): Document {
        val tenant = tenantProvider.currentTenant()
        val operator = userRealmProvider.currentUser()
        val metadata = immutableMetadata(command.metadata)
        val fileObjectId = identifierGenerator.nextId()
        val versionId = identifierGenerator.nextId()
        authorization.requireDocumentAction(tenant.tenantId, documentId, EDIT_ACTION)
        val mutationPermit = mutationGuard?.prepare(tenant.tenantId, documentId)
        var stored: StoredObject? = null
        try {
            val attempted = upload(tenant.tenantId, command.fileName, command.contentLength, command.contentType, metadata, content)
            stored = attempted.stored
            StoredObjectIntegrity.requireMatches(attempted.request, attempted.stored)
            val uploaded = stored ?: error("Stored object is unavailable after upload.")
            if (mutationGuard != null) {
                mutationGuard.revalidate(tenant.tenantId, documentId, checkNotNull(mutationPermit))
            }
            val document = transaction.execute {
                val existing = documentRepository.findForMutation(tenant.tenantId, documentId)
                    ?: throw DocumentNotFoundException(documentId)
                if (mutationGuard != null) {
                    mutationGuard.verifyLocked(tenant.tenantId, existing, checkNotNull(mutationPermit))
                }
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

    fun rename(documentId: Identifier, title: String): Document = rename(documentId, title, null)

    internal fun rename(
        documentId: Identifier,
        title: String,
        mutationGuard: DocumentMutationGuard?,
    ): Document {
        val tenant = tenantProvider.currentTenant()
        val operator = userRealmProvider.currentUser()
        authorization.requireDocumentAction(tenant.tenantId, documentId, EDIT_ACTION)
        val mutationPermit = mutationGuard?.prepare(tenant.tenantId, documentId)
        return transaction.execute {
            val document = documentRepository.findForMutation(tenant.tenantId, documentId)
                ?: throw DocumentNotFoundException(documentId)
            if (mutationGuard != null) {
                mutationGuard.verifyLocked(tenant.tenantId, document, checkNotNull(mutationPermit))
            }
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

    /**
     * The catalog-safe creation path contributes this reserved key only after
     * host ACL validation. Direct draft callers retain their existing behavior
     * when no catalog binding is present.
     */
    private fun createAuditDetails(document: Document, metadata: Map<String, String>): Map<String, String> =
        linkedMapOf<String, String>().apply {
            put("documentNumber", document.documentNumber)
            put("version", INITIAL_VERSION)
            metadata[DocumentCatalogBinding.METADATA_KEY]
                ?.takeIf { it.isNotBlank() }
                ?.let { folderId -> put("folderId", folderId) }
        }

    /**
     * Storage adapters are external code. Snapshot metadata before handing it
     * to an adapter so it cannot mutate the asset or audit state that follows
     * a successful upload.
     */
    private fun immutableMetadata(metadata: Map<String, String>): Map<String, String> =
        Collections.unmodifiableMap(LinkedHashMap(metadata))

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
