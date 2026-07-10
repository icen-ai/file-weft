package com.fileweft.application.doctor

import com.fileweft.core.context.TenantContext
import com.fileweft.core.id.Identifier
import com.fileweft.domain.document.Document
import com.fileweft.domain.document.DocumentRepository
import com.fileweft.domain.document.DocumentVersion
import com.fileweft.domain.file.FileObject
import com.fileweft.domain.file.FileObjectRepository
import com.fileweft.spi.authorization.AuthorizationDecision
import com.fileweft.spi.authorization.AuthorizationProvider
import com.fileweft.spi.authorization.AuthorizationRequest
import com.fileweft.spi.connector.ConnectorHealth
import com.fileweft.spi.connector.FileConnector
import com.fileweft.spi.connector.ConnectorRemoveRequest
import com.fileweft.spi.connector.ConnectorSyncRequest
import com.fileweft.spi.connector.ConnectorSyncResult
import com.fileweft.spi.identity.UserIdentity
import com.fileweft.spi.identity.UserRealmProvider
import com.fileweft.spi.storage.MultipartPart
import com.fileweft.spi.storage.MultipartUpload
import com.fileweft.spi.storage.StorageAdapter
import com.fileweft.spi.storage.StorageDownload
import com.fileweft.spi.storage.StorageObjectLocation
import com.fileweft.spi.storage.StorageUploadRequest
import com.fileweft.spi.storage.StoredObject
import com.fileweft.spi.tenant.TenantProvider
import java.io.InputStream
import java.net.URI
import java.time.Duration

internal class InMemoryDocumentRepository(
    var document: Document? = null,
) : DocumentRepository {
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
