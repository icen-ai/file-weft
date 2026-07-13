---
route: "concepts/tenant-catalog"
group: "concepts"
order: 3
locale: "zh"
nav: "租户与文件树"
title: "租户与目录隔离"
lead: "多租户不只是加一列 tenant_id，它必须从可信上下文流入每一次查询、每一条路径、每一个事件。而文件树归宿主所有。本页说明租户隔离与目录授权如何协同工作，同时不把目录结构泄漏到存储键中。"
format: "markdown"
---

## 01. 租户上下文来自可信来源，而不是请求参数

FileWeft 从不信任来自请求参数、路径变量或查询字符串的租户 ID。宿主从自己的认证上下文中解析租户，并通过 `TenantProvider` 提供。

```kotlin
@Component
class HostTenantProvider(private val hostContext: HostContext) : TenantProvider {
    override fun currentTenant(): TenantContext = hostContext.currentTenant()
}
```

租户上下文随后约束：

- 数据库读写
- 存储路径与桶名
- 事件、任务与审计日志
- 缓存与指标维度

> [!WARNING]
> 即使某个 ID 看起来全局唯一，仓库也必须按 `tenant_id` 过滤。缺少 `WHERE tenant_id = ?` 就是数据泄漏漏洞。

## 02. 目录树归宿主所有

FileWeft 不存储目录层级。浏览器只提交一个受约束的不透明 `folderId`。FileWeft 使用可信的租户、用户与操作上下文询问宿主目录提供者，然后将返回的 canonical ID 保存为元数据。

契约是 `DocumentCatalogProvider`：

```kotlin
interface DocumentCatalogProvider {
    fun listFolders(tenantId: Identifier): List<DocumentCatalogFolder>
    fun listFolders(request: DocumentCatalogAccessRequest): List<DocumentCatalogFolder>
    fun findFolder(tenantId: Identifier, folderId: String): DocumentCatalogFolder?
    fun findFolder(request: DocumentCatalogAccessRequest, folderId: String): DocumentCatalogFolder?
}
```

宿主实现会在返回目录前检查真实权限：

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

    // 当身份提供者已配置时，不会使用仅含 tenantId 的重载。
    override fun listFolders(tenantId: Identifier): List<DocumentCatalogFolder> =
        throw UnsupportedOperationException("Use the access-request overload")

    override fun findFolder(tenantId: Identifier, folderId: String): DocumentCatalogFolder? =
        throw UnsupportedOperationException("Use the access-request overload")
}
```

目录验证通过后，FileWeft 将 canonical ID 写入资产元数据：

```yaml
metadata:
  catalog.folder-id: "folder-42"
```

对象存储键中绝不包含目录 ID。宿主目录改名或换父级时，FileWeft 不需要移动任何字节。

## 03. 不会静默降级

目录模式开启后，所有需要目录的变更操作都必须经过提供者。如果提供者无法确认该变更是安全的，FileWeft 会返回 `FEATURE_UNAVAILABLE`，不会降级到租户级写入路径。

| 场景 | 结果 |
|---|---|
| 缺少目录提供者 | 依赖目录的功能不可用 |
| 提供者未返回目录 | 操作以 `INVALID_REQUEST` 拒绝 |
| 提供者拒绝操作 | 操作以 `FORBIDDEN` 拒绝 |
| 提供者确认访问 | FileWeft 继续执行并保存 `catalog.folder-id` |

## 04. 稳定的目录 ID

目录 ID 可能起源于数字、UUID 或外部组合键，但宿主应将其转换为稳定字符串。目录改名或换父级时保持 canonical ID；确实需要换 ID 时，执行显式目录移动。

```kotlin
// 宿主返回 canonical 目录 ID，而不是显示名称。
DocumentCatalogFolder(
    id = "folder-42",
    parentFolderId = "folder-7",
    displayName = "Q3 合规报告"
)
```

## 05. 绑定请求的流程

1. 浏览器在创建或更新文档时发送 `folderId=folder-42`。
2. 控制器校验请求格式并转换为领域命令。
3. 应用层使用可信租户与用户上下文调用 `DocumentCatalogProvider.findFolder(..., "folder-42")`。
4. 宿主检查用户是否有权把文档绑定到该目录。
5. FileWeft 保存文件，并在元数据中写入 `catalog.folder-id=folder-42`。
6. 后续读取使用已保存的元数据，不会每次下载都重新解析目录。

## 常见问题

**Q：可以信任浏览器传来的 `folderId` 吗？**
不可以。把它当作不透明提示。真正的授权决定发生在 `DocumentCatalogProvider` 内部，使用可信租户与用户上下文。

**Q：为什么不把目录 ID 放到对象键里？**
因为目录树归宿主所有。如果目录改名或移动，FileWeft 就要重写所有对象键。把目录放在元数据里，可以让 FileWeft 与宿主目录变更解耦。

**Q：如果宿主目录暂时不可用怎么办？**
FileWeft 会把缺少安全变更能力视为 `FEATURE_UNAVAILABLE`，不会继续用租户级默认目录。

## 下一步

- [目录提供者指南](../guides/catalog-provider.md)——在宿主中实现 `DocumentCatalogProvider`。
- [安全模型](./security-model.md)——租户、身份与授权提供者如何协同工作。
- [存储适配器指南](../guides/storage-adapter.md)——让存储键保持与目录结构无关。
