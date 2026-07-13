---
route: "concepts/tenant-catalog"
group: "concepts"
order: 3
locale: "en"
nav: "Tenancy & file trees"
title: "Tenant & catalog isolation"
lead: "Multi-tenancy is more than adding a column: it must flow from trusted context into every query, path, and event. File trees, on the other hand, are owned by the host. This page shows how tenant isolation and catalog authorization work together without leaking folder structure into storage keys."
format: "markdown"
---

## 01. Tenant context is trusted, not parsed

FileWeft never trusts a tenant ID that arrives in a request parameter, path variable or query string. The host resolves the tenant from its own authenticated context and supplies it through `TenantProvider`.

```kotlin
@Component
class HostTenantProvider(private val hostContext: HostContext) : TenantProvider {
    override fun currentTenant(): TenantContext = hostContext.currentTenant()
}
```

That tenant context then constrains:

- database reads and writes
- storage paths and bucket names
- events, tasks and audit logs
- caches and metrics dimensions

> [!WARNING]
> Even when an ID looks globally unique, repositories must still filter by `tenant_id`. A missing `WHERE tenant_id = ?` clause is a data-leak bug.

## 02. The host owns the folder tree

FileWeft does not store folder hierarchies. The browser submits only a bounded opaque `folderId`. FileWeft asks the host catalog provider with trusted tenant, user and operation context, then stores the returned canonical folder ID as metadata.

The contract is `DocumentCatalogProvider`:

```kotlin
interface DocumentCatalogProvider {
    fun listFolders(tenantId: Identifier): List<DocumentCatalogFolder>
    fun listFolders(request: DocumentCatalogAccessRequest): List<DocumentCatalogFolder>
    fun findFolder(tenantId: Identifier, folderId: String): DocumentCatalogFolder?
    fun findFolder(request: DocumentCatalogAccessRequest, folderId: String): DocumentCatalogFolder?
}
```

A host implementation checks real permissions before returning a folder:

```kotlin
@Component
class HostDocumentCatalogProvider(
    private val hostCatalog: HostCatalogService
) : DocumentCatalogProvider {

    override fun listFolders(request: DocumentCatalogAccessRequest): List<DocumentCatalogFolder> =
        hostCatalog.foldersAccessibleBy(
            tenantId = request.tenantId,
            userId = request.userId,
            operation = request.operation
        )

    override fun findFolder(request: DocumentCatalogAccessRequest, folderId: String): DocumentCatalogFolder? =
        hostCatalog.findFolder(request.tenantId, folderId)
            ?.takeIf {
                hostCatalog.canAccess(
                    tenantId = request.tenantId,
                    userId = request.userId,
                    operation = request.operation,
                    folderId = folderId
                )
            }

    // The tenant-only overloads are not used when identity providers are configured.
    override fun listFolders(tenantId: Identifier): List<DocumentCatalogFolder> =
        throw UnsupportedOperationException("Use the access-request overload")

    override fun findFolder(tenantId: Identifier, folderId: String): DocumentCatalogFolder? =
        throw UnsupportedOperationException("Use the access-request overload")
}
```

After the folder is validated, FileWeft writes the canonical ID into the asset metadata:

```yaml
metadata:
  catalog.folder-id: "folder-42"
```

Object storage keys never contain the folder ID. A rename or re-parent operation in the host catalog does not require FileWeft to move any bytes.

## 03. No silent fallback

When catalog mode is enabled, every mutation that needs a folder must pass through the provider. If the provider cannot confirm that the mutation is safe, FileWeft returns `FEATURE_UNAVAILABLE`. It never falls back to a tenant-wide write path.

| Scenario | Result |
|---|---|
| Catalog provider absent | Feature unavailable for catalog-dependent operations |
| Provider returns no folder | Operation rejected with `INVALID_REQUEST` |
| Provider denies the operation | Operation rejected with `FORBIDDEN` |
| Provider confirms access | FileWeft proceeds and stores `catalog.folder-id` |

## 04. Stable folder IDs

Folder IDs may originate as numbers, UUIDs or composite external keys, but the host should convert them to stable strings. Rename or re-parent a folder without changing its canonical ID. A real ID change requires an explicit catalog move.

```kotlin
// The host returns the canonical folder ID, not the display name.
DocumentCatalogFolder(
    id = "folder-42",
    parentFolderId = "folder-7",
    displayName = "Q3 Compliance Reports"
)
```

## 05. How a bind request flows

1. The browser sends `folderId=folder-42` when creating or updating a document.
2. The controller validates the request format and converts it to a domain command.
3. The application layer calls `DocumentCatalogProvider.findFolder(..., "folder-42")` with trusted tenant and user context.
4. The host checks whether the user may bind documents into that folder.
5. FileWeft stores the file and writes `catalog.folder-id=folder-42` in metadata.
6. Later reads use the stored metadata; they do not re-resolve the folder for every download.

## FAQ

**Q: Can I trust `folderId` from the browser?**
No. Treat it as an opaque hint. The real authorization decision happens inside `DocumentCatalogProvider`, using the trusted tenant and user context.

**Q: Why not put the folder ID in the object key?**
Because the host owns the tree. Renaming or moving a folder would force FileWeft to rewrite every object key. Keeping the folder in metadata decouples FileWeft from host catalog changes.

**Q: What if the host catalog is temporarily unavailable?**
FileWeft treats a missing safe-mutation capability as `FEATURE_UNAVAILABLE`. It does not proceed with a tenant-wide default folder.

## Next steps

- [Catalog provider guide](../guides/catalog-provider.md) — implement `DocumentCatalogProvider` in your host.
- [Security model](./security-model.md) — how tenant, identity and authorization providers work together.
- [Storage adapter guide](../guides/storage-adapter.md) — keep storage keys free of folder structure.
