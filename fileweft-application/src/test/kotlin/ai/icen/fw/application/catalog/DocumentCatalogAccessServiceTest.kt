package ai.icen.fw.application.catalog

import ai.icen.fw.application.security.ApplicationUnauthenticatedException
import ai.icen.fw.core.context.TenantContext
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.authorization.AuthorizationDecision
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.authorization.AuthorizationRequest
import ai.icen.fw.spi.catalog.DocumentCatalogAccessRequest
import ai.icen.fw.spi.catalog.DocumentCatalogFolder
import ai.icen.fw.spi.catalog.DocumentCatalogOperation
import ai.icen.fw.spi.catalog.DocumentCatalogProvider
import ai.icen.fw.spi.identity.UserIdentity
import ai.icen.fw.spi.identity.UserRealmProvider
import ai.icen.fw.spi.tenant.TenantProvider
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DocumentCatalogAccessServiceTest {
    @Test
    fun `passes trusted tenant user and browse intent to a user aware catalog`() {
        val catalog = RecordingCatalog()
        val authorization = RecordingAuthorization()
        val service = service(catalog, authorization)

        val folders = service.listAccessibleFolders()

        assertEquals(listOf("visible"), folders.map { it.id })
        assertEquals(Identifier("tenant-a"), catalog.lastRequest?.tenantId)
        assertEquals(Identifier("editor-a"), catalog.lastRequest?.userId)
        assertEquals(DocumentCatalogOperation.BROWSE, catalog.lastRequest?.operation)
        assertEquals("document:read", authorization.lastRequest?.action?.name)
        assertEquals("DOCUMENT_CATALOG", authorization.lastRequest?.resource?.type)
    }

    @Test
    fun `rejects a folder omitted by the current users catalog view before draft upload`() {
        val catalog = RecordingCatalog()
        val authorization = RecordingAuthorization()
        val service = service(catalog, authorization)

        assertFailsWith<IllegalArgumentException> { service.requireFolderForDocumentCreation("hidden") }
        assertEquals(DocumentCatalogOperation.BIND_DOCUMENT, catalog.lastRequest?.operation)
        assertEquals("document:create", authorization.lastRequest?.action?.name)
    }

    @Test
    fun `rejects oversized and control-character folder ids before host catalog access`() {
        listOf(
            "folder\u0000id",
            "x".repeat(257),
        ).forEach { folderId ->
            val catalog = RecordingCatalog()
            val authorization = RecordingAuthorization()
            val service = service(catalog, authorization)

            assertFailsWith<IllegalArgumentException> { service.requireFolderForDocumentCreation(folderId) }

            assertEquals(null, authorization.lastRequest)
            assertEquals(null, catalog.lastRequest)
        }
    }

    @Test
    fun `requires a readable folder through browse intent before a folder filtered document page`() {
        val catalog = RecordingCatalog()
        val authorization = RecordingAuthorization()
        val service = service(catalog, authorization)

        service.requireFolderForDocumentRead("visible")

        assertEquals(DocumentCatalogOperation.BROWSE, catalog.lastRequest?.operation)
        assertEquals(Identifier("tenant-a"), catalog.lastRequest?.tenantId)
        assertEquals(Identifier("editor-a"), catalog.lastRequest?.userId)
        assertEquals("document:read", authorization.lastRequest?.action?.name)
        assertEquals("DOCUMENT_CATALOG", authorization.lastRequest?.resource?.type)
    }

    @Test
    fun `derives an immutable document read scope from the current users catalog view`() {
        val catalog = RecordingCatalog()
        val authorization = RecordingAuthorization()
        val service = service(catalog, authorization)

        val readableFolderIds = service.readableFolderIds()

        assertEquals(setOf("visible"), readableFolderIds)
        assertEquals(DocumentCatalogOperation.BROWSE, catalog.lastRequest?.operation)
        assertEquals(Identifier("tenant-a"), catalog.lastRequest?.tenantId)
        assertEquals(Identifier("editor-a"), catalog.lastRequest?.userId)
        @Suppress("UNCHECKED_CAST")
        assertFailsWith<UnsupportedOperationException> {
            (readableFolderIds as MutableSet<String>).add("hidden")
        }
    }

    @Test
    fun `derives an immutable download scope with trusted browse context and download action`() {
        val catalog = RecordingCatalog(
            listOf(
                DocumentCatalogFolder("finance", null, "Finance"),
                DocumentCatalogFolder("contracts", null, "Contracts"),
            ),
        )
        val authorization = RecordingAuthorization()
        val service = service(catalog, authorization)
        val documentId = Identifier("document-a")

        val folderIds = service.readableFolderIdsForDocumentDownload(documentId)

        assertEquals(setOf("finance", "contracts"), folderIds)
        assertEquals(DocumentCatalogOperation.BROWSE, catalog.lastRequest?.operation)
        assertEquals(Identifier("tenant-a"), catalog.lastRequest?.tenantId)
        assertEquals(Identifier("editor-a"), catalog.lastRequest?.userId)
        assertEquals("document:download", authorization.lastRequest?.action?.name)
        assertEquals(documentId, authorization.lastRequest?.resource?.id)
        assertEquals("DOCUMENT", authorization.lastRequest?.resource?.type)
        @Suppress("UNCHECKED_CAST")
        assertFailsWith<UnsupportedOperationException> {
            (folderIds as MutableSet<String>).add("private")
        }

        val empty = service(RecordingCatalog(emptyList()), RecordingAuthorization())
            .readableFolderIdsForDocumentDownload(documentId)
        assertEquals(emptySet(), empty)
    }

    @Test
    fun `does not ask the host catalog when FileWeft base authorization denies access`() {
        val catalog = RecordingCatalog()
        val service = service(catalog, RecordingAuthorization(allowed = false))

        assertFailsWith<SecurityException> { service.listAccessibleFolders() }
        assertEquals(null, catalog.lastRequest)
    }

    @Test
    fun `classifies a missing trusted catalog user as unauthenticated`() {
        val catalog = RecordingCatalog()
        val authorization = RecordingAuthorization()
        val service = service(catalog, authorization, currentUser = null)

        assertFailsWith<ApplicationUnauthenticatedException> { service.listAccessibleFolders() }

        assertEquals(null, authorization.lastRequest)
        assertEquals(null, catalog.lastRequest)
    }

    @Test
    fun `lifecycle access uses the captured tenant and operator without rereading ambient providers`() {
        val tenantProvider = SwitchingTenantProvider(
            TenantContext(Identifier("tenant-entry")),
            TenantContext(Identifier("tenant-switched")),
        )
        val userProvider = SwitchingUserProvider(
            UserIdentity(Identifier("operator-entry"), "Entry operator"),
            UserIdentity(Identifier("operator-switched"), "Switched operator"),
        )
        val catalog = RecordingCatalog()
        val authorization = RecordingAuthorization()
        val service = DocumentCatalogAccessService(
            tenantProvider,
            userProvider,
            authorization,
            catalog,
        )
        val capturedTenantId = Identifier("tenant-captured")
        val capturedOperator = UserIdentity(Identifier("operator-captured"), "Captured operator")

        service.requireCurrentFolderForDocumentLifecycle(
            tenantId = capturedTenantId,
            operator = capturedOperator,
            documentId = Identifier("document-a"),
            folderId = "visible",
            actionName = "document:archive",
        )

        assertEquals(0, tenantProvider.calls)
        assertEquals(0, userProvider.calls)
        assertEquals(capturedTenantId, authorization.lastRequest?.resource?.tenantId)
        assertEquals(capturedOperator.id, authorization.lastRequest?.subject?.id)
        assertEquals(capturedTenantId, catalog.lastRequest?.tenantId)
        assertEquals(capturedOperator.id, catalog.lastRequest?.userId)
        assertEquals(DocumentCatalogOperation.BROWSE, catalog.lastRequest?.operation)
    }

    private fun service(
        catalog: DocumentCatalogProvider,
        authorization: AuthorizationProvider,
        currentUser: UserIdentity? = UserIdentity(Identifier("editor-a"), "Editor A"),
    ) = DocumentCatalogAccessService(
        tenantProvider = object : TenantProvider {
            override fun currentTenant() = TenantContext(Identifier("tenant-a"))
        },
        userRealmProvider = object : UserRealmProvider {
            override fun currentUser() = currentUser
            override fun findUser(userId: Identifier): UserIdentity? = null
        },
        authorizationProvider = authorization,
        catalog = catalog,
    )

    private class RecordingCatalog(
        private val folders: List<DocumentCatalogFolder> = listOf(DocumentCatalogFolder("visible", null, "Visible")),
    ) : DocumentCatalogProvider {
        var lastRequest: DocumentCatalogAccessRequest? = null

        override fun listFolders(tenantId: Identifier): List<DocumentCatalogFolder> = emptyList()

        override fun listFolders(request: DocumentCatalogAccessRequest): List<DocumentCatalogFolder> {
            lastRequest = request
            return folders
        }
    }

    private class RecordingAuthorization(private val allowed: Boolean = true) : AuthorizationProvider {
        var lastRequest: AuthorizationRequest? = null

        override fun authorize(request: AuthorizationRequest): AuthorizationDecision {
            lastRequest = request
            return AuthorizationDecision(allowed, "denied")
        }
    }

    private class SwitchingTenantProvider(
        vararg values: TenantContext,
    ) : TenantProvider {
        private val values = values
        var calls: Int = 0
            private set

        override fun currentTenant(): TenantContext = values[minOf(calls++, values.lastIndex)]
    }

    private class SwitchingUserProvider(
        vararg values: UserIdentity?,
    ) : UserRealmProvider {
        private val values = values
        var calls: Int = 0
            private set

        override fun currentUser(): UserIdentity? = values[minOf(calls++, values.lastIndex)]

        override fun findUser(userId: Identifier): UserIdentity? = null
    }
}
