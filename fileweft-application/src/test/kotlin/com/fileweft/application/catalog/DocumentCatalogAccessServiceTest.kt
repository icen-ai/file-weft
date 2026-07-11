package com.fileweft.application.catalog

import com.fileweft.core.context.TenantContext
import com.fileweft.core.id.Identifier
import com.fileweft.spi.authorization.AuthorizationDecision
import com.fileweft.spi.authorization.AuthorizationProvider
import com.fileweft.spi.authorization.AuthorizationRequest
import com.fileweft.spi.catalog.DocumentCatalogAccessRequest
import com.fileweft.spi.catalog.DocumentCatalogFolder
import com.fileweft.spi.catalog.DocumentCatalogOperation
import com.fileweft.spi.catalog.DocumentCatalogProvider
import com.fileweft.spi.identity.UserIdentity
import com.fileweft.spi.identity.UserRealmProvider
import com.fileweft.spi.tenant.TenantProvider
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
    fun `does not ask the host catalog when FileWeft base authorization denies access`() {
        val catalog = RecordingCatalog()
        val service = service(catalog, RecordingAuthorization(allowed = false))

        assertFailsWith<SecurityException> { service.listAccessibleFolders() }
        assertEquals(null, catalog.lastRequest)
    }

    private fun service(catalog: DocumentCatalogProvider, authorization: AuthorizationProvider) = DocumentCatalogAccessService(
        tenantProvider = object : TenantProvider {
            override fun currentTenant() = TenantContext(Identifier("tenant-a"))
        },
        userRealmProvider = object : UserRealmProvider {
            override fun currentUser() = UserIdentity(Identifier("editor-a"), "Editor A")
            override fun findUser(userId: Identifier): UserIdentity? = null
        },
        authorizationProvider = authorization,
        catalog = catalog,
    )

    private class RecordingCatalog : DocumentCatalogProvider {
        var lastRequest: DocumentCatalogAccessRequest? = null

        override fun listFolders(tenantId: Identifier): List<DocumentCatalogFolder> = emptyList()

        override fun listFolders(request: DocumentCatalogAccessRequest): List<DocumentCatalogFolder> {
            lastRequest = request
            return listOf(DocumentCatalogFolder("visible", null, "Visible"))
        }
    }

    private class RecordingAuthorization(private val allowed: Boolean = true) : AuthorizationProvider {
        var lastRequest: AuthorizationRequest? = null

        override fun authorize(request: AuthorizationRequest): AuthorizationDecision {
            lastRequest = request
            return AuthorizationDecision(allowed, "denied")
        }
    }
}
