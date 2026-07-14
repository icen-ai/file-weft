package ai.icen.fw.application.document

import ai.icen.fw.application.audit.AuditTrail
import ai.icen.fw.application.security.ApplicationAuthorization
import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.application.transaction.ApplicationTransactionBoundary
import ai.icen.fw.application.transaction.ApplicationTransactionOutcomeUnknownException
import ai.icen.fw.application.upload.StoredObjectIntegrity
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.core.id.IdentifierGenerator
import ai.icen.fw.domain.document.Document
import ai.icen.fw.domain.document.DocumentNumberAlreadyExistsException
import ai.icen.fw.domain.document.DocumentRepository
import ai.icen.fw.domain.document.DocumentVersion
import ai.icen.fw.domain.file.FileAsset
import ai.icen.fw.domain.file.FileAssetMutationRepository
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
import java.util.Locale

/**
 * Creates and edits draft documents while keeping object storage outside the
 * database transaction. A known rollback is compensated only after durable
 * references are proven absent; an unknown commit outcome retains the object
 * until persistence can be reconciled safely.
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

    fun create(command: CreateDocumentDraftCommand, content: InputStream): Document =
        createInternal(command, content, null)

    /**
     * Metadata-aware entry used by the additive schema write boundary. The
     * provider receives the tenant snapshot only after document authorization
     * succeeds, so schema existence and validation rules cannot be probed by
     * an unauthenticated or unauthorized caller.
     */
    @JvmSynthetic
    internal fun createWithMetadata(
        command: CreateDocumentDraftCommand,
        content: InputStream,
        metadataProvider: (Identifier) -> Map<String, String>,
    ): Document = createInternal(command, content, metadataProvider)

    private fun createInternal(
        command: CreateDocumentDraftCommand,
        content: InputStream,
        metadataProvider: ((Identifier) -> Map<String, String>)?,
    ): Document {
        ApplicationTransactionBoundary.requireInactive(transaction)
        val tenant = tenantProvider.currentTenant()
        val documentId = identifierGenerator.nextId()
        val operator = authorization.requireDocumentAction(tenant.tenantId, documentId, CREATE_ACTION)
        val metadata = immutableMetadata(metadataProvider?.invoke(tenant.tenantId) ?: command.metadata)
        transaction.execute {
            if (documentRepository.findByDocumentNumber(tenant.tenantId, command.documentNumber) != null) {
                throw DocumentNumberAlreadyExistsException(command.documentNumber)
            }
        }
        val fileObjectId = identifierGenerator.nextId()
        val assetId = identifierGenerator.nextId()
        val versionId = identifierGenerator.nextId()
        var stored: StoredObject? = null
        var persistenceAttempt: CreatePersistenceAttempt? = null
        try {
            val attempted = upload(
                tenant.tenantId,
                command.fileName,
                command.contentLength,
                command.contentType,
                storageMetadata(metadata, metadataProvider),
                content,
            )
            stored = attempted.stored
            StoredObjectIntegrity.requireMatches(attempted.request, attempted.stored)
            val uploaded = stored ?: error("Stored object is unavailable after upload.")
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
            persistenceAttempt = CreatePersistenceAttempt(
                fileObject = fileObject,
                fileAsset = asset,
                documentId = created.id,
                tenantId = created.tenantId,
                assetId = created.assetId,
                documentNumber = created.documentNumber,
                initialVersion = version,
            )
            val document = transaction.execute {
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
            try {
                stored?.let { uploaded ->
                    persistenceAttempt?.let { attempted ->
                        reconcileFailedCreate(attempted, failure)?.let { recovered ->
                            recordMetric(FileWeftMetric.UPLOAD_COUNT, tenant.tenantId.value)
                            return recovered
                        }
                    }
                    if (failure is ApplicationTransactionOutcomeUnknownException) throw failure
                    compensate(uploaded.location, failure)
                }
            } catch (reconciledFailure: Throwable) {
                recordMetric(FileWeftMetric.UPLOAD_FAILURE, tenant.tenantId.value)
                throw reconciledFailure
            }
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
    ): Document = addVersionInternal(documentId, command, content, mutationGuard, null)

    /** Schema-aware variant whose provider runs only after base and catalog authorization. */
    @JvmSynthetic
    internal fun addVersionWithMetadata(
        documentId: Identifier,
        command: AddDocumentVersionCommand,
        content: InputStream,
        mutationGuard: DocumentMutationGuard?,
        metadataProvider: (Identifier) -> Map<String, String>,
    ): Document = addVersionInternal(documentId, command, content, mutationGuard, metadataProvider)

    private fun addVersionInternal(
        documentId: Identifier,
        command: AddDocumentVersionCommand,
        content: InputStream,
        mutationGuard: DocumentMutationGuard?,
        metadataProvider: ((Identifier) -> Map<String, String>)?,
    ): Document {
        ApplicationTransactionBoundary.requireInactive(transaction)
        val tenant = tenantProvider.currentTenant()
        val fileObjectId = identifierGenerator.nextId()
        val versionId = identifierGenerator.nextId()
        val operator = authorization.requireDocumentAction(tenant.tenantId, documentId, EDIT_ACTION)
        val mutationPermit = mutationGuard?.prepare(tenant.tenantId, documentId)
        val metadata = immutableMetadata(metadataProvider?.invoke(tenant.tenantId) ?: command.metadata)
        var stored: StoredObject? = null
        var persistenceAttempt: AddVersionPersistenceAttempt? = null
        try {
            val attempted = upload(
                tenant.tenantId,
                command.fileName,
                command.contentLength,
                command.contentType,
                storageMetadata(metadata, metadataProvider),
                content,
            )
            stored = attempted.stored
            StoredObjectIntegrity.requireMatches(attempted.request, attempted.stored)
            val uploaded = stored ?: error("Stored object is unavailable after upload.")
            val fileObject = uploaded.toFileObject(fileObjectId, tenant.tenantId, command.fileName)
            val version = DocumentVersion(versionId, tenant.tenantId, documentId, command.versionNumber, fileObject.id)
            persistenceAttempt = AddVersionPersistenceAttempt(fileObject, version)
            if (mutationGuard != null) {
                mutationGuard.revalidate(tenant.tenantId, documentId, checkNotNull(mutationPermit))
            }
            val document = transaction.execute {
                val existing = documentRepository.findForMutation(tenant.tenantId, documentId)
                    ?: throw DocumentNotFoundException(documentId)
                if (mutationGuard != null) {
                    mutationGuard.verifyLocked(tenant.tenantId, existing, checkNotNull(mutationPermit))
                }
                val assetMutation = if (metadataProvider == null) {
                    null
                } else {
                    documentMetadataMutation(tenant.tenantId, existing, metadata)
                }
                persistenceAttempt = AddVersionPersistenceAttempt(
                    fileObject = fileObject,
                    version = version,
                    originalAsset = assetMutation?.original,
                    expectedAsset = assetMutation?.updated,
                )
                existing.addVersion(version)
                fileObjectRepository.save(fileObject)
                assetMutation?.let { mutation -> fileAssetRepository.save(mutation.updated) }
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
            try {
                stored?.let { uploaded ->
                    persistenceAttempt?.let { attempted ->
                        reconcileFailedAddVersion(attempted, failure)?.let { recovered ->
                            recordMetric(FileWeftMetric.UPLOAD_COUNT, tenant.tenantId.value)
                            return recovered
                        }
                    }
                    if (failure is ApplicationTransactionOutcomeUnknownException) throw failure
                    compensate(uploaded.location, failure)
                }
            } catch (reconciledFailure: Throwable) {
                recordMetric(FileWeftMetric.UPLOAD_FAILURE, tenant.tenantId.value)
                throw reconciledFailure
            }
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

    private fun reconcileFailedCreate(
        attempted: CreatePersistenceAttempt,
        failure: Throwable,
    ): Document? {
        val persisted = try {
            transaction.execute {
                CreatePersistenceSnapshot(
                    documentById = documentRepository.findById(attempted.tenantId, attempted.documentId),
                    documentByNumber = documentRepository.findByDocumentNumber(
                        attempted.tenantId,
                        attempted.documentNumber,
                    ),
                    fileObject = fileObjectRepository.findById(attempted.fileObject.tenantId, attempted.fileObject.id),
                    fileAsset = fileAssetRepository.findById(attempted.fileAsset.tenantId, attempted.fileAsset.id),
                )
            }
        } catch (reconciliationFailure: Throwable) {
            throw outcomeUnknown(failure, reconciliationFailure)
        }

        val exactDocument = persisted.exactDocument(attempted)
        val exactBinding =
            exactDocument != null &&
            sameFileObject(persisted.fileObject, attempted.fileObject) &&
            sameFileAsset(persisted.fileAsset, attempted.fileAsset)
        if (failure is ApplicationTransactionOutcomeUnknownException) {
            if (exactBinding) return checkNotNull(exactDocument)
            if (!persisted.references(attempted)) throw failure
            throw outcomeUnknown(failure, IllegalStateException(RECONCILIATION_MISMATCH_MESSAGE))
        }
        if (persisted.references(attempted)) {
            throw outcomeUnknown(failure, IllegalStateException(RECONCILIATION_MISMATCH_MESSAGE))
        }
        return null
    }

    private fun reconcileFailedAddVersion(
        attempted: AddVersionPersistenceAttempt,
        failure: Throwable,
    ): Document? {
        val persisted = try {
            transaction.execute {
                AddVersionPersistenceSnapshot(
                    document = documentRepository.findById(attempted.version.tenantId, attempted.version.documentId),
                    fileObject = fileObjectRepository.findById(
                        attempted.fileObject.tenantId,
                        attempted.fileObject.id,
                    ),
                    fileAsset = attempted.expectedAsset?.let { expected ->
                        fileAssetRepository.findById(expected.tenantId, expected.id)
                    },
                )
            }
        } catch (reconciliationFailure: Throwable) {
            throw outcomeUnknown(failure, reconciliationFailure)
        }

        val exactDocument = persisted.document?.takeIf { document ->
            document.id == attempted.version.documentId &&
                document.tenantId == attempted.version.tenantId &&
                document.versions.any { version -> sameDocumentVersion(version, attempted.version) }
        }
        val exactBinding =
            exactDocument != null &&
                sameFileObject(persisted.fileObject, attempted.fileObject) &&
                persisted.matchesExpectedAsset(attempted)
        val assetUnchanged = persisted.matchesOriginalAsset(attempted)
        if (failure is ApplicationTransactionOutcomeUnknownException) {
            if (exactBinding) return checkNotNull(exactDocument)
            if (
                persisted.fileObject == null &&
                !persisted.document.references(attempted.version) &&
                assetUnchanged
            ) {
                throw failure
            }
            throw outcomeUnknown(failure, IllegalStateException(RECONCILIATION_MISMATCH_MESSAGE))
        }
        if (
            persisted.fileObject != null ||
            persisted.document.references(attempted.version) ||
            !assetUnchanged
        ) {
            throw outcomeUnknown(failure, IllegalStateException(RECONCILIATION_MISMATCH_MESSAGE))
        }
        return null
    }

    private fun outcomeUnknown(
        failure: Throwable,
        reconciliationFailure: Throwable,
    ): ApplicationTransactionOutcomeUnknownException {
        val unknown = failure as? ApplicationTransactionOutcomeUnknownException
            ?: ApplicationTransactionOutcomeUnknownException(failure)
        if (reconciliationFailure !== unknown && reconciliationFailure !== unknown.cause) {
            unknown.addSuppressed(reconciliationFailure)
        }
        return unknown
    }

    private fun CreatePersistenceSnapshot.exactDocument(expected: CreatePersistenceAttempt): Document? {
        val byIdMatches = documentById.matchesCreatedDocument(expected)
        val byNumberMatches = documentByNumber.matchesCreatedDocument(expected)
        return when {
            byIdMatches && (documentByNumber == null || byNumberMatches) -> checkNotNull(documentById)
            documentById == null && byNumberMatches -> checkNotNull(documentByNumber)
            else -> null
        }
    }

    private fun Document?.matchesCreatedDocument(expected: CreatePersistenceAttempt): Boolean =
        this != null &&
            id == expected.documentId &&
            tenantId == expected.tenantId &&
            assetId == expected.assetId &&
            documentNumber == expected.documentNumber &&
            versions.any { version -> sameDocumentVersion(version, expected.initialVersion) }

    private fun CreatePersistenceSnapshot.references(attempted: CreatePersistenceAttempt): Boolean =
        fileObject != null ||
            fileAsset != null ||
            documentById != null ||
            documentByNumber.references(attempted)

    private fun Document?.references(attempted: CreatePersistenceAttempt): Boolean =
        this != null &&
            (
                id == attempted.documentId ||
                    assetId == attempted.assetId ||
                    versions.any { version ->
                        version.id == attempted.initialVersion.id ||
                            version.fileObjectId == attempted.fileObject.id
                    }
                )

    private fun Document?.references(version: DocumentVersion): Boolean =
        this != null && versions.any { persisted ->
            persisted.id == version.id || persisted.fileObjectId == version.fileObjectId
        }

    private fun sameFileObject(actual: FileObject?, expected: FileObject): Boolean =
        actual != null &&
            actual.id == expected.id &&
            actual.tenantId == expected.tenantId &&
            actual.fileName == expected.fileName &&
            actual.contentLength == expected.contentLength &&
            actual.storageType == expected.storageType &&
            actual.storagePath == expected.storagePath &&
            actual.contentType == expected.contentType &&
            actual.contentHash == expected.contentHash

    private fun sameFileAsset(actual: FileAsset?, expected: FileAsset): Boolean =
        actual != null &&
            actual.id == expected.id &&
            actual.tenantId == expected.tenantId &&
            actual.fileObjectId == expected.fileObjectId &&
            actual.assetType == expected.assetType &&
            actual.metadata == expected.metadata

    private fun sameDocumentVersion(actual: DocumentVersion, expected: DocumentVersion): Boolean =
        actual.id == expected.id &&
            actual.tenantId == expected.tenantId &&
            actual.documentId == expected.documentId &&
            actual.versionNumber == expected.versionNumber &&
            actual.fileObjectId == expected.fileObjectId

    private fun AddVersionPersistenceSnapshot.matchesExpectedAsset(attempted: AddVersionPersistenceAttempt): Boolean =
        attempted.expectedAsset == null || sameFileAsset(fileAsset, attempted.expectedAsset)

    private fun AddVersionPersistenceSnapshot.matchesOriginalAsset(attempted: AddVersionPersistenceAttempt): Boolean =
        attempted.originalAsset == null || sameFileAsset(fileAsset, attempted.originalAsset)

    private fun documentMetadataMutation(
        tenantId: Identifier,
        document: Document,
        metadata: Map<String, String>,
    ): FileAssetMetadataMutation {
        require(metadata.keys.none(::isProtectedHostMetadataKey)) {
            "Schema metadata must not use a host-reserved namespace."
        }
        val original = (fileAssetRepository as? FileAssetMutationRepository)
            ?.findForMutation(tenantId, document.assetId)
            ?: fileAssetRepository.findById(tenantId, document.assetId)
            ?: throw IllegalStateException("Document metadata asset is unavailable.")
        check(original.tenantId == tenantId && original.id == document.assetId) {
            "Document metadata asset is incompatible."
        }
        val updatedMetadata = LinkedHashMap<String, String>()
        original.metadata.entries
            .filter { entry -> isProtectedHostMetadataKey(entry.key) }
            .forEach { entry -> updatedMetadata[entry.key] = entry.value }
        updatedMetadata.putAll(metadata)
        val updated = FileAsset(
            id = original.id,
            tenantId = original.tenantId,
            fileObjectId = original.fileObjectId,
            assetType = original.assetType,
            metadata = immutableMetadata(updatedMetadata),
        )
        return FileAssetMetadataMutation(original, updated)
    }

    private fun isProtectedHostMetadataKey(key: String): Boolean {
        val normalized = key.lowercase(Locale.ROOT)
        return normalized.startsWith(CATALOG_METADATA_NAMESPACE) ||
            normalized.startsWith(FILEWEFT_METADATA_NAMESPACE)
    }

    /**
     * Schema metadata is persisted in FileWeft's asset record. It is not
     * duplicated into vendor object-user-metadata headers, whose limits and
     * read-back guarantees are adapter-specific. Legacy callers retain the
     * historical behavior of passing their metadata to storage.
     */
    private fun storageMetadata(
        metadata: Map<String, String>,
        metadataProvider: ((Identifier) -> Map<String, String>)?,
    ): Map<String, String> = if (metadataProvider == null) metadata else emptyMap()

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

    private data class CreatePersistenceAttempt(
        val fileObject: FileObject,
        val fileAsset: FileAsset,
        val documentId: Identifier,
        val tenantId: Identifier,
        val assetId: Identifier,
        val documentNumber: String,
        val initialVersion: DocumentVersion,
    )

    private data class CreatePersistenceSnapshot(
        val documentById: Document?,
        val documentByNumber: Document?,
        val fileObject: FileObject?,
        val fileAsset: FileAsset?,
    )

    private data class AddVersionPersistenceAttempt(
        val fileObject: FileObject,
        val version: DocumentVersion,
        val originalAsset: FileAsset? = null,
        val expectedAsset: FileAsset? = null,
    )

    private data class AddVersionPersistenceSnapshot(
        val document: Document?,
        val fileObject: FileObject?,
        val fileAsset: FileAsset?,
    )

    private data class FileAssetMetadataMutation(
        val original: FileAsset,
        val updated: FileAsset,
    )

    companion object {
        const val DOCUMENT_RESOURCE_TYPE = "DOCUMENT"
        const val DOCUMENT_ASSET_TYPE = "DOCUMENT"
        const val INITIAL_VERSION = "1.0"
        const val CREATE_ACTION = "document:create"
        const val EDIT_ACTION = "document:edit"
        const val ADD_VERSION_ACTION = "document:version:add"
        const val RENAME_ACTION = "document:rename"
        private const val CATALOG_METADATA_NAMESPACE = "catalog."
        private const val FILEWEFT_METADATA_NAMESPACE = "fileweft."
        private const val RECONCILIATION_MISMATCH_MESSAGE: String =
            "Persisted document upload state is partial or inconsistent and requires reconciliation."
    }
}
