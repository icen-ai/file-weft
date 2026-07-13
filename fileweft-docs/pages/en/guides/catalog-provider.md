---
route: "guides/catalog-provider"
group: "guides"
order: 3
locale: "en"
nav: "Catalog provider"
title: "Implement a catalog provider"
lead: "Plug your host folder tree into FileWeft by implementing DocumentCatalogProvider."
format: "markdown"
---

## Why a catalog provider matters

FileWeft never stores folder paths or folder ACLs. It asks the host for a folder canonical ID and persists only that opaque reference with the asset. This keeps the host in control of its own directory tree.

## Implement the SPI

```kotlin
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
            operation = request.operation.name,
        ).map { folder ->
            DocumentCatalogFolder(
                id = folder.id,
                parentFolderId = folder.parentId,
                displayName = folder.name,
            )
        }
    }
}
```

## Bind a document to a folder

When a folder-aware host is configured, the create-document request requires a `folderId`. FileWeft validates the folder through the provider and stores `catalog.folder-id` metadata:

```bash
curl -F "documentNumber=DOC-002" \
     -F "title=Foldered document" \
     -F "folderId=folder-42" \
     -F "file=@report.pdf" \
     http://localhost:8080/fileweft/v1/documents
```

> [!WARNING]
> Do not use folder names or paths in storage keys. The folder binding is metadata only.
