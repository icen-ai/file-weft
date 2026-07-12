package ai.icen.fw.web.runtime.v1.document

import ai.icen.fw.application.delivery.DocumentDeliveryRemovalStatus
import ai.icen.fw.application.delivery.DocumentDeliveryStatus
import ai.icen.fw.application.delivery.DocumentDeliveryStatusView
import ai.icen.fw.application.delivery.DocumentSyncStatusQueryRepository
import ai.icen.fw.application.delivery.DocumentSyncStatusQueryService
import ai.icen.fw.application.delivery.DocumentSyncStatusView
import ai.icen.fw.application.document.DocumentFolderReadScope
import ai.icen.fw.application.transaction.ApplicationTransaction
import ai.icen.fw.core.context.TenantContext
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.authorization.AuthorizationDecision
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.authorization.AuthorizationRequest
import ai.icen.fw.spi.delivery.DeliveryRequirement
import ai.icen.fw.spi.identity.UserIdentity
import ai.icen.fw.spi.identity.UserRealmProvider
import ai.icen.fw.spi.tenant.TenantProvider
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DocumentSyncStatusApiFacadeTest {
    @Test
    fun `maps every redacted application status field without exposing repository context`() {
        val repository = RecordingQueries(
            DocumentSyncStatusView(
                Identifier("document-1"),
                listOf(
                    DocumentDeliveryStatusView(
                        deliveryId = Identifier("delivery-primary"),
                        targetId = "archive-primary",
                        displayName = "Primary archive",
                        requirement = DeliveryRequirement.REQUIRED,
                        deliveryStatus = DocumentDeliveryStatus.FAILED,
                        deliveryRetryCount = 3,
                        removalStatus = DocumentDeliveryRemovalStatus.NOT_REQUESTED,
                        removalRetryCount = 0,
                        deliveryRetryable = true,
                        removalRetryable = false,
                        updatedTime = 101,
                    ),
                    DocumentDeliveryStatusView(
                        deliveryId = Identifier("delivery-search"),
                        targetId = "search-index",
                        displayName = "Search index",
                        requirement = DeliveryRequirement.OPTIONAL,
                        deliveryStatus = DocumentDeliveryStatus.SUCCEEDED,
                        deliveryRetryCount = 1,
                        removalStatus = DocumentDeliveryRemovalStatus.FAILED,
                        removalRetryCount = 2,
                        deliveryRetryable = false,
                        removalRetryable = true,
                        updatedTime = 202,
                    ),
                ),
            ),
        )

        val result = facade(repository).status("document-1")

        assertEquals("document-1", result.documentId)
        assertEquals(Identifier("tenant-private"), repository.tenantId)
        assertEquals(Identifier("document-1"), repository.documentId)
        assertEquals(
            listOf(
                listOf(
                    "delivery-primary", "archive-primary", "Primary archive", "REQUIRED", "FAILED",
                    3, "NOT_REQUESTED", 0, true, false, 101L,
                ),
                listOf(
                    "delivery-search", "search-index", "Search index", "OPTIONAL", "SUCCEEDED",
                    1, "FAILED", 2, false, true, 202L,
                ),
            ),
            result.deliveryTargets.map { target ->
                listOf(
                    target.deliveryId,
                    target.targetId,
                    target.displayName,
                    target.requirement,
                    target.deliveryStatus,
                    target.deliveryRetryCount,
                    target.removalStatus,
                    target.removalRetryCount,
                    target.deliveryRetryable,
                    target.removalRetryable,
                    target.updatedTime,
                )
            },
        )
    }

    @Test
    fun `rejects an unsafe document id before application or persistence access`() {
        val repository = RecordingQueries(DocumentSyncStatusView(Identifier("document-1")))
        val facade = facade(repository)

        assertFailsWith<IllegalArgumentException> { facade.status("document\u0000-private") }

        assertEquals(0, repository.calls)
    }

    private fun facade(repository: RecordingQueries): DocumentSyncStatusApiFacade = DocumentSyncStatusApiFacade(
        DocumentSyncStatusQueryService(
            tenantProvider = object : TenantProvider {
                override fun currentTenant(): TenantContext = TenantContext(Identifier("tenant-private"))
            },
            userRealmProvider = object : UserRealmProvider {
                override fun currentUser(): UserIdentity = UserIdentity(Identifier("reader-1"), "Reader")
                override fun findUser(userId: Identifier): UserIdentity? = null
            },
            authorizationProvider = object : AuthorizationProvider {
                override fun authorize(request: AuthorizationRequest): AuthorizationDecision = AuthorizationDecision(true)
            },
            queries = repository,
            transaction = DirectTransaction,
        ),
    )

    private class RecordingQueries(
        private val result: DocumentSyncStatusView,
    ) : DocumentSyncStatusQueryRepository {
        var calls: Int = 0
        var tenantId: Identifier? = null
        var documentId: Identifier? = null

        override fun findByDocument(
            tenantId: Identifier,
            documentId: Identifier,
            folderReadScope: DocumentFolderReadScope?,
        ): DocumentSyncStatusView {
            calls++
            this.tenantId = tenantId
            this.documentId = documentId
            return result
        }
    }

    private object DirectTransaction : ApplicationTransaction {
        override fun <T> execute(action: () -> T): T = action()
    }
}
