package com.fileweft.application.archive

import com.fileweft.application.audit.AuditTrail
import com.fileweft.application.catalog.DocumentLifecycleMutationGuard
import com.fileweft.application.catalog.DocumentLifecycleMutationPermit
import com.fileweft.application.delivery.DocumentDeliveryRemovalPlanner
import com.fileweft.application.delivery.DocumentDeliveryRemovalStatus
import com.fileweft.application.delivery.DocumentDeliveryStatus
import com.fileweft.application.delivery.DocumentDeliveryTarget
import com.fileweft.application.delivery.DocumentDeliveryTargetRepository
import com.fileweft.application.delivery.DeliveryDispatchFence
import com.fileweft.application.delivery.DeliveryDispatchOperation
import com.fileweft.application.document.DocumentNotFoundException
import com.fileweft.application.lifecycle.DocumentLifecycleMutationContext
import com.fileweft.application.lifecycle.DocumentLifecycleMutationTransaction
import com.fileweft.application.offline.OfflineDocumentService
import com.fileweft.application.offline.DocumentRestoreConflictException
import com.fileweft.application.offline.DocumentRestoreConflictReason
import com.fileweft.application.offline.RestoreOfflineDocumentService
import com.fileweft.application.outbox.OutboxEventRepository
import com.fileweft.application.transaction.ApplicationTransaction
import com.fileweft.core.context.TenantContext
import com.fileweft.core.event.OutboxEvent
import com.fileweft.core.id.Identifier
import com.fileweft.core.id.IdentifierGenerator
import com.fileweft.domain.document.Document
import com.fileweft.domain.document.DocumentRepository
import com.fileweft.domain.document.DocumentVersion
import com.fileweft.domain.document.InvalidLifecycleTransitionException
import com.fileweft.domain.document.LifecycleCommand
import com.fileweft.domain.document.LifecycleState
import com.fileweft.domain.audit.AuditRecord
import com.fileweft.domain.audit.AuditRecordRepository
import com.fileweft.spi.authorization.AuthorizationDecision
import com.fileweft.spi.authorization.AuthorizationProvider
import com.fileweft.spi.authorization.AuthorizationRequest
import com.fileweft.spi.identity.UserIdentity
import com.fileweft.spi.identity.UserRealmProvider
import com.fileweft.spi.delivery.DeliveryRequirement
import com.fileweft.spi.tenant.TenantProvider
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals

class DocumentRetentionServiceTest {
    @Test
    fun `archives a published document with explicit archive authorization`() {
        val repository = InMemoryDocumentRepository(publishedDocument())
        val audits = RecordingAudits()
        var action: String? = null
        val service = ArchiveDocumentService(
            tenantProvider(), userProvider(), authorizationProvider { request ->
                action = request.action.name
                AuthorizationDecision(true)
            }, repository, DirectTransaction, auditTrail(audits),
        )

        val archived = service.archive(Identifier("document-1"))

        assertEquals(LifecycleState.HISTORY, archived.lifecycleState)
        assertEquals("document:archive", action)
        assertEquals(archived, repository.saved)
        assertEquals("document:archive", audits.records.single().action)
        assertEquals("保留管理员", audits.records.single().operatorName)
    }

    @Test
    fun `takes a published document offline through the existing lifecycle service`() {
        val repository = InMemoryDocumentRepository(publishedDocument())
        val audits = RecordingAudits()
        val deliveries = RecordingDeliveries(deliveredTarget())
        val outbox = RecordingOutbox()
        val service = OfflineDocumentService(
            tenantProvider(), userProvider(), authorizationProvider { AuthorizationDecision(true) }, repository, DirectTransaction, auditTrail(audits),
            DocumentDeliveryRemovalPlanner(deliveries, outbox, SequenceIds("removal-event"), Clock.fixed(Instant.ofEpochMilli(10), ZoneOffset.UTC)),
        )

        val offline = service.offline(Identifier("document-1"))

        assertEquals(LifecycleState.OFFLINE, offline.lifecycleState)
        assertEquals(offline, repository.saved)
        assertEquals("document:offline", audits.records.single().action)
        assertEquals("1", audits.records.single().details["downstreamRemovalCount"])
        assertEquals(DocumentDeliveryRemovalStatus.PENDING, deliveries.target?.removalStatus)
        assertEquals(DocumentDeliveryRemovalPlanner.DELIVERY_REMOVAL_REQUESTED_EVENT_TYPE, outbox.events.single().type)

        assertThrows<InvalidLifecycleTransitionException> { service.offline(Identifier("document-1")) }
        assertEquals(1, deliveries.saveCalls)
        assertEquals(1, outbox.events.size)
    }

    @Test
    fun `archives request downstream removal only once`() {
        val repository = InMemoryDocumentRepository(publishedDocument())
        val deliveries = RecordingDeliveries(deliveredTarget())
        val outbox = RecordingOutbox()
        val service = ArchiveDocumentService(
            tenantProvider(),
            userProvider(),
            authorizationProvider { AuthorizationDecision(true) },
            repository,
            DirectTransaction,
            removalPlanner = removalPlanner(deliveries, outbox),
        )

        service.archive(Identifier("document-1"))

        assertThrows<InvalidLifecycleTransitionException> { service.archive(Identifier("document-1")) }
        assertEquals(1, deliveries.saveCalls)
        assertEquals(1, outbox.events.size)
    }

    @Test
    fun `does not create retention state for a missing tenant scoped document`() {
        val service = ArchiveDocumentService(
            tenantProvider(), userProvider(), authorizationProvider { AuthorizationDecision(true) }, InMemoryDocumentRepository(null), DirectTransaction,
        )

        assertThrows<DocumentNotFoundException> { service.archive(Identifier("missing")) }
    }

    @Test
    fun `archive rejects foreign tenant and wrong id returned by the final document lock without side effects`() {
        finalIdentityMismatches(::publishedDocument).forEach { (case, lockedDocument) ->
            val persistedDocument = publishedDocument()
            val repository = InMemoryDocumentRepository(persistedDocument).apply {
                findForMutationOverride = { _, _ -> lockedDocument }
            }
            val audits = RecordingAudits()
            val deliveries = RecordingDeliveries(
                deliveredTarget(
                    tenantId = lockedDocument.tenantId,
                    documentId = lockedDocument.id,
                ),
            )
            val outbox = RecordingOutbox()
            val guard = CountingLifecycleGuard()
            val service = ArchiveDocumentService(
                tenantProvider(),
                userProvider(),
                authorizationProvider { AuthorizationDecision(true) },
                repository,
                DirectTransaction,
                auditTrail(audits),
                removalPlanner(deliveries, outbox),
            )

            assertThrows<DocumentNotFoundException>(case) {
                service.archive(Identifier("document-1"), guard)
            }

            assertEquals(LifecycleState.PUBLISHED, persistedDocument.lifecycleState, case)
            assertEquals(LifecycleState.PUBLISHED, lockedDocument.lifecycleState, case)
            assertEquals(0, repository.saveCalls, case)
            assertEquals(null, repository.saved, case)
            assertEquals(0, deliveries.saveCalls, case)
            assertEquals(emptyList(), audits.records, case)
            assertEquals(emptyList(), outbox.events, case)
            assertEquals(0, guard.verifyCalls, case)
        }
    }

    @Test
    fun `offline rejects foreign tenant and wrong id returned by the final document lock without side effects`() {
        finalIdentityMismatches(::publishedDocument).forEach { (case, lockedDocument) ->
            val persistedDocument = publishedDocument()
            val repository = InMemoryDocumentRepository(persistedDocument).apply {
                findForMutationOverride = { _, _ -> lockedDocument }
            }
            val audits = RecordingAudits()
            val deliveries = RecordingDeliveries(
                deliveredTarget(
                    tenantId = lockedDocument.tenantId,
                    documentId = lockedDocument.id,
                ),
            )
            val outbox = RecordingOutbox()
            val guard = CountingLifecycleGuard()
            val service = OfflineDocumentService(
                tenantProvider(),
                userProvider(),
                authorizationProvider { AuthorizationDecision(true) },
                repository,
                DirectTransaction,
                auditTrail(audits),
                removalPlanner(deliveries, outbox),
            )

            assertThrows<DocumentNotFoundException>(case) {
                service.offline(Identifier("document-1"), guard)
            }

            assertEquals(LifecycleState.PUBLISHED, persistedDocument.lifecycleState, case)
            assertEquals(LifecycleState.PUBLISHED, lockedDocument.lifecycleState, case)
            assertEquals(0, repository.saveCalls, case)
            assertEquals(null, repository.saved, case)
            assertEquals(0, deliveries.saveCalls, case)
            assertEquals(emptyList(), audits.records, case)
            assertEquals(emptyList(), outbox.events, case)
            assertEquals(0, guard.verifyCalls, case)
        }
    }

    @Test
    fun `restores an offline document only after its current delivery generation is withdrawn`() {
        val document = publishedDocument().also { it.transition(LifecycleCommand.OFFLINE) }
        val repository = InMemoryDocumentRepository(document)
        val audits = RecordingAudits()
        val service = RestoreOfflineDocumentService(
            tenantProvider(), userProvider(), authorizationProvider { AuthorizationDecision(true) }, repository,
            RecordingDeliveries(deliveredTarget(DocumentDeliveryRemovalStatus.SUCCEEDED)), DirectTransaction, auditTrail(audits),
        )

        val restored = service.restore(document.id)

        assertEquals(LifecycleState.DRAFT, restored.lifecycleState)
        assertEquals(1, restored.deliveryGeneration)
        assertEquals("document:restore", audits.records.single().action)
    }

    @Test
    fun `does not restore while a delivered target is still awaiting withdrawal`() {
        val document = publishedDocument().also { it.transition(LifecycleCommand.OFFLINE) }
        val service = RestoreOfflineDocumentService(
            tenantProvider(), userProvider(), authorizationProvider { AuthorizationDecision(true) }, InMemoryDocumentRepository(document),
            RecordingDeliveries(deliveredTarget(DocumentDeliveryRemovalStatus.PENDING)), DirectTransaction,
        )

        val conflict = assertThrows<DocumentRestoreConflictException> { service.restore(document.id) }

        assertEquals(DocumentRestoreConflictReason.WITHDRAWAL_INCOMPLETE, conflict.reason)
        assertEquals("Document delivery withdrawal is incomplete.", conflict.message)
    }

    @Test
    fun `restore reports active delivery states with a stable typed conflict`() {
        listOf(DocumentDeliveryStatus.PENDING, DocumentDeliveryStatus.RETRYING).forEach { status ->
            val document = publishedDocument().also { it.transition(LifecycleCommand.OFFLINE) }
            val service = RestoreOfflineDocumentService(
                tenantProvider(),
                userProvider(),
                authorizationProvider { AuthorizationDecision(true) },
                InMemoryDocumentRepository(document),
                RecordingDeliveries(deliveredTarget(status = status)),
                DirectTransaction,
            )

            val conflict = assertThrows<DocumentRestoreConflictException>(status.name) {
                service.restore(document.id)
            }

            assertEquals(DocumentRestoreConflictReason.DELIVERY_IN_PROGRESS, conflict.reason, status.name)
            assertEquals("Document delivery is still in progress.", conflict.message, status.name)
        }
    }

    @Test
    fun `restore reports every incomplete successful delivery withdrawal with one stable reason`() {
        listOf(
            DocumentDeliveryRemovalStatus.NOT_REQUESTED,
            DocumentDeliveryRemovalStatus.PENDING,
            DocumentDeliveryRemovalStatus.RETRYING,
            DocumentDeliveryRemovalStatus.FAILED,
        ).forEach { removalStatus ->
            val document = publishedDocument().also { it.transition(LifecycleCommand.OFFLINE) }
            val service = RestoreOfflineDocumentService(
                tenantProvider(),
                userProvider(),
                authorizationProvider { AuthorizationDecision(true) },
                InMemoryDocumentRepository(document),
                RecordingDeliveries(deliveredTarget(removalStatus = removalStatus)),
                DirectTransaction,
            )

            val conflict = assertThrows<DocumentRestoreConflictException>(removalStatus.name) {
                service.restore(document.id)
            }

            assertEquals(DocumentRestoreConflictReason.WITHDRAWAL_INCOMPLETE, conflict.reason, removalStatus.name)
            assertEquals("Document delivery withdrawal is incomplete.", conflict.message, removalStatus.name)
        }
    }

    @Test
    fun `restore permits a failed delivery because no downstream object was accepted`() {
        val document = publishedDocument().also { it.transition(LifecycleCommand.OFFLINE) }
        val service = RestoreOfflineDocumentService(
            tenantProvider(),
            userProvider(),
            authorizationProvider { AuthorizationDecision(true) },
            InMemoryDocumentRepository(document),
            RecordingDeliveries(deliveredTarget(status = DocumentDeliveryStatus.FAILED)),
            DirectTransaction,
        )

        assertEquals(LifecycleState.DRAFT, service.restore(document.id).lifecycleState)
    }

    @Test
    fun `restore rejects foreign tenant and wrong id returned by the final document lock without side effects`() {
        finalIdentityMismatches { id, tenantId ->
            publishedDocument(id, tenantId).also { it.transition(LifecycleCommand.OFFLINE) }
        }.forEach { (case, lockedDocument) ->
            val persistedDocument = publishedDocument().also { it.transition(LifecycleCommand.OFFLINE) }
            val repository = InMemoryDocumentRepository(persistedDocument).apply {
                findForMutationOverride = { _, _ -> lockedDocument }
            }
            val audits = RecordingAudits()
            val guard = CountingLifecycleGuard()
            val deliveries = RecordingDeliveries(
                deliveredTarget(
                    removalStatus = DocumentDeliveryRemovalStatus.SUCCEEDED,
                    tenantId = lockedDocument.tenantId,
                    documentId = lockedDocument.id,
                ),
            )
            val service = RestoreOfflineDocumentService(
                tenantProvider(),
                userProvider(),
                authorizationProvider { AuthorizationDecision(true) },
                repository,
                deliveries,
                DirectTransaction,
                auditTrail(audits),
            )

            assertThrows<DocumentNotFoundException>(case) {
                service.restore(Identifier("document-1"), guard)
            }

            assertEquals(LifecycleState.OFFLINE, persistedDocument.lifecycleState, case)
            assertEquals(LifecycleState.OFFLINE, lockedDocument.lifecycleState, case)
            assertEquals(0, repository.saveCalls, case)
            assertEquals(null, repository.saved, case)
            assertEquals(0, deliveries.saveCalls, case)
            assertEquals(emptyList(), audits.records, case)
            assertEquals(0, guard.verifyCalls, case)
        }
    }

    @Test
    fun `retention lifecycle services resolve the current user exactly once`() {
        val archiveUsers = CountingUserProvider()
        ArchiveDocumentService(
            tenantProvider(),
            archiveUsers,
            authorizationProvider { AuthorizationDecision(true) },
            InMemoryDocumentRepository(publishedDocument()),
            DirectTransaction,
        ).archive(Identifier("document-1"))
        assertEquals(1, archiveUsers.currentUserCalls, "archive")

        val offlineUsers = CountingUserProvider()
        OfflineDocumentService(
            tenantProvider(),
            offlineUsers,
            authorizationProvider { AuthorizationDecision(true) },
            InMemoryDocumentRepository(publishedDocument()),
            DirectTransaction,
        ).offline(Identifier("document-1"))
        assertEquals(1, offlineUsers.currentUserCalls, "offline")

        val restoreUsers = CountingUserProvider()
        val offlineDocument = publishedDocument().also { it.transition(LifecycleCommand.OFFLINE) }
        RestoreOfflineDocumentService(
            tenantProvider(),
            restoreUsers,
            authorizationProvider { AuthorizationDecision(true) },
            InMemoryDocumentRepository(offlineDocument),
            RecordingDeliveries(null),
            DirectTransaction,
        ).restore(offlineDocument.id)
        assertEquals(1, restoreUsers.currentUserCalls, "restore")
    }

    @Test
    fun `guarded retention lifecycle keeps catalog calls outside the transaction and preserves lock order`() {
        run {
            val events = mutableListOf<String>()
            val transaction = TrackingTransaction()
            val repository = InMemoryDocumentRepository(publishedDocument()).apply {
                onFindForMutation = { events += "document-lock" }
            }
            val deliveries = RecordingDeliveries(deliveredTarget()).apply {
                onFindByDocument = { events += "delivery-read" }
            }
            val service = ArchiveDocumentService(
                tenantProvider(), userProvider(), authorizationProvider { AuthorizationDecision(true) }, repository, transaction,
                removalPlanner = removalPlanner(deliveries, RecordingOutbox()),
            )

            service.archive(Identifier("document-1"), TransactionAwareLifecycleGuard(transaction, events))

            assertEquals(
                listOf("prepare", "revalidate", "document-lock", "verify", "delivery-read"),
                events,
                "archive",
            )
            assertEquals(1, transaction.calls, "archive")
        }

        run {
            val events = mutableListOf<String>()
            val transaction = TrackingTransaction()
            val repository = InMemoryDocumentRepository(publishedDocument()).apply {
                onFindForMutation = { events += "document-lock" }
            }
            val deliveries = RecordingDeliveries(deliveredTarget()).apply {
                onFindByDocument = { events += "delivery-read" }
            }
            val service = OfflineDocumentService(
                tenantProvider(), userProvider(), authorizationProvider { AuthorizationDecision(true) }, repository, transaction,
                removalPlanner = removalPlanner(deliveries, RecordingOutbox()),
            )

            service.offline(Identifier("document-1"), TransactionAwareLifecycleGuard(transaction, events))

            assertEquals(
                listOf("prepare", "revalidate", "document-lock", "verify", "delivery-read"),
                events,
                "offline",
            )
            assertEquals(1, transaction.calls, "offline")
        }

        run {
            val events = mutableListOf<String>()
            val transaction = TrackingTransaction()
            val document = publishedDocument().also { it.transition(LifecycleCommand.OFFLINE) }
            val repository = InMemoryDocumentRepository(document).apply {
                onFindForMutation = { events += "document-lock" }
            }
            val deliveries = RecordingDeliveries(deliveredTarget(DocumentDeliveryRemovalStatus.SUCCEEDED)).apply {
                onFindByDocument = { events += "delivery-read" }
            }
            val service = RestoreOfflineDocumentService(
                tenantProvider(), userProvider(), authorizationProvider { AuthorizationDecision(true) }, repository, deliveries, transaction,
            )

            service.restore(document.id, TransactionAwareLifecycleGuard(transaction, events))

            assertEquals(
                listOf("prepare", "revalidate", "document-lock", "verify", "delivery-read"),
                events,
                "restore",
            )
            assertEquals(1, transaction.calls, "restore")
        }
    }

    @Test
    fun `ambient retention mutations do not open a nested transaction`() {
        run {
            val transaction = TrackingTransaction()
            val service = ArchiveDocumentService(
                tenantProvider(), userProvider(), authorizationProvider { AuthorizationDecision(true) },
                InMemoryDocumentRepository(publishedDocument()), transaction,
            )
            val context = service.prepareArchive(Identifier("document-1"), null)
            val validated = context.revalidate()

            transaction.execute {
                DocumentLifecycleMutationTransaction.execute {
                    service.archiveInCurrentTransaction(validated)
                }
            }

            assertEquals(1, transaction.calls, "archive")
        }

        run {
            val transaction = TrackingTransaction()
            val service = OfflineDocumentService(
                tenantProvider(), userProvider(), authorizationProvider { AuthorizationDecision(true) },
                InMemoryDocumentRepository(publishedDocument()), transaction,
            )
            val context = service.prepareOffline(Identifier("document-1"), null)
            val validated = context.revalidate()

            transaction.execute {
                DocumentLifecycleMutationTransaction.execute {
                    service.offlineInCurrentTransaction(validated)
                }
            }

            assertEquals(1, transaction.calls, "offline")
        }

        run {
            val transaction = TrackingTransaction()
            val document = publishedDocument().also { it.transition(LifecycleCommand.OFFLINE) }
            val service = RestoreOfflineDocumentService(
                tenantProvider(), userProvider(), authorizationProvider { AuthorizationDecision(true) },
                InMemoryDocumentRepository(document), RecordingDeliveries(null), transaction,
            )
            val context = service.prepareRestore(document.id, null)
            val validated = context.revalidate()

            transaction.execute {
                DocumentLifecycleMutationTransaction.execute {
                    service.restoreInCurrentTransaction(validated)
                }
            }

            assertEquals(1, transaction.calls, "restore")
        }
    }

    @Test
    fun `ambient retention mutations require the lifecycle marker before repository access`() {
        val archiveRepository = InMemoryDocumentRepository(publishedDocument())
        val archive = ArchiveDocumentService(
            tenantProvider(), userProvider(), authorizationProvider { AuthorizationDecision(true) },
            archiveRepository, DirectTransaction,
        )
        val archiveToken = archive.prepareArchive(Identifier("document-1"), null).revalidate()
        assertThrows<IllegalStateException> {
            DirectTransaction.execute { archive.archiveInCurrentTransaction(archiveToken) }
        }
        assertEquals(0, archiveRepository.findForMutationCalls, "archive")

        val offlineRepository = InMemoryDocumentRepository(publishedDocument())
        val offline = OfflineDocumentService(
            tenantProvider(), userProvider(), authorizationProvider { AuthorizationDecision(true) },
            offlineRepository, DirectTransaction,
        )
        val offlineToken = offline.prepareOffline(Identifier("document-1"), null).revalidate()
        assertThrows<IllegalStateException> {
            DirectTransaction.execute { offline.offlineInCurrentTransaction(offlineToken) }
        }
        assertEquals(0, offlineRepository.findForMutationCalls, "offline")

        val restoreRepository = InMemoryDocumentRepository(
            publishedDocument().also { it.transition(LifecycleCommand.OFFLINE) },
        )
        val restore = RestoreOfflineDocumentService(
            tenantProvider(), userProvider(), authorizationProvider { AuthorizationDecision(true) },
            restoreRepository, RecordingDeliveries(null), DirectTransaction,
        )
        val restoreToken = restore.prepareRestore(Identifier("document-1"), null).revalidate()
        assertThrows<IllegalStateException> {
            DirectTransaction.execute { restore.restoreInCurrentTransaction(restoreToken) }
        }
        assertEquals(0, restoreRepository.findForMutationCalls, "restore")
    }

    @Test
    fun `ambient retention mutations reject a context for another action before repository access`() {
        val context = DocumentLifecycleMutationContext.prepare(
            tenantId = Identifier("tenant-1"),
            operator = UserIdentity(Identifier("user-1"), "保留管理员"),
            documentId = Identifier("document-1"),
            action = "document:other",
            guard = null,
        )
        val validated = context.revalidate()

        val archiveRepository = InMemoryDocumentRepository(publishedDocument())
        val archiveFailure = assertThrows<IllegalArgumentException> {
            DirectTransaction.execute {
                DocumentLifecycleMutationTransaction.execute {
                    ArchiveDocumentService(
                        tenantProvider(), userProvider(), authorizationProvider { AuthorizationDecision(true) },
                        archiveRepository, DirectTransaction,
                    ).archiveInCurrentTransaction(validated)
                }
            }
        }
        assertEquals("Lifecycle mutation context belongs to a different action.", archiveFailure.message)
        assertEquals(0, archiveRepository.findForMutationCalls, "archive")

        val offlineRepository = InMemoryDocumentRepository(publishedDocument())
        val offlineFailure = assertThrows<IllegalArgumentException> {
            DirectTransaction.execute {
                DocumentLifecycleMutationTransaction.execute {
                    OfflineDocumentService(
                        tenantProvider(), userProvider(), authorizationProvider { AuthorizationDecision(true) },
                        offlineRepository, DirectTransaction,
                    ).offlineInCurrentTransaction(validated)
                }
            }
        }
        assertEquals("Lifecycle mutation context belongs to a different action.", offlineFailure.message)
        assertEquals(0, offlineRepository.findForMutationCalls, "offline")

        val restoreRepository = InMemoryDocumentRepository(
            publishedDocument().also { it.transition(LifecycleCommand.OFFLINE) },
        )
        val restoreFailure = assertThrows<IllegalArgumentException> {
            DirectTransaction.execute {
                DocumentLifecycleMutationTransaction.execute {
                    RestoreOfflineDocumentService(
                        tenantProvider(), userProvider(), authorizationProvider { AuthorizationDecision(true) },
                        restoreRepository, RecordingDeliveries(null), DirectTransaction,
                    ).restoreInCurrentTransaction(validated)
                }
            }
        }
        assertEquals("Lifecycle mutation context belongs to a different action.", restoreFailure.message)
        assertEquals(0, restoreRepository.findForMutationCalls, "restore")
    }

    private fun publishedDocument(
        id: Identifier = Identifier("document-1"),
        tenantId: Identifier = Identifier("tenant-1"),
    ): Document = Document(
        id = id,
        tenantId = tenantId,
        assetId = Identifier("asset-1"),
        documentNumber = "DOC-001",
        title = "Contract",
        versions = listOf(DocumentVersion(Identifier("version-1"), tenantId, id, "1.0", Identifier("file-1"))),
        currentVersionId = Identifier("version-1"),
    ).also {
        it.transition(LifecycleCommand.SUBMIT)
        it.transition(LifecycleCommand.APPROVE)
        it.transition(LifecycleCommand.PUBLISH_SUCCEEDED)
    }

    private fun deliveredTarget(
        removalStatus: DocumentDeliveryRemovalStatus = DocumentDeliveryRemovalStatus.NOT_REQUESTED,
        status: DocumentDeliveryStatus = DocumentDeliveryStatus.SUCCEEDED,
        tenantId: Identifier = Identifier("tenant-1"),
        documentId: Identifier = Identifier("document-1"),
    ) = DocumentDeliveryTarget(
        id = Identifier("delivery-1"),
        tenantId = tenantId,
        documentId = documentId,
        profileId = "regulated",
        targetId = "archive",
        displayName = "Archive",
        connectorId = "archive-connector",
        requirement = DeliveryRequirement.REQUIRED,
        status = status,
        externalId = "archive:tenant-1:document-1",
        deliveryGeneration = 1,
        removalStatus = removalStatus,
    ).restoreDispatch(
        DeliveryDispatchFence(
            Identifier(if (removalStatus == DocumentDeliveryRemovalStatus.NOT_REQUESTED) "delivery-event" else "removal-event"),
            if (removalStatus == DocumentDeliveryRemovalStatus.NOT_REQUESTED) {
                DeliveryDispatchOperation.DELIVERY
            } else {
                DeliveryDispatchOperation.REMOVAL
            },
            if (removalStatus == DocumentDeliveryRemovalStatus.NOT_REQUESTED) 1 else 2,
        ),
    )

    private fun finalIdentityMismatches(factory: (Identifier, Identifier) -> Document): List<Pair<String, Document>> = listOf(
        "foreign tenant" to factory(Identifier("document-1"), Identifier("tenant-foreign")),
        "wrong document id" to factory(Identifier("document-wrong"), Identifier("tenant-1")),
    )

    private fun tenantProvider(): TenantProvider = object : TenantProvider {
        override fun currentTenant(): TenantContext = TenantContext(Identifier("tenant-1"))
    }

    private fun userProvider(): UserRealmProvider = object : UserRealmProvider {
        override fun currentUser(): UserIdentity = UserIdentity(Identifier("user-1"), "保留管理员")
        override fun findUser(userId: Identifier): UserIdentity? = null
    }

    private class CountingUserProvider : UserRealmProvider {
        var currentUserCalls: Int = 0
            private set

        override fun currentUser(): UserIdentity {
            currentUserCalls++
            return UserIdentity(Identifier("user-1"), "保留管理员")
        }

        override fun findUser(userId: Identifier): UserIdentity? = null
    }

    private fun authorizationProvider(authorizer: (AuthorizationRequest) -> AuthorizationDecision): AuthorizationProvider =
        object : AuthorizationProvider {
            override fun authorize(request: AuthorizationRequest): AuthorizationDecision = authorizer(request)
        }

    private class InMemoryDocumentRepository(
        private var document: Document?,
    ) : DocumentRepository {
        var saved: Document? = null
        var saveCalls: Int = 0
            private set
        var findForMutationCalls: Int = 0
            private set
        var findForMutationOverride: ((Identifier, Identifier) -> Document?)? = null
        var onFindForMutation: (() -> Unit)? = null

        override fun findById(tenantId: Identifier, documentId: Identifier): Document? =
            document?.takeIf { it.tenantId == tenantId && it.id == documentId }

        override fun findForMutation(tenantId: Identifier, documentId: Identifier): Document? {
            findForMutationCalls++
            onFindForMutation?.invoke()
            findForMutationOverride?.let { provider -> return provider(tenantId, documentId) }
            return findById(tenantId, documentId)
        }

        override fun save(document: Document) {
            saveCalls++
            this.document = document
            saved = document
        }
    }

    private object DirectTransaction : ApplicationTransaction {
        override fun <T> execute(action: () -> T): T = action()
    }

    private class TrackingTransaction : ApplicationTransaction {
        var active: Boolean = false
            private set
        var calls: Int = 0
            private set

        override fun <T> execute(action: () -> T): T {
            check(!active) { "Nested transaction is not allowed in this test." }
            calls++
            active = true
            return try {
                action()
            } finally {
                active = false
            }
        }
    }

    private fun auditTrail(repository: RecordingAudits) = AuditTrail(
        repository,
        object : com.fileweft.core.id.IdentifierGenerator { override fun nextId(): Identifier = Identifier("audit-1") },
        Clock.fixed(Instant.ofEpochMilli(10), ZoneOffset.UTC),
    )

    private class RecordingAudits : AuditRecordRepository {
        val records = mutableListOf<AuditRecord>()
        override fun append(record: AuditRecord) { records += record }
        override fun findByResource(tenantId: Identifier, resourceType: String, resourceId: Identifier, limit: Int): List<AuditRecord> = emptyList()
    }

    private class RecordingDeliveries(var target: DocumentDeliveryTarget?) : DocumentDeliveryTargetRepository {
        var saveCalls: Int = 0
            private set
        var onFindByDocument: (() -> Unit)? = null

        override fun findById(tenantId: Identifier, deliveryId: Identifier): DocumentDeliveryTarget? =
            target?.takeIf { it.tenantId == tenantId && it.id == deliveryId }

        override fun findByDocument(tenantId: Identifier, documentId: Identifier): List<DocumentDeliveryTarget> {
            onFindByDocument?.invoke()
            return target?.takeIf { it.tenantId == tenantId && it.documentId == documentId }?.let(::listOf) ?: emptyList()
        }

        override fun save(target: DocumentDeliveryTarget) {
            saveCalls++
            this.target = target
        }
    }

    private class RecordingOutbox : OutboxEventRepository {
        val events = mutableListOf<OutboxEvent>()
        override fun append(event: OutboxEvent) {
            events += event
        }
    }

    private class SequenceIds(vararg values: String) : IdentifierGenerator {
        private val ids = ArrayDeque(values.toList())
        override fun nextId(): Identifier = Identifier(ids.removeFirst())
    }

    private class CountingLifecycleGuard : DocumentLifecycleMutationGuard {
        var verifyCalls: Int = 0
            private set

        override fun prepareLifecycle(
            tenantId: Identifier,
            operator: UserIdentity,
            documentId: Identifier,
            actionName: String,
        ): DocumentLifecycleMutationPermit {
            check(operator.id == Identifier("user-1"))
            return TestLifecyclePermit
        }

        override fun revalidateLifecycle(
            tenantId: Identifier,
            operator: UserIdentity,
            documentId: Identifier,
            permit: DocumentLifecycleMutationPermit,
        ) {
            check(operator.id == Identifier("user-1"))
        }

        override fun verifyLifecycleLocked(
            tenantId: Identifier,
            document: Document,
            permit: DocumentLifecycleMutationPermit,
        ) {
            verifyCalls++
        }
    }

    private class TransactionAwareLifecycleGuard(
        private val transaction: TrackingTransaction,
        private val events: MutableList<String>,
    ) : DocumentLifecycleMutationGuard {
        override fun prepareLifecycle(
            tenantId: Identifier,
            operator: UserIdentity,
            documentId: Identifier,
            actionName: String,
        ): DocumentLifecycleMutationPermit {
            check(!transaction.active) { "Catalog preparation must be outside the transaction." }
            check(operator.id == Identifier("user-1"))
            events += "prepare"
            return TestLifecyclePermit
        }

        override fun revalidateLifecycle(
            tenantId: Identifier,
            operator: UserIdentity,
            documentId: Identifier,
            permit: DocumentLifecycleMutationPermit,
        ) {
            check(!transaction.active) { "Catalog revalidation must be outside the transaction." }
            check(operator.id == Identifier("user-1"))
            events += "revalidate"
        }

        override fun verifyLifecycleLocked(
            tenantId: Identifier,
            document: Document,
            permit: DocumentLifecycleMutationPermit,
        ) {
            check(transaction.active) { "Locked catalog verification must be inside the transaction." }
            events += "verify"
        }
    }

    private object TestLifecyclePermit : DocumentLifecycleMutationPermit

    private fun removalPlanner(
        deliveries: RecordingDeliveries,
        outbox: RecordingOutbox,
    ) = DocumentDeliveryRemovalPlanner(
        deliveries,
        outbox,
        SequenceIds("removal-event"),
        Clock.fixed(Instant.ofEpochMilli(10), ZoneOffset.UTC),
    )
}
