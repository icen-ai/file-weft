package ai.icen.fw.application.doctor

import ai.icen.fw.core.context.TenantContext
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.domain.document.Document
import ai.icen.fw.domain.document.DocumentMutationRepository
import ai.icen.fw.domain.document.DocumentVersion
import ai.icen.fw.domain.file.FileObject
import ai.icen.fw.domain.file.FileObjectRepository
import ai.icen.fw.spi.authorization.AuthorizationDecision
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.authorization.AuthorizationRequest
import ai.icen.fw.spi.connector.ConnectorHealth
import ai.icen.fw.spi.connector.FileConnector
import ai.icen.fw.spi.connector.ConnectorRemoveRequest
import ai.icen.fw.spi.connector.ConnectorSyncRequest
import ai.icen.fw.spi.connector.ConnectorSyncResult
import ai.icen.fw.spi.identity.UserIdentity
import ai.icen.fw.spi.identity.UserRealmProvider
import ai.icen.fw.spi.storage.MultipartPart
import ai.icen.fw.spi.storage.MultipartUpload
import ai.icen.fw.spi.storage.StorageAdapter
import ai.icen.fw.spi.storage.StorageDownload
import ai.icen.fw.spi.storage.StorageObjectLocation
import ai.icen.fw.spi.storage.StorageUploadRequest
import ai.icen.fw.spi.storage.StoredObject
import ai.icen.fw.spi.tenant.TenantProvider
import java.io.InputStream
import java.net.URI
import java.time.Duration

internal class InMemoryDocumentRepository(
    var document: Document? = null,
) : DocumentMutationRepository {
    override fun findForMutation(tenantId: Identifier, documentId: Identifier): Document? =
        findById(tenantId, documentId)

    override fun findByDocumentNumber(tenantId: Identifier, documentNumber: String): Document? = null

    override fun findById(tenantId: Identifier, documentId: Identifier): Document? =
        document?.takeIf { it.tenantId == tenantId && it.id == documentId }

    override fun save(document: Document) {
        this.document = document
    }
}

internal class InMemoryFileObjectRepository(
    var fileObject: FileObject? = null,
) : FileObjectRepository {
    override fun findById(tenantId: Identifier, fileObjectId: Identifier): FileObject? =
        fileObject?.takeIf { it.tenantId == tenantId && it.id == fileObjectId }

    override fun save(fileObject: FileObject) {
        this.fileObject = fileObject
    }
}

internal class FixedTenantProvider(
    tenantId: String = "tenant-1",
) : TenantProvider {
    private val tenantIdentifier = Identifier(tenantId)

    override fun currentTenant(): TenantContext = TenantContext(tenantIdentifier)
}

internal class FixedUserRealmProvider(
    private val user: UserIdentity? = UserIdentity(Identifier("user-1")),
) : UserRealmProvider {
    override fun currentUser(): UserIdentity? = user

    override fun findUser(userId: Identifier): UserIdentity? = user?.takeIf { it.id == userId }
}

internal class FixedAuthorizationProvider(
    private val decision: AuthorizationDecision,
) : AuthorizationProvider {
    override fun authorize(request: AuthorizationRequest): AuthorizationDecision = decision
}

internal open class StorageAdapterStub : StorageAdapter {
    override fun upload(request: StorageUploadRequest, content: InputStream): StoredObject = unsupported()
    override fun download(location: StorageObjectLocation): StorageDownload = unsupported()
    override fun delete(location: StorageObjectLocation) = unsupported<Unit>()
    override fun exists(location: StorageObjectLocation): Boolean = unsupported()
    override fun accessUrl(location: StorageObjectLocation, expiresIn: Duration): URI = unsupported()
    override fun beginMultipartUpload(request: StorageUploadRequest): MultipartUpload = unsupported()
    override fun uploadPart(upload: MultipartUpload, partNumber: Int, content: InputStream, contentLength: Long): MultipartPart = unsupported()
    override fun completeMultipartUpload(upload: MultipartUpload, parts: List<MultipartPart>): StoredObject = unsupported()
    override fun abortMultipartUpload(upload: MultipartUpload) = unsupported<Unit>()

    protected fun <T> unsupported(): T = throw UnsupportedOperationException("Test double")
}

internal class FixedConnector(
    private val connectorHealth: ConnectorHealth,
) : FileConnector {
    override fun sync(request: ConnectorSyncRequest): ConnectorSyncResult = unsupported()
    override fun remove(request: ConnectorRemoveRequest): ConnectorSyncResult = unsupported()
    override fun health(): ConnectorHealth = connectorHealth

    private fun <T> unsupported(): T = throw UnsupportedOperationException("Test double")
}

internal fun documentWithActiveVersion(): Document = Document(
    id = Identifier("document-1"),
    tenantId = Identifier("tenant-1"),
    assetId = Identifier("asset-1"),
    documentNumber = "DOC-001",
    title = "Contract",
    versions = listOf(
        DocumentVersion(
            id = Identifier("version-1"),
            tenantId = Identifier("tenant-1"),
            documentId = Identifier("document-1"),
            versionNumber = "1.0",
            fileObjectId = Identifier("file-1"),
        ),
    ),
    currentVersionId = Identifier("version-1"),
)

internal fun fileObject(): FileObject = FileObject(
    id = Identifier("file-1"),
    tenantId = Identifier("tenant-1"),
    fileName = "contract.pdf",
    contentLength = 10,
    storageType = "local",
    storagePath = "objects/test/file",
    contentType = "application/pdf",
)
