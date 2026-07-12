package ai.icen.fw.dev.api.catalog

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.catalog.DocumentCatalogFolder
import ai.icen.fw.spi.catalog.DocumentCatalogProvider

/**
 * Development reference adapter for a host-owned document catalog.
 *
 * Both tenants intentionally share the opaque `inbox` folder ID so acceptance
 * tests prove that tenant context, rather than the external folder ID alone,
 * prevents catalog leakage.
 */
class DevDocumentCatalogProvider : DocumentCatalogProvider {
    override fun listFolders(tenantId: Identifier): List<DocumentCatalogFolder> = foldersByTenant[tenantId.value].orEmpty()

    private companion object {
        val foldersByTenant = mapOf(
            "alpha" to listOf(
                DocumentCatalogFolder("root", null, "Alpha workspace"),
                DocumentCatalogFolder("inbox", "root", "Incoming"),
                DocumentCatalogFolder("contracts", "root", "Contracts"),
                DocumentCatalogFolder("finance", "root", "Finance"),
                DocumentCatalogFolder("operations", "root", "Operations"),
            ),
            "beta" to listOf(
                DocumentCatalogFolder("root", null, "Beta workspace"),
                DocumentCatalogFolder("inbox", "root", "Incoming"),
                DocumentCatalogFolder("projects", "root", "Projects"),
                DocumentCatalogFolder("governance", "root", "Governance"),
                DocumentCatalogFolder("delivery", "root", "Delivery"),
            ),
        )
    }
}
