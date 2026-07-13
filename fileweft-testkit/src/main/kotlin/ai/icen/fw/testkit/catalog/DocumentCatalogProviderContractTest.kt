package ai.icen.fw.testkit.catalog

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.catalog.DocumentCatalogAccessRequest
import ai.icen.fw.spi.catalog.DocumentCatalogOperation
import ai.icen.fw.spi.catalog.DocumentCatalogProvider
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

abstract class DocumentCatalogProviderContractTest {
    protected abstract val catalogProvider: DocumentCatalogProvider

    protected abstract fun tenantId(): Identifier

    protected abstract fun userId(): Identifier

    @Test
    fun `lists folders with unique ids for a tenant`() {
        val folders = catalogProvider.listFolders(tenantId())

        assertTrue(folders.isNotEmpty(), "Catalog must contain at least one folder for the test tenant.")
        assertEquals(
            folders.size,
            folders.map { it.id }.distinct().size,
            "Folder ids must be unique within a tenant.",
        )
        folders.forEach { folder ->
            assertTrue(folder.id.isNotBlank(), "Folder id must not be blank.")
            assertTrue(folder.displayName.isNotBlank(), "Folder display name must not be blank.")
        }
    }

    @Test
    fun `findFolder is consistent with listFolders`() {
        val tenantId = tenantId()
        val folders = catalogProvider.listFolders(tenantId)

        folders.forEach { listed ->
            val found = catalogProvider.findFolder(tenantId, listed.id)
            assertNotNull(found, "Listed folder '${listed.id}' must be findable.")
            assertEquals(listed.id, found?.id)
            assertEquals(listed.displayName, found?.displayName)
        }

        assertNull(catalogProvider.findFolder(tenantId, "fileweft-missing-folder"), "Unknown folder must not resolve.")
    }

    @Test
    fun `user-aware listFolders returns valid folders`() {
        val request = DocumentCatalogAccessRequest(tenantId(), userId(), DocumentCatalogOperation.BROWSE)
        val folders = catalogProvider.listFolders(request)

        folders.forEach { folder ->
            assertTrue(folder.id.isNotBlank(), "User-scoped folder id must not be blank.")
            assertTrue(folder.displayName.isNotBlank(), "User-scoped folder display name must not be blank.")
        }
    }
}
