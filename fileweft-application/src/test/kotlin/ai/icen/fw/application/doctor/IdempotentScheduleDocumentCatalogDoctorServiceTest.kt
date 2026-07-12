package ai.icen.fw.application.doctor

import ai.icen.fw.application.catalog.DocumentCatalogAccessService
import ai.icen.fw.application.catalog.DocumentCatalogBindingChangedException
import ai.icen.fw.application.document.DocumentNotFoundException
import ai.icen.fw.application.idempotency.IdempotencyResult
import ai.icen.fw.application.idempotency.RequestIdempotency
import ai.icen.fw.application.idempotency.RequestIdempotencyClaim
import ai.icen.fw.application.idempotency.RequestIdempotencyRecord
import ai.icen.fw.application.idempotency.RequestIdempotencyRepository
import ai.icen.fw.application.idempotency.RequestIdempotencyService
import ai.icen.fw.application.idempotency.RequestIdempotencyStatus
import ai.icen.fw.application.security.ApplicationForbiddenException
import ai.icen.fw.application.task.BackgroundTask
import ai.icen.fw.application.task.TaskRepository
import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.core.context.TenantContext
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.core.id.IdentifierGenerator
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
import ai.icen.fw.spi.catalog.DocumentCatalogOperation
import ai.icen.fw.spi.catalog.DocumentCatalogProvider
import ai.icen.fw.spi.identity.UserIdentity
import ai.icen.fw.spi.identity.UserRealmProvider
import ai.icen.fw.spi.tenant.TenantProvider
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IdempotentScheduleDocumentCatalogDoctorServiceTest {
    @Test
    fun `source folder ACL is enforced before idempotency or task persistence`() {
        val fixture = Fixture(catalogVisible = false)

        assertFailsWith<DocumentNotFoundException> {
            fixture.service.schedule(DOCUMENT_ID, "doctor-catalog-denied")
        }

        assertEquals(listOf(SOURCE_FOLDER_ID), fixture.catalog.folderRequests)
        assertEquals(listOf(DocumentCatalogOperation.BROWSE), fixture.catalog.operations)
        assertTrue(fixture.catalog.transactionStates.none { it })
        assertEquals(0, fixture.idempotency.findCalls)
        assertEquals(0, fixture.documents.mutationReads)
        assertEquals(0, fixture.assets.mutationReads)
        assertTrue(fixture.tasks.tasks.isEmpty())
    }

    @Test
    fun `same key replay fails closed when permission is revoked after catalog preparation`() {
        val fixture = Fixture()
        val receipt = fixture.service.schedule(DOCUMENT_ID, "doctor-replay-key")
        val authorizationCalls = fixture.authorization.requests.size
        val catalogCalls = fixture.catalog.folderRequests.size
        val idempotencyFinds = fixture.idempotency.findCalls
        fixture.authorization.denyFromCall = authorizationCalls + 4

        assertFailsWith<ApplicationForbiddenException> {
            fixture.service.schedule(DOCUMENT_ID, "doctor-replay-key")
        }

        assertEquals(receipt.taskId, fixture.tasks.tasks.single().id)
        assertEquals(catalogCalls + 1, fixture.catalog.folderRequests.size)
        assertEquals(idempotencyFinds, fixture.idempotency.findCalls)
        assertEquals(
            listOf("document:doctor", "document:read", "document:doctor", "document:doctor"),
            fixture.authorization.requests.drop(authorizationCalls).map { request -> request.action.name },
        )
        assertEquals(
            1,
            fixture.authorization.requests.drop(authorizationCalls).map { request -> request.subject }.distinct().size,
        )
    }

    @Test
    fun `binding change immediately before the final lock cannot create a task receipt`() {
        val fixture = Fixture()
        fixture.documents.beforeMutation = { fixture.assets.replaceBinding("finance") }

        assertFailsWith<DocumentCatalogBindingChangedException> {
            fixture.service.schedule(DOCUMENT_ID, "doctor-binding-race")
        }

        assertEquals(1, fixture.documents.mutationReads)
        assertEquals(1, fixture.assets.mutationReads)
        assertTrue(fixture.tasks.tasks.isEmpty())
        assertEquals(0, fixture.idempotency.completeCalls)
    }

    @Test
    fun `catalog scheduling rejects a repository without asset mutation locks`() {
        val failure = assertFailsWith<IllegalArgumentException> {
            Fixture(mutationCapableAssets = false)
        }

        assertTrue(failure.message.orEmpty().contains("FileAssetMutationRepository"))
    }

    private class Fixture(
        catalogVisible: Boolean = true,
        mutationCapableAssets: Boolean = true,
    ) {
        val transaction = TrackingTransaction()
        val documents = RecordingDocuments(transaction)
        val assets = RecordingAssets(transaction)
        private val assetRepository: FileAssetRepository = if (mutationCapableAssets) {
            assets
        } else {
            ReadOnlyAssets(assets.asset)
        }
        val tasks = RecordingTasks()
        val idempotency = MemoryIdempotencyRepository()
        val authorization = RecordingAuthorization(transaction)
        val catalog = RecordingCatalog(catalogVisible, transaction)
        private val catalogAccess = DocumentCatalogAccessService(
            FixedTenant,
            FixedUsers,
            authorization,
            catalog,
        )
        val service = IdempotentScheduleDocumentCatalogDoctorService(
            tenants = FixedTenant,
            users = FixedUsers,
            authorization = authorization,
            documents = documents,
            assets = assetRepository,
            tasks = tasks,
            identifiers = PrefixIds("task"),
            transaction = transaction,
            clock = CLOCK,
            idempotency = RequestIdempotencyService(
                idempotency,
                transaction,
                PrefixIds("request"),
                CLOCK,
            ),
            catalogAccess = catalogAccess,
        )
    }

    private class TrackingTransaction : ApplicationTransaction {
        var active: Boolean = false
            private set

        override fun <T> execute(action: () -> T): T {
            check(!active) { "Nested test transaction is not expected." }
            active = true
            return try {
                action()
            } finally {
                active = false
            }
        }
    }

    private class RecordingDocuments(
        private val transaction: TrackingTransaction,
    ) : DocumentRepository {
        var mutationReads: Int = 0
            private set
        var beforeMutation: (() -> Unit)? = null

        override fun findById(tenantId: Identifier, documentId: Identifier): Document? {
            assertTrue(transaction.active)
            return DOCUMENT.takeIf { tenantId == TENANT_ID && documentId == DOCUMENT_ID }
        }

        override fun findForMutation(tenantId: Identifier, documentId: Identifier): Document? {
            assertTrue(transaction.active)
            mutationReads++
            beforeMutation?.invoke()
            return DOCUMENT.takeIf { tenantId == TENANT_ID && documentId == DOCUMENT_ID }
        }

        override fun save(document: Document) = Unit
    }

    private class RecordingAssets(
        private val transaction: TrackingTransaction,
    ) : FileAssetMutationRepository {
        var asset: FileAsset = fileAsset(SOURCE_FOLDER_ID)
            private set
        var mutationReads: Int = 0
            private set

        override fun findById(tenantId: Identifier, fileAssetId: Identifier): FileAsset? {
            assertTrue(transaction.active)
            return asset.takeIf { it.tenantId == tenantId && it.id == fileAssetId }
        }

        override fun findForMutation(tenantId: Identifier, fileAssetId: Identifier): FileAsset? {
            assertTrue(transaction.active)
            mutationReads++
            return asset.takeIf { it.tenantId == tenantId && it.id == fileAssetId }
        }

        override fun save(fileAsset: FileAsset) {
            asset = fileAsset
        }

        fun replaceBinding(folderId: String) {
            asset = fileAsset(folderId)
        }
    }

    private class ReadOnlyAssets(
        private var asset: FileAsset,
    ) : FileAssetRepository {
        override fun findById(tenantId: Identifier, fileAssetId: Identifier): FileAsset? =
            asset.takeIf { it.tenantId == tenantId && it.id == fileAssetId }

        override fun save(fileAsset: FileAsset) {
            asset = fileAsset
        }
    }

    private class RecordingTasks : TaskRepository {
        val tasks = mutableListOf<BackgroundTask>()

        override fun enqueue(task: BackgroundTask) {
            tasks += task
        }

        override fun findById(tenantId: Identifier, taskId: Identifier): BackgroundTask? =
            tasks.firstOrNull { task -> task.tenantId == tenantId && task.id == taskId }

        override fun findByBusiness(
            tenantId: Identifier,
            businessId: Identifier,
            limit: Int,
        ): List<BackgroundTask> = tasks
            .filter { task -> task.tenantId == tenantId && task.businessId == businessId }
            .take(limit)
    }

    private class MemoryIdempotencyRepository : RequestIdempotencyRepository {
        private var record: RequestIdempotencyRecord? = null
        var findCalls: Int = 0
            private set
        var completeCalls: Int = 0
            private set

        override fun findByKeyDigest(tenantId: Identifier, keyDigest: String): RequestIdempotencyRecord? {
            findCalls++
            return record?.takeIf { current -> current.tenantId == tenantId && current.keyDigest == keyDigest }
        }

        override fun claim(
            request: RequestIdempotency,
            newRecordId: Identifier,
            now: Long,
        ): RequestIdempotencyClaim {
            record?.let { current -> return RequestIdempotencyClaim(current, acquired = false) }
            val created = RequestIdempotencyRecord(
                id = newRecordId,
                tenantId = request.tenantId,
                keyDigest = request.keyDigest,
                operatorId = request.operatorId,
                action = request.action,
                resourceType = request.resourceType,
                resourceId = request.resourceId,
                subresourceId = request.subresourceId,
                requestFingerprint = request.requestFingerprint,
                status = RequestIdempotencyStatus.IN_PROGRESS,
                result = null,
                completedTime = null,
                createdTime = now,
                updatedTime = now,
            )
            record = created
            return RequestIdempotencyClaim(created, acquired = true)
        }

        override fun complete(
            recordId: Identifier,
            tenantId: Identifier,
            keyDigest: String,
            result: IdempotencyResult,
            completedAt: Long,
        ): RequestIdempotencyRecord {
            completeCalls++
            val current = requireNotNull(record)
            val completed = RequestIdempotencyRecord(
                id = current.id,
                tenantId = current.tenantId,
                keyDigest = current.keyDigest,
                operatorId = current.operatorId,
                action = current.action,
                resourceType = current.resourceType,
                resourceId = current.resourceId,
                subresourceId = current.subresourceId,
                requestFingerprint = current.requestFingerprint,
                status = RequestIdempotencyStatus.COMPLETED,
                result = result,
                completedTime = completedAt,
                createdTime = current.createdTime,
                updatedTime = completedAt,
            )
            record = completed
            return completed
        }
    }

    private class RecordingAuthorization(
        private val transaction: TrackingTransaction,
    ) : AuthorizationProvider {
        val requests = mutableListOf<AuthorizationRequest>()
        var denyFromCall: Int? = null

        override fun authorize(request: AuthorizationRequest): AuthorizationDecision {
            assertFalse(transaction.active, "Authorization must stay outside local transactions.")
            requests += request
            val denied = denyFromCall?.let { firstDenied -> requests.size >= firstDenied } == true
            return AuthorizationDecision(!denied, if (denied) "revoked" else null)
        }
    }

    private class RecordingCatalog(
        private val visible: Boolean,
        private val transaction: TrackingTransaction,
    ) : DocumentCatalogProvider {
        val folderRequests = mutableListOf<String>()
        val operations = mutableListOf<DocumentCatalogOperation>()
        val transactionStates = mutableListOf<Boolean>()

        override fun listFolders(tenantId: Identifier): List<DocumentCatalogFolder> = emptyList()

        override fun findFolder(
            request: DocumentCatalogAccessRequest,
            folderId: String,
        ): DocumentCatalogFolder? {
            transactionStates += transaction.active
            assertFalse(transaction.active, "Catalog ACL must stay outside local transactions.")
            folderRequests += folderId
            operations += request.operation
            return DocumentCatalogFolder(folderId, null, "Contracts").takeIf { visible }
        }
    }

    private class PrefixIds(
        private val prefix: String,
    ) : IdentifierGenerator {
        private var sequence: Int = 0
        override fun nextId(): Identifier = Identifier("$prefix-${++sequence}")
    }

    private object FixedTenant : TenantProvider {
        override fun currentTenant(): TenantContext = TenantContext(TENANT_ID)
    }

    private object FixedUsers : UserRealmProvider {
        override fun currentUser(): UserIdentity = UserIdentity(OPERATOR_ID, "Catalog Doctor")
        override fun findUser(userId: Identifier): UserIdentity? = null
    }

    private companion object {
        val TENANT_ID = Identifier("tenant-1")
        val DOCUMENT_ID = Identifier("document-1")
        val ASSET_ID = Identifier("asset-1")
        val FILE_OBJECT_ID = Identifier("file-object-1")
        val OPERATOR_ID = Identifier("operator-1")
        const val SOURCE_FOLDER_ID = "contracts"
        val CLOCK: Clock = Clock.fixed(Instant.ofEpochMilli(100), ZoneOffset.UTC)
        val DOCUMENT = Document(
            id = DOCUMENT_ID,
            tenantId = TENANT_ID,
            assetId = ASSET_ID,
            documentNumber = "DOC-1",
            title = "Catalog Doctor document",
        )

        fun fileAsset(folderId: String): FileAsset = FileAsset(
            id = ASSET_ID,
            tenantId = TENANT_ID,
            fileObjectId = FILE_OBJECT_ID,
            assetType = "DOCUMENT",
            metadata = mapOf(DocumentCatalogBinding.METADATA_KEY to folderId),
        )
    }
}
