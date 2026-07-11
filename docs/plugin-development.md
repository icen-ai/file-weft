# FileWeft 插件开发

FileWeft 的插件优先级固定为：客户显式 Spring Bean、插件贡献、框架默认实现。客户已经注册的 `StorageAdapter`、连接器或其他 SPI 不会被插件覆盖。

插件实现 `FileWeftPlugin`，并只通过 SPI 贡献能力。插件可以提供一个存储适配器、以稳定连接器 ID 命名的多个下游连接器、Doctor 检查器、Agent、Agent 事件触发器、Outbox 处理器、后台任务处理器和审批路由。插件不得修改 Core、不得把厂商 SDK 类型泄漏到 SPI，也不得让 Agent 直接修改领域聚合。

业务目录通常由宿主 Spring Bean 提供 `DocumentCatalogProvider`，因为目录 ACL 和组织体系属于宿主而非 FileWeft。实现可以覆写带 `DocumentCatalogAccessRequest` 的方法，以其中可信的租户、用户和 `BROWSE` / `BIND_DOCUMENT` 操作过滤目录；FileWeft 不会把浏览器提交的租户或用户转交给目录实现。Starter 检测到唯一目录 Provider 后会提供 `DocumentCatalogAccessService`，用于在绑定 `catalog.folder-id` 元数据前执行基础文档授权与目录存在性校验。

```kotlin
class ArchivePlugin : FileWeftPlugin {
    override fun id(): String = "archive-platform"

    override fun connectors(): Map<String, FileConnector> = mapOf(
        "archive" to ArchiveConnector(),
    )

    override fun agents(): List<FileWeftAgent> = listOf(ClassificationAgent())

    override fun agentTaskTriggers(): List<AgentTaskTrigger> = listOf(DocumentCreatedTrigger())

    override fun reviewRouteProviders(): List<DocumentReviewRouteProvider> = listOf(ArchiveDualControlRoute())
}
```

Spring Boot 应用可以将该插件直接注册为 `FileWeftPlugin` Bean。非 Spring 或独立插件 JAR 则在 JAR 中提供以下文件：

```text
META-INF/services/com.fileweft.spi.plugin.FileWeftPlugin
```

文件内容为实现类的完整类名。ServiceLoader 发现的插件与 Spring Bean 插件可以共存；同一实现同时以两种方式出现时以 Spring Bean 为准。不同实现不得使用相同插件 ID，插件连接器 ID 也不得和客户 Bean 或另一插件冲突，应用会在启动时给出明确错误。

连接器必须自行做到超时、重试、幂等和健康检查。事件及任务处理器会被重试，因此必须以事件 ID 或任务 ID 实现幂等。`FileConnector.remove` 会在文档下线或归档后的独立 Outbox 事件中调用；实现必须以 `ConnectorInvocation.idempotencyKey` 幂等删除，接受框架传入的外部 ID（历史同步没有外部 ID 时可能是稳定的业务文档 ID），并把可恢复故障返回为 `RETRYABLE_FAILURE`。审批路由实现 `DocumentReviewRouteProvider`，以稳定 `id()` 返回一份非空任务列表；路由解析在 FileWeft 的数据库事务之外执行，若实现需要查询宿主的组织或策略服务，必须自行设定超时、重试、错误记录和诊断。一个路由的所有任务通过后才会发布，任一任务驳回则结束审批。Agent 的输出只会成为可审计的建议；业务 API 应调用 `ConfirmAgentSuggestionService`，它会校验当前租户、`agent:suggestion:confirm` 权限并写审计/操作日志。确认本身不会改变业务数据，后续变更仍必须由独立应用用例明确执行。

发布插件前至少运行对应的 `fileweft-testkit` 合约测试：存储适配器使用 `StorageAdapterContractTest`（普通上传、下载、删除，以及 multipart 完成和可重试取消），连接器使用 `FileConnectorContractTest`，授权实现使用 `AuthorizationContractTest`。若对象存储对非末片有最小大小限制，适配器测试可覆写 `multipartParts()` 提供符合该服务约束的分片。
