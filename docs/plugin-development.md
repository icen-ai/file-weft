# FlowWeft 插件开发

> **Agent 兼容边界：** `0.0.2` 与 `0.0.3` 均不提供 Agent 产品能力。现有 Agent
> SPI/getter 仅为源码与二进制兼容保留，默认运行时和插件清单不会注册或宣传
> 它们。后续 1.0 将按 [ADR 0001](decisions/0001-flowweft-1.0-product-scope.md)
> 新增独立 Provider/Plugin 契约；本文保留的旧 Agent 示例仍只是历史 ABI 说明，
> 不能作为 1.0 接入方式。

FlowWeft 的插件优先级固定为：客户显式 Spring Bean、插件贡献、框架默认实现。客户已经注册的 `StorageAdapter`、连接器或其他 SPI 不会被插件覆盖。

插件实现 `FileWeftPlugin`，并只通过 SPI 贡献能力。`0.0.3` 插件可以提供一个存储适配器、以稳定连接器 ID 命名的多个下游连接器、Doctor 检查器、Outbox 处理器、后台任务处理器和审批路由。插件不得修改 Core，也不得把厂商 SDK 类型泄漏到 SPI。历史 Agent/Agent 事件触发器 getter 继续存在，但本版本默认不消费，插件不得依赖它们形成新产品能力；1.0 智能 Provider 将使用独立的增量契约。

插件是与宿主应用运行在同一 JVM、共享同一进程权限和依赖类路径的可信代码，不是安全沙箱。Spring Bean 与 `ServiceLoader` 发现只解决装载方式，不提供文件、网络、凭据或租户数据隔离。正式环境只能安装经过代码审查、依赖与制品来源校验的插件；需要运行不可信扩展时，应把扩展放入独立进程，通过受鉴权、限流和审计的协议与 FlowWeft 通信。

`FileWeftPluginRegistry` 在构造时只调用每个有效插件的各项贡献 getter 一次，并把返回的集合、映射、实例与发现来源保存为不可变快照。后续 Starter、Doctor 和 Worker 都读取同一份实例快照，不会再次调用 getter。因此贡献 getter 必须确定、无远程调用且无业务副作用；不要依赖 getter 被重复执行，也不要在返回后修改原集合。建议把有状态 Connector 和处理器创建为插件字段，便于插件自身的生命周期管理。历史 Agent getter 即使为兼容仍可被构造期读取，也不会因此进入默认产品清单或执行路径。

正式 `GET /fileweft/v1/plugins` 只从这份构造期快照生成安全清单，并要求可信当前租户下的 `system:plugins:read` 授权。它只公开经过校验的稳定插件 ID，以及固定能力类别与数量；不会序列化 `FileWeftPlugin`、贡献实例、实现类、Bean/JAR 来源、连接器 ID、配置、路径或异常。插件 ID 应控制在 128 个字符内，不含首尾空白、ISO 控制字符或 Unicode FORMAT 字符；超出公共安全边界的插件仍可能参与旧的进程内扩展，但清单查询会失败关闭，插件作者不应依赖这种兼容行为。HTTP 请求期间不会重新调用 `id()` 或任何贡献 getter。

业务目录通常由宿主 Spring Bean 提供 `DocumentCatalogProvider`，因为目录 ACL 和组织体系属于宿主而非 FlowWeft。实现可以覆写带 `DocumentCatalogAccessRequest` 的方法，以其中可信的租户、用户和 `BROWSE` / `BIND_DOCUMENT` 操作过滤目录；FlowWeft 不会把浏览器提交的租户或用户转交给目录实现。Starter 检测到唯一目录 Provider 后会提供 `DocumentCatalogAccessService`，用于在绑定 `catalog.folder-id` 元数据前执行基础文档授权与目录存在性校验。目录感知的新增版本、改名和 `DocumentCatalogBindingService.move` 都会先验证当前源目录；新增版本在上传后重验源目录，移动还会独立验证目标目录。Catalog 调用发生在 FlowWeft 管理的数据库事务外，随后按 document → asset 顺序取得 mutation lock 并复核原始绑定，避免隐藏目录文档被猜测 ID 后移动或修改。绑定竞态会失败并要求重新授权，只修改资产元数据的成功移动仍保留审计。宿主不得绕过这些应用服务直接修改保留的 `catalog.*` / `fileweft.*` 资产 metadata；所有绑定写入必须遵守同一锁顺序，并把目录应用服务作为顶层用例调用，不能再包进宿主的外层数据库事务。外部目录 ACL 是短命决策快照，无法与宿主目录系统做跨系统原子提交。

自定义持久化若要开放目录移动、新增版本和改名，资产仓储还必须实现加法接口 `FileAssetMutationRepository`，其 `findForMutation` 必须提供真实的租户级互斥或等价 CAS，不能回退成普通查询。默认 PostgreSQL 实现使用 `SELECT ... FOR UPDATE`，并已用两条独立连接验证竞争 UPDATE 和竞争锁都会等待/超时。只实现旧 `FileAssetRepository` 的宿主仍可使用目录读取和目录草稿创建；Starter 不会装配目录修改服务，正式 Web 对相关修改安全返回 `503 FEATURE_UNAVAILABLE`。

自定义持久化若要开放正式生命周期写接口，还必须提供 `RequestIdempotencyRepository` 的等价原子语义。仓储只能保存租户作用域的 key 摘要，不能保存或记录原始 `Idempotency-Key`；唯一域必须覆盖租户与摘要，operator/action/resource/subresource/request fingerprint 作为不可变绑定比较。`claim` 必须在调用用例的最终本地事务中先于 document/asset/workflow 锁执行，并与领域保存、审计、Outbox 和 `complete` 同时提交或回滚。并发插入同 key 时，失败方只能在赢家提交后重放完整结果，赢家回滚时下一方才可取得执行权。可见的 `IN_PROGRESS` 不是租约，不能超时接管；不具备这些保证的宿主必须让正式写能力安全返回 `503`，不能退化到进程内 Map、普通查询加保存或 Redis 与业务数据库的双写。

无目录宿主应暴露 `IdempotentDocumentLifecycleService` 与 `IdempotentDocumentReviewWorkflowService`；启用目录的宿主只能暴露对应的 `IdempotentDocumentCatalogLifecycleService` 与 `IdempotentDocumentCatalogReviewWorkflowService`。目录版本会在每次重放前重新执行源目录 ACL，并在最终事务内复核绑定。不要同时注册 flat 与 catalog-aware 候选，也不要从 Controller 直接调用 application-internal ambient hook：这些 hook 只供幂等协调器在已经 claim 的同一最终事务中复用，调用方负责先完成授权、目录快照和事务外策略解析。

## 动态目录与组合系统实现规范

目录选择可以逐请求动态传入，但请求只传不透明的 `folderId`，不能传 Provider 实例、租户、用户或权限结果。数值主键、UUID、雪花 ID 都可以先转成字符串。FlowWeft 对调用方输入做有界校验，再用可信上下文调用 Provider；Provider 返回的 canonical ID 才会写入 `catalog.folder-id`，对象存储路径不会包含目录 ID。

`listFolders(...)` 不是分页接口：每次调用必须返回该 tenant/user/operation 作用域内完整的可见森林；结果可以为空，但最多 10,000 个节点。folder ID 与非空 parent ID 必须非空、已去除首尾 Unicode whitespace、最多 256 个 UTF-16 code unit；displayName 必须非空、已去除首尾 whitespace、最多 512 个 code unit；三者都不得含 ISO control 或 Unicode FORMAT 字符。folder ID 在同一结果内必须唯一，每个非空 parent ID 必须指向同一结果中的可见节点，父链不得成环。ACL 隐藏父节点时必须把可见子节点重新挂为根或挂到另一可见父节点，不能泄露隐藏父 ID。FlowWeft 会在返回任何节点前校验整份快照，任一违规都会整批失败关闭，不会替 Provider 修剪或修补。

`findFolder(...)` 可以把请求别名（例如 `finance`）解析为不同的 canonical ID（例如 `erp:folder-8241`），但返回 ID 必须稳定并满足同一文本边界；只有 canonical ID 会写入 `catalog.folder-id`。目录改名或换父级应保持 ID 不变；确需换 ID 时使用 `DocumentCatalogBindingService.move` 显式重绑。Provider 不得跨租户解析同名 ID。

以下示例展示按租户动态路由。`listFolders(request)` 与 `findFolder(request, id)` 必须执行当前用户 ACL；tenant-only 方法用于 Doctor 等系统诊断，不能被业务 Controller 直接暴露：

```kotlin
interface BusinessCatalogClient {
    fun listAll(tenantId: Identifier): List<HostFolder>

    fun listVisible(
        tenantId: Identifier,
        userId: Identifier,
        operation: DocumentCatalogOperation,
    ): List<HostFolder>

    fun findVisible(
        tenantId: Identifier,
        userId: Identifier,
        operation: DocumentCatalogOperation,
        requestedId: String,
    ): HostFolder?
}

data class HostFolder(
    val canonicalId: String,
    val parentCanonicalId: String?,
    val displayName: String,
)

class RoutedDocumentCatalogProvider(
    private val clientsByTenant: Map<Identifier, BusinessCatalogClient>,
) : DocumentCatalogProvider {
    override fun listFolders(tenantId: Identifier): List<DocumentCatalogFolder> =
        client(tenantId).listAll(tenantId).map(::toFolder)

    override fun listFolders(request: DocumentCatalogAccessRequest): List<DocumentCatalogFolder> =
        client(request.tenantId)
            .listVisible(request.tenantId, request.userId, request.operation)
            .map(::toFolder)

    override fun findFolder(
        request: DocumentCatalogAccessRequest,
        folderId: String,
    ): DocumentCatalogFolder? = client(request.tenantId)
        .findVisible(request.tenantId, request.userId, request.operation, folderId)
        ?.let(::toFolder)

    private fun client(tenantId: Identifier): BusinessCatalogClient =
        clientsByTenant[tenantId]
            ?: throw IllegalStateException("Catalog is not configured for the current tenant.")

    private fun toFolder(source: HostFolder): DocumentCatalogFolder = DocumentCatalogFolder(
        source.canonicalId,
        source.parentCanonicalId,
        source.displayName,
    )
}
```

多个 OA、ERP、知识库同时接入时，优先注册一个组合 `DocumentCatalogProvider` Bean，由它按租户或 canonical ID 命名空间路由；不要注册多个 Provider 再依赖 `@Primary`。同一租户汇聚多个来源时，应使用稳定前缀（例如 `oa:`、`erp:`）避免 ID 碰撞，并让父 ID 使用相同命名空间。也可以由宿主显式提供唯一的聚合 `DocumentCatalogAccessService`，但安全边界最终仍必须只有一个。

远程目录调用必须设置短超时、有限的只读重试、熔断和诊断。缓存键至少包含 tenant、user、operation、folder ID，不能把某个用户的可见结果共享给其他用户；缓存 TTL 还要服从权限撤销时效。`BROWSE` 可只返回可见目录，`BIND_DOCUMENT` 应执行更严格的写入/归档规则。Provider 调用发生在 FlowWeft 数据库事务外，但写入前仍会二次验证与取得本地 mutation lock；宿主不要用长 TTL 缓存抵消这层撤权保护。

```kotlin
class ArchivePlugin : FileWeftPlugin {
    private val archiveConnector = ArchiveConnector()

    override fun id(): String = "archive-platform"

    override fun connectors(): Map<String, FileConnector> = mapOf(
        "archive" to archiveConnector,
    )

    // Historical ABI only; do not contribute these in the 0.0.3 default runtime:
    // override fun agents(): List<FileWeftAgent> = listOf(ClassificationAgent())
    // override fun agentTaskTriggers(): List<AgentTaskTrigger> = listOf(DocumentCreatedTrigger())

    override fun reviewRouteProviders(): List<DocumentReviewRouteProvider> = listOf(ArchiveDualControlRoute())
}
```

Spring Boot 应用可以将该插件直接注册为 `FileWeftPlugin` Bean。非 Spring 或独立插件 JAR 则在 JAR 中提供以下文件：

```text
META-INF/services/ai.icen.fw.spi.plugin.FileWeftPlugin
```

文件内容为实现类的完整类名。ServiceLoader 发现的插件与 Spring Bean 插件可以共存；同一实现同时以两种方式出现时以 Spring Bean 为准。不同实现不得使用相同插件 ID，插件连接器 ID 也不得和客户 Bean 或另一插件冲突，应用会在启动时给出明确错误。

连接器必须自行做到超时、重试、幂等和健康检查。事件及任务处理器会被重试，因此必须以事件 ID 或任务 ID 实现幂等。`FileConnector.remove` 会在文档下线或归档后的独立 Outbox 事件中调用；实现必须以 `ConnectorInvocation.idempotencyKey` 幂等删除，接受框架传入的外部 ID（历史同步没有外部 ID 时可能是稳定的业务文档 ID），并把可恢复故障返回为 `RETRYABLE_FAILURE`。审批路由实现 `DocumentReviewRouteProvider`，以稳定 `id()` 返回一份非空任务列表；路由解析在 FlowWeft 的数据库事务之外执行，若实现需要查询宿主的组织或策略服务，必须自行设定超时、重试、错误记录和诊断。一个路由的所有任务通过后才会发布，任一任务驳回则结束审批。历史 `ConfirmAgentSuggestionService` 及 `agent:suggestion:confirm` 权限仅为兼容保留；`0.0.3` 的默认运行时与正式 HTTP 不提供 Agent 建议确认产品流程。

发布插件前至少运行对应的 `fileweft-testkit` 合约测试：存储适配器使用 `StorageAdapterContractTest`（对象版本与租户隔离、长度/类型、幂等删除、访问 URL、multipart 重传与取消），连接器使用 `FileConnectorContractTest`（健康协议、顺序和并发幂等同步、稳定外部 ID、撤回幂等），授权实现使用 `AuthorizationProviderContractTest`。目录 Provider 继承 `DocumentCatalogProviderContractTest`，验证 tenant/user 可见森林的 10,000 节点上限、规范文本、唯一 ID、同快照父节点、无环父链，以及 `findFolder` 与列举结果一致性。多下游配置实现可使用 `DocumentDeliveryProfileProviderContractTest`，校验租户档案列表、默认档案和 target 路由的一致性；Metadata 扩展使用 `MetadataSchemaResolverContractTest` 与 `MetadataProcessorContractTest` 验证租户/版本失败关闭和确定性不可变规范化。插件入口、结构化日志、计数器、Gauge 和可变 Trace carrier 分别继承 `FileWeftPluginContractTest`、`FileWeftLoggerContractTest`、`FileWeftMetricsContractTest`、`FileWeftGaugeRecorderContractTest` 与 `TraceContextScopeContractTest`；插件合约不会调用仅为 ABI 保留的旧 Agent contribution getter。若对象存储对非末片有最小大小限制，适配器测试可覆写 `multipartParts()` 和 `replacementMultipartPart()` 提供符合该服务约束的分片；若下游测试环境限制并发，可覆写连接器合约的并发度和等待时长。

FlowWeft 1.0 的智能 Provider 使用独立的公共 TestKit。Agent 侧分别继承 `AgentAuthorizationProviderContractTest`、`AgentAtomicDispatchAuthorizationProviderContractTest`、`AgentExecutionContextConsumerContractTest`、`AgentProviderFailureMapperContractTest`、`AgentPolicyProviderContractTest`、`LanguageModelProviderContractTest`、`AgentEvaluatorContractTest`、`AgentToolDescriptorProviderContractTest` 与 `AgentDescriptorBoundToolExecutorContractTest`。它们校验稳定 Provider/descriptor、能力与请求匹配、非空 `CompletionStage`、精确结果绑定、取消句柄、raw SDK 故障脱敏，以及 execution context/dispatch fence 的单次消费和重放。执行上下文与工具执行合约只接受由真实授权复核、可选审批和执行期再授权生成的公开能力类型；TestKit 不提供伪造这些安全凭据的捷径，也不会把 Provider 自报用量当作可信退款依据，只验证其不超过预留上界。

检索侧使用 `RetrievalAuthorizationPlannerContractTest`、`CandidateRetrieverContractTest`、`RetrievalLineageResolverContractTest`、`RetrievalCandidateAuthorizerContractTest`、`RetrievalContentProviderContractTest` 和 `RerankerContractTest` 验证授权允许/拒绝失败关闭、descriptor 快照、tenant/ACL 前置回执、当前授权、内容出站决策、精确血缘和取消能力声明；`ContentExtractorContractTest`、`ContentChunkerContractTest`、`EmbeddingProviderContractTest` 与 `RetrievalIndexProviderContractTest` 验证抽取/分块/向量/索引的 request、descriptor、manifest、generation、CAS revision 和结果绑定。候选、血缘、逐条授权与内容合约只能通过对应的公开 Gate 构造或执行安全类型，不能用“全库召回后过滤”样例替代。索引合约的 stage、seal、activate、mutation 和 state 用例彼此独立，真实适配器必须为每个 hook 准备专用隔离 source/generation，不能依赖 JUnit 执行顺序。

三套 TestKit 按产品边界独立发布：`fileweft-testkit` 保留存储、连接器、目录、Metadata、插件与可观测性合约；Agent Provider 使用 `ai.icen:flowweft-agent-testkit`；检索 Provider 使用 `ai.icen:flowweft-retrieval-testkit`。两个 1.0 TestKit 都用 Java 与 Kotlin consumer 子类编译并执行自身合约。独立的 `release-smoke/library-consumer` 只通过这两个发布后的 Maven 坐标取类，并由 JUnit 实际发现和执行 Java 8 的 Agent 模型合约与 Kotlin/JVM 8 的安全 Gate 候选检索合约；它不是只编译或只实例化的仓库内夹具，也不会从旧 `fileweft-testkit` 偶然取得新 Provider API。真实 Provider 发布验收仍须在独立样例宿主中使用专用测试数据继承相同抽象类。异步等待默认有界，远程服务可覆写 `asynchronousTimeout()`，但不得关闭结果绑定、授权 Gate 或取消能力一致性断言。

最小接入方式是在插件模块测试依赖中加入仓库内的 TestKit，并继承相应抽象测试类：

```kotlin
dependencies {
    testImplementation(project(":fileweft-testkit"))
}

class ArchiveStorageContractTest : StorageAdapterContractTest() {
    override val storageAdapter: StorageAdapter = ArchiveStorageAdapter(/* test configuration */)

    override fun uploadRequest(): StorageUploadRequest = StorageUploadRequest(
        tenantId = Identifier("contract-tenant"),
        objectName = "contract.txt",
        contentLength = content().size.toLong(),
        contentType = "text/plain",
    )
}
```

连接器、授权、交付档案、插件和可观测性实现同样继承对应的公共 ContractTest，并提供受控的测试租户、请求、档案或内存观测后端。真实远程测试数据必须使用专用环境和唯一标识，避免误操作生产资源。
