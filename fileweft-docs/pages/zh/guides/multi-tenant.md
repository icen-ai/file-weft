---
route: "guides/multi-tenant"
group: "guides"
order: 8
locale: "zh"
nav: "多租户"
title: "实现租户隔离"
lead: "通过实现 TenantProvider SPI，让租户在数据库查询、存储路径、事件、任务、日志和缓存中都被安全隔离。"
format: "markdown"
---

FileWeft 从设计之初就面向多租户部署。租户隔离不是某个配置开关，而是跨查询、存储路径、事件、任务、日志和缓存的横切职责。本页说明如何提供租户上下文，以及为什么不能信任请求参数中的租户 ID。

## 1. 提供租户上下文

租户身份的唯一稳定来源是 `TenantProvider` SPI。FileWeft 在需要当前租户时都会调用它。

```kotlin
@Component
class SecurityTenantProvider : TenantProvider {

    override fun currentTenant(): TenantContext {
        val authentication = SecurityContextHolder.getContext().authentication
        val tenantId = authentication.details as? String
            ?: throw IllegalStateException("Tenant not resolved by host")
        return TenantContext(Identifier(tenantId))
    }
}
```

典型宿主集成从以下来源派生租户：

- 身份提供方签发的 JWT claim。
- 网关校验后的自定义 HTTP 头。
- mTLS 客户端证书属性。
- 入口层映射的子域名或路径前缀。

> [!WARNING]
> 不要在 FileWeft 内部直接读取 `X-Tenant-Id` 等客户端头。只信任由认证层建立的上下文。

## 2. 数据库查询隔离

所有 FileWeft 业务表都包含 `tenant_id`。仓储对每个查询都按当前租户上下文过滤，聚合和计数查询也不例外。

这意味着：

1. 缺少 `TenantProvider` 会让操作失败关闭，而不是回退到共享租户。
2. 相同文档编号在不同租户下不会互相看到对方的草稿。
3. 审计日志、工作流任务和 Outbox 事件全部按租户隔离。

## 3. 存储路径隔离

存储适配器通过 `StorageUploadRequest.tenantId` 拿到租户 ID。行为良好的适配器会把租户 ID 包含在对象名或桶前缀中：

```kotlin
private fun resolvePath(tenantId: Identifier, objectName: String): Path =
    root.resolve(tenantId.value).resolve(objectName)
```

对于 S3 等对象存储，使用键前缀而不是每个租户一个桶，除非你的运维模型确实需要：

```
s3://bucket/{tenantId}/documents/{objectName}
```

> [!NOTE]
> 租户 ID 也会写入资产元数据，但对象键必须独立于元数据保持租户隔离。

## 4. 事件、任务、日志与缓存

租户上下文贯穿所有异步路径：

| 关注点 | 隔离机制 |
| --- | --- |
| Outbox 事件 | 每个事件携带 `tenantId`；Worker 只处理解析租户的事件。 |
| 后台任务 | `fw_task` 行包含 `tenant_id`；Handler 通过 `TaskExecution.tenantId` 接收。 |
| 审计日志 | 查询端点按当前租户上下文过滤。 |
| 缓存 | 缓存键必须包含租户 ID。FileWeft 不提供跨租户共享缓存。 |

## 5. 开发 fallback 不是生产多租户方案

本地开发和单节点测试可以启用固定租户：

```yaml
fileweft:
  default-tenant-enabled: true
  default-tenant-id: tenant-a
```

这个 fallback 对集成测试和演示有用，但不是生产多租户方案。生产环境必须：

1. 禁用 `default-tenant-enabled`。
2. 提供由身份网关支撑的真正的 `TenantProvider`。
3. 验证每个仓储查询的执行计划都包含 `tenant_id`。

> [!WARNING]
> 不要把固定租户或本地存储 fallback 描述成生产多租户方案。它们会消除隔离，仅用于开发。

## 6. 失败模式

| 场景 | 行为 |
| --- | --- |
| TenantProvider 返回 null | 操作以 `FEATURE_UNAVAILABLE` 或 `UNAUTHENTICATED` 失败。 |
| URL 参数中的租户与上下文不一致 | URL 参数被忽略；上下文租户优先。 |
| 缓存键遗漏租户 ID | 宿主缓存存在跨租户数据泄漏风险。 |
| 存储适配器忽略 tenantId | 存在跨租户对象访问风险。 |

## 常见问题

**Q：租户可以共享同一个数据库 schema 吗？**
可以。FileWeft 使用 `tenant_id` 行级隔离。使用一个 schema 还是多个 schema 是宿主的运维选择。

**Q：我可以在一次请求内切换租户吗？**
不可以。租户上下文是请求作用域的。跨租户的批量操作应拆分为多个请求或后台任务。

**Q：如何测试租户隔离？**
使用 `fileweft-testkit`，用两个不同的租户上下文运行同一操作，断言各自只能看到自己的数据。

## 下一步

- [Spring Boot 宿主](spring-boot.md) 装配 `TenantProvider` Bean。
- [审计日志](audit-log.md) 了解租户上下文如何保护审计查询。
- [存储适配器](storage-adapter.md) 实现租户级对象路径。
