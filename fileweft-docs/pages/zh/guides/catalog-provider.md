---
route: "guides/catalog-provider"
group: "guides"
order: 3
locale: "zh"
nav: "目录 Provider"
title: "实现目录 Provider"
lead: "通过实现 DocumentCatalogProvider，把宿主目录树接入 FileWeft。"
format: "markdown"
---

## 为什么需要目录 Provider

FileWeft 不保存目录路径或目录 ACL。它向宿主索要目录的 canonical ID，并只在资产元数据里保留这个不透明引用，目录控制权始终留在宿主。

## 实现 SPI

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
        // 按用户可见性过滤，而不是返回整个租户目录。
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

## 把文档绑定到目录

配置目录感知宿主后，创建文档请求必须带 `folderId`。FileWeft 会通过 Provider 校验目录，并存储 `catalog.folder-id` 元数据：

```bash
curl -F "documentNumber=DOC-002" \
     -F "title=Foldered document" \
     -F "folderId=folder-42" \
     -F "file=@report.pdf" \
     http://localhost:8080/fileweft/v1/documents
```

> [!WARNING]
> 不要在对象键里使用目录名或路径。目录绑定只是元数据。
