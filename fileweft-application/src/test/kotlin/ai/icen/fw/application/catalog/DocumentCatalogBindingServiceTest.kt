package ai.icen.fw.application.catalog

import ai.icen.fw.application.audit.AuditTrail
import ai.icen.fw.application.document.DocumentNotFoundException
import ai.icen.fw.application.security.ApplicationUnauthenticatedException
import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.core.context.TenantContext
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.core.id.IdentifierGenerator
import ai.icen.fw.domain.audit.AuditRecord
import ai.icen.fw.domain.audit.AuditRecordRepository
import ai.icen.fw.domain.document.Document
import ai.icen.fw.domain.document.DocumentRepository
import ai.icen.fw.domain.file.FileAsset
import ai.icen.fw.domain.file.FileAssetMutationRepository
import ai.icen.fw.domain.file.FileAssetRepository
import ai.icen.fw.spi.authorization.AuthorizationDecision
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.authorization.AuthorizationRequest
import ai.icen.fw.spi.catalog.DocumentCatalogAccessRequest
import ai.icen.fw.spi.catalog.DocumentCatalogBinding
import ai.icen.fw.spi.catalog.DocumentCatalogFolder
import ai.icen.fw.spi.catalog.DocumentCatalogProvider
import ai.icen.fw.spi.identity.UserIdentity
import ai.icen.fw.spi.identity.UserRealmProvider
import ai.icen.fw.spi.tenant.TenantProvider
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DocumentCatalogBindingServiceTest {
    @Test
    fun `moves only opaque folder metadata under the document lock and remains idempotent`() {
        val fixture = Fixture()

        fixture.service.move(fixture.document.id, TARGET_FOLDER_ID)
        fixture.service.move(fixture.document.id, TARGET_FOLDER_ID)

        assertEquals(TARGET_FOLDER_ID, fixture.assets.asset.metadata[DocumentCatalogBinding.METADATA_KEY])
        assertEquals("host", fixture.assets.asset.metadata["source"])
        assertEquals(1, fixture.assets.saved.size)
        assertEquals(2, fixture.documents.mutationReads)
        assertEquals(
            listOf(
                SOURCE_FOLDER_ID,
                TARGET_FOLDER_ID,
                SOURCE_FOLDER_ID,
                TARGET_FOLDER_ID,
                TARGET_FOLDER_ID,
                TARGET_FOLDER_ID,
            ),
            fixture.catalog.folderRequests,
        )
        assertTrue(fixture.catalog.transactionStates.all { active -> !active })
        assertTrue(
            fixture.events.indexOf("document:find-for-mutation") <
                fixture.events.indexOf("asset:find-for-mutation"),
        )
        assertEquals(2, fixture.assets.ordinaryReads)
        assertEquals(2, fixture.assets.mutationReads)
        assertEquals(1, fixture.audits.records.size)
        assertEquals("document:catalog:move", fixture.audits.records.single().action)
        assertEquals(SOURCE_FOLDER_ID, fixture.audits.records.single().details["previousFolderId"])
        assertEquals(TARGET_FOLDER_ID, fixture.audits.records.single().details["folderId"])
        assertEquals("Editor A", fixture.audits.records.single().operatorName)
    }

    @Test
    fun `checks base authorization before any database or catalog access`() {
        val fixture = Fixture(authorized = false)

        assertThrows<SecurityException> {
            fixture.service.move(fixture.document.id, TARGET_FOLDER_ID)
        }

        assertEquals(0, fixture.transaction.executions)
        assertEquals(0, fixture.documents.ordinaryReads)
        assertEquals(0, fixture.documents.mutationReads)
        assertTrue(fixture.assets.reads.isEmpty())
        assertTrue(fixture.catalog.folderRequests.isEmpty())
        assertTrue(fixture.assets.saved.isEmpty())
        assertTrue(fixture.audits.records.isEmpty())
    }

    @Test
    fun `rejects a missing current user before any database or catalog access`() {
        val fixture = Fixture(authenticated = false)

        assertThrows<ApplicationUnauthenticatedException> {
            fixture.service.move(fixture.document.id, TARGET_FOLDER_ID)
        }

        assertEquals(0, fixture.transaction.executions)
        assertEquals(0, fixture.documents.ordinaryReads)
        assertTrue(fixture.catalog.folderRequests.isEmpty())
        assertTrue(fixture.assets.saved.isEmpty())
        assertTrue(fixture.audits.records.isEmpty())
    }

    @Test
    fun `fails fast when the asset repository has no mutation capability`() {
        val failure = assertThrows<IllegalArgumentException> {
            Fixture(mutationCapable = false)
        }

        assertTrue(failure.message.orEmpty().contains("FileAssetMutationRepository"))
    }

    @Test
    fun `rejects malicious snapshot identities before catalog access or writes`() {
        val foreignDocumentFixture = Fixture()
        foreignDocumentFixture.documents.findByIdOverride = { _, _ ->
            Document(
                id = foreignDocumentFixture.document.id,
                tenantId = Identifier("tenant-b"),
                assetId = foreignDocumentFixture.document.assetId,
                documentNumber = "DOC-FOREIGN",
                title = "Foreign document",
            )
        }

        assertThrows<DocumentNotFoundException> {
            foreignDocumentFixture.service.move(foreignDocumentFixture.document.id, TARGET_FOLDER_ID)
        }
        assertTrue(foreignDocumentFixture.catalog.folderRequests.isEmpty())
        assertTrue(foreignDocumentFixture.assets.saved.isEmpty())
        assertTrue(foreignDocumentFixture.audits.records.isEmpty())

        val wrongAssetFixture = Fixture()
        wrongAssetFixture.assets.findByIdOverride = { _, _ ->
            FileAsset(
                id = Identifier("asset-wrong"),
                tenantId = TENANT_ID,
                fileObjectId = Identifier("file-wrong"),
                assetType = "DOCUMENT",
            )
        }

        assertThrows<IllegalStateException> {
            wrongAssetFixture.service.move(wrongAssetFixture.document.id, TARGET_FOLDER_ID)
        }
        assertTrue(wrongAssetFixture.catalog.folderRequests.isEmpty())
        assertTrue(wrongAssetFixture.assets.saved.isEmpty())
        assertTrue(wrongAssetFixture.audits.records.isEmpty())
    }

    @Test
    fun `does not reveal or move a hidden source even when the target is visible`() {
        val fixture = Fixture(
            rawFolderId = HIDDEN_FOLDER_ID,
            visibleFolderIds = setOf(TARGET_FOLDER_ID),
        )

        assertThrows<DocumentNotFoundException> {
            fixture.service.move(fixture.document.id, TARGET_FOLDER_ID)
        }

        assertEquals(listOf(HIDDEN_FOLDER_ID), fixture.catalog.folderRequests)
        assertEquals(1, fixture.documents.ordinaryReads)
        assertEquals(0, fixture.documents.mutationReads)
        assertTrue(fixture.assets.saved.isEmpty())
        assertTrue(fixture.audits.records.isEmpty())
    }

    @Test
    fun `keeps an unavailable target as invalid input after authorizing the source`() {
        val fixture = Fixture(visibleFolderIds = setOf(SOURCE_FOLDER_ID))

        val failure = assertThrows<IllegalArgumentException> {
            fixture.service.move(fixture.document.id, TARGET_FOLDER_ID)
        }

        assertFalse(DocumentNotFoundException::class.java.isInstance(failure))
        assertEquals(listOf(SOURCE_FOLDER_ID, TARGET_FOLDER_ID), fixture.catalog.folderRequests)
        assertEquals(0, fixture.documents.mutationReads)
        assertTrue(fixture.assets.saved.isEmpty())
        assertTrue(fixture.audits.records.isEmpty())
    }

    @Test
    fun `revalidates the source after target resolution and rejects a revoked acl before locking`() {
        val fixture = Fixture()
        fixture.catalog.afterLookup = { folderId ->
            if (folderId == TARGET_FOLDER_ID) {
                fixture.catalog.hide(SOURCE_FOLDER_ID)
            }
        }

        assertThrows<DocumentNotFoundException> {
            fixture.service.move(fixture.document.id, TARGET_FOLDER_ID)
        }

        assertEquals(
            listOf(SOURCE_FOLDER_ID, TARGET_FOLDER_ID, SOURCE_FOLDER_ID),
            fixture.catalog.folderRequests,
        )
        assertEquals(0, fixture.documents.mutationReads)
        assertEquals(0, fixture.assets.mutationReads)
        assertTrue(fixture.assets.saved.isEmpty())
        assertTrue(fixture.audits.records.isEmpty())
        assertTrue(fixture.catalog.transactionStates.none { it })
    }

    @Test
    fun `rejects invalid canonical target ids as provider failures without persisting`() {
        listOf(" $TARGET_FOLDER_ID ", "x".repeat(257), "unsafe\u0000canonical").forEach { invalidCanonicalId ->
            val fixture = Fixture(
                folderOverrides = mapOf(
                    TARGET_FOLDER_ID to DocumentCatalogFolder(invalidCanonicalId, null, "Invalid canonical folder"),
                ),
            )

            val failure = assertThrows<IllegalStateException> {
                fixture.service.move(fixture.document.id, TARGET_FOLDER_ID)
            }

            assertTrue(failure.message.orEmpty().contains("provider returned an invalid canonical folder id"))
            assertEquals(listOf(SOURCE_FOLDER_ID, TARGET_FOLDER_ID), fixture.catalog.folderRequests)
            assertEquals(0, fixture.documents.mutationReads)
            assertTrue(fixture.assets.saved.isEmpty())
            assertTrue(fixture.audits.records.isEmpty())
        }
    }

    @Test
    fun `rechecks the raw source binding after locking and rejects a race without writes`() {
        val fixture = Fixture()
        fixture.documents.beforeFindForMutation = {
            fixture.assets.replaceBinding("finance")
        }

        assertThrows<DocumentCatalogBindingChangedException> {
            fixture.service.move(fixture.document.id, TARGET_FOLDER_ID)
        }

        assertEquals(
            listOf(SOURCE_FOLDER_ID, TARGET_FOLDER_ID, SOURCE_FOLDER_ID),
            fixture.catalog.folderRequests,
        )
        assertEquals(1, fixture.documents.mutationReads)
        assertTrue(fixture.assets.saved.isEmpty())
        assertTrue(fixture.audits.records.isEmpty())
    }

    @Test
    fun `rejects malicious locked identities without moving or auditing`() {
        listOf("document", "asset").forEach { maliciousResult ->
            val fixture = Fixture()
            if (maliciousResult == "document") {
                fixture.documents.findForMutationOverride = { _, _ ->
                    Document(
                        id = fixture.document.id,
                        tenantId = Identifier("tenant-b"),
                        assetId = fixture.document.assetId,
                        documentNumber = "DOC-FOREIGN",
                        title = "Foreign document",
                    )
                }
            } else {
                fixture.assets.findForMutationOverride = { _, _ ->
                    FileAsset(
                        id = Identifier("asset-wrong"),
                        tenantId = TENANT_ID,
                        fileObjectId = Identifier("file-wrong"),
                        assetType = "DOCUMENT",
                    )
                }
            }

            assertThrows<DocumentCatalogBindingChangedException> {
                fixture.service.move(fixture.document.id, TARGET_FOLDER_ID)
            }

            assertTrue(fixture.assets.saved.isEmpty())
            assertTrue(fixture.audits.records.isEmpty())
        }
    }

    @Test
    fun `does not move or audit when the final document or asset disappears`() {
        listOf("document", "asset").forEach { missingResource ->
            val fixture = Fixture()
            if (missingResource == "document") {
                fixture.documents.findForMutationOverride = { _, _ -> null }
            } else {
                fixture.assets.findForMutationOverride = { _, _ -> null }
            }

            assertThrows<RuntimeException> {
                fixture.service.move(fixture.document.id, TARGET_FOLDER_ID)
            }

            assertTrue(fixture.assets.saved.isEmpty())
            assertTrue(fixture.audits.records.isEmpty())
        }
    }

    private class Fixture(
        rawFolderId: String? = SOURCE_FOLDER_ID,
        visibleFolderIds: Set<String> = setOf(SOURCE_FOLDER_ID, TARGET_FOLDER_ID),
        folderOverrides: Map<String, DocumentCatalogFolder> = emptyMap(),
        authorized: Boolean = true,
        authenticated: Boolean = true,
        mutationCapable: Boolean = true,
    ) {
        val events = mutableListOf<String>()
        val transaction = TrackingTransaction(events)
        val document = Document(
            id = Identifier("document-1"),
            tenantId = TENANT_ID,
            assetId = Identifier("asset-1"),
            documentNumber = "DOC-001",
            title = "Contract",
        )
        val documents = RecordingDocuments(document, transaction, events)
        val assets = RecordingAssets(
            FileAsset(
                document.assetId,
                TENANT_ID,
                Identifier("file-1"),
                "DOCUMENT",
                linkedMapOf<String, String>().apply {
                    rawFolderId?.let { folderId -> put(DocumentCatalogBinding.METADATA_KEY, folderId) }
                    put("source", "host")
                },
            ),
            transaction,
            events,
        )
        private val assetRepository: FileAssetRepository = if (mutationCapable) {
            assets
        } else {
            NonLockingAssets(assets.asset)
        }
        val audits = RecordingAudits(transaction)
        val catalog = RecordingCatalog(visibleFolderIds, folderOverrides, transaction, events)
        private val authorization = RecordingAuthorization(authorized, events)
        private val tenants = object : TenantProvider {
            override fun currentTenant() = TenantContext(TENANT_ID)
        }
        private val users = object : UserRealmProvider {
            override fun currentUser() = if (authenticated) {
                UserIdentity(Identifier("editor-a"), "Editor A")
            } else {
                null
            }
            override fun findUser(userId: Identifier): UserIdentity? = null
        }
        private val access = DocumentCatalogAccessService(tenants, users, authorization, catalog)
        val service = DocumentCatalogBindingService(
            tenantProvider = tenants,
            userRealmProvider = users,
            catalogAccess = access,
            documents = documents,
            assets = assetRepository,
            transaction = transaction,
            auditTrail = AuditTrail(
                audits,
                SequenceIds(),
                Clock.fixed(Instant.ofEpochMilli(100), ZoneOffset.UTC),
            ),
        )
    }

    private class TrackingTransaction(
        private val events: MutableList<String>,
    ) : ApplicationTransaction {
        var active: Boolean = false
            private set
        var executions: Int = 0
            private set

        override fun <T> execute(action: () -> T): T {
            check(!active) { "Nested test transactions are not expected." }
            executions++
            events += "transaction:start"
            active = true
            return try {
                action()
            } finally {
                active = false
                events += "transaction:end"
            }
        }
    }

    private class RecordingDocuments(
        private val document: Document,
        private val transaction: TrackingTransaction,
        private val events: MutableList<String>,
    ) : DocumentRepository {
        var ordinaryReads: Int = 0
        var mutationReads: Int = 0
        var beforeFindForMutation: (() -> Unit)? = null
        var findByIdOverride: ((Identifier, Identifier) -> Document?)? = null
        var findForMutationOverride: ((Identifier, Identifier) -> Document?)? = null

        override fun findById(tenantId: Identifier, documentId: Identifier): Document? {
            assertTrue(transaction.active)
            ordinaryReads++
            events += "document:find"
            findByIdOverride?.let { provider -> return provider(tenantId, documentId) }
            return document.takeIf { candidate -> candidate.tenantId == tenantId && candidate.id == documentId }
        }

        override fun findForMutation(tenantId: Identifier, documentId: Identifier): Document? {
            assertTrue(transaction.active)
            mutationReads++
            events += "document:find-for-mutation"
            beforeFindForMutation?.invoke()
            findForMutationOverride?.let { provider -> return provider(tenantId, documentId) }
            return document.takeIf { candidate -> candidate.tenantId == tenantId && candidate.id == documentId }
        }

        override fun save(document: Document) = Unit
    }

    private class RecordingAssets(
        var asset: FileAsset,
        private val transaction: TrackingTransaction,
        private val events: MutableList<String>,
    ) : FileAssetMutationRepository {
        var ordinaryReads: Int = 0
        var mutationReads: Int = 0
        var findByIdOverride: ((Identifier, Identifier) -> FileAsset?)? = null
        var findForMutationOverride: ((Identifier, Identifier) -> FileAsset?)? = null
        val reads = mutableListOf<Identifier>()
        val saved = mutableListOf<FileAsset>()

        override fun findById(tenantId: Identifier, fileAssetId: Identifier): FileAsset? {
            assertTrue(transaction.active)
            ordinaryReads++
            reads += fileAssetId
            events += "asset:find"
            findByIdOverride?.let { provider -> return provider(tenantId, fileAssetId) }
            return asset.takeIf { candidate -> candidate.tenantId == tenantId && candidate.id == fileAssetId }
        }

        override fun findForMutation(tenantId: Identifier, fileAssetId: Identifier): FileAsset? {
            assertTrue(transaction.active)
            mutationReads++
            reads += fileAssetId
            events += "asset:find-for-mutation"
            findForMutationOverride?.let { provider -> return provider(tenantId, fileAssetId) }
            return asset.takeIf { candidate -> candidate.tenantId == tenantId && candidate.id == fileAssetId }
        }

        override fun save(fileAsset: FileAsset) {
            assertTrue(transaction.active)
            asset = fileAsset
            saved += fileAsset
        }

        fun replaceBinding(folderId: String) {
            asset = FileAsset(
                asset.id,
                asset.tenantId,
                asset.fileObjectId,
                asset.assetType,
                asset.metadata + (DocumentCatalogBinding.METADATA_KEY to folderId),
            )
        }
    }

    private class NonLockingAssets(
        private val asset: FileAsset,
    ) : FileAssetRepository {
        override fun findById(tenantId: Identifier, fileAssetId: Identifier): FileAsset? =
            asset.takeIf { candidate -> candidate.tenantId == tenantId && candidate.id == fileAssetId }

        override fun save(fileAsset: FileAsset) = Unit
    }

    private class RecordingCatalog(
        visibleFolderIds: Set<String>,
        private val folderOverrides: Map<String, DocumentCatalogFolder>,
        private val transaction: TrackingTransaction,
        private val events: MutableList<String>,
    ) : DocumentCatalogProvider {
        private val visibleFolderIds = visibleFolderIds.toMutableSet()
        val folderRequests = mutableListOf<String>()
        val transactionStates = mutableListOf<Boolean>()
        var afterLookup: ((String) -> Unit)? = null

        override fun listFolders(tenantId: Identifier): List<DocumentCatalogFolder> = emptyList()

        override fun findFolder(request: DocumentCatalogAccessRequest, folderId: String): DocumentCatalogFolder? {
            transactionStates += transaction.active
            assertFalse(transaction.active, "A host catalog must never be called inside a database transaction.")
            folderRequests += folderId
            events += "catalog:$folderId"
            val folder = if (folderId in visibleFolderIds) {
                folderOverrides[folderId]
                    ?: DocumentCatalogFolder(folderId, null, "Folder $folderId")
            } else {
                null
            }
            afterLookup?.invoke(folderId)
            return folder
        }

        fun hide(folderId: String) {
            visibleFolderIds -= folderId
        }
    }

    private class RecordingAuthorization(
        private val allowed: Boolean,
        private val events: MutableList<String>,
    ) : AuthorizationProvider {
        override fun authorize(request: AuthorizationRequest): AuthorizationDecision {
            events += "authorization:${request.action.name}"
            return AuthorizationDecision(allowed, "denied")
        }
    }

    private class RecordingAudits(
        private val transaction: TrackingTransaction,
    ) : AuditRecordRepository {
        val records = mutableListOf<AuditRecord>()

        override fun append(record: AuditRecord) {
            assertTrue(transaction.active)
            records += record
        }

        override fun findByResource(
            tenantId: Identifier,
            resourceType: String,
            resourceId: Identifier,
            limit: Int,
        ): List<AuditRecord> = records.filter { record ->
            record.tenantId == tenantId && record.resourceType == resourceType && record.resourceId == resourceId
        }.take(limit)
    }

    private class SequenceIds : IdentifierGenerator {
        private var counter = 0
        override fun nextId() = Identifier("audit-${++counter}")
    }

    private companion object {
        val TENANT_ID = Identifier("tenant-a")
        const val SOURCE_FOLDER_ID = "inbox"
        const val TARGET_FOLDER_ID = "contracts"
        const val HIDDEN_FOLDER_ID = "executive"
    }
}
