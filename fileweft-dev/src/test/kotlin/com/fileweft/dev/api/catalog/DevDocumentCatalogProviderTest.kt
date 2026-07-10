package com.fileweft.dev.api.catalog

import com.fileweft.core.id.Identifier
import kotlin.test.assertEquals
import kotlin.test.assertNull
import org.junit.jupiter.api.Test

class DevDocumentCatalogProviderTest {
    private val catalog = DevDocumentCatalogProvider()

    @Test
    fun `returns distinct tenant trees while allowing equivalent opaque folder ids`() {
        assertEquals("Alpha workspace", catalog.findFolder(Identifier("alpha"), "root")?.displayName)
        assertEquals("Beta workspace", catalog.findFolder(Identifier("beta"), "root")?.displayName)
        assertEquals("Incoming", catalog.findFolder(Identifier("alpha"), "inbox")?.displayName)
        assertEquals("Incoming", catalog.findFolder(Identifier("beta"), "inbox")?.displayName)
        assertNull(catalog.findFolder(Identifier("beta"), "contracts"))
        assertNull(catalog.findFolder(Identifier("alpha"), "projects"))
    }
}
