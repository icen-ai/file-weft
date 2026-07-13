# FileWeft 文档重写共享上下文

> 供所有子代理使用的统一事实来源与风格指南。禁止编造未在仓库中体现的 API、配置或行为。

## 最高优先级产品决策：Agent 无限期延后

`0.0.2` 不提供 FileWeft Agent 产品能力。Agent 将重新设计，但只能在
`1.0.0` 正式发布后才允许重新评估，且这不构成对 `1.x`、下一版本或任何版本的
交付承诺。现有 `fileweft-agent`、Agent SPI/公共 ABI 及 V012/V026 中相关表和列
仅为源码、二进制与数据库兼容保留；默认运行时、Doctor/插件清单、正式 HTTP 与
Dev 均不暴露。本文后续任何 Agent 描述如与本段冲突，均视为历史事实或兼容说明，
不得写成当前能力、接入教程或 Roadmap 承诺。

## 1. 项目定位（必须原样使用）

FileWeft 是面向企业的 Kotlin/JVM 文件智能基础设施，不是简单的文件上传模块、业务文档系统、Dify/ESE 包装或云存储 SDK。它是可扩展的基础设施框架。

正式 Maven group：`ai.icen`；JVM 包名：`ai.icen.fw`。本文面向 `v0.0.2` 发布合同；`ai.icen:*:0.0.2` 只有在受保护标签流水线及远端匿名冷缓存解析成功后才可作为稳定制品消费，不能仅凭源码或标签预先声称远端验证成功。已撤回旧试推坐标 `com.fileweft:*:0.0.1`，不得使用；正式 `ai.icen:*:0.0.1` 仍是必须保留的历史升级边界。

## 2. 架构分层（禁止改方向）

```
starter
    ↓
application
    ↓
domain
    ↓
core

adapter
    ↓
spi
```

- core：标识、结果、错误、事件、上下文。不依赖 Spring/ORM/外部 SDK。
- spi：身份、授权、租户、存储、连接器、工作流、AI、任务、诊断契约。无实现。
- domain：文档、文件资产、生命周期、版本、工作流、审计领域规则。无基础设施调用。
- application：上传、发布、下线、审批、Doctor、同步编排等用例。
- adapter：MinIO/OSS/S3/Dify/ESE/AppBuilder 等外部实现。
- persistence：PostgreSQL、MySQL、KingbaseES、Flyway 迁移与仓储实现。
- starter：Boot 2/3 自动装配。

## 3. 核心设计原则（必须体现）

- SPI 优先：先检查是否应有 SPI，再增加依赖。
- 失败关闭：缺失上下文或歧义 Provider 让操作不可用，不静默扩大访问。
- 本地原子，显式收敛：不承诺跨 PostgreSQL/对象存储/下游的分布式事务。
- 外部系统不可靠：连接器必须实现超时、重试、幂等、错误记录、健康检查。
- 不在数据库事务中调用外部系统：业务事务 → Outbox 事件 → 异步 Worker → 连接器。
- 多租户隔离：租户影响查询、存储路径、事件、任务、日志、缓存；不信任请求参数中的 tenantId。
- Doctor 是一等公民：主要组件提供可诊断能力。
- 公共 API 保持 Java 友好：禁止 public API 使用 suspend、Flow、value class、sealed interface、data object。

## 4. 关键 SPI 契约（只写真实存在的方法）

### 4.1 身份与租户

```kotlin
interface TenantProvider {
    fun currentTenant(): TenantContext
}

interface UserRealmProvider {
    fun currentUser(): UserIdentity?
    fun findUser(userId: Identifier): UserIdentity?
}

interface AuthorizationProvider {
    fun authorize(request: AuthorizationRequest): AuthorizationDecision
}
```

AuthorizationRequest 含 subject、resource（带 tenantId）、action、environment。用户 ID 是区分大小写、不 trim/归一化的不透明字符串，最多 256 UTF-16 code unit，首尾无 Unicode whitespace，不含 ISO control/format 字符。

### 4.2 存储

```kotlin
interface StorageAdapter {
    fun upload(request: StorageUploadRequest, content: InputStream): StoredObject
    fun download(location: StorageObjectLocation): StorageDownload
    fun delete(location: StorageObjectLocation)
    fun exists(location: StorageObjectLocation): Boolean
    fun accessUrl(location: StorageObjectLocation, expiresIn: Duration): URI
    fun beginMultipartUpload(request: StorageUploadRequest): MultipartUpload
    fun uploadPart(upload: MultipartUpload, partNumber: Int, content: InputStream, contentLength: Long): MultipartPart
    fun completeMultipartUpload(upload: MultipartUpload, parts: List<MultipartPart>): StoredObject
    fun abortMultipartUpload(upload: MultipartUpload)
}
```

### 4.3 目录

```kotlin
interface DocumentCatalogProvider {
    fun listFolders(tenantId: Identifier): List<DocumentCatalogFolder>
    fun listFolders(request: DocumentCatalogAccessRequest): List<DocumentCatalogFolder>
    fun findFolder(tenantId: Identifier, folderId: String): DocumentCatalogFolder?
    fun findFolder(request: DocumentCatalogAccessRequest, folderId: String): DocumentCatalogFolder?
}
```

目录 ID 写入资产 metadata key `catalog.folder-id`。对象存储路径不包含目录 ID。

### 4.4 审批路由

```kotlin
interface DocumentReviewRouteProvider {
    fun id(): String
    fun resolve(request: DocumentReviewRouteRequest): DocumentReviewRoute
}
```

解析在 FileWeft 数据库事务外执行；多任务并行会签，全部通过才发布，任一驳回结束。

### 4.5 连接器

```kotlin
interface FileConnector {
    fun sync(request: ConnectorSyncRequest): ConnectorSyncResult
    fun remove(request: ConnectorRemoveRequest): ConnectorSyncResult
    fun health(): ConnectorHealth
}
```

状态：SUCCESS、RETRYABLE_FAILURE、PERMANENT_FAILURE。健康：HEALTHY、DEGRADED、UNHEALTHY。连接器必须有超时、重试、幂等、健康检查。

### 4.6 后台任务

```kotlin
interface FileWeftTaskHandler {
    fun supports(task: TaskExecution): Boolean
    fun handle(task: TaskExecution): TaskHandlingResult
    fun onExhausted(task: TaskExecution, message: String) = Unit
}
```

语义为 at-least-once，handler 以 task id 幂等。

### 4.7 Doctor

```kotlin
interface DoctorChecker {
    fun name(): String
    fun check(context: DoctorCheckContext): DoctorCheckResult
}
```

必须无副作用、返回可操作结果而不是抛出异常。

### 4.8 插件

```kotlin
interface FileWeftPlugin {
    fun id(): String
    fun storageAdapters(): List<StorageAdapter> = emptyList()
    fun connectors(): Map<String, FileConnector> = emptyMap()
    fun doctorCheckers(): List<DoctorChecker> = emptyList()
    fun agents(): List<FileWeftAgent> = emptyList()
    fun agentTaskTriggers(): List<AgentTaskTrigger> = emptyList()
    fun outboxEventHandlers(): List<OutboxEventHandler> = emptyList()
    fun taskHandlers(): List<FileWeftTaskHandler> = emptyList()
    fun reviewRouteProviders(): List<DocumentReviewRouteProvider> = emptyList()
}
```

插件是可信进程内代码，不是安全沙箱。优先级：客户 Bean > 插件 Bean > 框架默认。

## 5. 正式 HTTP API v1（只写已交付路由）

前缀 `/fileweft/v1`。统一 JSON 外层：

```json
{
  "code": "OK",
  "message": "OK",
  "data": {},
  "error": null,
  "traceId": "optional-host-trace-id"
}
```

稳定错误码：INVALID_REQUEST、UNAUTHENTICATED、FORBIDDEN、NOT_FOUND、METHOD_NOT_ALLOWED、NOT_ACCEPTABLE、UNSUPPORTED_MEDIA_TYPE、RANGE_NOT_SUPPORTED、CONFLICT、FEATURE_UNAVAILABLE、CONTENT_UNAVAILABLE、OUTCOME_UNKNOWN、INTERNAL_ERROR。

已交付路由：
- 上传会话：`POST /uploads`、`GET /uploads/{uploadId}`、`PUT /uploads/{uploadId}/parts/{partNumber}`、`POST /uploads/{uploadId}/complete`、`DELETE /uploads/{uploadId}`
- 文档：`GET /documents`、`POST /documents`、`GET /documents/{documentId}`、`PATCH /documents/{documentId}`、`POST /documents/{documentId}/versions`、`GET /documents/{documentId}/content`、`GET /documents/{documentId}/versions/{versionId}/content`
- 生命周期/审批：`POST /documents/{documentId}/revise|publish|offline|restore|archive|submit`、`POST /workflows/{workflowId}/tasks/{taskId}/approve|reject`、`GET /workflows/tasks`、`GET /documents/{documentId}/workflows`、`GET /documents/{documentId}/workflow-decisions`
- 交付：`GET /documents/{documentId}/sync-status`、`POST /documents/{documentId}/deliveries/{deliveryId}/retry`、`POST /documents/{documentId}/deliveries/{deliveryId}/removal/retry`
- 审计/日志：`GET /documents/{documentId}/logs`
- Doctor：`GET /documents/{documentId}/doctor`、`POST /documents/{documentId}/doctor/tasks`、`GET /documents/{documentId}/doctor/tasks/{taskId}`、`GET /doctor`
- 系统：`GET /plugins`、`GET /health`

下载成功为二进制流，固定 `attachment`、`nosniff`、`private, no-store`，不提供 Range/HEAD/ETag/Content-Range/存储 URL。

## 6. 配置要点（真实配置）

```yaml
fileweft:
  persistence:
    migration-mode: validate # migrate | validate | disabled
    schema: fileweft
    create-schema: false
  worker:
    enabled: true
    fixed-delay-millis: 1000
    outbox-batch-size: 50
    task-batch-size: 50
    process-outbox: true
    process-tasks: true
    process-upload-cleanup: true
  outbox:
    lease-duration-millis: 300000
    legacy-running-grace-millis: 300000
    backlog-metrics-enabled: true
    backlog-metrics-interval-millis: 30000
    backlog-metrics-query-timeout-seconds: 5
  task:
    lease-duration-millis: 60000
    legacy-running-grace-millis: 300000
  sync:
    connector-timeout-millis: 30000
    source-access-url-ttl-millis: 900000
    circuit-breaker-failure-threshold: 3
    circuit-breaker-open-duration-millis: 30000
    connector-max-concurrent-invocations: 16
    connector-invocation-queue-capacity: 256
    default-profile-id: regulated
    profiles:
      - id: regulated
        display-name: 受监管发布
        targets:
          - id: compliance
            display-name: 合规归档
            connector-id: complianceConnector
            required: true
            owner-ref: compliance-ops
          - id: search
            display-name: 检索索引
            connector-id: searchConnector
            required: false
            owner-ref: search-ops
  upload:
    resumable-session-ttl-millis: 86400000
    resumable-cleanup-batch-size: 100
```

开发 fallback（仅用于固定单租户/开发/单节点）：

```properties
fileweft.default-tenant-enabled=true
fileweft.default-tenant-id=tenant-a
fileweft.storage.local-enabled=true
fileweft.storage.local-root=/var/lib/fileweft
```

## 7. 生命周期状态

文档生命周期关键状态：DRAFT → PENDING_REVIEW → PUBLISHED → OFFLINE → ARCHIVED。恢复草稿使用 `restore`（OFFLINE → DRAFT）。

多下游交付：
- 全部必达目标成功 → PUBLISHED
- 必达目标失败/重试 → SYNC_ERROR，继续重试
- 可选目标失败 → 仍可 PUBLISHED，记录保留
- 不回滚已成功目标
- 下线/归档为每个已交付目标写入 `document.delivery.target.removal.requested` Outbox 事件

## 8. 指标与可观测性

计数器：`fileweft.upload_count`、`fileweft.upload_failure`、`fileweft.sync_success`、`fileweft.sync_failure`、`fileweft.delivery_removal_success`、`fileweft.delivery_removal_failure`、`fileweft.doctor_failure`、`fileweft.task_success`、`fileweft.task_failure`。

Gauge：`fileweft.outbox_backlog`（state: ready/delayed/running/expired/failed）、`fileweft.outbox_oldest_ready_age_seconds`、`fileweft.outbox_backlog_observation_failure`。

标签禁止 tenantId、文档 ID、用户 ID 等高基数/敏感信息。

## 9. 文档风格要求（sa-token 级开源标准）

- 每页必须有一段引人入胜的 lead/前言，说明“这页解决什么问题”。
- 使用编号步骤、比较表格、提示框（NOTE/WARNING/TIP）、代码块。
- 代码示例必须完整、可运行或接近可运行；不要半截代码。
- 关键概念用类比或图示性描述解释，不要只列定义。
- 中英文双语必须内容对应，中文自然，英文专业。
- 每页顶部使用 frontmatter（见现有页面格式）。
- Markdown 格式优先，必要时用 HTML 表格/callout。
- 包含“常见问题”或“下一步”小节。
- 不要编造不存在的 API、配置或行为。

## 10. 禁止事项

- 不要把厂商 SDK 泄漏到 Core、Domain、SPI。
- 不要把 FileWeft 描述成单纯上传工具或网盘。
- 不要仅凭 `0.0.2` 源码、预期标签或本地制品声称远端发布/冷缓存验证已经成功；稳定可消费状态必须以受保护标签流水线和匿名远端解析证据为准。
- 不要把 Dev-only 的 `/api/**` 描述成正式公共协议。
- 不要把固定租户/本地存储 fallback 描述成生产多租户方案。
