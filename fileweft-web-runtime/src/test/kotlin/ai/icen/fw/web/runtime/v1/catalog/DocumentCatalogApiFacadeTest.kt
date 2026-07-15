package ai.icen.fw.web.runtime.v1.catalog

import ai.icen.fw.application.catalog.DocumentCatalogAccessService
import ai.icen.fw.application.catalog.DocumentCatalogBindingCommand
import ai.icen.fw.domain.document.Document
import ai.icen.fw.core.context.TenantContext
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.authorization.AuthorizationDecision
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.authorization.AuthorizationRequest
import ai.icen.fw.spi.catalog.DocumentCatalogAccessRequest
import ai.icen.fw.spi.catalog.DocumentCatalogFolder
import ai.icen.fw.spi.catalog.DocumentCatalogProvider
import ai.icen.fw.spi.identity.UserIdentity
import ai.icen.fw.spi.identity.UserRealmProvider
import ai.icen.fw.spi.tenant.TenantProvider
import ai.icen.fw.web.api.v1.catalog.DocumentCatalogPageQuery
import ai.icen.fw.web.runtime.v1.V1FeatureUnavailableException
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DocumentCatalogApiFacadeTest {
    @Test
    fun `pages a validated visible tree with a compact opaque cursor`() {
        val provider = MutableCatalog(
            listOf(
                DocumentCatalogFolder("root-z", null, "Root Z"),
                DocumentCatalogFolder("child-b", "root-z", "Child B"),
                DocumentCatalogFolder("root-a", null, "Root A"),
            ),
        )
        val facade = facade(provider)

        val first = facade.page(DocumentCatalogPageQuery(limit = 2))
        val second = facade.page(DocumentCatalogPageQuery(first.nextCursor, 2))

        assertEquals(listOf("child-b", "root-a"), first.items.map { folder -> folder.id })
        assertNotNull(first.nextCursor)
        assertEquals(listOf("root-z"), second.items.map { folder -> folder.id })
        assertEquals("root-z", first.items.single { it.id == "child-b" }.parentFolderId)
        assertNull(second.nextCursor)
        assertEquals(2, provider.calls)
    }

    @Test
    fun `fails closed when a dynamic acl change invalidates a page boundary`() {
        val provider = MutableCatalog(
            listOf(
                DocumentCatalogFolder("a", null, "A"),
                DocumentCatalogFolder("b", null, "B"),
                DocumentCatalogFolder("c", null, "C"),
            ),
        )
        val facade = facade(provider)
        val cursor = requireNotNull(facade.page(DocumentCatalogPageQuery(limit = 1)).nextCursor)
        provider.folders = listOf(
            DocumentCatalogFolder("b", null, "B"),
            DocumentCatalogFolder("c", null, "C"),
        )

        assertThrows<IllegalArgumentException> {
            facade.page(DocumentCatalogPageQuery(cursor, 1))
        }
    }

    @Test
    fun `rejects malformed and wrong-kind cursors`() {
        val facade = facade(MutableCatalog(listOf(DocumentCatalogFolder("a", null, "A"))))

        assertThrows<IllegalArgumentException> {
            facade.page(DocumentCatalogPageQuery("not-a-cursor", 20))
        }
        assertThrows<IllegalArgumentException> {
            facade.page(DocumentCatalogPageQuery("AQMAAAAAAAAAAAAAAAAAAAAAAA", 20))
        }
    }

    @Test
    fun `moves through the controlled command without accepting tenant or user input`() {
        var captured: Pair<Identifier, String>? = null
        val access = access(MutableCatalog(listOf(DocumentCatalogFolder("target", null, "Target"))))
        val facade = DocumentCatalogApiFacade(
            access,
            object : DocumentCatalogBindingCommand {
                override fun move(documentId: Identifier, folderId: String): Document {
                    captured = documentId to folderId
                    return Document(
                        id = documentId,
                        tenantId = Identifier("tenant-a"),
                        assetId = Identifier("asset-a"),
                        documentNumber = "DOC-1",
                        title = "Document",
                    )
                }
            },
        )

        val result = facade.move("document-a", "target")

        assertEquals(Identifier("document-a") to "target", captured)
        assertEquals("document-a", result.documentId)
        assertThrows<V1FeatureUnavailableException> {
            DocumentCatalogApiFacade(access).move("document-a", "target")
        }
    }

    private fun facade(provider: DocumentCatalogProvider): DocumentCatalogApiFacade =
        DocumentCatalogApiFacade(access(provider))

    private fun access(provider: DocumentCatalogProvider): DocumentCatalogAccessService =
        DocumentCatalogAccessService(
                tenantProvider = object : TenantProvider {
                    override fun currentTenant(): TenantContext = TenantContext(Identifier("tenant-a"))
                },
                userRealmProvider = object : UserRealmProvider {
                    override fun currentUser(): UserIdentity = UserIdentity(Identifier("user-a"), "User A")
                    override fun findUser(userId: Identifier): UserIdentity? = null
                },
                authorizationProvider = object : AuthorizationProvider {
                    override fun authorize(request: AuthorizationRequest): AuthorizationDecision = AuthorizationDecision(true)
                },
                catalog = provider,
        )

    private class MutableCatalog(
        var folders: List<DocumentCatalogFolder>,
    ) : DocumentCatalogProvider {
        var calls = 0
            private set

        override fun listFolders(tenantId: Identifier): List<DocumentCatalogFolder> = emptyList()

        override fun listFolders(request: DocumentCatalogAccessRequest): List<DocumentCatalogFolder> {
            calls += 1
            return folders
        }
    }
}
