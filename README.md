# FileWeft

FileWeft 是面向企业的 Kotlin/JVM 文件智能基础设施。

当前实现已完成任务书定义的基础链路：`core → spi → domain → application → persistence → starter → adapter → doctor → agent`，并提供本地存储、诊断、确认式 Agent 任务与可重试 Outbox Worker 基线。

`.ai` 手册的基础能力对照、验证命令以及开源发布前仍需由项目所有者决定的事项见[实现对照与发布门槛](docs/implementation-status.md)。

## 构建要求

- 构建运行时：JDK 17+（当前验证环境为 JDK 21）
- 核心及除 Spring Boot 3 Starter 与开发验收应用外的模块：产物字节码兼容 Java 8，`check` 会额外在 Java 8 运行时执行其测试
- Spring Boot 3 Starter：产物字节码兼容 Java 17

## 验证

```powershell
.\gradlew.bat check
.\gradlew.bat compatibilityCheck
.\gradlew.bat verifySbom
```

`compatibilityCheck` 是发版前的 JVM 运行时门禁：Java 8 基线模块会在 Java 8、11、21、25 上运行测试；Java 17 模块会在 Java 17、21、25 上运行测试。Gradle 工具链会自动取得本机缺失的受支持 JDK，首次执行可能需要下载并花费较长时间；日常 `check` 仍只强制 Java 8 基线回归。

依赖版本通过 `gradle/libs.versions.toml` 管理，所有配置启用依赖锁定。`verifySbom` 生成并校验全仓 CycloneDX JSON/XML 物料清单，适合在发版流水线中归档。

## 本地开发

启动仅包含 PostgreSQL 与 RustFS 的基础服务：

```powershell
docker compose -f .docker\docker-compose.dev.yaml up -d postgres
```

## 开发验收台

仓库内的 `fileweft-dev` 是开发专用的可运行验收应用，不会被核心、领域或 SPI 依赖。它真实装配正式的 `fileweft-web-spring-boot3-starter`，控制台 Nginx 将 `/fileweft/` 代理到开发 API；同时通过真实 PostgreSQL、RustFS（S3 兼容）、独立 `fw-dev-worker`、Outbox Worker 和独立 HTTP 下游平台覆盖上传、版本、审批、发布、多下游交付、审计与 Doctor。Compose 中 API 节点不消费异步队列，后台执行由 Worker 容器承担，用于验收生产推荐的角色拆分。

依赖版本和 SHA-256 校验已提交到仓库；升级依赖时请遵循[可复现构建说明](docs/reproducible-builds.md)。

启动完整编排：

```powershell
$env:FILEWEFT_DEV_PLATFORM_SHARED_SECRET = ([guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N'))
docker compose -f .docker\docker-compose.dev.yaml up -d --build --wait
```

`FILEWEFT_DEV_PLATFORM_SHARED_SECRET` 是开发 API/Worker 与独立下游模拟器之间的系统凭据，至少 32 个字符且每次新建本地编排时应生成新的值。它不会下发给浏览器，也不能替代用户登录令牌。Compose 会拒绝未设置该变量的完整编排，避免意外以公开固定凭据启动模拟下游。

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

模拟下游平台只绑定宿主机 `127.0.0.1`，并且不再经控制台 Nginx 代理暴露 `/platform/`。除健康检查外的所有平台接口都要求该系统凭据；控制台的“下游镜像”通过已登录的 FileWeft API 服务端转发读取，因此浏览器无法获得平台密钥。平台只允许从 `rustfs` 容器拉取 HTTP(S) 文件 URL，拒绝 URI 用户信息、跳转和超过 512 MiB 的响应。需要演练其他受控存储主机时，可显式设置 `FILEWEFT_DEV_PLATFORM_ALLOWED_DOWNLOAD_HOSTS`（逗号分隔）和 `FILEWEFT_DEV_PLATFORM_MAX_DOWNLOAD_BYTES`，不要把任意公网或内网地址加入允许列表。

审计将用户 ID 视为不透明字符串，并同时保存操作发生时的显示名快照。接入方可在 `UserRealmProvider` 中将 Long、Int、UUID 或其他身份系统 ID 转为字符串；FileWeft 不维护用户表，也不会在查询历史审计时反查并改写原有操作者名称。

验收控制台默认英文，可切换完整中文。其“角色验收实验室”内置 TXT、Markdown、CSV、JSON 文件样例：拥有创建权限的用户可将它们上传为真实 RustFS 草稿；审批、Outbox 与只读路线则只展示当前用户经服务端授权的操作控件。控制台的创建、改名、新增版本、授权下载以及提交/审批/驳回/修订/下线/恢复/归档已走 `/fileweft/v1` 正式协议；Doctor、同步、审计及工作流详情投影仍走 `/api`，不能被嵌入方当作公共协议。

运行完整 Compose 验收回归：

请在启动 Compose 的同一终端会话中执行，或显式恢复启动时使用的同一个 `FILEWEFT_DEV_PLATFORM_SHARED_SECRET`；重新生成值会使验收客户端与已运行的平台凭据不一致。

```powershell
$env:FILEWEFT_RUN_DEV_E2E='true'
.\gradlew.bat :fileweft-dev:test
```

该测试会创建唯一编号文档，验证编辑者上传和提交、单人审批与双人会签、管理员处理 Outbox、下游平台下载 RustFS 对象，并覆盖可选下游失败与必达下游人工恢复。

### 浏览器验收回归

`fileweft-dev/web` 内置锁定版本的 Playwright 测试。它只针对本地 Compose 验收台，不会访问生产地址；覆盖完整中文切换、角色控件过滤、真实内置样例上传/提交、单人与双人审批、驳回修订、直接创建、重命名、版本、授权下载、目录移动、Doctor、任务处理、下游镜像、断点续传与 Alpha/Beta 租户文件可见性隔离。本里程碑的常规模块测试、真实 PostgreSQL/RustFS 双租户 Compose E2E 均已通过；浏览器侧共 9 条 Playwright 用例通过，其中包含一条独立的 formal-v1 协议验收。

首次执行先安装锁定的 Node 依赖和 Chromium：

```powershell
Push-Location .\fileweft-dev\web
npm ci
npx playwright install chromium
Pop-Location
```

在完整 Compose 编排健康后执行：

```powershell
$env:FILEWEFT_RUN_DEV_UI_E2E='true'
.\gradlew.bat :fileweft-dev:check --no-daemon
```

也可在 `fileweft-dev/web` 中直接运行 `npm run test:e2e`。需要测试其他本地地址时，设置 `FILEWEFT_DEV_UI_BASE_URL`；测试报告会输出到被 Git 忽略的 `playwright-report/`。

## 宿主文件树与目录权限

FileWeft 不拥有业务系统的目录，也不会把目录名称写入对象存储路径。宿主实现 `DocumentCatalogProvider`，以租户内不透明字符串 ID 返回文件夹；选中的 ID 仅以 `catalog.folder-id` 元数据绑定到文件资产。这样同一个 `inbox` ID 可以在不同租户中独立存在，目录改名或移动也无需迁移 FileWeft 数据。

新版 SPI 可接收 `DocumentCatalogAccessRequest`，其中的租户、当前用户和操作意图由 FileWeft 的可信上下文生成。目录 ACL 应由宿主在该方法中实施，不能信任前端传入的租户或用户。为兼容现有实现，旧的 `listFolders(tenantId)` 仍有效；需要按用户过滤时覆写请求版本。Starter 自动生成 `DocumentCatalogAccessService` 时要求恰好一个 `DocumentCatalogProvider`；即使候选之一标记了 `@Primary`，多个未聚合的目录安全边界也会让启动明确失败。确需组合多个目录源时，宿主必须显式提供一个负责聚合与 ACL 的 `DocumentCatalogAccessService`，且访问服务本身仍只能有一个。创建草稿前先校验 `document:create` 与目录权限，再将返回 ID 写入 `DocumentCatalogBinding.METADATA_KEY`；文件树移动则要求 `document:edit` 和目标目录 ACL，目标目录解析后还会再次验证源目录，最后只更新资产元数据并记录审计，不移动对象、不改变生命周期或重新推送下游。

逐请求 `folderId`、canonical ID 约束、按租户动态路由、多个 OA/ERP 目录聚合及远程 ACL 缓存要求见[目录动态路由实现规范](docs/plugin-development.md#动态目录与组合系统实现规范)。

目录模式下，改名、新版本以及提交、审批、修订、发布、下线、恢复和归档都会先在短事务中冻结文档到资产的原始目录绑定，在事务外执行当前动作授权和源目录 `BROWSE` ACL，外部审批路由或交付策略返回后再次验证，最后按 document → asset（审批再到 workflow）的锁序复核绑定。权限撤销、目录移动竞态或跨租户恶意仓储结果都会在业务写入和审计前失败。自定义资产仓储若没有 `FileAssetMutationRepository` 行锁能力，Starter 不会装配目录安全的 mutation/lifecycle 门面；目录模式的 Controller 或宿主入口必须把该能力缺失视为不可用，不能回退调用租户级底层服务。相同条件下的 `CatalogDoctorChecker` 会在异步 Doctor 中校验绑定目录仍属于该租户；它不模拟用户或检查用户 ACL，因此不会绕过宿主权限模型。

为保持已有嵌入代码兼容，`DocumentCommandService`、`DocumentReviewWorkflowService`、`PublishDocumentService`、`OfflineDocumentService`、`RestoreOfflineDocumentService` 和 `ArchiveDocumentService` 仍是无目录宿主可直接使用的租户级原语。启用目录后直接注入这些原语不会自动获得目录 ACL；官方 Controller 与宿主自定义入口必须改用目录 mutation/lifecycle 门面。正式生命周期 HTTP 通过统一解析器严格选择 flat 或 catalog-aware 能力；目录能力不完整时固定返回 `503`，不会降级到 flat。

正式写接口使用 `V020` 持久化请求幂等记录：原始 `Idempotency-Key` 只在内存中校验，数据库仅保存租户作用域摘要，并把当前用户、动作、资源/子资源和 typed-command 指纹绑定到第一次成功结果。`revise`、`publish`、`offline`、`restore`、`archive`、`submit` 以及工作流任务 `approve/reject` 均已提供 flat/catalog-aware Application 边界和 Boot 2/3 正式 HTTP；每次重放仍重新执行当前授权和目录 ACL，审批路由与交付策略只在未命中后于事务外解析。最终事务采用 idempotency → document → asset → workflow 的锁序，使领域状态、审计、Outbox 与稳定回执同时提交；Controller 不得另包外层事务。

## 多下游交付

发布不再把“所有下游”折叠成一个同步结果。接入方通过 `DocumentDeliveryProfileProvider` 为租户提供可选交付档案；每个档案由多个 `DocumentDeliveryTargetDefinition` 组成，目标使用不透明字符串 `id`、`connectorId` 和可选 `ownerRef`，并声明为 `REQUIRED` 或 `OPTIONAL`。`DeliveryConnectorResolver` 将 `connectorId` 解析为实际的 `FileConnector`，不把 Spring 或厂商 SDK 泄漏到 SPI。

审批或直接发布时，FileWeft 在同一业务事务中冻结目标快照，并为每个目标写入独立 Outbox 事件。目标记录含状态、外部 ID、失败原因与重试次数，因此一个目标重试不会重复推送已成功的目标。

- 全部必达目标成功：文档成为 `PUBLISHED`。
- 必达目标重试中或失败：文档显示 `SYNC_ERROR`，Outbox 继续按策略重试；恢复成功后自动回到 `PUBLISHED`。
- 可选目标失败：文档仍可 `PUBLISHED`，但交付记录保留“待处理”、责任引用和错误原因。
- 不执行默认分布式回滚：成功下游不会因为另一个下游失败而被自动删除。删除/撤回必须由业务显式发起，避免误删已生效的外部记录。
- 重试耗尽后，拥有 `document:delivery:retry` 权限的管理员可只重排失败目标；原目标 ID 同时作为连接器幂等键。

文档下线或归档属于显式撤回：系统会在同一业务事务中为每个已经成功交付的目标冻结一条 `document.delivery.target.removal.requested` Outbox 事件。外部 `FileConnector.remove` 调用仍只在 Worker 中执行；撤回拥有独立于“交付成功”的 `PENDING`、`RETRYING`、`SUCCEEDED`、`FAILED` 状态和重试次数，因此不会把“曾经交付成功”误显示为“已撤回”。暂时性失败由 Worker 自动重试，永久失败可由拥有同一 `document:delivery:retry` 权限的管理员只重排该目标的撤回事件。连接器应返回外部 ID；若历史连接器未返回，框架会以稳定的文档 ID 作为撤回调用的回退外部键，连接器实现必须保证该键可幂等处理。

需要更新已发布内容时，使用受控路径：`PUBLISHED → OFFLINE → document:restore → DRAFT → 新版本 → 审批 → 发布`。恢复草稿前，当前交付代次不得有 `PENDING` 或 `RETRYING` 同步，且每个已成功交付目标都必须已撤回成功；这样不会在新版本已发布后让旧版本的迟到请求覆盖下游。每次审批进入发布都会递增交付代次，并为同一目标创建新的交付快照；旧代次的迟到 Outbox 事件会被安全忽略。若下线恰好发生在一次下游调用进行期间，随后返回的成功结果会立即补发撤回事件。

Starter 可直接使用单连接器兼容默认档案：当应用或插件只提供一个 `FileConnector` 且未声明 `profiles` 时，Starter 会创建 `default` 档案，并把这个唯一连接器兼容解析为 `fileweft.sync.connector-name`（默认也是 `default`）；连接器 Bean 不必命名为 `default`。存在多个连接器时，必须在档案目标中显式填写对应的 `connector-id`，或替换下列 SPI 实现以接入租户自己的策略中心：

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

Doctor 会额外运行 `delivery-profile` 检查：它按当前租户读取全部可用档案，逐个验证目标的 `connectorId` 能否解析，并把档案、目标、责任引用与未解析项写入诊断证据。该检查不调用 `FileConnector`、不写入任何业务数据；连接器实际连通性仍由 `connector` 检查负责。这样，漏注册连接器或租户档案为空会在发布前的诊断中被明确标出，而不是等到用户提交发布事务时才失败。

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

`DocumentDownloadService` 是文档内容的唯一应用层入口：它在读取对象前按当前租户执行 `document:download` 授权，校验版本属于文档，再从对应的 tenant-scoped 文件对象流式读取。接入宿主目录后，默认 Starter 还会在数据库事务外冻结当前用户的可信目录范围，并在解析文件的同一个短事务内按租户、文档和目录再次复核；隐藏目录和空范围统一表现为文档不存在，且不会打开对象存储或写入下载审计。下载意图以 `document:download` 写入审计和操作日志；Storage URL 不会作为前端 API 返回值，因此前端权限不能绕过 FileWeft 的服务端授权。

正式 Web Starter 提供 `/fileweft/v1/documents/{documentId}/content` 与 `/fileweft/v1/documents/{documentId}/versions/{versionId}/content`。成功响应使用 `attachment`、RFC 5987 文件名、安全 ASCII fallback、内容类型白名单、`nosniff` 与 `private, no-store`；只有对象存储已报告并与持久化值核对的长度才会成为 `Content-Length`。首版协议不承诺断点续传：任何 `Range` 都在打开下载前固定返回 416，显式 `HEAD` 固定返回 405 和 `Allow: GET`，且不返回 ETag、`Accept-Ranges`、内容哈希或存储地址。开始流式输出前的失败使用固定 JSON 外层；开始输出后的 I/O 中断只关闭流并终止响应，不会把 JSON 拼到二进制尾部。

开发验收 API 仍保留当前版本和指定历史版本的流式兼容端点。Dev 控制台只为服务端下发了 `document:download` 的角色显示下载控件；按钮已经使用带 Bearer Token 的正式 `/fileweft/v1` 授权下载请求而非裸 S3 地址，便于演示下载授权与跨租户拒绝。

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
