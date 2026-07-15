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

        assertVisibleForest(folders, "Tenant catalog", requireNonEmpty = true)
    }

    @Test
    fun `findFolder is consistent with listFolders`() {
        val tenantId = tenantId()
        val folders = catalogProvider.listFolders(tenantId)

        folders.forEach { listed ->
            val found = catalogProvider.findFolder(tenantId, listed.id)
            assertNotNull(found, "Listed folder '${listed.id}' must be findable.")
            assertEquals(listed.id, found?.id)
            assertEquals(listed.parentFolderId, found?.parentFolderId)
            assertEquals(listed.displayName, found?.displayName)
        }

        assertNull(catalogProvider.findFolder(tenantId, "fileweft-missing-folder"), "Unknown folder must not resolve.")
    }

    @Test
    fun `user-aware listFolders returns valid folders`() {
        val request = DocumentCatalogAccessRequest(tenantId(), userId(), DocumentCatalogOperation.BROWSE)
        val folders = catalogProvider.listFolders(request)

        assertVisibleForest(folders, "User-scoped catalog", requireNonEmpty = false)
    }

    private fun assertVisibleForest(
        folders: List<ai.icen.fw.spi.catalog.DocumentCatalogFolder>,
        scope: String,
        requireNonEmpty: Boolean,
    ) {
        if (requireNonEmpty) {
            assertTrue(folders.isNotEmpty(), "$scope must contain at least one folder for the test tenant.")
        }
        assertTrue(folders.size <= MAX_VISIBLE_FOLDER_COUNT, "$scope must not exceed $MAX_VISIBLE_FOLDER_COUNT folders.")
        assertEquals(folders.size, folders.map { it.id }.distinct().size, "$scope folder ids must be unique.")
        val byId = folders.associateBy { it.id }
        folders.forEach { folder ->
            assertCanonicalText(folder.id, MAX_FOLDER_ID_LENGTH, "$scope folder id")
            assertCanonicalText(folder.displayName, MAX_FOLDER_DISPLAY_NAME_LENGTH, "$scope display name")
            folder.parentFolderId?.let { parentId ->
                assertCanonicalText(parentId, MAX_FOLDER_ID_LENGTH, "$scope parent folder id")
                assertTrue(byId.containsKey(parentId), "$scope parent '$parentId' must be visible in the same forest.")
            }
        }
        folders.forEach { start ->
            val path = HashSet<String>()
            var currentId: String? = start.id
            while (currentId != null) {
                assertTrue(path.add(currentId), "$scope must not contain a folder cycle.")
                currentId = byId[currentId]?.parentFolderId
            }
        }
    }

    private fun assertCanonicalText(value: String, maximumLength: Int, field: String) {
        assertTrue(value.isNotBlank(), "$field must not be blank.")
        assertEquals(value.trim(), value, "$field must not contain leading or trailing whitespace.")
        assertTrue(value.length <= maximumLength, "$field must not exceed $maximumLength characters.")
        assertTrue(
            value.none { character ->
                Character.isISOControl(character) || Character.getType(character) == Character.FORMAT.toInt()
            },
            "$field must not contain control or Unicode format characters.",
        )
    }

    private companion object {
        const val MAX_FOLDER_ID_LENGTH = 256
        const val MAX_FOLDER_DISPLAY_NAME_LENGTH = 512
        const val MAX_VISIBLE_FOLDER_COUNT = 10_000
    }
}
