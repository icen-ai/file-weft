# 正式 Web API v1 设计

`fileweft-dev` 中的 `/api/**` 是开发验收界面专用入口，不是嵌入方可以依赖的公共协议。它包含 Dev Session、开发用 JDBC 投影和演示平台行为，不能通过改路径直接升级为生产 API。开发验收应用现在同时真实装配正式 Boot 3 Web Starter，并由控制台 Nginx 代理 `/fileweft/`，但这不会改变 `/api/**` 的 Dev-only 边界。

本设计定义正式 API 的增量交付边界。它不会把 MVC、Servlet、认证 SDK 或数据库查询引入 Core、Domain、SPI 或默认 Starter。

## 模块与兼容性

当前已交付的工件是：

- `fileweft-web-api`：JDK 8 基线的纯契约、响应模型、错误码与 DTO；不依赖 Spring、JDBC、领域实体或任意 FileWeft 运行时模块。
- `fileweft-web-runtime`：JDK 8 基线的纯 JVM v1 文档读写与下载门面；显式依赖 `fileweft-application` 与 `fileweft-web-api`，将应用层已授权、已脱敏的读取视图和草稿命令结果映射为公共 DTO，并把 caller-owned 下载归一为只含安全响应元数据的 `Closeable` 句柄。它不引入 Spring、MVC、Servlet、JDBC 或 HTTP 路由，也不接受租户/用户参数。
- `fileweft-web-spring-boot2-starter`：JDK 8 / Spring Boot 2.7 的可选 MVC 适配器；通过 `spring.factories` 注册。读取路由以安全的 `DocumentQueryService` 为条件，首批写入路由以 `DocumentDraftService` 为条件，内容路由以 `DocumentDownloadService` 为条件，能力未安装时不会伪装成可用接口。
- `fileweft-web-spring-boot3-starter`：JDK 17 / Spring Boot 3 的可选 MVC 适配器；通过 `AutoConfiguration.imports` 注册，并与 Boot 2 保持同一路径、状态码和响应外层及条件装配语义。

Web Starter 不隐式引入数据库或替代原有运行时 Starter。宿主应按自己的 Spring Boot 代际同时选择对应的 `fileweft-spring-boot*-starter` 与 `fileweft-web-spring-boot*-starter`；未装配某项安全应用服务时，对应 Controller 会保持不可用。原有 Starter 与 Dev 路由不改变，正式 Web 保持加法兼容。

开发验收台通过真实 `fileweft-web-spring-boot3-starter` Controller 验收正式路径，而不是复制一套模拟 v1 Controller。UI 的创建、改名、新增版本和当前/历史版本授权下载已经走 `/fileweft/v1`；需要展示工作流、审批、Doctor、同步与审计综合投影的页面仍使用 `/api`。这种并行仅用于明确区分已稳定的公共协议与丰富的 Dev 验收能力，不能把后者视为兼容承诺。

## 信任边界

公共 Controller 不接受 `tenantId`、用户 ID、角色或权限作为业务参数，也不会从不受信任的请求头写入 FileWeft 上下文。它只调用宿主已经提供的：

- `TenantProvider`
- `UserRealmProvider`
- `AuthorizationProvider`
- 可选 `TraceContextProvider`

宿主的认证层负责将已验证的请求身份绑定到这些 SPI。默认 Starter 的空用户和拒绝授权实现意味着未接入可信身份时，受保护 API 安全失败。Controller 只做参数校验、DTO 转换和应用服务调用；文档授权、租户过滤、审计和状态机规则仍在 Application/Domain。

接入 `DocumentCatalogProvider` 时，目录 ACL 不是仅用于 `folderId` 筛选的表面校验：应用层会从可信当前租户和用户派生完整可读目录范围，并将该范围同时约束文档详情、未筛选分页和内容下载；空目录范围一律拒绝回退到无目录条件的查询。下载采用两阶段可见性校验：宿主目录调用在数据库事务外冻结 scope，解析版本和文件的同一个短事务再通过 tenant-scoped 查询端口复核文档仍位于该 scope；隐藏目录统一为 404，且不会访问对象存储或写下载审计。未接入目录 SPI 的宿主保持原有“文档授权 + 租户隔离”兼容行为，但不能宣称提供宿主目录树隔离。

## 协议稳定性

所有正式业务路由将以 `/fileweft/v1` 开头。除授权内容流的二进制成功响应外，业务成功响应与开始流式输出前的业务执行错误均生产 `application/json` 并使用统一外层；multipart 只改变请求的 `Content-Type`。内容流开始后的 I/O 失败会关闭 caller-owned 句柄并终止响应，不会尝试在部分二进制后追加 JSON。未匹配路由、请求体解码和内容协商错误仍由宿主 Spring Web 的全局异常策略处理：

```json
{
  "code": "OK",
  "message": "OK",
  "data": {},
  "error": null,
  "traceId": "optional-host-trace-id"
}
```

失败响应的 `data` 为 `null`，`error` 只包含与外层一致的固定 `code`/`message`；它不接受任意 attributes。错误不返回 SQL、对象存储路径、下游外部 ID、Outbox payload、凭据或未脱敏异常栈。稳定错误码至少区分 `INVALID_REQUEST`、`UNAUTHENTICATED`、`FORBIDDEN`、`NOT_FOUND`、`METHOD_NOT_ALLOWED`、`RANGE_NOT_SUPPORTED`、`CONFLICT`、`FEATURE_UNAVAILABLE`、`CONTENT_UNAVAILABLE` 和 `INTERNAL_ERROR`。时间使用 epoch milliseconds；标识均为不透明字符串。

`fileweft-web-api` 已定义 `DocumentDetailDto(document, versions)`，保证 `GET` 文档不会临时返回领域聚合或无版本归属的 Map。公开版本元数据不含存储路径、文件对象 ID、租户 ID、资产 ID 或内容哈希；内容哈希如确有业务必要，必须由后续单独的完整性读取权限与 DTO 提供。写命令只返回 `{documentId, versionId?}`：不做提交后查询，也不向只有写权限的调用者泄漏标题、文档号、生命周期或旧版本信息。审批任务不公开宿主用户 ID 或评论，只能在经过当前调用者计算后返回 `assignedToCurrentUser`。Doctor DTO 不接受原始 evidence；Web 映射层还必须将 checker 的自由文本归一为安全文案，不能直接透传下游信息。

当前已交付并通过两代 MVC 契约测试的正式路由是：

- `GET /fileweft/v1/documents/{documentId}`：当前租户、当前用户已授权且位于可读目录范围内的文档和版本视图。
- `GET /fileweft/v1/documents`：使用不透明 cursor 的文档摘要分页，可选生命周期和目录筛选。
- `POST /fileweft/v1/documents`：multipart 创建草稿；`documentNumber`、`title`、`file` 必须各出现一次，`folderId` 最多一次。
- `POST /fileweft/v1/documents/{documentId}/versions`：multipart 新增草稿版本；`versionNumber`、`file` 必须各出现一次。
- `PATCH /fileweft/v1/documents/{documentId}`：以 JSON `{\"title\": \"...\"}` 修改草稿标题。
- `GET /fileweft/v1/documents/{documentId}/content`：经当前租户、用户、目录范围和 `document:download` 授权后的当前版本内容流。
- `GET /fileweft/v1/documents/{documentId}/versions/{versionId}/content`：同一授权边界下显式选择属于该文档的历史版本内容流。

上传文件名、长度和内容类型只从服务器收到的 multipart file part 派生，客户端不能另行提交对象键、资产 ID、哈希或存储 metadata；文件名拒绝路径分隔符、`.` 和 `..`。创建和新增版本返回 `201`，改名返回 `200`。成功创建后仅当文档 ID 是长度受限的安全单路径段时才附带相对 `Location`；自定义标识不满足这个条件时仍返回成功 body，不会在业务提交后制造一次失败。

下载成功固定使用 `attachment`。`Content-Disposition` 同时提供不含控制字符的有界 ASCII fallback 与 RFC 5987 UTF-8 `filename*`；遗留路径、CRLF、Unicode FORMAT、孤立 surrogate 和超长文件名都不会进入原始响应头。内容类型只允许固定被动白名单，HTML、SVG、XML、JavaScript、JSON 和非法值回退 `application/octet-stream`；响应附带 `X-Content-Type-Options: nosniff` 与 `Cache-Control: private, no-store`。只有 Storage 实际报告且已与持久化元数据核对的长度才设置 `Content-Length`，持久化 fallback 不会伪装成传输层验证结果。响应不提供 ETag、`Accept-Ranges`、`Content-Range`、内容哈希、对象键或 Storage URL。

首版下载协议明确不支持 Range 与 HEAD 派生下载。请求只要出现 `Range` 头，就会在授权、仓储和对象存储之前固定返回 `416 RANGE_NOT_SUPPORTED`；两个内容路径的显式 `HEAD` 固定返回 `405 METHOD_NOT_ALLOWED` 与 `Allow: GET`，不会借用 Spring 的自动 HEAD 行为打开文件。

未接入宿主目录 SPI 时，创建可以省略 `folderId`，但提交 `folderId` 会安全失败为 `503 FEATURE_UNAVAILABLE`。接入唯一的目录 SPI 后，默认 Starter 会同时装配目录创建、目录感知修改和下载可见性服务：创建必须给出目录并通过应用层目录授权与保留绑定；新增版本和改名会先快照并授权文档的当前源目录，新增版本在上传后还会再次检查源目录权限，再按 document → asset 顺序取得两级 mutation lock 并复核原始绑定。Catalog SPI 始终在 FileWeft 管理的数据库事务外调用，权限撤销或绑定竞态会在提交前失败，新增版本已上传的对象会被补偿删除。自定义装配若只提供目录创建、或资产仓储没有安全 mutation lock 能力，新增版本和改名仍安全失败为 `503 FEATURE_UNAVAILABLE`，绝不退化调用无目录保护的通用写服务；自定义 `DocumentDownloadService` 也必须显式保留等价的目录可见性校验。

后续正式路由按以下形态增量交付；在对应 Controller 和测试完成前不视为可用接口：

- `submit`、`revise`、`publish`、`offline`、`restore`、`archive`：受控生命周期操作。
- `POST /fileweft/v1/workflows/{workflowId}/tasks/{taskId}/approve|reject`：明确支持多人会签，绝不以仅含 document ID 的“audit”路由猜测任务。
- `GET /fileweft/v1/documents/{documentId}/doctor` 与 `POST /doctor/tasks`：即时 Doctor 和可恢复的异步 Doctor。
- `GET /sync-status`、`GET /logs`、`GET /plugins`、`GET /health`：在相应的脱敏读模型和系统授权服务完成后加入；不能复用 Dev 的直连 JDBC DTO。

分页使用不透明 cursor 而不是让调用方拼接数据库 offset；当前 runtime 的 v1 codec 版本化 Base64URL 编码只包含稳定排序键 `updatedTime` 与 `documentId`，不含租户、用户、路径或密钥。它不是加密或签名机制：篡改/损坏 cursor 会被统一拒绝，而租户过滤与授权仍由应用层负责。每个列表请求仍以可信当前租户作为唯一数据域。所有文本输入均限制长度并拒绝控制字符；下载文件名与内容类型则由共享 runtime 策略归一，保证 Boot 2 与 Boot 3 不会各自解释不受信任的持久化 metadata。

## 安全与运行要求

- 上传大小、网关缓冲、超时和限流仍由宿主网关/认证层按实际风险模型配置；FileWeft 不伪造全局分布式限流。
- 下载保持 `DocumentDownloadService` 的授权、目录可见性和审计，不向前端暴露存储地址或凭据；自定义 Service 装配不能绕过默认 Starter 的目录 guard。
- Controller 只转发 runtime 已归一的 `Content-Disposition`、内容类型和 verified length，并关闭异常详情回显；它不会从持久化长度推测 `Content-Length`。
- CORS、CSRF、会话、OAuth/OIDC、mTLS 和 Actuator 暴露由宿主安全策略决定；Web Adapter 不提供弱默认认证。
- 正式写路由尚未承诺 `Idempotency-Key`。生产发布前必须提供与业务结果一起持久化的租户级请求幂等记录，使断线重试可以返回第一次已提交的结果；Controller 进程内缓存不能代替该能力。

## 测试与迁移门槛

每个正式路由至少覆盖：当前租户隔离、无用户/拒绝授权、参数错误、领域冲突、Trace 外层、敏感字段脱敏和响应稳定性。应用层将无用户与策略拒绝分别建模，供 Web 适配器稳定映射为 401/403，绝不依赖异常消息判断。Boot 2 与 Boot 3 都要有自动装配上下文与 MVC 契约测试；纯 `fileweft-web-api` 还要有 Java 8/Java 互操作测试。

本里程碑已完成相关常规模块测试、真实 PostgreSQL/RustFS 双租户 Compose E2E 和 9 条 Playwright 浏览器用例；其中包含独立的 formal-v1 用例，验证 Dev 应用确实通过正式 Starter 暴露 v1，而不只是前端改写 URL。该结果闭环了当前已交付读写与下载路由的 Dev v1 验收，但不覆盖尚未实现的持久化请求幂等、正式生命周期/多人审批、Doctor、同步状态和日志等 HTTP 映射。

首次采用正式 API 时，宿主应先接入可信 `TenantProvider`、`UserRealmProvider` 和 `AuthorizationProvider`，再逐步把自己的 Controller 调用迁移到 `/fileweft/v1`。Dev UI 当前的分阶段接入只作为可运行示例，不代表所有 `/api` 能力已经形成正式协议。
