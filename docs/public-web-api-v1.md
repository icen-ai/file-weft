# 正式 Web API v1 设计

`fileweft-dev` 中的 `/api/**` 是开发验收界面专用入口，不是嵌入方可以依赖的公共协议。它包含 Dev Session、开发用 JDBC 投影和演示平台行为，不能通过改路径直接升级为生产 API。

本设计定义正式 API 的增量交付边界。它不会把 MVC、Servlet、认证 SDK 或数据库查询引入 Core、Domain、SPI 或默认 Starter。

## 模块与兼容性

当前已交付的工件是：

- `fileweft-web-api`：JDK 8 基线的纯契约、响应模型、错误码与 DTO；不依赖 Spring、JDBC、领域实体或任意 FileWeft 运行时模块。
- `fileweft-web-runtime`：JDK 8 基线的纯 JVM v1 文档读取门面；显式依赖 `fileweft-application` 与 `fileweft-web-api`，只将应用层已授权、已脱敏的文档查询视图映射为公共 DTO。它不引入 Spring、MVC、Servlet、JDBC 或 HTTP 路由，也不接受租户/用户参数。
- `fileweft-web-spring-boot2-starter`：JDK 8 / Spring Boot 2.7 的可选 MVC 适配器；通过 `spring.factories` 注册，只在安全的 `DocumentQueryService` 已存在时暴露 v1 读取路由。
- `fileweft-web-spring-boot3-starter`：JDK 17 / Spring Boot 3 的可选 MVC 适配器；通过 `AutoConfiguration.imports` 注册，并与 Boot 2 保持同一路径、状态码和响应外层。

Web Starter 不隐式引入数据库或替代原有运行时 Starter。宿主应按自己的 Spring Boot 代际同时选择对应的 `fileweft-spring-boot*-starter` 与 `fileweft-web-spring-boot*-starter`；若安全查询服务未装配，Controller 会保持不可用。原有 Starter 与 Dev 路由不改变，正式 Web 保持加法兼容。

## 信任边界

公共 Controller 不接受 `tenantId`、用户 ID、角色或权限作为业务参数，也不会从不受信任的请求头写入 FileWeft 上下文。它只调用宿主已经提供的：

- `TenantProvider`
- `UserRealmProvider`
- `AuthorizationProvider`
- 可选 `TraceContextProvider`

宿主的认证层负责将已验证的请求身份绑定到这些 SPI。默认 Starter 的空用户和拒绝授权实现意味着未接入可信身份时，受保护 API 安全失败。Controller 只做参数校验、DTO 转换和应用服务调用；文档授权、租户过滤、审计和状态机规则仍在 Application/Domain。

接入 `DocumentCatalogProvider` 时，目录 ACL 不是仅用于 `folderId` 筛选的表面校验：应用层会从可信当前租户和用户派生完整可读目录范围，并将该范围同时约束文档详情与未筛选分页；空目录范围一律拒绝回退到无目录条件的查询。未接入目录 SPI 的宿主保持原有“文档授权 + 租户隔离”兼容行为，但不能宣称提供宿主目录树隔离。

## 协议稳定性

所有正式业务路由将以 `/fileweft/v1` 开头，并只生产 `application/json`。已匹配的 FileWeft 业务路由，其成功与业务执行错误均使用统一外层；未匹配路由、请求体解码和内容协商错误仍由宿主 Spring Web 的全局异常策略处理：

```json
{
  "code": "OK",
  "message": "OK",
  "data": {},
  "error": null,
  "traceId": "optional-host-trace-id"
}
```

失败响应的 `data` 为 `null`，`error` 只包含与外层一致的固定 `code`/`message`；它不接受任意 attributes。错误不返回 SQL、对象存储路径、下游外部 ID、Outbox payload、凭据或未脱敏异常栈。稳定错误码至少区分 `INVALID_REQUEST`、`UNAUTHENTICATED`、`FORBIDDEN`、`NOT_FOUND`、`CONFLICT`、`FEATURE_UNAVAILABLE` 和 `INTERNAL_ERROR`。时间使用 epoch milliseconds；标识均为不透明字符串。

`fileweft-web-api` 已定义 `DocumentDetailDto(document, versions)`，保证 `GET` 文档不会临时返回领域聚合或无版本归属的 Map。公开版本元数据不含存储路径、文件对象 ID、租户 ID、资产 ID 或内容哈希；内容哈希如确有业务必要，必须由后续单独的完整性读取权限与 DTO 提供。审批任务不公开宿主用户 ID 或评论，只能在经过当前调用者计算后返回 `assignedToCurrentUser`。Doctor DTO 不接受原始 evidence；Web 映射层还必须将 checker 的自由文本归一为安全文案，不能直接透传下游信息。

当前已交付并通过两代 MVC 契约测试的正式路由是：

- `GET /fileweft/v1/documents/{documentId}`：当前租户、当前用户已授权且位于可读目录范围内的文档和版本视图。
- `GET /fileweft/v1/documents`：使用不透明 cursor 的文档摘要分页，可选生命周期和目录筛选。

后续正式路由按以下形态增量交付；在对应 Controller 和测试完成前不视为可用接口：

- `POST /fileweft/v1/documents`：multipart 创建草稿。`folderId` 先经可信当前用户的宿主目录 ACL 验证，再由应用层写入保留目录绑定；客户端不能直接提交任意资产/存储 metadata。
- `PATCH /fileweft/v1/documents/{documentId}`、`POST /versions`、`submit`、`revise`、`publish`、`offline`、`restore`、`archive`：受控生命周期与版本操作。
- `POST /fileweft/v1/workflows/{workflowId}/tasks/{taskId}/approve|reject`：明确支持多人会签，绝不以仅含 document ID 的“audit”路由猜测任务。
- `GET /fileweft/v1/documents/{documentId}/content`：经应用层下载授权后的流式响应。
- `GET /fileweft/v1/documents/{documentId}/doctor` 与 `POST /doctor/tasks`：即时 Doctor 和可恢复的异步 Doctor。
- `GET /sync-status`、`GET /logs`、`GET /plugins`、`GET /health`：在相应的脱敏读模型和系统授权服务完成后加入；不能复用 Dev 的直连 JDBC DTO。

分页使用不透明 cursor 而不是让调用方拼接数据库 offset；当前 runtime 的 v1 codec 版本化 Base64URL 编码只包含稳定排序键 `updatedTime` 与 `documentId`，不含租户、用户、路径或密钥。它不是加密或签名机制：篡改/损坏 cursor 会被统一拒绝，而租户过滤与授权仍由应用层负责。每个列表请求仍以可信当前租户作为唯一数据域。所有文本输入均须限制长度、拒绝控制字符；下载适配器仍须独立执行 RFC 5987 文件名编码与内容类型白名单，不能信任 DTO。

## 安全与运行要求

- 上传大小、网关缓冲、超时和限流仍由宿主网关/认证层按实际风险模型配置；FileWeft 不伪造全局分布式限流。
- 下载保持 `DocumentDownloadService` 的授权和审计，不向前端暴露存储地址或凭据。
- Controller 必须使用安全的 `Content-Disposition` 文件名编码、白名单/回退内容类型，并关闭异常详情回显。
- CORS、CSRF、会话、OAuth/OIDC、mTLS 和 Actuator 暴露由宿主安全策略决定；Web Adapter 不提供弱默认认证。

## 测试与迁移门槛

每个正式路由至少覆盖：当前租户隔离、无用户/拒绝授权、参数错误、领域冲突、Trace 外层、敏感字段脱敏和响应稳定性。应用层将无用户与策略拒绝分别建模，供 Web 适配器稳定映射为 401/403，绝不依赖异常消息判断。Boot 2 与 Boot 3 都要有自动装配上下文与 MVC 契约测试；纯 `fileweft-web-api` 还要有 Java 8/Java 互操作测试。开发编排会在后续把 v1 调用加入双租户端到端验收。

由于正式 API 是新工件和新路径，旧 Dev UI 无需迁移。首次采用时，宿主应先接入可信 `TenantProvider`、`UserRealmProvider` 和 `AuthorizationProvider`，再逐步把自己的 Controller 调用迁移到 `/fileweft/v1`。
