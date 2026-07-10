package com.fileweft.spi.catalog

import com.fileweft.core.id.Identifier
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class DocumentCatalogProviderTest {
    @Test
    fun `resolves opaque folder ids only within the supplied tenant`() {
        val provider = object : DocumentCatalogProvider {
            override fun listFolders(tenantId: Identifier): List<DocumentCatalogFolder> = when (tenantId.value) {
                "alpha" -> listOf(DocumentCatalogFolder("shared-folder", null, "Alpha"))
                "beta" -> listOf(DocumentCatalogFolder("shared-folder", null, "Beta"))
                else -> emptyList()
            }
        }

        assertEquals("Alpha", provider.findFolder(Identifier("alpha"), "shared-folder")?.displayName)
        assertEquals("Beta", provider.findFolder(Identifier("beta"), "shared-folder")?.displayName)
        assertNull(provider.findFolder(Identifier("alpha"), "unknown"))
    }

    @Test
    fun `rejects invalid catalog folder shapes`() {
        assertFailsWith<IllegalArgumentException> { DocumentCatalogFolder("", null, "Folder") }
        assertFailsWith<IllegalArgumentException> { DocumentCatalogFolder("folder", "folder", "Folder") }
    }
}
