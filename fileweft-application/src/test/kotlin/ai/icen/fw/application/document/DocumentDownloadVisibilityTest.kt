package ai.icen.fw.application.document

import ai.icen.fw.application.audit.AuditTrail
import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.core.context.TenantContext
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.core.id.IdentifierGenerator
import ai.icen.fw.domain.audit.AuditRecord
import ai.icen.fw.domain.audit.AuditRecordRepository
import ai.icen.fw.domain.document.Document
import ai.icen.fw.domain.document.DocumentMutationRepository
import ai.icen.fw.domain.document.DocumentVersion
import ai.icen.fw.domain.document.LifecycleState
import ai.icen.fw.domain.file.FileObject
import ai.icen.fw.domain.file.FileObjectRepository
import ai.icen.fw.spi.authorization.AuthorizationDecision
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.authorization.AuthorizationRequest
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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.URI
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DocumentDownloadVisibilityTest {
    @Test
    fun `short circuits denied base authorization before preparing visibility`() {
        val fixture = Fixture(authorized = false)

        assertThrows<SecurityException> {
            fixture.service.download(DOCUMENT_ID)
        }

        assertEquals(0, fixture.downloadAccess.downloadCalls)
        assertEquals(0, fixture.legacyAccess.calls)
        assertEquals(0, fixture.transaction.executions)
        assertEquals(0, fixture.queries.detailCalls)
        assertTrue(fixture.storage.requested.isEmpty())
        assertTrue(fixture.audits.records.isEmpty())
    }

    @Test
    fun `prepares catalog scope outside the transaction and verifies it inside before file resolution`() {
        val fixture = Fixture(folderIds = linkedSetOf("finance", "contracts"))

        fixture.service.download(DOCUMENT_ID).close()

        assertEquals(1, fixture.downloadAccess.downloadCalls)
        assertEquals(0, fixture.downloadAccess.genericCalls)
        assertFalse(fixture.downloadAccess.calledInTransaction)
        assertEquals(1, fixture.queries.detailCalls)
        assertTrue(fixture.queries.calledInTransaction)
        assertEquals(TENANT_ID, fixture.queries.lastTenantId)
        assertEquals(DOCUMENT_ID, fixture.queries.lastDocumentId)
        assertEquals(listOf("finance", "contracts"), fixture.queries.lastScope?.folderIds)
        assertEquals(1, fixture.files.reads)
        assertEquals(1, fixture.audits.records.size)
        assertEquals(listOf(StorageObjectLocation("memory", "tenant-a/file-1")), fixture.storage.requested)
        assertFalse(fixture.storage.calledInTransaction)
    }

    @Test
    fun `returns not found for a hidden document without file audit or storage access`() {
        val fixture = Fixture(folderIds = setOf("contracts"))

        assertThrows<DocumentNotFoundException> {
            fixture.service.download(DOCUMENT_ID)
        }

        assertEquals(1, fixture.queries.detailCalls)
        assertEquals(0, fixture.files.reads)
        assertTrue(fixture.audits.records.isEmpty())
        assertTrue(fixture.storage.requested.isEmpty())
    }

    @Test
    fun `treats an empty trusted scope as not found before entering a transaction`() {
        val fixture = Fixture(folderIds = emptySet())

        assertThrows<DocumentNotFoundException> {
            fixture.service.download(DOCUMENT_ID)
        }

        assertEquals(1, fixture.downloadAccess.downloadCalls)
        assertEquals(0, fixture.transaction.executions)
        assertEquals(0, fixture.queries.detailCalls)
        assertEquals(0, fixture.documents.reads)
        assertTrue(fixture.audits.records.isEmpty())
        assertTrue(fixture.storage.requested.isEmpty())
    }

    @Test
    fun `falls back safely to the legacy folder read scope contract`() {
        val fixture = Fixture(folderIds = setOf("finance"), downloadSpecificAccess = false)

        fixture.service.download(DOCUMENT_ID).close()

        assertEquals(1, fixture.legacyAccess.calls)
        assertEquals(0, fixture.downloadAccess.downloadCalls)
        assertFalse(fixture.legacyAccess.calledInTransaction)
        assertEquals(listOf("finance"), fixture.queries.lastScope?.folderIds)
    }

    @Test
    fun `does not change the old unscoped download behavior when visibility is absent`() {
        val fixture = Fixture(folderIds = emptySet(), withVisibility = false)

        fixture.service.download(DOCUMENT_ID).close()

        assertEquals(0, fixture.downloadAccess.downloadCalls)
        assertEquals(0, fixture.legacyAccess.calls)
        assertEquals(0, fixture.queries.detailCalls)
        assertEquals(1, fixture.files.reads)
        assertEquals(1, fixture.storage.requested.size)
    }

    @Test
    fun `keeps the old positional nullable audit constructor source compatible`() {
        val fixture = Fixture(withVisibility = false)

        fixture.legacyServiceWithNullAudit().download(DOCUMENT_ID).close()

        assertEquals(1, fixture.storage.requested.size)
    }

    @Test
    fun `freezes an immutable permit and rejects tenant or document reuse before querying`() {
        val mutableFolders = linkedSetOf("finance")
        val transaction = TrackingTransaction()
        val access = RecordingDownloadAccess(mutableFolders, transaction)
        val queries = RecordingQueries(transaction)
        val visibility = DocumentDownloadVisibility(access, queries)
        val permit = visibility.prepare(TENANT_ID, DOCUMENT_ID)
        mutableFolders.clear()

        assertEquals(listOf("finance"), permit.folderReadScope.folderIds)
        @Suppress("UNCHECKED_CAST")
        assertThrows<UnsupportedOperationException> {
            (permit.folderReadScope.folderIds as MutableList<String>).add("private")
        }

        assertThrows<DocumentNotFoundException> {
            visibility.verify(Identifier("tenant-b"), document(), permit)
        }
        assertThrows<DocumentNotFoundException> {
            visibility.verify(
                TENANT_ID,
                Document(Identifier("document-b"), TENANT_ID, Identifier("asset-b"), "DOC-B", "Document B"),
                permit,
            )
        }

        assertEquals(0, queries.detailCalls)
    }

    private class Fixture(
        authorized: Boolean = true,
        folderIds: Set<String> = setOf("finance"),
        downloadSpecificAccess: Boolean = true,
        withVisibility: Boolean = true,
    ) {
        val transaction = TrackingTransaction()
        val authorization = RecordingAuthorization(authorized, transaction)
        val documents = RecordingDocuments(transaction)
        val files = RecordingFiles(transaction)
        val storage = RecordingStorage(transaction)
        val audits = RecordingAudits(transaction)
        val downloadAccess = RecordingDownloadAccess(folderIds, transaction)
        val legacyAccess = RecordingLegacyAccess(folderIds, transaction)
        val queries = RecordingQueries(transaction)
        private val visibility = DocumentDownloadVisibility(
            if (downloadSpecificAccess) downloadAccess else legacyAccess,
            queries,
        )
        private val auditTrail = AuditTrail(
            audits,
            object : IdentifierGenerator { override fun nextId() = Identifier("audit-1") },
            Clock.fixed(Instant.ofEpochMilli(100), ZoneOffset.UTC),
        )
        private val users = object : UserRealmProvider {
            override fun currentUser() = UserIdentity(Identifier("user-a"), "下载用户")
            override fun findUser(userId: Identifier): UserIdentity? = null
        }
        val service = if (withVisibility) {
            DocumentDownloadService(
                tenantProvider(),
                users,
                authorization,
                documents,
                files,
                storage,
                transaction,
                auditTrail,
                visibility,
            )
        } else {
            DocumentDownloadService(
                tenantProvider(),
                users,
                authorization,
                documents,
                files,
                storage,
                transaction,
                auditTrail,
            )
        }

        fun legacyServiceWithNullAudit(): DocumentDownloadService = DocumentDownloadService(
            tenantProvider(),
            users,
            authorization,
            documents,
            files,
            storage,
            transaction,
            null,
        )
    }

    private class RecordingDownloadAccess(
        private val folderIds: Set<String>,
        private val transaction: TrackingTransaction,
    ) : DocumentFolderDownloadAccess {
        var downloadCalls: Int = 0
        var genericCalls: Int = 0
        var calledInTransaction: Boolean = false

        override fun readableFolderIdsForDocumentDownload(documentId: Identifier): Set<String> {
            downloadCalls++
            calledInTransaction = transaction.active
            return folderIds
        }

        override fun readableFolderIds(): Set<String> {
            genericCalls++
            return folderIds
        }

        override fun requireFolderForDocumentRead(folderId: String) = Unit
    }

    private class RecordingLegacyAccess(
        private val folderIds: Set<String>,
        private val transaction: TrackingTransaction,
    ) : DocumentFolderReadAccess {
        var calls: Int = 0
        var calledInTransaction: Boolean = false

        override fun readableFolderIds(): Set<String> {
            calls++
            calledInTransaction = transaction.active
            return folderIds
        }

        override fun requireFolderForDocumentRead(folderId: String) = Unit
    }

    private class RecordingQueries(
        private val transaction: TrackingTransaction,
    ) : DocumentQueryRepository {
        var detailCalls: Int = 0
        var calledInTransaction: Boolean = false
        var lastTenantId: Identifier? = null
        var lastDocumentId: Identifier? = null
        var lastScope: DocumentFolderReadScope? = null

        override fun findDetail(
            tenantId: Identifier,
            documentId: Identifier,
            folderReadScope: DocumentFolderReadScope?,
        ): DocumentDetailView? {
            detailCalls++
            calledInTransaction = transaction.active
            lastTenantId = tenantId
            lastDocumentId = documentId
            lastScope = folderReadScope
            return detail().takeIf {
                tenantId == TENANT_ID && documentId == DOCUMENT_ID && folderReadScope?.folderIds?.contains("finance") == true
            }
        }

        override fun findPage(
            tenantId: Identifier,
            request: DocumentPageRequest,
            folderReadScope: DocumentFolderReadScope?,
        ): DocumentPageResult = DocumentPageResult(emptyList())
    }

    private class RecordingAuthorization(
        private val allowed: Boolean,
        private val transaction: TrackingTransaction,
    ) : AuthorizationProvider {
        override fun authorize(request: AuthorizationRequest): AuthorizationDecision {
            check(!transaction.active)
            return AuthorizationDecision(allowed, "denied")
        }
    }

    private class RecordingDocuments(
        private val transaction: TrackingTransaction,
    ) : DocumentMutationRepository {
        override fun findForMutation(tenantId: Identifier, documentId: Identifier): Document? = findById(tenantId, documentId)
        override fun findByDocumentNumber(tenantId: Identifier, documentNumber: String): Document? = null

        var reads: Int = 0

        override fun findById(tenantId: Identifier, documentId: Identifier): Document? {
            check(transaction.active)
            reads++
            return document().takeIf { it.tenantId == tenantId && it.id == documentId }
        }

        override fun save(document: Document) = Unit
    }

    private class RecordingFiles(
        private val transaction: TrackingTransaction,
    ) : FileObjectRepository {
        var reads: Int = 0

        override fun findById(tenantId: Identifier, fileObjectId: Identifier): FileObject? {
            check(transaction.active)
            reads++
            return FileObject(
                fileObjectId,
                tenantId,
                "document.txt",
                7,
                "memory",
                "tenant-a/file-1",
                "text/plain",
            )
        }

        override fun save(fileObject: FileObject) = Unit
    }

    private class RecordingStorage(
        private val transaction: TrackingTransaction,
    ) : StorageAdapter {
        val requested = mutableListOf<StorageObjectLocation>()
        var calledInTransaction: Boolean = false

        override fun download(location: StorageObjectLocation): StorageDownload {
            calledInTransaction = transaction.active
            requested += location
            return StorageDownload(ByteArrayInputStream("content".toByteArray()), 7, "text/plain")
        }

        override fun upload(request: StorageUploadRequest, content: InputStream): StoredObject =
            throw UnsupportedOperationException()
        override fun delete(location: StorageObjectLocation) = Unit
        override fun exists(location: StorageObjectLocation): Boolean = true
        override fun accessUrl(location: StorageObjectLocation, expiresIn: Duration): URI = URI("http://localhost/file")
        override fun beginMultipartUpload(request: StorageUploadRequest): MultipartUpload = throw UnsupportedOperationException()
        override fun uploadPart(
            upload: MultipartUpload,
            partNumber: Int,
            content: InputStream,
            contentLength: Long,
        ): MultipartPart = throw UnsupportedOperationException()
        override fun completeMultipartUpload(upload: MultipartUpload, parts: List<MultipartPart>): StoredObject =
            throw UnsupportedOperationException()
        override fun abortMultipartUpload(upload: MultipartUpload) = Unit
    }

    private class RecordingAudits(
        private val transaction: TrackingTransaction,
    ) : AuditRecordRepository {
        val records = mutableListOf<AuditRecord>()

        override fun append(record: AuditRecord) {
            check(transaction.active)
            records += record
        }

        override fun findByResource(
            tenantId: Identifier,
            resourceType: String,
            resourceId: Identifier,
            limit: Int,
        ): List<AuditRecord> = emptyList()
    }

    private class TrackingTransaction : ApplicationTransaction {
        var executions: Int = 0
        var active: Boolean = false

        override fun <T> execute(action: () -> T): T {
            check(!active)
            executions++
            active = true
            return try {
                action()
            } finally {
                active = false
            }
        }
    }

    private companion object {
        val TENANT_ID = Identifier("tenant-a")
        val DOCUMENT_ID = Identifier("document-a")

        fun tenantProvider() = object : TenantProvider {
            override fun currentTenant() = TenantContext(TENANT_ID)
        }

        fun document() = Document(
            DOCUMENT_ID,
            TENANT_ID,
            Identifier("asset-a"),
            "DOC-A",
            "Document A",
            versions = listOf(
                DocumentVersion(Identifier("version-a"), TENANT_ID, DOCUMENT_ID, "1.0", Identifier("file-a")),
            ),
            currentVersionId = Identifier("version-a"),
        )

        fun detail() = DocumentDetailView(
            DocumentSummaryView(
                DOCUMENT_ID,
                "DOC-A",
                "Document A",
                LifecycleState.DRAFT,
                10,
                10,
                folderId = "finance",
            ),
        )
    }
}
