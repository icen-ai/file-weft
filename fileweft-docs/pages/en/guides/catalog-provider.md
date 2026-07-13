---
route: "guides/catalog-provider"
group: "guides"
order: 3
locale: "en"
nav: "Catalog provider"
title: "Implement a catalog provider"
lead: "Bind your host folder tree to FileWeft documents without surrendering control of directory paths, ACLs, or display names."
format: "markdown"
---

FileWeft deliberately does not store folder paths or folder ACLs. Instead, it asks the host for an opaque folder identifier through the `DocumentCatalogProvider` SPI and persists only that reference as asset metadata. This keeps your directory model authoritative while letting FileWeft attach documents to the right place.

## 1. Why a catalog provider matters

Imagine FileWeft as a warehouse that stores boxes. It knows what is inside each box, but it does not know the floor plan of your building. The catalog provider is the map: it tells FileWeft which room a box belongs to, without FileWeft needing to own the blueprint.

Benefits:

- Your host keeps ownership of folder hierarchy and permissions.
- Storage object keys remain independent of folder names or paths.
- Renaming or moving a folder in your host does not require rewriting stored bytes.

## 2. Implement the SPI

The contract has two overload pairs: tenant-wide listing and access-scoped listing. Always prefer the access-scoped overloads so user visibility is enforced.

```kotlin
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.catalog.DocumentCatalogAccessRequest
import ai.icen.fw.spi.catalog.DocumentCatalogFolder
import ai.icen.fw.spi.catalog.DocumentCatalogOperation
import ai.icen.fw.spi.catalog.DocumentCatalogProvider
import org.springframework.stereotype.Component

@Component
class HostCatalogProvider : DocumentCatalogProvider {

    override fun listFolders(tenantId: Identifier): List<DocumentCatalogFolder> {
        return folderRepository.findByTenant(tenantId.value)
            .map { folder ->
                DocumentCatalogFolder(
                    id = folder.id,
                    parentFolderId = folder.parentId,
                    displayName = folder.name,
                )
            }
    }

    override fun listFolders(request: DocumentCatalogAccessRequest): List<DocumentCatalogFolder> {
        // Enforce user-scoped visibility instead of tenant-wide listing.
        return folderRepository.findVisibleTo(
            tenantId = request.tenantId.value,
            userId = request.userId.value,
            operation = request.operation,
        ).map { folder ->
            DocumentCatalogFolder(
                id = folder.id,
                parentFolderId = folder.parentId,
                displayName = folder.name,
            )
        }
    }

    override fun findFolder(tenantId: Identifier, folderId: String): DocumentCatalogFolder? {
        return folderRepository.findByTenantAndId(tenantId.value, folderId)
            ?.let { folder ->
                DocumentCatalogFolder(
                    id = folder.id,
                    parentFolderId = folder.parentId,
                    displayName = folder.name,
                )
            }
    }

    override fun findFolder(request: DocumentCatalogAccessRequest, folderId: String): DocumentCatalogFolder? {
        return folderRepository.findVisibleTo(
            tenantId = request.tenantId.value,
            userId = request.userId.value,
            operation = request.operation,
        ).find { it.id == folderId }
            ?.let { folder ->
                DocumentCatalogFolder(
                    id = folder.id,
                    parentFolderId = folder.parentId,
                    displayName = folder.name,
                )
            }
    }
}
```

> [!NOTE]
> The folder ID is written to asset metadata under the key `catalog.folder-id`. Object storage paths never contain the folder ID.

## 3. Bind a document to a folder

When a folder-aware host is configured, the create-document request requires a `folderId`. FileWeft validates the folder through the provider before persisting the opaque reference.

```bash
curl -F "documentNumber=DOC-002" \
     -F "title=Foldered document" \
     -F "folderId=folder-42" \
     -F "file=@report.pdf" \
     http://localhost:8080/fileweft/v1/documents
```

The resulting document stores only `folder-42`. Your host decides what `folder-42` means, who can see it, and where it lives in the UI.

## 4. Enforce access control

The `DocumentCatalogAccessRequest` carries the tenant, user, and requested operation. Use it to return only folders the caller is allowed to act on.

```kotlin
val request = DocumentCatalogAccessRequest(
    tenantId = tenantContext.tenantId,
    userId = userIdentity.id,
    operation = DocumentCatalogOperation.BIND_DOCUMENT,
)
```

If a user does not have visibility to the requested folder, return `null` or omit it from the list. FileWeft will surface this as a validation failure rather than creating a document in an invisible folder.

> [!WARNING]
> Do not use folder names or paths in storage keys. The folder binding is metadata only. Moving a folder must never require renaming stored objects.

## FAQ

**Q: Can I have multiple catalog providers?**
Only one provider is active for tenant-wide lookups. Additional beans can coexist, but the framework selects one through normal Spring primary/qualifier resolution.

**Q: What happens if the folder is deleted in the host?**
FileWeft keeps the opaque reference. It is the host's responsibility to decide whether deletion should block new uploads or orphan existing documents.

**Q: Does FileWeft cache folder lists?**
No. FileWeft calls the provider when validating create or update requests, so your host controls freshness and consistency.

## Next steps

- [Implement a storage adapter](storage-adapter.md) to decide where bytes are physically stored.
- [Multi-tenant isolation](multi-tenant.md) to ensure folder queries are scoped per tenant.
