# FileWeft

FileWeft 是面向企业的 Kotlin/JVM 文件智能基础设施。

当前实现已完成任务书定义的基础链路：`core → spi → domain → application → persistence → starter → adapter → doctor → agent`，并提供本地存储、诊断、确认式 Agent 任务与可重试 Outbox Worker 基线。

## 构建要求

- 构建运行时：JDK 17+（当前验证环境为 JDK 21）
- 核心及除 Spring Boot 3 Starter 外的模块：产物字节码兼容 Java 8
- Spring Boot 3 Starter：产物字节码兼容 Java 17

## 验证

```powershell
.\gradlew.bat check
```

依赖版本通过 `gradle/libs.versions.toml` 管理，所有配置启用依赖锁定。

## 本地开发

启动仅包含 PostgreSQL 与 RustFS 的基础服务：

```powershell
docker compose -f .docker\docker-compose.dev.yaml up -d postgres
```

## 开发验收台

仓库内的 `fileweft-dev` 是开发专用的可运行验收应用，不会被核心、领域或 SPI 依赖。它通过真实 PostgreSQL、RustFS（S3 兼容）、Outbox Worker 和独立 HTTP 下游平台覆盖上传、版本、审批、发布、多下游交付、审计与 Doctor。

启动完整编排：

```powershell
docker compose -f .docker\docker-compose.dev.yaml up -d --build --wait
```

| 服务 | 地址 | 用途 |
| --- | --- | --- |
| 验收控制台 | http://127.0.0.1:8088 | 登录、文档流转、审批、Doctor、下游镜像 |
| FileWeft 开发 API | http://127.0.0.1:8080 | 验收 API |
| 模拟下游平台 | http://127.0.0.1:8081 | 接收发布同步并验证预签名 S3 下载 |
| RustFS 控制台 | http://127.0.0.1:9001 | S3 开发对象存储 |

预置开发用户：

| 用户名 | 密码 | 角色 |
| --- | --- | --- |
| `admin@alpha` | `dev-admin` | 管理员 |
| `editor@alpha` | `dev-editor` | 编辑者 |
| `reviewer@alpha` | `dev-reviewer` | 审批者 |
| `viewer@alpha` | `dev-viewer` | 只读者 |

开发应用使用独立的 `fileweft_dev` 和 `fileweft_dev_platform` schema；不会读取或覆写 `public` schema 的测试数据。预置账号和密码只适用于本地开发容器，禁止用于任何生产环境。

审计将用户 ID 视为不透明字符串，并同时保存操作发生时的显示名快照。接入方可在 `UserRealmProvider` 中将 Long、Int、UUID 或其他身份系统 ID 转为字符串；FileWeft 不维护用户表，也不会在查询历史审计时反查并改写原有操作者名称。

验收控制台默认英文，可切换完整中文。其“角色验收实验室”内置 TXT、Markdown、CSV、JSON 文件样例：拥有创建权限的用户可将它们上传为真实 RustFS 草稿；审批、Outbox 与只读路线则只展示当前用户经服务端授权的操作控件。

运行完整 Compose 验收回归：

```powershell
$env:FILEWEFT_RUN_DEV_E2E='true'
.\gradlew.bat :fileweft-dev:test
```

该测试会创建唯一编号文档，验证编辑者上传和提交、单人审批与双人会签、管理员处理 Outbox、下游平台下载 RustFS 对象，并覆盖可选下游失败与必达下游人工恢复。

## 多下游交付

发布不再把“所有下游”折叠成一个同步结果。接入方通过 `DocumentDeliveryProfileProvider` 为租户提供可选交付档案；每个档案由多个 `DocumentDeliveryTargetDefinition` 组成，目标使用不透明字符串 `id`、`connectorId` 和可选 `ownerRef`，并声明为 `REQUIRED` 或 `OPTIONAL`。`DeliveryConnectorResolver` 将 `connectorId` 解析为实际的 `FileConnector`，不把 Spring 或厂商 SDK 泄漏到 SPI。

审批或直接发布时，FileWeft 在同一业务事务中冻结目标快照，并为每个目标写入独立 Outbox 事件。目标记录含状态、外部 ID、失败原因与重试次数，因此一个目标重试不会重复推送已成功的目标。

- 全部必达目标成功：文档成为 `PUBLISHED`。
- 必达目标重试中或失败：文档显示 `SYNC_ERROR`，Outbox 继续按策略重试；恢复成功后自动回到 `PUBLISHED`。
- 可选目标失败：文档仍可 `PUBLISHED`，但交付记录保留“待处理”、责任引用和错误原因。
- 不执行默认分布式回滚：成功下游不会因为另一个下游失败而被自动删除。删除/撤回必须由业务显式发起，避免误删已生效的外部记录。
- 重试耗尽后，拥有 `document:delivery:retry` 权限的管理员可只重排失败目标；原目标 ID 同时作为连接器幂等键。

文档下线或归档属于显式撤回：系统会在同一业务事务中为每个已经成功交付的目标冻结一条 `document.delivery.target.removal.requested` Outbox 事件。外部 `FileConnector.remove` 调用仍只在 Worker 中执行；撤回拥有独立于“交付成功”的 `PENDING`、`RETRYING`、`SUCCEEDED`、`FAILED` 状态和重试次数，因此不会把“曾经交付成功”误显示为“已撤回”。暂时性失败由 Worker 自动重试，永久失败可由拥有同一 `document:delivery:retry` 权限的管理员只重排该目标的撤回事件。连接器应返回外部 ID；若历史连接器未返回，框架会以稳定的文档 ID 作为撤回调用的回退外部键，连接器实现必须保证该键可幂等处理。

Starter 可直接使用单连接器兼容默认档案；多连接器可在配置中声明档案，或替换下列 SPI 实现以接入租户自己的策略中心：

```yaml
fileweft:
  sync:
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
```

开发验收台预置 `regulated`（合规、协作必达；搜索可选）和 `internal`（协作必达）两个档案；文档检视器会展示每个目标的责任组、状态、错误和重试次数，并按服务端权限显示人工重试控件。

## 可插拔审批路由

`DocumentReviewRouteProvider` 是审批人和会签策略的 SPI。它接收当前租户、文档的不可变摘要、提交人和可选的请求审批人，返回工作流类型和一个或多个审批任务；任务中的用户 ID 与审计用户 ID 一样都是不透明字符串。路由解析发生在 FileWeft 数据库事务之外，解析完成后系统会在最终事务中重新读取并确认文档没有变化，避免远程策略调用占用事务连接。

默认路由 ID 为 `default`，保持原来的兼容行为：创建一个指定审批人（或未指派）的 `DOCUMENT_REVIEW` 任务。路由返回多个任务时属于并行会签：所有任务批准前文档维持 `PENDING_REVIEW`，不会创建发布或下游交付事件；任一任务驳回则结束该工作流。已有接入无需迁移。宿主可将实现注册为 Spring Bean 或从 `FileWeftPlugin.reviewRouteProviders()` 贡献，并配置默认路由：

```yaml
fileweft:
  workflow:
    default-review-route-id: finance-dual-control
```

路由提供者若查询外部组织、角色或策略系统，必须自行实现超时、重试、幂等和 Doctor/监控记录；它不应在其调用路径中持有 FileWeft 的数据库事务。开发验收台提供 `dual-control` 路由，Alpha 和 Beta 两个租户均会分配“审批者 + 管理员”两个任务；编辑者在草稿操作区可选择标准审批或双人会签，以验证第一人通过后仍未发布的行为。

## 断点续传上传与完整性

`ResumableUploadService` 面向大文件和不稳定网络提供持久化 multipart 会话。宿主服务可按下列顺序封装自己的 HTTP 或 RPC API：

1. 以稳定的调用方幂等键执行 `start(StartResumableUploadCommand)`。
2. 用 `uploadPart(sessionId, partNumber, contentLength, stream)` 逐片上传；每片确认后可安全刷新页面或重试。
3. 用 `inspect(sessionId)` 从服务端读取已确认分片，再继续缺失分片。
4. 用 `complete(sessionId)` 幂等创建 `FileObject`、`FileAsset` 与 `file.uploaded` Outbox 事件；放弃时用 `abort(sessionId)`。

会话和分片的用户操作全部带租户条件；跨租户扫描只存在于受控 Worker 的过期清理，不接受任何前端租户参数。底层对象存储 upload ID、存储路径及凭据不得交给前端；开发验收台只把安全的会话视图保存在浏览器本地。完成中的会话不被自动删除，因为对象存储可能已接受完成请求；该保守策略避免生成指向已删除对象的文件记录。

普通上传、文档初版、新增版本与续传完成都会验证对象长度；调用方提供 `contentHash` 时还会验证 SHA-256。校验失败会补偿删除远端对象，绝不会写入不完整的文件、文档或 Outbox 状态。生产 Worker 的 TTL、批量清理和运行角色配置见 [生产部署与恢复](docs/production-operations.md)。

## 持久化后台任务与 Doctor

`fw_task` 是独立于 Outbox 的通用后台任务表，适用于 Doctor、AI、索引、转码等可恢复工作。它采用 PostgreSQL `SKIP LOCKED` 领取任务，并使用带所有者的过期租约：Worker 宕机后，超过租约的 `RUNNING` 任务会重新变为可领取状态。处理器通过 `FileWeftTaskHandler` SPI 注册，必须以任务 ID 实现幂等；框架统一处理退避重试、重试耗尽和本地失败投影。

Doctor 提供两条受控路径：即时检查用于交互式请求；异步检查在请求时先完成 `document:doctor` 授权，随后由无用户会话的后台 Worker 仅执行只读技术检查。结果写入 `fw_doctor_record`，因此运营者可保留诊断历史，而不会让后台线程绕过用户权限。开发验收台可排队 Doctor、查看任务状态与打开历史报告；仅管理员可手动处理任务队列。

## 操作日志与请求追踪

每一条由 `AuditTrail` 写入的业务审计记录，都会在同一个应用事务内镜像为 `fw_operation_log`。两者共享同一个不可变 ID，并保留租户、资源、动作、外部用户 ID、显示名快照、JSON 明细与发生时间；操作日志额外保存可选的 `trace_id`。因此审计语义保持兼容，而运维系统可以按资源或 Trace 聚合操作证据。

`TraceContextProvider` 是不绑定日志框架或链路追踪厂商的 SPI。Starter 默认提供安全的空实现；接入 OpenTelemetry、Micrometer Tracing、消息头或其他宿主追踪系统时，只需提供该 SPI Bean。开发验收应用会接受格式受限的 `X-Trace-Id`（否则生成新的 ID），在响应中回显，并在文档检视器的“操作追踪”区域展示。

Outbox 将 Trace 作为独立的 `trace_id` 持久化字段，而不是混入业务 payload。Worker 在处理时若宿主提供 `TraceContextScope`，会暂时恢复事件 Trace、运行连接器与审计投影，随后恢复 Worker 原上下文；因此重试和多下游交付仍可关联到最初的发布/审批操作。接入方使用 OpenTelemetry 等追踪系统时，应同时实现 `TraceContextScope`；未实现时仍安全运行，只是不建立跨异步上下文。

## 受控文件下载

`DocumentDownloadService` 是文档内容的唯一应用层入口：它在读取对象前按当前租户执行 `document:download` 授权，校验版本属于文档，再从对应的 tenant-scoped 文件对象流式读取。下载意图以 `document:download` 写入审计和操作日志；Storage URL 不会作为前端 API 返回值，因此前端权限不能绕过 FileWeft 的服务端授权。

开发验收 API 提供当前版本和指定历史版本的流式端点。Dev 控制台只为服务端下发了 `document:download` 的角色显示下载控件；按钮使用带 Bearer Token 的 API 请求而非裸 S3 地址，便于演示下载授权与跨租户拒绝。

Spring Boot Starter 默认使用本地存储；可通过以下配置修改根目录：

```properties
fileweft.storage.local-root=./fileweft-data
```

运行 PostgreSQL 集成测试：

```powershell
$env:FILEWEFT_RUN_POSTGRES_TESTS='true'
.\gradlew.bat :fileweft-persistence:test
```

> 集成测试会重置开发库的 `public` schema，只能连接专用开发/测试数据库，不能指向任何生产数据库。
