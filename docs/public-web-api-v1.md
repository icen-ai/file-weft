# 正式 Web API v1 设计

`fileweft-dev` 中的 `/api/**` 是开发验收界面专用入口，不是嵌入方可以依赖的公共协议。它包含 Dev Session、开发用 JDBC 投影和演示平台行为，不能通过改路径直接升级为生产 API。开发验收应用现在同时真实装配正式 Boot 3 Web Starter，并由控制台 Nginx 代理 `/fileweft/`，但这不会改变 `/api/**` 的 Dev-only 边界。

本设计定义正式 API 的增量交付边界。它不会把 MVC、Servlet、认证 SDK 或数据库查询引入 Core、Domain、SPI 或默认 Starter。

## 模块与兼容性

本文描述当前 `v0.0.3` 正式 HTTP 合同；V026 决策证据等既有接口仍属于历史 `v0.0.2` 合同，`0.0.3` 在其上增加 metadata schema、文档元数据输入和审批撤回。只有相应版本的受保护标签流水线全部成功且远端公开仓库完成匿名冷缓存解析后，才能假定这些路由已存在于可消费制品；源码、标签预期、本地构建或本文本身不能替代这份远端证据。

当前已交付的工件是：

- `fileweft-metadata-api`：JDK 8 基线、Java 友好的不可变版本化 schema/field/value/validation 契约，不依赖 Spring、数据库或厂商 SDK。
- `fileweft-metadata-runtime`：JDK 8 基线的当前/历史 schema 注册、校验、规范化和处理实现；按可信租户上下文解析，不负责 HTTP 或持久化。
- `fileweft-web-api`：JDK 8 基线的纯契约、响应模型、错误码与 DTO；不依赖 Spring、JDBC、领域实体或任意 FileWeft 运行时模块。
- `fileweft-web-runtime`：JDK 8 基线的纯 JVM v1 文档读写、metadata schema/输入映射、正式断点续传、审计日志、插件清单、健康、工作流、Doctor 查询与下载门面；显式依赖 `fileweft-application` 与 `fileweft-web-api`，将应用层已授权、已脱敏的读取视图和命令结果映射为公共 DTO，并把 caller-owned 下载归一为只含安全响应元数据的 `Closeable` 句柄。它不引入 Spring、MVC、Servlet、JDBC 或 HTTP 路由，也不接受租户/用户参数。
- `fileweft-web-spring-boot2-starter`：JDK 8 / Spring Boot 2.7 的可选 MVC 适配器；通过 `spring.factories` 注册。读取路由以安全的 `DocumentQueryService` 为条件，首批写入路由以 `DocumentDraftService` 为条件，内容路由以 `DocumentDownloadService` 为条件，能力未安装时不会伪装成可用接口。
- `fileweft-web-spring-boot3-starter`：JDK 17 / Spring Boot 3 的可选 MVC 适配器；通过 `AutoConfiguration.imports` 注册，并与 Boot 2 保持同一路径、状态码和响应外层及条件装配语义。

Web Starter 不隐式引入数据库或替代原有运行时 Starter。宿主应按自己的 Spring Boot 代际同时选择对应的 `fileweft-spring-boot*-starter` 与 `fileweft-web-spring-boot*-starter`；条件式读取/写入 Controller 在缺少安全应用服务时不会注册，生命周期 Controller 则始终注册并以 `503 FEATURE_UNAVAILABLE` 明确表示缺少对应 flat/catalog-aware 能力。原有 Starter 与 Dev 路由不改变，正式 Web 保持加法兼容。

开发验收台通过真实 `fileweft-web-spring-boot3-starter` Controller 验收正式路径，而不是复制一套模拟 v1 Controller。UI 的文档写入、当前/历史版本授权下载、审批待办、普通审批历史、受权决策证据、生命周期/审批动作、同步状态、失败目标重排、文档审计历史、插件清单、进程存活以及文档/系统 Doctor 已经走正式 `/fileweft/v1`；丰富的 Dev 平台镜像和维护动作仍留在 `/api`，不构成公共兼容承诺。

## 信任边界

公共 Controller 不接受 `tenantId`、用户 ID、角色或权限作为业务参数，也不会从不受信任的请求头写入 FileWeft 上下文。它只调用宿主已经提供的：

- `TenantProvider`
- `UserRealmProvider`
- `AuthorizationProvider`
- 可选 `TraceContextProvider`

宿主的认证层负责将已验证的请求身份绑定到这些 SPI。默认 Starter 的空用户和拒绝授权实现意味着未接入可信身份时，受保护 API 安全失败。Controller 只做参数校验、DTO 转换和应用服务调用；文档授权、租户过滤、审计和状态机规则仍在 Application/Domain。

宿主用户 ID 是区分大小写、不会 trim、大小写折叠或 Unicode 归一化的不透明字符串。`Long`、`Int`、UUID 和外部目录标识必须由宿主在身份 SPI 中稳定转换为字符串；值必须非空、最多 256 个 UTF-16 code unit、首尾没有 Unicode whitespace，且不含 ISO control 或 FileWeft 固定拒绝表中的 Unicode format 字符。非法可信身份会在授权前安全失败，不能由 Controller 或请求参数补写。

接入 `DocumentCatalogProvider` 时，目录 ACL 不是仅用于 `folderId` 筛选的表面校验：应用层会从可信当前租户和用户派生完整可读目录范围，并将该范围同时约束文档详情、未筛选分页和内容下载；空目录范围一律拒绝回退到无目录条件的查询。下载采用两阶段可见性校验：宿主目录调用在数据库事务外冻结 scope，解析版本和文件的同一个短事务再通过 tenant-scoped 查询端口复核文档仍位于该 scope；隐藏目录统一为 404，且不会访问对象存储或写下载审计。未接入目录 SPI 的宿主保持原有“文档授权 + 租户隔离”兼容行为，但不能宣称提供宿主目录树隔离。

## 协议稳定性

所有正式业务路由将以 `/fileweft/v1` 开头。除授权内容流的二进制成功响应外，业务成功响应与开始流式输出前的业务执行错误均生产 `application/json` 并使用统一外层；multipart 只改变请求的 `Content-Type`。内容流开始后的 I/O 失败会关闭 caller-owned 句柄并终止响应，不会尝试在部分二进制后追加 JSON。未匹配路由仍由宿主 Spring Web 的全局异常策略处理；正式上传资源是明确例外，其路径内的请求体解码失败及 405/406/415 由上传专用、路径作用域内的 resolver 转成同一安全外层，其他宿主路由仍交还宿主策略：

```json
{
  "code": "OK",
  "message": "OK",
  "data": {},
  "error": null,
  "traceId": "optional-host-trace-id"
}
```

失败响应的 `data` 为 `null`，`error` 只包含与外层一致的固定 `code`/`message`；它不接受任意 attributes。错误不返回 SQL、对象存储路径、下游外部 ID、Outbox payload、凭据或未脱敏异常栈。稳定错误码至少区分 `INVALID_REQUEST`、`UNAUTHENTICATED`、`FORBIDDEN`、`NOT_FOUND`、`METHOD_NOT_ALLOWED`、`NOT_ACCEPTABLE`、`UNSUPPORTED_MEDIA_TYPE`、`RANGE_NOT_SUPPORTED`、`CONFLICT`、`FEATURE_UNAVAILABLE`、`CONTENT_UNAVAILABLE`、`OUTCOME_UNKNOWN` 和 `INTERNAL_ERROR`。`OUTCOME_UNKNOWN` 固定使用 `503`，表示非事务型外部副作用与数据库提交暂时无法安全归类，调用方必须检查资源状态后重放同一命令。时间使用 epoch milliseconds；标识均为不透明字符串。

`fileweft-web-api` 已定义 `DocumentDetailDto(document, versions)`，保证 `GET` 文档不会临时返回领域聚合或无版本归属的 Map。公开版本元数据不含存储路径、文件对象 ID、租户 ID、资产 ID 或内容哈希；内容哈希如确有业务必要，必须由后续单独的完整性读取权限与 DTO 提供。写命令只返回 `{documentId, versionId?}`：不做提交后查询，也不向只有写权限的调用者泄漏标题、文档号、生命周期或旧版本信息。审批任务不公开宿主用户 ID 或评论，只能在经过当前调用者计算后返回 `assignedToCurrentUser`。Doctor DTO 不接受原始 evidence；Web 映射层还必须将 checker 的自由文本归一为安全文案，不能直接透传下游信息。

当前已交付并通过两代 MVC 契约测试的正式路由是：

- `POST /fileweft/v1/uploads`：以 JSON 创建或重放一个裸字节上传会话；要求恰好一个 `Idempotency-Key`，返回 `201` 与可继续操作该资源的相对 `Location`，不直接创建文档或版本。宿主若配置了不能表示为安全单路径段的内部 ID 生成器，正式资源会固定安全失败，而不会返回缺少 `Location` 的半成功响应。
- `GET /fileweft/v1/uploads/{uploadId}`：读取当前所有者的公开状态、服务端已确认分片和可选完成回执；它同时承担断线恢复和完成结果查询，不重复提供 `/status`。
- `PUT /fileweft/v1/uploads/{uploadId}/parts/{partNumber}`：流式接收 `application/octet-stream`，要求恰好一个正数 `X-FileWeft-Part-Length`，并在存储确认及实际读取长度精确匹配后才持久化分片确认。
- `POST /fileweft/v1/uploads/{uploadId}/complete`：以服务端持久化的连续分片和私有 ETag 权威列表同步完成，成功及重放返回同一 `fileObjectId/fileAssetId` 回执。
- `DELETE /fileweft/v1/uploads/{uploadId}`：仅在状态已知可安全取消时终止 multipart，并返回公开终态视图。
- `GET /fileweft/v1/documents/{documentId}`：当前租户、当前用户已授权且位于可读目录范围内的文档和版本视图。
- `GET /fileweft/v1/documents`：使用不透明 cursor 的文档摘要分页，可选生命周期和目录筛选。
- `GET /fileweft/v1/metadata/schemas/{schemaId}`：按可信当前租户解析指定 schema 的当前版本，返回 schema `id`、`version` 及字段的安全规则投影。
- `POST /fileweft/v1/documents`：multipart 创建草稿；`documentNumber`、`title`、`file` 必须各出现一次，`folderId` 最多一次，可选携带一次 `metadataSchemaId` 与重复的 `metadata=field=value`。
- `POST /fileweft/v1/documents/{documentId}/versions`：multipart 新增草稿版本；`versionNumber`、`file` 必须各出现一次，可选使用同一 metadata 输入协议。
- `PATCH /fileweft/v1/documents/{documentId}`：以 JSON `{\"title\": \"...\"}` 修改草稿标题。
- `GET /fileweft/v1/documents/{documentId}/content`：经当前租户、用户、目录范围和 `document:download` 授权后的当前版本内容流。
- `GET /fileweft/v1/documents/{documentId}/versions/{versionId}/content`：同一授权边界下显式选择属于该文档的历史版本内容流。
- `GET /fileweft/v1/workflows/tasks`：当前用户的待审批任务分页；只含分配给当前用户或未分配、且文档与工作流仍待审批的任务。
- `GET /fileweft/v1/documents/{documentId}/workflows`：当前用户可见文档的审批历史分页；任务投影不含受理人、评论和操作者。
- `GET /fileweft/v1/documents/{documentId}/workflow-decisions`：同时要求 `document:audit` 与 `document:read` 的受权决策证据分页；新决策返回不可变的操作者 ID/名称快照和 `decidedTime`，不返回受理人、评论、租户或任意 attributes；遗留任务以 `decisionEvidenceRecorded=false` 且操作者/时间为 `null` 表达未知。
- `GET /fileweft/v1/documents/{documentId}/sync-status`：当前发布代次的脱敏交付状态；只返回目标名称、要求、状态、重试计数及两种安全重排就绪标志。
- `GET /fileweft/v1/documents/{documentId}/logs`：同时要求 `document:audit` 与 `document:read`，并受当前目录可见范围约束的审计分页；只返回日志 ID、动作、字符串操作者 ID/名称快照、可选 Trace ID 与时间，不返回原始 details。
- `GET /fileweft/v1/documents/{documentId}/doctor`：即时执行经过双重文档授权与目录可见性复核的脱敏诊断。
- `POST /fileweft/v1/documents/{documentId}/doctor/tasks`：持久化排队异步诊断；要求恰好一个 `Idempotency-Key`，fresh/replay 均返回同一任务回执和 `202`。
- `GET /fileweft/v1/documents/{documentId}/doctor/tasks/{taskId}`：精确查询该文档下的 Doctor 任务及可选终态报告。
- `GET /fileweft/v1/doctor`：需要独立的 `system:doctor:read` 权限，诊断可信当前租户的系统组件；`GET /fileweft/doctor` 是实现手册兼容别名。
- `GET /fileweft/v1/plugins`：需要独立的 `system:plugins:read`，按稳定插件 ID 分页返回允许列表内的能力类型和数量；不返回实现类、Bean/JAR、连接器 ID、配置或实例。`GET /fileweft/plugins` 是手册兼容别名。
- `GET /fileweft/v1/health`：公开、依赖无关的进程 liveness，只返回 `status=UP`；它不声明数据库、对象存储、插件或下游 readiness，深度诊断仍使用授权 Doctor。`GET /fileweft/health` 是手册兼容别名。

上传文件名、长度和内容类型只从服务器收到的 multipart file part 派生，客户端不能另行提交对象键、资产 ID、哈希或任意存储 metadata；文件名拒绝路径分隔符、`.` 和 `..`。schema 约束的业务 metadata 是唯一例外，必须使用下节的受限协议。创建和新增版本返回 `201`，改名返回 `200`。成功创建后仅当文档 ID 是长度受限的安全单路径段时才附带相对 `Location`；自定义标识不满足这个条件时仍返回成功 body，不会在业务提交后制造一次失败。

## Metadata schema 与文档元数据

Metadata schema 是不可变、带版本的公共契约。字段类型固定为 `STRING`、`NUMBER`、`BOOLEAN`、`DATE`、`ENUM` 和 `STRING_LIST`，并可声明必填、允许值、最大长度和格式。每个 schema 与单次 HTTP metadata 输入最多 128 个字段；`format` 使用 RE2/J 的线性时间正则方言，不支持反向引用、环视等依赖回溯的 Java 正则构造，非法格式会在 schema 注册时失败关闭。schema 查询只返回 `id`、`version` 与这些字段规则，不公开租户、注册来源、实现类或内部配置；租户始终由 `TenantProvider` 提供，HTTP 请求不能提交或覆盖。

创建文档或新增版本不带任何 metadata 参数时保持 `0.0.2` 兼容行为。只要出现 `metadataSchemaId` 或 `metadata`，就必须恰好提交一个 `metadataSchemaId`；每个 `metadata` part 使用第一个 `=` 分隔 `field=value`，同名字段不得重复，空字符串可作为 STRING 值交由 schema 判断。Runtime 在基础文档授权（目录模式还包括目录 ACL）成功后解析当前 schema，按精确版本复核、校验并规范化输入，再把规范化值及 `metadata.schema-id` / `metadata.schema-version` 标记交给应用写入边界。创建时写入文档资产；新增版本时在同一事务完整替换文档级 schema metadata 并保留 `catalog.*` / `fileweft.*` 框架键。schema 业务 metadata 不写入厂商对象 user-metadata header，也不以对象存储作为唯一真相。缺失必填字段、未知字段、类型或规则不匹配固定作为无敏感回显的非法请求；schema/处理能力没有安全装配时失败关闭，不会按原始 Map 绕过校验。

下载成功固定使用 `attachment`。`Content-Disposition` 同时提供不含控制字符的有界 ASCII fallback 与 RFC 5987 UTF-8 `filename*`；遗留路径、CRLF、Unicode FORMAT、孤立 surrogate 和超长文件名都不会进入原始响应头。内容类型只允许固定被动白名单，HTML、SVG、XML、JavaScript、JSON 和非法值回退 `application/octet-stream`；响应附带 `X-Content-Type-Options: nosniff` 与 `Cache-Control: private, no-store`。只有 Storage 实际报告且已与持久化元数据核对的长度才设置 `Content-Length`，持久化 fallback 不会伪装成传输层验证结果。响应不提供 ETag、`Accept-Ranges`、`Content-Range`、内容哈希、对象键或 Storage URL。

首版下载协议明确不支持 Range 与 HEAD 派生下载。请求只要出现 `Range` 头，就会在授权、仓储和对象存储之前固定返回 `416 RANGE_NOT_SUPPORTED`；两个内容路径的显式 `HEAD` 固定返回 `405 METHOD_NOT_ALLOWED` 与 `Allow: GET`，不会借用 Spring 的自动 HEAD 行为打开文件。

未接入宿主目录 SPI 时，创建可以省略 `folderId`，但提交 `folderId` 会安全失败为 `503 FEATURE_UNAVAILABLE`。自动生成目录访问边界时，默认 Starter 要求恰好一个 `DocumentCatalogProvider`；多个未聚合 provider 即使存在 `@Primary` 也会启动失败。宿主如需组合多个 provider，必须显式提供一个聚合 ACL 的 `DocumentCatalogAccessService`，且多个 access 候选同样会启动失败。具备 `FileAssetMutationRepository` 行锁能力时，Starter 才装配目录感知修改与 action-aware 生命周期 guard。创建必须给出目录并通过应用层目录授权与保留绑定；新增版本和改名会先快照并授权当前源目录，新增版本在上传后还会再次检查源目录权限，目录移动则在目标目录解析后重新验证源目录，再按 document → asset 顺序取得两级 mutation lock 并复核原始绑定。生命周期与审批 guard 使用实际动作权限和源目录 `BROWSE` ACL，在审批路由或交付策略外调后再次验证，最终按 document → asset → workflow 锁序复核原始绑定。Catalog SPI 始终在 FileWeft 管理的数据库事务外调用，权限撤销或绑定竞态会在提交前失败，新增版本已上传的对象会被补偿删除。自定义装配若只有目录创建，或资产仓储没有安全 mutation lock 能力，目录模式的正式写入口必须保持不可用，不能回退调用租户级底层服务；自定义 `DocumentDownloadService` 也必须显式保留等价的目录可见性校验。

现有 lifecycle/workflow 应用服务继续作为无目录宿主的租户级兼容原语，因此不会被 Starter 静默改写语义。目录宿主若绕过 catalog-aware 幂等边界直接调用这些原语，就不具备目录 ACL 保证。正式 lifecycle Controller 通过统一能力解析器选择 flat 或 guarded 路径：没有 access 时才允许 flat；存在 access 但缺少唯一 guarded capability 时固定返回 `503 FEATURE_UNAVAILABLE`。多个 access 或同类型 lifecycle/review 候选即使有 `@Primary` 也会启动失败；flat 与 catalog-aware 混装但无法形成唯一安全配对时不会回退，而是固定返回 `503`。

以下正式命令路由已由 Boot 2 与 Boot 3 Web Starter 对齐交付，成功与重放均返回 `200` 和仅含稳定 `documentId/workflowId/taskId` 的回执：

- `POST /fileweft/v1/documents/{documentId}/revise|publish|offline|restore|archive|submit`
- `POST /fileweft/v1/workflows/{workflowId}/withdraw`：撤回仍在等待审批的工作流。
- `POST /fileweft/v1/workflows/{workflowId}/tasks/{taskId}/approve|reject`：明确支持多人会签，绝不以仅含 document ID 的“audit”路由猜测任务。

`publish` 可选 `deliveryProfileId`，`submit` 可选 `reviewRouteId`，审批/驳回可选有界评论；这些值进入服务端 typed-command 指纹。同一 key 改变动作、资源、任务、评论、路由或交付档案都会返回 `409`。

审批撤回要求恰好一个 `Idempotency-Key`，只允许 `PENDING_REVIEW` 文档及其 `PENDING` 工作流转为文档 `DRAFT`、工作流 `WITHDRAWN`。可信 `submittedBy` 与当前用户相同时可按提交者身份撤回；否则必须通过 `document:review:withdraw` 策略授权。V029 之前的历史工作流允许 `submittedBy=NULL`，这种记录不能猜测提交者，只能由策略授权的操作者撤回。每次重放仍重新认证、检查租户和目录可见性；跨租户、隐藏或竞态变化不会泄露工作流存在性，已完成审批固定冲突。最终事务遵守 idempotency → document → asset（目录模式）→ workflow 锁序，并原子保存状态、审计与稳定回执，使撤回与最后一次审批决定只能有一个成功结果。

失败交付恢复使用两条不猜测操作的正式命令；二者都要求 `document:delivery:retry` 和恰好一个 `Idempotency-Key`，成功及重放固定返回 `{documentId, deliveryId, operation}`：

- `POST /fileweft/v1/documents/{documentId}/deliveries/{deliveryId}/retry`
- `POST /fileweft/v1/documents/{documentId}/deliveries/{deliveryId}/removal/retry`

状态中的 `deliveryRetryable` / `removalRetryable` 不是单纯由目标 `FAILED` 推断：只有当前事件围栏的操作、事件类型及同租户 Outbox 终态都精确匹配 `FAILED` 时才为 `true`。这也覆盖 Outbox 已提交失败、但进程在本地终态投影前退出而遗留的 `PENDING/RETRYING` 目标；正式恢复命令可安全接管该窗口。命令最终事务固定按 idempotency → document → asset（目录模式）→ delivery target → current Outbox 加锁，并原子推进派发序号、写新事件、审计和幂等回执。旧事件或失租 Worker 的迟到结果无法覆盖新重排状态。公共 DTO 不含 profile/connector/owner、下游外部 ID、错误文本、Outbox ID/payload、事件 ID、租约 token 或派发序号。

审批待办同时要求 `document:audit` 和 `document:read`；普通文档审批历史作为文档详情的身份脱敏投影，只要求 `document:read`。受权决策证据入口 `/workflow-decisions` 则同时要求 `document:audit` 与 `document:read`。三者都在目录模式下将查询限制在当前用户可浏览的目录范围。待办项的 `assignedToCurrentUser` 与 `actionableByCurrentUser` 均由可信用户快照计算，客户端不能提交用户 ID；不可见或跨租户文档固定表现为 `404`，可见但没有记录则返回空页。

从 V026 起，新审批或驳回会在任务上不可变地保存决策时的 `decisionOperatorId`、可选 `decisionOperatorName` 与 `decidedTime`。显示名只是当时的安全快照，不用于重新授权；身份 ID 才是宿主稳定标识。既有已完成任务无法从审计或当前受理人可靠推导真实决策者，因此迁移不会猜测或回填；JSON 以 `decisionEvidenceRecorded=false` 且三个证据字段为 `null` 表达 UNKNOWN/未记录。旧的 `/workflows` 路由继续保持身份脱敏，避免把新增证据意外扩大给只有 `document:read` 的调用者。

Doctor 的文档入口同时要求 `document:doctor` 与 `document:read`，目录模式还会在可能调用远程 checker 的前后两次验证完整可读目录范围；隐藏或跨租户文档表现为 `404`。系统入口使用独立的 `system:doctor:read`，不复用文档权限。公共响应只保留固定组件标识、状态以及按 `(component,status)` 生成的固定文案；插件 checker 名称统一聚合为 `extensions`，原始 reason、repair、evidence、异常、租户、Worker payload/error 和租约信息都不会进入 HTTP。Doctor JSON 响应均带 `Cache-Control: private, no-store` 与 `X-Content-Type-Options: nosniff`；显式 `HEAD` 固定返回 `405` 和对应 `Allow`，不会触发诊断或排队。

异步任务查询以 `fw_task` 状态为唯一权威：`PENDING/RUNNING/RETRY` 即使数据库暂存有旧报告也不会把它宣称为终态，只有已确认的 `SUCCESS` 才附带报告；`FAILED` 只返回安全任务状态，不会误用上一轮失租执行遗留的报告。默认 Starter 的 Worker 使用 owner + 随机 lease token 围栏，并在写 Doctor 报告的同一短本地事务中锁定、复核当前任务租约；失租 Worker 的晚到结果不能覆盖新一轮执行。排队的幂等 claim、任务、审计和稳定回执在同一事务提交，每次重放仍重新执行当前身份、动作和目录 ACL 检查。

分页使用不透明 cursor 而不是让调用方拼接数据库 offset。文档分页 codec 封装 `updatedTime + documentId`；审批待办、普通审批历史、受权决策证据和审计日志使用带查询种类的独立 codec，封装不可变的 `createdTime + id`，四种查询不能交换 cursor；插件清单 cursor 只封装稳定插件 ID。审计查询以 `fw_audit_record` 为唯一日志项，并按同租户、同资源、同 ID 左连接镜像 Operation 仅补 Trace，避免同一事件出现两份记录或在翻页边界漏项。审计 cursor 是向后的 keyset 边界而不是数据库快照：首屏之后新增且排序位于边界之前（更新）的正常审计不会插入后续页面，调用方从首屏重新读取即可看到；人为写入旧时间的回填记录若排序位于边界之后，则允许进入后续页面。初始集合不会重复，首版不承诺跨请求的完整快照隔离。版本化 Base64URL 内容不含租户、用户、路径或密钥，也不是加密或签名机制：错种类、篡改或损坏 cursor 会被统一拒绝，而租户过滤与授权仍由应用层负责。每个受保护列表请求仍以可信当前租户作为唯一授权数据域。所有文本输入均限制长度并拒绝控制字符；下载文件名与内容类型则由共享 runtime 策略归一，保证 Boot 2 与 Boot 3 不会各自解释不受信任的持久化 metadata。

## 正式断点续传资源

上传资源是会话层能力：创建请求只接受 `fileName`、正数 `contentLength`、可选 `contentType` 与可选 `contentHash`，资产类型在服务端固定为 `DOCUMENT`，metadata 固定为空。它不会接受草案曾出现的 `documentNumber`、`title`、`totalParts`，也不会把 dev 路由请求体中的 `assetType/idempotencyKey` 形态提升为公共协议。完成只创建 `FileObject + FileAsset + FILE_UPLOADED` Outbox 事件；把该资产消费为文档或新版本属于独立业务命令，不能让上传 Controller 复制文档生命周期、目录和审批规则。

只有创建操作使用 `Idempotency-Key`。Runtime 先按公共字符集和 1～128 长度规则验证；Application 再从一次可信身份快照取得租户，把版本域、可信租户与原始 key 做长度分帧 SHA-256，并在任何 Storage 或数据库操作前替换为 `v1:sha256:<64hex>`。数据库唯一性仍以租户为范围；原始 key 不进入会话表、DTO、错误或日志。同一所有者重放相同命令会从同一次可信身份快照返回原会话及其当前 `uploadedParts`；同 key 改变文件命令或被另一所有者占用固定为 `409 CONFLICT`，不说明占用方。分片以 PUT 资源替换语义重试，完成以 `uploadId` 和持久化状态重放，因此二者不再引入第二套幂等键记录。

公开上传 DTO 只包含 `uploadId`、文件名/长度、调用方提交的可选类型/预期 SHA-256、epoch 时间、公开状态、无 ETag 的分片确认及完成回执。状态映射为 `UPLOADING/FINALIZING/COMPLETED/FAILED/ABORTED/EXPIRED`；内部 `ABORTING`、`QUARANTINED` 和 staging 标记不会通过普通用户资源暴露。响应永不包含租户、owner、`storageUploadId`、对象位置、存储 ETag 或 `lastError`，并固定携带 `Cache-Control: private, no-store`、`Pragma: no-cache` 与 `X-Content-Type-Options: nosniff`。同租户其他 owner、跨租户、不存在、无 owner 的历史行及隔离状态统一为 `404` 且不调用 Storage；已拥有资源但后来失去 `file:upload` 才返回 `403`。

分片号必须为 1～10000，声明长度和实际 body 都必须为正。Controller 以 servlet 流直接转交 Runtime/Application，不把大分片物化为 `ByteArray`；Application 对 Storage 实际消费的字节计数，并在 Storage 返回后再读取一个字节验证既没有截断也没有尾随内容，失配时不会写会话分片确认。完成前，持久化分片号必须形成从 1 开始的连续序列，总长度必须等于会话完整长度；Storage ETag 只在服务端用于 multipart complete。

`complete` 同步返回稳定的 `uploadId/fileObjectId/fileAssetId`。`completedAt` 是可空的提交后观测值：若完成已提交但二次检查点读取失败，本次 200 可返回 `null`，后续 GET/重放会返回持久化时间，不能把该字段当作幂等身份。响应丢失后，客户端先 GET 同一资源：`COMPLETED` 的 `completion` 给出相同资源 ID 与可用的持久化完成时间，`FINALIZING` 表示应等待并以同一 upload ID 重试。新鲜的完成 claim 不会被并发请求探测或破坏；陈旧 `COMPLETING` 会先检查最终对象，存在时流式重新计算实际长度和 SHA-256，再原子补齐 FileObject、FileAsset、Outbox 与会话终态。最终对象尚不可见时仍保留 `FINALIZING` 围栏并返回 `503 OUTCOME_UNKNOWN`，避免后来请求与仍在运行的慢速 Storage 完成调用竞态。Storage SPI 用 `MultipartCompletionRejectedException` 单独表示“请求已停止且确定没有发布对象”；Application 只有在仓储能原子恢复 `ACTIVE`、清空全部旧分片确认点并刷新一个完整会话 TTL 的重试窗口时才返回 `409 CONFLICT`，随后 GET 会看到空 `uploadedParts`，客户端必须重新 PUT 分片。超时、断链、确认丢失、含糊服务错误及可能代表早先已完成的 `NoSuchUpload` 都不能使用该异常，也绝不会凭一次 `exists=false` 解除围栏。开始、分片或取消阶段的明确 Storage 不可用返回 `503 FEATURE_UNAVAILABLE`。任何未知结果都不删除可能已经完成的对象。系统过期清理和卡滞诊断继续是 Worker/运维边界；`/api/resumable-uploads/maintenance` 仍是 Dev-only 入口，不属于正式公共资源。

Boot 2 与 Boot 3 分别通过 `spring.factories` 和 `AutoConfiguration.imports` 注册镜像自动装配。只有 servlet Web 环境存在 `ResumableUploadService` 时才提供 Controller；该服务自身已经显式依赖可信 `TenantProvider` 与身份、授权等 Application 依赖。宿主可覆盖 facade、响应工厂或 Controller。Web Starter 不创建存储、仓储、身份或上传服务，也不会在能力缺失时注册一个返回假成功的路由。

## 持久化请求幂等地基

上述生命周期、审批（含撤回）、交付恢复与 Doctor 排队命令必须收到恰好一个 `Idempotency-Key`：首字符为 ASCII 字母或数字，后续只允许 ASCII 字母、数字、`.`、`_`、`~`、`:`、`-`，总长 1 到 128。缺失、重复、空白、控制字符或越界都固定归类为 `400 INVALID_REQUEST`，原始 key 不得进入日志、异常、审计或数据库。

对于生命周期、审批、交付恢复与 Doctor 排队命令，Application 会把原始 key 与可信租户共同做带版本前缀的 SHA-256 摘要，只持久化摘要；同一租户下的摘要唯一。记录还绑定可信当前用户、动作、资源、可选子资源以及由已校验命令生成的请求指纹。完全相同的重试返回第一次提交的稳定资源 ID；任一绑定维度不同都固定返回 `409 CONFLICT`，不得说明冲突来自用户、路径还是请求体。请求指纹由服务端对 typed command 生成，客户端不能直接提交。正式续传创建则使用上一节定义的 Application 会话级租户域摘要，不写 `fw_idempotency_record`；实际分片长度和可选完整文件哈希由上传协议独立校验。

认证、动作授权和目录可见性必须在每次重放前重新检查，幂等记录不是权限缓存。快速重放应位于领域状态校验以及审批路由、交付策略等外部解析之前；未命中后，最终短事务的锁序固定为 idempotency → document → asset → workflow，并在同一事务内完成 claim、领域保存、审计、Outbox 和安全结果。外部 Catalog、审批路由或交付策略不得被包进该事务。并发同 key 会由 PostgreSQL 唯一键串行：首请求提交后其他请求重放结果，首请求回滚后下一请求取得执行权；正确实现不会提交可见的 `IN_PROGRESS` 记录。

当前地基故意不承诺自动过期或清理窗口。正式保留期、合规归档和重新执行窗口在形成稳定协议前，运行方不得删除 `fw_idempotency_record` 或复用已经使用的 key；任何已提交的 `IN_PROGRESS` 行都表示集成不变量被破坏，应告警并人工诊断，不能自动接管。

## 安全与运行要求

- 上传大小、网关缓冲、超时和限流仍由宿主网关/认证层按实际风险模型配置；FileWeft 不伪造全局分布式限流。
- 下载保持 `DocumentDownloadService` 的授权、目录可见性和审计，不向前端暴露存储地址或凭据；自定义 Service 装配不能绕过默认 Starter 的目录 guard。
- Controller 只转发 runtime 已归一的 `Content-Disposition`、内容类型和 verified length，并关闭异常详情回显；它不会从持久化长度推测 `Content-Length`。
- CORS、CSRF、会话、OAuth/OIDC、mTLS 和 Actuator 暴露由宿主安全策略决定；Web Adapter 不提供弱默认认证。
- 正式生命周期写路由已强制使用持久化 `Idempotency-Key` 协议；Controller 进程内缓存不能代替该能力，宿主自定义持久化若不满足原子 claim/complete 契约必须让能力保持 `503`。

## 测试与迁移门槛

每个正式路由至少覆盖：当前租户隔离、无用户/拒绝授权、参数错误、领域冲突、Trace 外层、敏感字段脱敏和响应稳定性。应用层将无用户与策略拒绝分别建模，供 Web 适配器稳定映射为 401/403，绝不依赖异常消息判断。Boot 2 与 Boot 3 都要有自动装配上下文与 MVC 契约测试；纯 `fileweft-web-api` 还要有 Java 8/Java 互操作测试。

本里程碑的 formal-v1 Playwright 用例通过正式 Starter 实际执行缺失/非法 key、submit/approve/Doctor schedule fresh+replay、同 key 异 typed command 冲突，并从正式待办发现审批任务，验证普通审批历史保持身份脱敏、无 `document:audit` 权限的用户不能读取决策证据、有权限用户可读取准确的不可变决策者快照、遗留任务显示未知，以及审计分页、插件清单、公开 liveness、Doctor 脱敏、双租户隔离和任务轮询；其余浏览器用例继续覆盖中英文、角色控件、单人与双人审批的不同决策者、驳回修订以及正式同步状态与人工重排。

`0.0.3` 新增面由 Metadata API/runtime、Application、两代 Starter MVC、领域/持久化撤回及三方言迁移测试覆盖。除非对应 Dev/Playwright 用例和 CNB `devAcceptanceCheck` 对精确提交取得绿灯，不应把这些模块级证据扩写成浏览器端验收已经完成。

首次采用正式 API 时，宿主应先接入可信 `TenantProvider`、`UserRealmProvider` 和 `AuthorizationProvider`，再逐步把自己的 Controller 调用迁移到 `/fileweft/v1`。Dev UI 只作为可运行示例；仍留在 `/api` 的验收能力不构成正式协议。
