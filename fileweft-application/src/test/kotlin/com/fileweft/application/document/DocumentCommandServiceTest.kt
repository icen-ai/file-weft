package com.fileweft.application.document

import com.fileweft.application.audit.AuditTrail
import com.fileweft.application.catalog.DocumentLifecycleMutationGuard
import com.fileweft.application.catalog.DocumentLifecycleMutationPermit
import com.fileweft.application.lifecycle.DocumentLifecycleMutationContext
import com.fileweft.application.lifecycle.DocumentLifecycleMutationTransaction
import com.fileweft.application.transaction.ApplicationTransaction
import com.fileweft.core.context.TenantContext
import com.fileweft.core.id.Identifier
import com.fileweft.core.id.IdentifierGenerator
import com.fileweft.domain.audit.AuditRecord
import com.fileweft.domain.audit.AuditRecordRepository
import com.fileweft.domain.document.Document
import com.fileweft.domain.document.DocumentRepository
import com.fileweft.domain.document.DocumentVersion
import com.fileweft.domain.document.LifecycleCommand
import com.fileweft.domain.document.LifecycleState
import com.fileweft.spi.authorization.AuthorizationDecision
import com.fileweft.spi.authorization.AuthorizationProvider
import com.fileweft.spi.authorization.AuthorizationRequest
import com.fileweft.spi.identity.UserIdentity
import com.fileweft.spi.identity.UserRealmProvider
import com.fileweft.spi.tenant.TenantProvider
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DocumentCommandServiceTest {
    @Test
    fun `uses one authorized user snapshot when revising and auditing`() {
        val document = rejectedDocument()
        val audits = RecordingAudits()
        val users = object : UserRealmProvider {
            var currentUserCalls: Int = 0

            override fun currentUser(): UserIdentity {
                currentUserCalls++
                return if (currentUserCalls == 1) {
                    UserIdentity(Identifier("10001"), "外部编辑者")
                } else {
                    UserIdentity(Identifier("unexpected-user"), "错误快照")
                }
            }

            override fun findUser(userId: Identifier): UserIdentity? = null
        }
        var authorizedOperator: Identifier? = null
        val service = DocumentCommandService(
            tenantProvider = object : TenantProvider { override fun currentTenant() = TenantContext(Identifier("tenant-1")) },
            userRealmProvider = users,
            authorizationProvider = object : AuthorizationProvider {
                override fun authorize(request: AuthorizationRequest): AuthorizationDecision {
                    authorizedOperator = request.subject.id
                    return AuthorizationDecision(true)
                }
            },
            documentRepository = InMemoryDocuments(document),
            transaction = DirectTransaction,
            auditTrail = AuditTrail(
                audits,
                object : IdentifierGenerator { override fun nextId(): Identifier = Identifier("audit-1") },
                Clock.fixed(Instant.ofEpochMilli(10), ZoneOffset.UTC),
            ),
        )

        val revised = service.revise(document.id)

        assertEquals(LifecycleState.DRAFT, revised.lifecycleState)
        assertEquals("document:revise", audits.records.single().action)
        assertEquals("10001", audits.records.single().operatorId?.value)
        assertEquals("外部编辑者", audits.records.single().operatorName)
        assertEquals(Identifier("10001"), authorizedOperator)
        assertEquals(1, users.currentUserCalls)
    }

    @Test
    fun `preserves public submit reject and revise lifecycle semantics`() {
        val cases = listOf(
            LegacyCommandCase(
                document = draftDocument(),
                expectedState = LifecycleState.PENDING_REVIEW,
                expectedAction = "document:submit",
                invoke = { service, documentId -> service.submit(documentId) },
            ),
            LegacyCommandCase(
                document = pendingReviewDocument(),
                expectedState = LifecycleState.REJECTED,
                expectedAction = "document:reject",
                invoke = { service, documentId -> service.reject(documentId) },
            ),
            LegacyCommandCase(
                document = rejectedDocument(),
                expectedState = LifecycleState.DRAFT,
                expectedAction = "document:revise",
                invoke = { service, documentId -> service.revise(documentId) },
            ),
        )

        cases.forEach { case ->
            val documents = InMemoryDocuments(case.document)
            val audits = RecordingAudits()
            val transaction = CountingTransaction()
            val service = service(documents, transaction, audits)

            val result = case.invoke(service, case.document.id)

            assertEquals(case.expectedState, result.lifecycleState, case.expectedAction)
            assertEquals(1, documents.saveCalls, case.expectedAction)
            assertEquals(1, transaction.calls, case.expectedAction)
            assertEquals(case.expectedAction, audits.records.single().action)
        }
    }

    @Test
    fun `guarded legacy revise keeps external preparation and revalidation outside the final transaction`() {
        val fixture = orderedFixture(rejectedDocument())

        val revised = fixture.service.revise(fixture.document.id, fixture.guard)

        assertEquals(LifecycleState.DRAFT, revised.lifecycleState)
        assertEquals(1, fixture.transaction.calls)
        assertEquals(expectedGuardedOrder(), fixture.events)
    }

    @Test
    fun `prepared revise mutation reuses one ambient transaction without opening another`() {
        val fixture = orderedFixture(rejectedDocument())

        val context = fixture.service.prepareRevise(fixture.document.id, fixture.guard)
        assertEquals(0, fixture.transaction.calls)
        assertFalse(fixture.transaction.active)
        val validated = context.revalidate()
        assertEquals(0, fixture.transaction.calls)
        assertFalse(fixture.transaction.active)

        val revised = fixture.transaction.execute {
            DocumentLifecycleMutationTransaction.execute {
                fixture.service.reviseInCurrentTransaction(validated)
            }
        }

        assertEquals(LifecycleState.DRAFT, revised.lifecycleState)
        assertEquals(1, fixture.transaction.calls)
        assertEquals(expectedGuardedOrder(), fixture.events)
    }

    @Test
    fun `ambient revise requires the lifecycle final transaction marker before repository access`() {
        val fixture = orderedFixture(rejectedDocument())
        val context = fixture.service.prepareRevise(fixture.document.id, fixture.guard)
        val validated = context.revalidate()

        val failure = assertFailsWith<IllegalStateException> {
            fixture.transaction.execute {
                fixture.service.reviseInCurrentTransaction(validated)
            }
        }

        assertEquals(
            "Lifecycle ambient mutation requires the caller's active final transaction.",
            failure.message,
        )
        assertEquals(LifecycleState.REJECTED, fixture.document.lifecycleState)
        assertFalse(fixture.events.contains("document:lock"))
        assertFalse(fixture.events.contains("document:save"))
    }

    @Test
    fun `ambient revise rejects a context for another action before locking or writing`() {
        val document = rejectedDocument()
        val documents = InMemoryDocuments(document)
        val audits = RecordingAudits()
        val transaction = CountingTransaction()
        val service = service(documents, transaction, audits)
        val wrongAction = DocumentLifecycleMutationContext.prepare(
            tenantId = Identifier("tenant-1"),
            operator = UserIdentity(Identifier("10001"), "外部编辑者"),
            documentId = document.id,
            action = "document:submit",
            guard = null,
        )
        val validated = wrongAction.revalidate()

        assertFailsWith<IllegalArgumentException> {
            transaction.execute {
                DocumentLifecycleMutationTransaction.execute {
                    service.reviseInCurrentTransaction(validated)
                }
            }
        }

        assertEquals(0, documents.mutationReads)
        assertEquals(0, documents.saveCalls)
        assertEquals(emptyList(), audits.records)
        assertEquals(LifecycleState.REJECTED, document.lifecycleState)
    }

    @Test
    fun `guard identity mismatch fails before the final transaction and repository access`() {
        val document = rejectedDocument()
        val documents = InMemoryDocuments(document)
        val transaction = CountingTransaction()
        val service = service(documents, transaction, RecordingAudits())
        val guard = IdentityMismatchGuard()

        val failure = assertFailsWith<IllegalArgumentException> {
            service.revise(document.id, guard)
        }

        assertEquals("Lifecycle guard operator identity changed between phases.", failure.message)
        assertEquals(Identifier("10001"), guard.preparedOperatorId)
        assertEquals(0, transaction.calls)
        assertEquals(0, documents.mutationReads)
        assertEquals(0, documents.saveCalls)
        assertEquals(LifecycleState.REJECTED, document.lifecycleState)
    }

    @Test
    fun `rejects foreign tenant and wrong id documents returned by the mutation repository without side effects`() {
        val requestedDocumentId = Identifier("document-1")
        val maliciousDocuments = listOf(
            rejectedDocument(tenantId = Identifier("tenant-2")),
            rejectedDocument(documentId = Identifier("document-2")),
        )

        maliciousDocuments.forEach { maliciousDocument ->
            val documents = MaliciousMutationDocuments(maliciousDocument)
            val audits = RecordingAudits()
            val service = DocumentCommandService(
                tenantProvider = object : TenantProvider {
                    override fun currentTenant() = TenantContext(Identifier("tenant-1"))
                },
                userRealmProvider = object : UserRealmProvider {
                    override fun currentUser() = UserIdentity(Identifier("10001"), "外部编辑者")
                    override fun findUser(userId: Identifier): UserIdentity? = null
                },
                authorizationProvider = object : AuthorizationProvider {
                    override fun authorize(request: AuthorizationRequest) = AuthorizationDecision(true)
                },
                documentRepository = documents,
                transaction = DirectTransaction,
                auditTrail = AuditTrail(
                    audits,
                    object : IdentifierGenerator { override fun nextId(): Identifier = Identifier("audit-1") },
                    Clock.fixed(Instant.ofEpochMilli(10), ZoneOffset.UTC),
                ),
            )
            val originalState = maliciousDocument.lifecycleState

            assertFailsWith<DocumentNotFoundException> {
                service.revise(requestedDocumentId)
            }

            assertEquals(originalState, maliciousDocument.lifecycleState)
            assertEquals(0, documents.saveCalls)
            assertEquals(emptyList(), audits.records)
        }
    }

    private fun draftDocument(
        documentId: Identifier = Identifier("document-1"),
        tenantId: Identifier = Identifier("tenant-1"),
    ) = Document(
        documentId, tenantId, Identifier("asset-1"), "DOC-001", "Contract",
        versions = listOf(DocumentVersion(Identifier("version-1"), tenantId, documentId, "1.0", Identifier("file-1"))),
        currentVersionId = Identifier("version-1"),
    )

    private fun pendingReviewDocument(
        documentId: Identifier = Identifier("document-1"),
        tenantId: Identifier = Identifier("tenant-1"),
    ) = draftDocument(documentId, tenantId).also { it.transition(LifecycleCommand.SUBMIT) }

    private fun rejectedDocument(
        documentId: Identifier = Identifier("document-1"),
        tenantId: Identifier = Identifier("tenant-1"),
    ) = pendingReviewDocument(documentId, tenantId).also { it.transition(LifecycleCommand.REJECT) }

    private fun service(
        documents: DocumentRepository,
        transaction: ApplicationTransaction,
        audits: RecordingAudits,
    ) = DocumentCommandService(
        tenantProvider = object : TenantProvider {
            override fun currentTenant() = TenantContext(Identifier("tenant-1"))
        },
        userRealmProvider = object : UserRealmProvider {
            override fun currentUser() = UserIdentity(Identifier("10001"), "外部编辑者")
            override fun findUser(userId: Identifier): UserIdentity? = null
        },
        authorizationProvider = object : AuthorizationProvider {
            override fun authorize(request: AuthorizationRequest) = AuthorizationDecision(true)
        },
        documentRepository = documents,
        transaction = transaction,
        auditTrail = auditTrail(audits),
    )

    private fun auditTrail(audits: RecordingAudits) = AuditTrail(
        audits,
        object : IdentifierGenerator { override fun nextId(): Identifier = Identifier("audit-1") },
        Clock.fixed(Instant.ofEpochMilli(10), ZoneOffset.UTC),
    )

    private fun orderedFixture(document: Document): OrderedFixture {
        val events = mutableListOf<String>()
        val transaction = RecordingTransaction(events)
        val documents = OrderedDocuments(document, transaction, events)
        val audits = RecordingAudits(events, transaction)
        val guard = OrderedGuard(events, transaction)
        val service = DocumentCommandService(
            tenantProvider = object : TenantProvider {
                override fun currentTenant(): TenantContext {
                    assertFalse(transaction.active)
                    events += "tenant"
                    return TenantContext(Identifier("tenant-1"))
                }
            },
            userRealmProvider = object : UserRealmProvider {
                override fun currentUser(): UserIdentity {
                    assertFalse(transaction.active)
                    events += "user"
                    return UserIdentity(Identifier("10001"), "外部编辑者")
                }

                override fun findUser(userId: Identifier): UserIdentity? = null
            },
            authorizationProvider = object : AuthorizationProvider {
                override fun authorize(request: AuthorizationRequest): AuthorizationDecision {
                    assertFalse(transaction.active)
                    events += "authorize"
                    return AuthorizationDecision(true)
                }
            },
            documentRepository = documents,
            transaction = transaction,
            auditTrail = auditTrail(audits),
        )
        return OrderedFixture(document, events, transaction, guard, service)
    }

    private fun expectedGuardedOrder(): List<String> = listOf(
        "tenant",
        "user",
        "authorize",
        "guard:prepare",
        "guard:revalidate",
        "tx:start",
        "document:lock",
        "guard:verify",
        "document:save",
        "audit",
        "tx:commit",
    )

    private class InMemoryDocuments(private var document: Document) : DocumentRepository {
        var mutationReads: Int = 0
            private set
        var saveCalls: Int = 0
            private set

        override fun findById(tenantId: Identifier, documentId: Identifier): Document? =
            document.takeIf { it.tenantId == tenantId && it.id == documentId }

        override fun findForMutation(tenantId: Identifier, documentId: Identifier): Document? {
            mutationReads++
            return findById(tenantId, documentId)
        }

        override fun save(document: Document) {
            saveCalls++
            this.document = document
        }
    }

    private class MaliciousMutationDocuments(
        private val maliciousDocument: Document,
    ) : DocumentRepository {
        var saveCalls: Int = 0

        override fun findById(tenantId: Identifier, documentId: Identifier): Document? = null

        override fun findForMutation(tenantId: Identifier, documentId: Identifier): Document = maliciousDocument

        override fun save(document: Document) {
            saveCalls += 1
        }
    }

    private class OrderedDocuments(
        private var document: Document,
        private val transaction: RecordingTransaction,
        private val events: MutableList<String>,
    ) : DocumentRepository {
        override fun findById(tenantId: Identifier, documentId: Identifier): Document? =
            document.takeIf { it.tenantId == tenantId && it.id == documentId }

        override fun findForMutation(tenantId: Identifier, documentId: Identifier): Document? {
            assertTrue(transaction.active)
            events += "document:lock"
            return findById(tenantId, documentId)
        }

        override fun save(document: Document) {
            assertTrue(transaction.active)
            events += "document:save"
            this.document = document
        }
    }

    private class OrderedGuard(
        private val events: MutableList<String>,
        private val transaction: RecordingTransaction,
    ) : DocumentLifecycleMutationGuard {
        override fun prepareLifecycle(
            tenantId: Identifier,
            operator: UserIdentity,
            documentId: Identifier,
            actionName: String,
        ): DocumentLifecycleMutationPermit {
            assertFalse(transaction.active)
            assertEquals(Identifier("10001"), operator.id)
            events += "guard:prepare"
            return OrderedPermit
        }

        override fun revalidateLifecycle(
            tenantId: Identifier,
            operator: UserIdentity,
            documentId: Identifier,
            permit: DocumentLifecycleMutationPermit,
        ) {
            assertFalse(transaction.active)
            assertEquals(Identifier("10001"), operator.id)
            assertTrue(permit === OrderedPermit)
            events += "guard:revalidate"
        }

        override fun verifyLifecycleLocked(
            tenantId: Identifier,
            document: Document,
            permit: DocumentLifecycleMutationPermit,
        ) {
            assertTrue(transaction.active)
            assertTrue(permit === OrderedPermit)
            events += "guard:verify"
        }
    }

    private object OrderedPermit : DocumentLifecycleMutationPermit

    private class IdentityMismatchGuard : DocumentLifecycleMutationGuard {
        var preparedOperatorId: Identifier? = null
            private set

        override fun prepareLifecycle(
            tenantId: Identifier,
            operator: UserIdentity,
            documentId: Identifier,
            actionName: String,
        ): DocumentLifecycleMutationPermit {
            preparedOperatorId = operator.id
            return IdentityPermit(Identifier("different-operator"))
        }

        override fun revalidateLifecycle(
            tenantId: Identifier,
            operator: UserIdentity,
            documentId: Identifier,
            permit: DocumentLifecycleMutationPermit,
        ) {
            val identityPermit = permit as IdentityPermit
            require(identityPermit.operatorId == operator.id) {
                "Lifecycle guard operator identity changed between phases."
            }
        }

        override fun verifyLifecycleLocked(
            tenantId: Identifier,
            document: Document,
            permit: DocumentLifecycleMutationPermit,
        ) = error("Identity mismatch must fail before locked verification.")
    }

    private class IdentityPermit(val operatorId: Identifier) : DocumentLifecycleMutationPermit

    private class RecordingAudits(
        private val events: MutableList<String>? = null,
        private val transaction: RecordingTransaction? = null,
    ) : AuditRecordRepository {
        val records = mutableListOf<AuditRecord>()
        override fun append(record: AuditRecord) {
            transaction?.let { assertTrue(it.active) }
            events?.add("audit")
            records += record
        }
        override fun findByResource(tenantId: Identifier, resourceType: String, resourceId: Identifier, limit: Int): List<AuditRecord> = emptyList()
    }

    private class CountingTransaction : ApplicationTransaction {
        var calls: Int = 0
            private set

        override fun <T> execute(action: () -> T): T {
            calls++
            return action()
        }
    }

    private class RecordingTransaction(
        private val events: MutableList<String>,
    ) : ApplicationTransaction {
        var calls: Int = 0
            private set
        var active: Boolean = false
            private set

        override fun <T> execute(action: () -> T): T {
            calls++
            check(!active) { "Document command opened a nested transaction." }
            events += "tx:start"
            active = true
            return try {
                action().also { events += "tx:commit" }
            } catch (failure: Throwable) {
                events += "tx:rollback"
                throw failure
            } finally {
                active = false
            }
        }
    }

    private class LegacyCommandCase(
        val document: Document,
        val expectedState: LifecycleState,
        val expectedAction: String,
        val invoke: (DocumentCommandService, Identifier) -> Document,
    )

    private class OrderedFixture(
        val document: Document,
        val events: MutableList<String>,
        val transaction: RecordingTransaction,
        val guard: OrderedGuard,
        val service: DocumentCommandService,
    )

    private object DirectTransaction : ApplicationTransaction {
        override fun <T> execute(action: () -> T): T = action()
    }
}
