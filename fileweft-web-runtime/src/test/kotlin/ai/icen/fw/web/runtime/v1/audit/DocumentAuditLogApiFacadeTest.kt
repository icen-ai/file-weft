package ai.icen.fw.web.runtime.v1.audit

import ai.icen.fw.application.audit.DocumentAuditLogPageCursor
import ai.icen.fw.application.audit.DocumentAuditLogPageRequest
import ai.icen.fw.application.audit.DocumentAuditLogPageResult
import ai.icen.fw.application.audit.DocumentAuditLogQueryRepository
import ai.icen.fw.application.audit.DocumentAuditLogQueryService
import ai.icen.fw.application.audit.DocumentAuditLogView
import ai.icen.fw.application.document.DocumentFolderReadScope
import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.core.context.TenantContext
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.authorization.AuthorizationDecision
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.authorization.AuthorizationRequest
import ai.icen.fw.spi.identity.UserIdentity
import ai.icen.fw.spi.identity.UserRealmProvider
import ai.icen.fw.spi.tenant.TenantProvider
import ai.icen.fw.web.api.v1.audit.DocumentAuditLogPageQuery
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DocumentAuditLogApiFacadeTest {
    @Test
    fun `maps a redacted page and round trips the opaque cursor`() {
        val repository = PagingRepository()
        val facade = facade(repository)

        val first = facade.page("document-a", DocumentAuditLogPageQuery(limit = 1))
        val second = facade.page("document-a", DocumentAuditLogPageQuery(first.nextCursor, 1))

        assertEquals(listOf("audit-b"), first.items.map { item -> item.id })
        assertEquals("long-external-user-id", first.items.single().operatorId)
        assertEquals("审核员乙", first.items.single().operatorName)
        assertEquals("trace-b", first.items.single().traceId)
        assertEquals(listOf("audit-a"), second.items.map { item -> item.id })
        assertNull(second.nextCursor)
        assertEquals("audit-b", repository.requests[1].cursor?.id?.value)
        assertEquals(200, repository.requests[1].cursor?.createdTime)
    }

    @Test
    fun `rejects malformed or wrong-kind cursors before persistence`() {
        val repository = PagingRepository()
        val facade = facade(repository)

        assertThrows<IllegalArgumentException> {
            facade.page("document-a", DocumentAuditLogPageQuery("not-valid", 20))
        }
        assertThrows<IllegalArgumentException> {
            facade.page("document-a", DocumentAuditLogPageQuery("AQMAAgBh", 20))
        }
        assertEquals(0, repository.requests.size)
    }

    private fun facade(repository: DocumentAuditLogQueryRepository): DocumentAuditLogApiFacade =
        DocumentAuditLogApiFacade(
            DocumentAuditLogQueryService(
                tenantProvider = object : TenantProvider {
                    override fun currentTenant(): TenantContext = TenantContext(Identifier("tenant-a"))
                },
                userRealmProvider = object : UserRealmProvider {
                    override fun currentUser(): UserIdentity = UserIdentity(Identifier("reviewer-a"), "Reviewer A")
                    override fun findUser(userId: Identifier): UserIdentity? = null
                },
                authorizationProvider = object : AuthorizationProvider {
                    override fun authorize(request: AuthorizationRequest): AuthorizationDecision = AuthorizationDecision(true)
                },
                queries = repository,
                transaction = object : ApplicationTransaction {
                    override fun <T> execute(action: () -> T): T = action()
                },
            ),
        )

    private class PagingRepository : DocumentAuditLogQueryRepository {
        val requests = mutableListOf<DocumentAuditLogPageRequest>()

        override fun findPage(
            tenantId: Identifier,
            documentId: Identifier,
            request: DocumentAuditLogPageRequest,
            folderReadScope: DocumentFolderReadScope?,
        ): DocumentAuditLogPageResult {
            requests += request
            return if (request.cursor == null) {
                val item = log("audit-b", 200, "trace-b")
                DocumentAuditLogPageResult(documentId, listOf(item), DocumentAuditLogPageCursor(item.createdTime, item.id))
            } else {
                DocumentAuditLogPageResult(documentId, listOf(log("audit-a", 100, null)))
            }
        }

        private fun log(id: String, time: Long, trace: String?): DocumentAuditLogView = DocumentAuditLogView(
            id = Identifier(id),
            action = "document:create",
            createdTime = time,
            operatorId = Identifier("long-external-user-id"),
            operatorName = "审核员乙",
            traceId = trace?.let(::Identifier),
        )
    }
}
