# FileWeft 0.0.3 设计评审

> 本文是对 `v0.0.3` 签出代码的一次纯工程设计评审，不考虑发布门禁、兼容承诺与既有规约，仅从工程质量角度记录观察结果。所有结论基于实际源码，附文件路径证据。本文不代表任何已批准的变更计划。

## 总体判断

底子和品味明显高于平均水平：分层依赖方向真实执行（`fileweft-core` 零依赖，core/spi/domain 中无 Spring/ORM/厂商 SDK import）；`Document`（`fileweft-domain/.../document/Document.kt`）与 `WorkflowInstance` 是真正的状态机聚合而非贫血模型；outbox 使用 `FOR UPDATE SKIP LOCKED` + lease_token；commit 结果未知被显式建模为 `ApplicationTransactionOutcomeUnknownException`；分片上传对"确定失败 vs 结果未知"的区分（`MultipartCompletionRejectedException`）处理正确；V023/V029 迁移的前置校验与两阶段 `NOT VALID` 约束、V028 的 NO PAD 修复体现了少见的运维成熟度。

主要债务集中在一个主题：**同一个问题存在两套甚至三套并行的解，靠人肉纪律维持同步，并且已经开始漂移**。其次是静默失守的默认值、贯穿安全边界的字符串暗线、以及只在 connector 路径到位的弹性防护。

---

## 一、重复实现已在多处漂移

### 1.1 Boot2/Boot3 starter 是复制粘贴 fork，且已语义漂移

对 26 对对应文件做归一化 diff（仅替换 boot2/boot3 与类名前缀），**0 对相同**。差异不是表面的：

- `DocumentV1ContentController.kt` vs `V1DocumentContentController.kt`：diff 179 行；boot2 用独立 Advice + `transportFailure` 包装，boot3 内联 advice、方法名不同、新增 boot2 没有的 HEAD 拒绝结构。
- `DocumentV1WriteController.kt` vs `V1DocumentWriteController.kt`：diff 102 行，**校验文案不同**（boot2 `"Document file"` vs boot3 `"documentNumber must be provided exactly once"`），返回类型不同，boot2 的 `traceContextProvider` 有默认值而 boot3 没有。
- 两边最后一次提交是同一 commit，即漂移是在"同步维护"状态下产生的。

web-api/web-runtime 抽走了业务逻辑，但 controller plumbing 没有抽象：boot3 的 14 个 controller 各自手写 `execute`/`failureResponse`/`catch`，外加 `requiredSingle`/`currentTraceId` 复制。任何一处修复都要双写 ×14。应抽 `starter-common` 模块（Boot2 已用 `@AutoConfiguration`，条件具备），controller 外壳下沉为共享基类或 `@ControllerAdvice`。

### 1.2 三套方言迁移 ~95% 重复，但不变量不对等

- postgres 与 kingbase 的 29 个迁移文件中 20 个逐字节相同。
- 真正的问题是**同一领域不变量在不同方言的强制强度不同**：PG 有重量级 CHECK（V020 digest 正则与状态一致性、V026 决策证据一致性、V029 submitted_by 的 23 行 Unicode 防御正则），而 `mysql/V029` 与 `kingbase/V029` 全文仅 8 行——加列加一句 "enforced at the application boundary"。PG 部署有数据库兜底，MySQL/Kingbase 完全靠应用层自觉，且应用层是否覆盖全部规则无法从 schema 验证。
- 读路径把状态机一致性和数据合法性在 SQL 里重写了三遍（`JdbcWorkflowQueryRepository.kt`，609 行，三处 EXISTS/NOT EXISTS 各自编码同一套规则），损坏行被**静默隐藏**而非暴露。建议改为"写路径强约束 + Doctor 报警"。

### 1.3 领域层重复概念

- `AuditRecord`（`domain/audit/AuditRecord.kt`）与 `OperationLogRecord`（`domain/operation/OperationLogRecord.kt`）字段逐项雷同、仓储接口签名完全一致，且校验已开始分叉（前者对空白 operatorName 静默归一化，后者抛 require）。若"业务审计 vs 操作证据"的区分是真实的，类型系统里没有任何东西表达它。
- 两套幂等机制：`fw_upload_session` 自带 `UNIQUE(tenant_id, idempotency_key)`（V013），V020 又引入通用 `fw_idempotency_record`，语义并存且无互相指引。
- 两套 connector 注册：`FileWeftPlugin.connectors(): Map<String, FileConnector>` 与 `DeliveryConnectorResolver.findConnector(String)`，靠不透明字符串对齐。
- 两套存储位置模型：`FileObject(storageType, storagePath)`（domain）与 `StorageObjectLocation(storageType, path)`（spi），字段命名都不一致。
- `saveTask` 的 PG 声明式 upsert 与 MySQL "insert→catch→SELECT FOR UPDATE→Kotlin 重放→UPDATE" 四条语句路径各写一遍一致性规则（`JdbcWorkflowInstanceRepository.kt:104-255`），并用 `dialect.returningClause(emptyList()).isEmpty()` 当方言开关，很隐晦。

---

## 二、静默失守的默认值与死代码

### 2.1 fail-open / fail-late 的 Repository 默认值

`DocumentRepository.kt:27`：`findByDocumentNumber` 默认返回 `null`——文档号唯一性检查在 `DocumentDraftService.kt:88-90` 依赖它，未覆写的实现**静默关闭唯一性约束**。同文件 `:15-18` `findForMutation` 默认抛 `UnsupportedOperationException`（运行时地雷）。而同类问题在 `FileAssetMutationRepository` 用了正确的子接口方案。三种方案解决一个问题，最差的两种用在更重要的聚合上。

### 2.2 死代码与半死代码

- `core/result/`（`Result`、`ErrorDetail`、`ErrorCode`、`FileWeftException`）在全仓库只被 core 自己的测试引用；实际生效的是 domain 两套平行异常族，不携带 `ErrorCode` 也不继承 `FileWeftException`。要么在 application 边界真正采用，要么删掉。
- `MetadataValue`（metadata-api）：main 源码零引用，仅 Java 兼容测试使用。
- `FileWeftRuntimeConfiguration`：582 行、90+ 方法全部没有 `@Bean`（Boot2/Boot3 同），唯一作用是 `@Import` 五个拆分配置，与拆分后的配置类方法一一重复。读者必须先意识到"没有 @Bean 的同名方法是僵尸"才能看懂装配。`FileWeftRuntimeFactories`（933 行、被 6 个配置类各 new 一份）本质是把 Spring 当手工 DI 容器再写一遍，同一 bean 的装配逻辑存在于三个地方。
- boot3 `V1DocumentWriteController.kt:43-55` 与 `:100-110` 的 `create`/`addVersion` 无 `@PostMapping`（boot2 同款），HTTP 不可达、测试也不调，但作为 public controller 方法具有误导性。
- `DomainEvent` 是贫血接口且全仓库只有一个真实实现（`OutboxEvent`）：core 的"领域事件"抽象就是 outbox 记录本身，domain 聚合不产生任何事件。

### 2.3 诊断信息被让位给防泄露，且日志策略不统一

- `MetadataSchemaRegistry.kt:48-50`、`DefaultMetadataProcessor.kt:30-32`、`MetadataFieldCodec.kt:39-41` 把原始异常换成无 cause、无 schema id、无字段名的固定消息；format 编译失败时运维拿到的全部信息是 "Metadata schema configuration is invalid."。application main 源码 grep 不到任何 logger。
- `ResilientFileConnector.kt:191,341` 把下游真实异常装进 `InvocationAttempt.failed` 后丢弃，熔断打开/半开失败/池饱和均无日志，运维只见无差别 RETRYABLE_FAILURE。
- 日志三处三种做法：`Slf4jFileWeftLogger`（SPI）、`java.util.logging`（`JdbcApplicationTransaction.kt:113`、`FileWeftWorkerSchedulingConfiguration.kt:21-22`）、无日志（connector 弹性）。
- 不回显用户输入的安全立场成立，但 configuration 类（500 级）异常至少应保留 cause 或落一条服务端日志。

### 2.4 误用 JDK 异常语义

`WorkflowExceptions.kt:38-42`：`WorkflowTaskAssignmentDeniedException : SecurityException`——`SecurityException` 会被安全扫描、日志告警、框架兜底特殊处理，用它表达业务冲突是拿错误的信号通道传业务事件。同文件 `:45-50` `WorkflowTaskNotFoundException : NoSuchElementException` 同样牵强。对比 document 侧干净的 `DocumentConflictException` 体系，两个子域风格不统一。

---

## 三、贯穿边界的字符串暗线

- `DocumentCatalogBinding.METADATA_KEY = "catalog.folder-id"`（spi）：SPI 常量规定写进 domain `FileAsset.metadata` 的魔法键；持久层把它放进无 schema 的 JSON blob，参与每条查询的授权可见性过滤（`JdbcWorkflowQueryRepository.kt:305-306`，MySQL 侧变成 `JSON_CONTAINS` 对手写拼接的 JSON 数组串）。V001–V029 没有任何针对该 JSON 路径的表达式索引，rename 即静默破坏可见性。这是 EAV 反模式用在授权路径上，建议把 folder 归属提升为一等列。
- connectorId 即 Spring bean 名：`Map<String, FileConnector>` 注入，bean name 直接成为 delivery profile 里 `connectorId` 的匹配键（`FileWeftDeliveryConfiguration.kt:126`）。没有任何接口方法声明"你的 id 是什么"，rename bean 即让配置中的 profile 全部 UNRESOLVED。
- 续传会话状态编码进 `lastError` 字符串列：`isUserVisible` 靠 `lastError == "fileweft:resumable-upload:creation-staging:v1"` 判断（`ResumableUploadContext.kt:110-114`）。用错误消息字段承载状态机语义，一个显式 status/flag 列更诚实。
- MySQL 唯一键冲突识别依赖错误消息正则 `` for key `tenant_id` ``（`JdbcDocumentRepository.kt:178-191`）：V001 的未命名键为保持已发布 checksum 永不改名，将来任何首列为 tenant_id 的未命名唯一键都会让该正则误判。
- checker 名字符串、"document:doctor"/"document:read" 字面量、常量重复定义（`FILEWEFT_COMPATIBILITY_PREFIX` 两处、续传 marker 字符串 companion 与 top-level 各一份）散落各处，改名无编译期保护。

---

## 四、弹性防护只在 connector 路径到位

connector 路径质量很高（`ResilientFileConnector.kt:213-291`：单 monitor 状态机、OPEN 期 fast-fail、只允许一个 half-open 探测、"迟到的在途结果不能关闭已打开的熔断"这个细节绝大多数手写熔断器都会漏），但整体不对等：

1. **S3 storage 路径零防护**：`S3Client.builder()` 无 `overrideConfiguration`（无 apiCallTimeout/attemptTimeout/retry 策略），upload/download 在调用方线程直连，无超时、重试、并发上限（`S3StorageAdapter.kt:56-67`）。connector 三层防护、storage 零防护，与项目自己对"所有外部系统调用"的要求矛盾。
2. **无 per-connector 舱壁**：`ConnectorInvocationExecutor` 全局共享 16 线程 + 256 队列；一个不理会 interrupt 的慢下游占满线程后，所有健康 connector 的调用都会被 REJECTED。`ConnectorResiliencePolicy` 也是全局单值。
3. **插件 DoctorChecker 无超时**：`DoctorApplicationService` 只限 checker 数量（64）不限时长，插件贡献的 checker 不经任何超时包装直接同步执行，一个卡死的 checker 无限阻塞诊断 HTTP 请求。
4. **S3 multipart 完成后全量回下载算哈希**（`S3StorageAdapter.kt:196-204`）：大文件流量延迟翻倍；`uploadPart` 已用 `DigestingInputStream` 算过每 part 的 sha256 却丢掉只留 eTag，本可在 part 层保留摘要、完成时合成。

---

## 五、0.0.3 本版本新引入的问题

1. **Boot3 bean 改名是冲突炸弹**（0.0.3 自己引入的回归）：`fileWeftTransaction` 等改为 `transaction`、`documents`、`workflows`、`audits` 等短名（`fileweft-spring-boot3-starter/.../FileWeftDocumentConfiguration.kt:78-148`）。宿主极可能已有同名 bean；类型不同则 `@ConditionalOnMissingBean` 不生效、Boot 默认禁止 override，宿主直接启动失败。Boot2→Boot3 迁移也会静默改变所有 bean 名。应回滚或兼容化。
2. **RE2 正则每请求重复编译**：`MetadataValidator.evaluate` 每次先全量 `MetadataSchemaConfiguration.validate(schema)`（编译所有 format 正则），normalize 时再逐字段编译一次——一次 128 字段写入最多 256 次 RE2 编译（`MetadataValidator.kt:24`、`MetadataFieldCodec.kt:35,79`）。schema 不可变，按 format 字符串缓存几乎是免费的。
3. **multipart `field=value` 可用性差**：全 string 无类型表达力；字段名不能含 `=`；且校验抛出的好文案（"Each metadata entry must use field=value format"）被 `V1ApiResponseFactory.kt:100-104` 统一吞成 `"Request is invalid."`——客户端永远不知道哪个字段错了，严格校验反而制造集成调试成本。一个 `application/json` 的 multipart part 是更常规的选择。
4. **metadata 校验逻辑与常量三处重复**：`requireContractName`（api）与 `isSafeFieldName`（runtime）逐字重复；reserved namespace 前缀检查在 runtime 与 application 两处手工同步；128/16384 限制常量两处各写一份。`DocumentMetadataService.processTrusted:60,75-77` 的两道 reserved 检查在 runtime validator 之后**永不可达**——为不信任自己的依赖写的死防御。
5. **`MetadataSchemaContext` 四个字段三个是死参数**：`tenantId`/`resourceType`/`operation` 被默认 registry 完全忽略；SPI 签名暗示租户隔离解析，默认实现不做任何区分，容易给人"已经隔离"的错觉。要么默认实现使用 tenantId，要么承认这是预留抽象。
6. **防御层叠边际收益递减**：`submittedBy` 创建后不可变却在 withdraw 事务内复查快照；`DocumentLifecycleMutationTransaction` 只是 ThreadLocal 深度计数，防"忘包事务"防不了"包错层"；对 `Map.size` 抛异常的敌对 Map 防御。每条单独看都有注释辩护，整体抬高代码量并让读者分不清哪些对应真实威胁。建议明确一条"防御到哪一层为止"的界线。

---

## 六、其他值得记录的设计缺陷

- **接口默认方法对 Java 实现者不可见**：构建只设 `JVM_1_8` 未开 `-Xjvm-default`，Kotlin 接口默认方法全部进 `DefaultImpls`。Java 实现 `FileWeftPlugin`（9 方法 8 个"可选"）等接口时必须逐个显式实现——"可选"只对 Kotlin 实现者成立。规约盯着 suspend/Flow 这些语法点，漏了对 Java SPI 用户影响更大的坑。
- **一个 `Identifier` 包打天下**：tenantId、documentId、traceId、uploadId 全是同一类型，`findById(tenantId: Identifier, documentId: Identifier)` 参数调换零防护。既然已是手写包装类（规避 value class），应按 ID 种类分型。
- **评审路由去重误伤合法场景**：`DocumentReviewRouteProvider.kt:42` 要求 tasks 全 distinct，而 `DocumentReviewRouteTask.equals` 只比 assigneeId——两个未指派（null）的并行审批任务会被误判"重复"拒绝构造，与并行会签的设计意图冲突。根因是 task 没有 assignee 之外的身份。
- **全库零外键**：87 个迁移文件 `FOREIGN KEY`/`REFERENCES` 零命中。配合手写 UPDATE-then-INSERT upsert，并发撞 PK 时抛原始 SQLException 而非领域异常（catch 只翻译 doc_no 冲突）。引用完整性没有最后防线，此取舍需明确记录为风险接受。
- **胖 SPI 接口**：`StorageAdapter` 9 个方法，本地文件系统也必须实现分片三件套和预签名 `accessUrl`；`StorageDownload` 包住 `InputStream` 但不实现 `Closeable`、未定义谁负责关闭——资源所有权在 SPI 层悬空。`LocalStorageAdapter.accessUrl` 返回 `file:` URI 是抽象泄漏（下游平台必然取不到），根因是 SPI 契约无法表达"本存储 URL 不可远程寻址"。
- **指标枚举封闭**：`FileWeftMetric`/`FileWeftGauge` 把指标目录硬编码进 SPI，插件无法发自己的指标，每加一个一方指标都是一次 ABI 变更——与"可扩展框架"定位相悖。Micrometer/OTel 实现的基数纪律（只放行三个低基数 tag）本身是对的。
- **`LocalStorageAdapter` 的防御深度值得肯定**：key 正则 + normalize + `startsWith(root)` + 逐段符号链接检查 + staging/ATOMIC_MOVE 发布 + 对象与元数据两段发布带补偿删除 + 256 条纹锁 + 完成时逐 part 重新落盘哈希、从不信任调用方给的 hash。
- **Doctor 输出卫生与双重鉴权是一等公民**（`DoctorApplicationService.kt:135-188`：控制字符剥离、长度上限、evidence 敏感 key 过滤、名字不符降级 ERROR；`DocumentDoctorQueryService` 慢检查前后双重鉴权防 TOCTOU）。但 evidence 用数组序号（`connector.0.status`）而非稳定 connectorId，序号随 bean 顺序漂移。
- **testkit 的 `DoctorCheckerContractTest` 明显薄于兄弟合约**：只断言 name/reason 非空；`DoctorApplicationService` 实际依赖的不变量（不抛异常、side-effect-free、evidence 无敏感 key、有界输出）一个没测。connector/storage 合约（并发幂等、陈旧确认拒绝）的深度落差很大。
- **行尾不统一是 Flyway 实际隐患**：`postgres/V001` 纯 CRLF，`mysql/V001` 混合 CRLF/LF，多个 Kotlin 源也是 CRLF。缺 `.gitattributes` 约束；Flyway checksum 对行尾敏感，一个"修好行尾"的提交就会让已发布迁移 checksum 失配。
- **小项汇总**：`fw_audit_record` 有 `updated_time NOT NULL`（追加型审计表不该有更新语义，表模板无差别套用痕迹）；`varchar(64)`→256 的演进说明初始默认被证伪，且 256 的 JVM UTF-16 语义只有 PG 在 CHECK 里模拟；`@EnableScheduling` 挂 starter 配置类是对宿主 context 的全局副作用；`Document.addVersion` 同一方法里重复 id 用裸 `check`、重复版本号用类型化异常；`Document`/`WorkflowInstance` 无 createdAt/updatedAt，企业级聚合不知道自己的创建时间。

---

## 七、优先级建议（假设无限制）

1. **抽 `starter-common`，Boot2/Boot3 只留注册元数据差异**；同时回滚或兼容化 Boot3 bean 短名（0.0.3 回归）。
2. **收敛重复概念**：删或启用 `core/result`；合并或显式区分审计双记录；`Identifier` 按 ID 种类分型；统一存储位置模型。
3. **补 storage 侧弹性与 per-connector 舱壁、Doctor checker 超时**；熔断/metadata 配置异常保留 cause + 最小服务端日志；统一日志策略。
4. **Repository 默认值改编译期约束**（子接口模式）；读路径坏数据从"静默过滤"改为"Doctor 报警"；folder 归属提升为一等列。
5. **RE2 编译缓存** + reserved 前缀/限制常量单一事实源——一次改动消除 metadata 层最大的性能与维护隐患。
6. 补 `-Xjvm-default=all`（或显式声明 Java 实现者必须实现全部默认方法）；给 `DoctorCheckerContractTest` 补齐实际依赖的不变量；加 `.gitattributes` 锁定迁移与源码行尾。
