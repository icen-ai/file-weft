---
route: "reference/domain-model"
group: "reference"
order: 5
locale: "zh"
nav: "领域模型"
title: "核心领域模型"
lead: "FileWeft 把业务概念与基础设施分离。本页展示核心实体、职责以及它们如何与存储、工作流和交付产生关联。"
format: "markdown"
---

## 模型概览

```
TenantContext ──► Document ──► Version ──► FileAsset ──► StorageObjectLocation
     │              │            │
     │              ▼            ▼
     │         Lifecycle    Workflow / ReviewTask
     │              │            │
     ▼              ▼            ▼
AuthorizationDecision   OutboxEvent   DeliveryTarget
```

上图是概念性的。领域层不包含 Spring、ORM 或厂商 SDK，适配器和持久化负责把这些概念映射到真实基础设施。

## Document

`Document` 是顶层业务记录，包含不透明标识符、可信租户范围、业务元数据、当前生命周期状态以及指向最新 `Version` 的引用。

文档不存储字节。内容通过 `Version` 关联到 `FileAsset`。

## Version

`Version` 表示文档的一个不可变代次。创建新版本会保留历史并开启新的交付代次。

每个版本引用：

- 所属文档
- 代次或序列标记
- 关联的 `FileAsset`
- 上传时提取的业务元数据

> [!NOTE]
> 交付按代次隔离。旧版本的延迟结果不能覆盖当前文档状态。

## FileAsset 与存储位置

`FileAsset` 记录一次成功上传的结果，包括内容大小、内容类型以及对领域不透明的 `StorageObjectLocation`。

领域层从不打开 `InputStream`，也不直接访问桶。只有在应用层需要字节时，才会把位置传给 `StorageAdapter`。

## Lifecycle

文档生命周期是证据而非标志位。常见路径为：

```
DRAFT → PENDING_REVIEW → PUBLISHED → OFFLINE → ARCHIVED
```

| 流转 | 命令 | 结果 |
|------|------|------|
| 开始审批 | `submit` | `DRAFT` → `PENDING_REVIEW` |
| 直接发布 | `publish` | `DRAFT` 或审批通过 → `PUBLISHED` |
| 下线 | `offline` | `PUBLISHED` → `OFFLINE` |
| 恢复草稿 | `restore` | `OFFLINE` → `DRAFT` |
| 长期归档 | `archive` | `OFFLINE` → `ARCHIVED` |
| 新建代次 | `revise` | 创建新 `Version` 并遵守状态规则 |

状态变更、审计记录和 Outbox 事件在同一个业务事务中一起提交。

## Workflow 与审批任务

`DocumentReviewRouteProvider` 将路由解析为若干 `ReviewTask`。任务默认并行；全部通过才发布，任一驳回结束审批。

```kotlin
interface DocumentReviewRouteProvider {
    fun id(): String
    fun resolve(request: DocumentReviewRouteRequest): DocumentReviewRoute
}
```

路由解析在 FileWeft 数据库事务外执行，以便宿主策略安全地查询外部系统。

## DeliveryTarget

文档发布时，FileWeft 会为当前同步 profile 中的每个目标创建一个交付尝试。

| 字段 | 含义 |
|------|------|
| `targetId` | Profile 目标标识 |
| `connectorId` | 负责该目标的 `FileConnector` Bean 名称 |
| `required` | 失败是否阻塞 `PUBLISHED` |
| `externalId` | 下游系统返回的标识 |
| `status` | `SUCCESS`、`RETRYABLE_FAILURE` 或 `PERMANENT_FAILURE` |

所有必达目标成功后，文档才进入 `PUBLISHED`。可选目标失败不会阻塞发布。已成功目标不会被回滚。

## Outbox 与 Task

领域层不直接调用外部系统，而是发出 Outbox 事件。例如，当文档下线或归档时，FileWeft 会写入 `document.delivery.target.removal.requested` 事件，让每个下游目标异步清理。

`FileWeftTaskHandler` 以 at-least-once 语义处理持久任务，handler 必须按 task id 幂等。

```kotlin
interface FileWeftTaskHandler {
    fun supports(task: TaskExecution): Boolean
    fun handle(task: TaskExecution): TaskHandlingResult
    fun onExhausted(task: TaskExecution, message: String) = Unit
}
```

## 审计与租户上下文

每次状态变更都会产生审计记录，绑定到可信的 `UserIdentity` 和 `TenantContext`。租户上下文影响查询、存储路径、事件、任务、日志和缓存。

> [!WARNING]
> 不要信任请求参数中的 `tenantId`。FileWeft 始终向 `TenantProvider` 索取当前可信租户。

## 各层实体归属

| 层 | 负责 |
|---|------|
| `core` | 标识符、结果、错误、事件、上下文 |
| `spi` | 租户、身份、存储、目录、工作流、连接器、Doctor、任务契约；遗留 Agent ABI 仅兼容保留 |
| `domain` | Document、FileAsset、Lifecycle、Version、Workflow、Audit 规则 |
| `application` | 上传、发布、下线、审批、Doctor、同步编排等用例 |
| `adapter` | 宿主或插件提供的存储/连接器实现；OSS、Dify、ESE、AppBuilder 官方适配器仍是未来路线图工作 |
| `persistence` | 仓储实现与 Flyway 迁移 |

## 常见问题

**一个文档可以有多个当前版本吗？**
不可以。文档只指向一个当前版本，但完整版本历史会被保留。

**实际文件内容存在哪里？**
在 `StorageAdapter` 背后的对象存储后端。领域层只知道不透明的 `StorageObjectLocation`。

**为什么工作流在数据库事务外解析？**
这样宿主策略可以调用外部目录或身份系统，同时避免持有数据库锁或产生回滚副作用。

## 下一步

- [SPI 总览](./spi.md)
- [生命周期与交付概念](../concepts/lifecycle-delivery.md)
- [模块边界](../concepts/module-boundaries.md)
