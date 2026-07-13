---
route: "architecture/security"
group: "architecture"
order: 2
locale: "zh"
nav: "安全架构"
title: "所有边界都失败关闭"
lead: "FileWeft 只在完整安全边界存在时才装配能力。上下文缺失、Provider 歧义或未经验证的自定义持久化都会让操作不可用，而不是静默扩大访问范围。"
format: "markdown"
---

## 问题背景

企业文件基础设施位于不可信调用方与敏感字节之间。Provider 解析中的一个小错误、对象键的泄漏，或过度共享的诊断信息，都可能导致租户数据相互暴露。FileWeft 的默认姿态是：只要任何必要边界不明确，就拒绝服务。

## 失败关闭的能力装配

FileWeft 只有在能为每个影响安全的边界确定唯一、无歧义的 Provider 后，才会暴露对应能力：

| 边界 | Provider | 重要性 |
| --- | --- | --- |
| 租户隔离 | `TenantProvider` | 所有查询和存储路径都按当前租户隔离 |
| 身份 | `UserRealmProvider` | 动作归属于真实、已验证的用户 |
| 授权 | `AuthorizationProvider` | 每个受保护动作都请求显式策略决定 |
| 目录结构 | `DocumentCatalogProvider` | 文件夹可见性决定用户能访问哪些文档 |
| 生命周期转换 | 生命周期守卫 | 状态变更强制执行业务审批路径 |

如果出现两个目录 Provider 或两个生命周期候选，FileWeft 不会通过猜测、`@Primary` 或类路径顺序来挑选——只要这种选择可能改变安全语义，操作就会报错并停止。

> **WARNING**
> 不要依赖 Spring `@Primary` 来解析安全敏感的 Provider。FileWeft 会把多个合格候选视为装配失败。

## 自定义持久化必须先证明可信

你可以替换内置持久化，但受保护写入在自定义层证明以下能力之前会被禁用：

1. 在业务事务期间持有真正的修改锁。
2. 与业务写入原子地完成幂等 claim。
3. 在最底层查询层强制执行租户过滤。

没有这些保证，FileWeft 只会暴露只读投影并拒绝命令，以避免并发修改或跨租户写入的风险。

## 公共投影隐藏内部信息

HTTP DTO 被故意设计得很精简。以下内容不会泄漏给 API 调用方：

- 预签名存储 URL 或对象键。
- 连接器内部信息，如远端文档 ID 或凭证。
- Doctor 原始证据。
- 租户标识。
- 异常中的不安全诊断文本。

审计视图只暴露稳定的动作名称和操作者快照，不会返回可能携带敏感 metadata 的任意 `details` JSON。

```bash
# 安全：文档响应只暴露业务标识
curl http://localhost:8080/fileweft/v1/documents/fw-doc-123
```

```json
{
  "code": "OK",
  "message": "OK",
  "data": {
    "documentId": "fw-doc-123",
    "documentNumber": "POL-2025-001",
    "status": "PUBLISHED",
    "versionId": "fw-ver-456",
    "createdBy": "user-alice",
    "updatedTime": 1783900000000
  },
  "error": null,
  "traceId": "trace-abc"
}
```

> **NOTE**
> 下载以二进制流返回，响应头固定包含 `attachment`、`nosniff` 和 `private, no-store`。FileWeft 不会向 API 客户端暴露存储 URL、Range 协商或 ETag。

## 插件是可信代码

FileWeft 插件与宿主应用运行在同一个 JVM 中，共享类加载器、权限和内存空间。进程内没有沙箱。

```kotlin
interface FileWeftPlugin {
    fun id(): String
    fun storageAdapters(): List<StorageAdapter> = emptyList()
    fun connectors(): Map<String, FileConnector> = emptyMap()
    fun doctorCheckers(): List<DoctorChecker> = emptyList()
    // ...
}
```

| 建议 | 避免 |
| --- | --- |
| 安装来自经过评审、已签名制品的插件 | 从不信任的网络下载并加载插件 |
| 将第三方扩展放在独立进程中，通过鉴权协议接入 | 默认授予插件无限制的文件系统或网络访问 |
| 在启动时审计插件 ID 和 Bean 优先级 | 误以为类路径隔离能提供保护 |

> **WARNING**
> **进程内没有沙箱。** 恶意插件可以读取内存、外泄凭证、修改宿主状态。请把每个插件都视为可信计算基的一部分。

## 租户上下文不是请求参数

当前租户始终来自可信的 `TenantProvider`，而不是查询字符串或 JSON 字段。请求参数可以影响业务逻辑，但不能覆盖隔离：

```kotlin
interface TenantProvider {
    fun currentTenant(): TenantContext
}
```

这条规则适用于数据库查询、存储路径前缀、事件路由、任务领取、日志上下文和缓存键。

## 授权检查是显式的

每个受保护命令都会构建一个 `AuthorizationRequest`，并向配置的 `AuthorizationProvider` 请求决定：

```kotlin
interface AuthorizationProvider {
    fun authorize(request: AuthorizationRequest): AuthorizationDecision
}
```

请求包含主体、带 `tenantId` 的资源、动作和环境。Provider 缺失或返回 `DENY` 都会立即失败。不存在隐式允许。

## 常见问题

**Q: 本地开发能否关闭安全检查？**

FileWeft 提供固定租户 fallback 和本地存储 fallback，但仅用于单租户本地开发，不是生产多租户方案。生产部署必须提供真实的 `TenantProvider`、`UserRealmProvider` 和 `AuthorizationProvider` 实现。

**Q: 如果自定义仓储忘记按租户过滤会怎样？**

FileWeft 的 SPI 契约要求查询带租户作用域。如果自定义持久化实现无法证明租户隔离，受保护写入会在启动时保持禁用，并记录装配错误。

**Q: Doctor 报告暴露给运维是否安全？**

文档级 Doctor 报告面向运维设计，但仍会省略原始对象键、连接器凭证和租户标识。在此基础上仍需按角色实施自己的访问控制。

## 下一步

- [一致性模型](/architecture/consistency) — FileWeft 如何在收敛远程系统的同时保持本地状态原子。
- [事件驱动交付](/architecture/event-driven) — 事件如何在不出租租户边界的情况下传播。
- [存储适配器指南](/guides/storage-adapter) — 实现遵守相同失败关闭边界的存储后端。
