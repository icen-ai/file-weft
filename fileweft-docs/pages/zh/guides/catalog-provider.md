---
route: "guides/catalog-provider"
group: "guides"
order: 3
locale: "zh"
nav: "目录 Provider"
title: "实现目录 Provider"
lead: "把宿主目录树绑定到 FileWeft 文档，同时不放弃对目录路径、ACL 和显示名称的控制权。"
format: "markdown"
---

FileWeft 有意不保存目录路径或目录 ACL。它通过 `DocumentCatalogProvider` SPI 向宿主索要一个不透明的目录标识符，并只把这个引用作为资产元数据持久化。这样目录模型仍由你说了算，FileWeft 也能把文档挂到正确位置。

## 1. 为什么需要目录 Provider

把 FileWeft 想象成一个仓库：它知道每个箱子里有什么，但不知道你大楼的平面图。目录 Provider 就是地图：它告诉 FileWeft 箱子属于哪个房间，而 FileWeft 不需要拥有蓝图。

好处：

- 宿主持有目录层级和权限。
- 对象存储键与目录名或路径解耦。
- 在宿主里重命名或移动目录不需要重写已存储的字节。

## 2. 实现 SPI

契约有两组重载：租户级列表和访问级列表。应优先使用访问级重载，以强制按用户可见性过滤。

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
        // 按用户可见性过滤，而不是返回整个租户目录。
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
> 目录 ID 写入资产元数据键 `catalog.folder-id`。对象存储路径不包含目录 ID。

## 3. 把文档绑定到目录

配置目录感知宿主后，创建文档请求必须带 `folderId`。FileWeft 会通过 Provider 校验目录，然后持久化这个不透明引用。

```bash
curl -F "documentNumber=DOC-002" \
     -F "title=Foldered document" \
     -F "folderId=folder-42" \
     -F "file=@report.pdf" \
     http://localhost:8080/fileweft/v1/documents
```

生成的文档只保存 `folder-42`。宿主决定 `folder-42` 代表什么、谁能看到它、在 UI 中位于何处。

## 4. 强制执行访问控制

`DocumentCatalogAccessRequest` 携带租户、用户和请求操作。用它只返回调用方有权操作的目录。

```kotlin
val request = DocumentCatalogAccessRequest(
    tenantId = tenantContext.tenantId,
    userId = userIdentity.id,
    operation = DocumentCatalogOperation.BIND_DOCUMENT,
)
```

如果用户看不到目标目录，返回 `null` 或从列表中省略。FileWeft 会把它作为校验失败抛出，而不是在不可见目录中创建文档。

> [!WARNING]
> 不要在对象键里使用目录名或路径。目录绑定只是元数据。移动目录绝不应要求重命名已存储对象。

## 常见问题

**Q：可以有多个目录 Provider 吗？**
租户级查找只会激活一个 Provider。多个 Bean 可以共存，但框架通过 Spring 的 primary/qualifier 机制选择其一。

**Q：如果宿主删除了目录会怎样？**
FileWeft 保留不透明引用。是否阻止新上传或让已有文档悬空，由宿主决定。

**Q：FileWeft 会缓存目录列表吗？**
不会。FileWeft 在校验创建或更新请求时调用 Provider，因此新鲜度和一致性由宿主控制。

## 下一步

- [实现存储适配器](storage-adapter.md) 决定字节物理存放在哪里。
- [多租户隔离](multi-tenant.md) 确保目录查询按租户隔离。
