# FileWeft 插件开发

FileWeft 的插件优先级固定为：客户显式 Spring Bean、插件贡献、框架默认实现。客户已经注册的 `StorageAdapter`、连接器或其他 SPI 不会被插件覆盖。

插件实现 `FileWeftPlugin`，并只通过 SPI 贡献能力。插件可以提供一个存储适配器、以稳定连接器 ID 命名的多个下游连接器、Doctor 检查器、Agent、Agent 事件触发器、Outbox 处理器和后台任务处理器。插件不得修改 Core、不得把厂商 SDK 类型泄漏到 SPI，也不得让 Agent 直接修改领域聚合。

```kotlin
class ArchivePlugin : FileWeftPlugin {
    override fun id(): String = "archive-platform"

    override fun connectors(): Map<String, FileConnector> = mapOf(
        "archive" to ArchiveConnector(),
    )

    override fun agents(): List<FileWeftAgent> = listOf(ClassificationAgent())

    override fun agentTaskTriggers(): List<AgentTaskTrigger> = listOf(DocumentCreatedTrigger())
}
```

Spring Boot 应用可以将该插件直接注册为 `FileWeftPlugin` Bean。非 Spring 或独立插件 JAR 则在 JAR 中提供以下文件：

```text
META-INF/services/com.fileweft.spi.plugin.FileWeftPlugin
```

文件内容为实现类的完整类名。ServiceLoader 发现的插件与 Spring Bean 插件可以共存；同一实现同时以两种方式出现时以 Spring Bean 为准。不同实现不得使用相同插件 ID，插件连接器 ID 也不得和客户 Bean 或另一插件冲突，应用会在启动时给出明确错误。

连接器必须自行做到超时、重试、幂等和健康检查。事件及任务处理器会被重试，因此必须以事件 ID 或任务 ID 实现幂等。Agent 的输出只会成为可审计的建议；调用方必须通过显式确认后的应用用例才可以改变业务数据。

发布插件前至少运行对应的 `fileweft-testkit` 合约测试：存储适配器使用 `StorageAdapterContractTest`，连接器使用 `FileConnectorContractTest`，授权实现使用 `AuthorizationContractTest`。
