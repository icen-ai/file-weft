# 生产部署与恢复

FileWeft 的 Web 节点默认不消费 Outbox 或后台任务。部署时推荐使用相同的应用工件启动两个角色：

```yaml
# Web 节点：默认值，无需配置 fileweft.worker.enabled
fileweft:
  worker:
    enabled: false

# 异步 Worker 节点
fileweft:
  worker:
    enabled: true
    fixed-delay-millis: 1000
    outbox-batch-size: 50
    task-batch-size: 50
    process-outbox: true
    process-tasks: true
    process-upload-cleanup: true

  upload:
    resumable-session-ttl-millis: 86400000
    resumable-cleanup-batch-size: 100
```

若需要拆分资源池，可让下游同步节点只开启 `process-outbox`，让 Doctor/Agent 节点只开启 `process-tasks`，让存储维护节点只开启 `process-upload-cleanup`。所有节点可以水平扩展：Outbox 与后台任务均通过数据库租约/锁领取，重复投递由事件或任务的幂等键约束。

Worker 每轮失败只记录日志，不会丢弃待处理记录；下一轮会继续领取符合重试时间或租约已过期的工作。生产报警应至少覆盖同步失败、任务失败、任务失租（`fileweft.task_lease_lost`）、Doctor 失败和持久化 Outbox 积压。

## 持久化 Outbox 积压指标与运行角色

Outbox 积压不是 API 节点上的内存计数，而是对 `fw_outbox_event` 的全局数据库聚合快照。启用了 Micrometer `MeterRegistry` 的 Starter 会导出以下指标：

| 指标 | 固定 `state` / 标签 | 运营含义 |
| --- | --- | --- |
| `fileweft.outbox_backlog` | `ready` | 已到执行时间的 `PENDING`/`RETRY`；持续增长通常表示 Worker 不足、下游受阻或领取失败。 |
| `fileweft.outbox_backlog` | `delayed` | 尚在退避窗口的 `PENDING`/`RETRY`；短暂存在是正常现象。 |
| `fileweft.outbox_backlog` | `running` | 仍在有效租约内、暂不可回收的 `RUNNING`。 |
| `fileweft.outbox_backlog` | `expired` | 已可回收的 `RUNNING`，包括 token 租约到期或无 token 历史记录超过 legacy grace。持续非零应检查处理时长、旧 Worker 排空和下游超时。 |
| `fileweft.outbox_backlog` | `failed` | 已进入终态的 `FAILED`，需要按交付/事件的既有运维流程排查和人工重排。 |
| `fileweft.outbox_oldest_ready_age_seconds` | 无 | 最早 `ready` 事件的等待秒数；没有 `ready` 事件时为 `0`。 |
| `fileweft.outbox_backlog_observation_failure` | 无 | 最近一次实际执行的积压读取是否失败；`0` 为成功，`1` 为失败。 |

五个 `state` 彼此互斥，且 `state` 是唯一允许的 gauge 标签。指标不包含 `tenantId`、文档 ID、用户 ID、连接器 ID 或下游错误文本；租户级排查必须使用审计、操作日志、Trace 与受权限保护的交付状态查询。多个 Outbox Worker 观察的是同一份全局数据库状态，因此查询或报警规则不能把各实例序列相加；应按 `state` 取最近值或最大值。

默认启用后，每个 Outbox Worker 进程每 30 秒最多尝试一次采样；可通过下列配置调整（间隔和查询超时必须大于零）：

```yaml
fileweft:
  worker:
    enabled: true
    process-outbox: true
    fixed-delay-millis: 1000
  outbox:
    backlog-metrics-enabled: true
    backlog-metrics-interval-millis: 30000
    backlog-metrics-query-timeout-seconds: 5
```

采样只挂在 Outbox 轮询角色上：Web 节点维持默认的 `fileweft.worker.enabled=false`，不会产生积压 gauge；仅处理 `process-tasks` 或 `process-upload-cleanup` 的 Worker 也不会产生。若集群需要这组指标，至少保留一个 `enabled=true` 且 `process-outbox=true` 的 Worker。设置 `backlog-metrics-enabled=false` 会使默认读取器、发布器和观察执行通道都不装配。采样在每次 Outbox 轮询结束后尝试，故实际频率还受轮询周期和进程存活影响，不是独立于 Worker 的定时探针。

每次采样先提交到独立的单线程、零队列观察通道，Outbox Worker 不会等待该数据库工作；若前一次慢查询仍在运行，新采样不会排队。实际运行时会在一个独立、短暂的数据库事务内执行只针对 `PENDING`、`RETRY`、`RUNNING` 和 `FAILED` 的聚合查询，事务外才更新指标后端；单条 JDBC 查询最长默认 5 秒。它不会在事务中调用连接器，也不会影响事件的确认、退避或租约恢复。`fileweft.outbox_backlog_observation_failure=1` 表示最近一个已实际执行的读取失败，下一次成功会写回 `0`；通道拒绝或进程退出前未执行的任务不会在 Worker 线程直接调用客户指标适配器。默认使用 `JdbcOutboxBacklogReader` 与 Micrometer 导出器；宿主可通过 `OutboxBacklogReader` 或 `FileWeftGaugeRecorder` Bean 替换查询或指标后端。自定义读取器必须维持五种状态的互斥分类与最早 `ready` 创建时间，自定义导出器必须把写入理解为当前值替换、快速且非阻塞，并隔离自身失败，不能改变 Outbox 处理语义。

默认读取已经排除 `SUCCESS` 历史，但它仍需统计保留的 `FAILED`、待处理和运行中记录。长期保留大量终态失败记录的部署，应按自身合规策略归档/分区，并在需要时提供分区感知的 `OutboxBacklogReader`；不要通过取消查询超时或让观察任务在 Worker 线程中排队来换取指标完整性。

## Outbox 租约与滚动升级

`V018` 为 `fw_outbox_event` 增加持久化租约字段。新 Worker 每次只领取一条事件，并在短事务内写入独立的 `lease_owner`、随机 `lease_token` 与到期时间；只有持有同一 token 的 Worker 才能确认成功、重试或失败，迟到的旧 Worker 不能覆盖新领取者的状态。默认租约为 5 分钟（`fileweft.outbox.lease-duration-millis=300000`）。该值应大于一次外部调用的最长预期耗时及必要余量；租约到期后其他 Worker 可以重新领取事件，因此不能把它当作“最多执行一次”保证。

`fileweft.outbox.worker-id` 应在同时运行的 Worker 间唯一，建议使用 Pod/主机实例 ID。未配置或空白时 Starter 会生成 `fileweft-outbox-<UUID>`，便于独立进程启动但不适合跨重启关联诊断。`fileweft.outbox.legacy-running-grace-millis` 默认也是 5 分钟，只用于回收升级前没有 token 的历史 `RUNNING` 记录；它不是旧、新 Worker 并行运行的安全屏障。

滚动升级到 `V018` 时，必须先暂停旧版本 Worker 的轮询并排空其正在处理的 Outbox，再执行迁移并启动租约感知的新 Worker。不要仅依赖 legacy grace：旧 Worker 可能仍在调用下游，而新 Worker 回收同一事件会产生重复交付。若故障场景无法排空，legacy grace 应覆盖旧 Worker 的最长外部调用时间，并持续观察 `RUNNING` 记录、下游幂等命中和失败告警。

Outbox 语义仍然是至少一次（at-least-once）：进程崩溃、超时或租约到期后，同一事件可能再次调用处理器。每个 `OutboxEventHandler` 必须以事件 ID 实现幂等；下游连接器还必须使用 `ConnectorInvocation.idempotencyKey` 去重。租约 token 只防止陈旧确认覆盖较新的所有权，不能替代外部系统幂等。

正式交付处理器只接受携带当前持久化 lease token 的 Worker 调用；对旧的无租约 `handle(event)` 入口安全失败。保留的两参数构造器仅用于显式兼容集成，并在新版 Worker 传入 lease 时继续走旧行为。生产 Starter 会要求自定义 `DocumentDeliveryTargetRepository` 同时实现 mutation 行锁能力，并要求唯一的 `OutboxEventMutationRepository`；缺失时启动失败而不是静默让事件进入无处理器失败。Outbox 标记 `FAILED` 与本地 target 终态投影之间仍可能遇到进程退出，但同步状态和显式幂等恢复会识别“当前事件精确失败 + target 仍 PENDING/RETRYING”的组合并原子推进新事件，无需手工改表。

旧版本使用单目标 `document.publish.requested` 事件。该兼容处理器现在默认关闭，正式部署只消费带目标快照的 `document.delivery.target.requested` 与 `document.delivery.target.removal.requested`。升级前必须先暂停旧 Worker，并确认旧事件已经处理或由运营人员明确处置；不要让旧处理器与新的多目标交付链路同时工作。仅在处理升级遗留事件的受控维护窗口中，才可临时设置 `fileweft.sync.legacy-publish-handler-enabled=true`，处理完成后立即恢复为 `false` 并重启节点。自动猜测“交付或撤回”的旧 `RetryDocumentDeliveryService` 也默认不装配；`fileweft.sync.legacy-delivery-retry-enabled=true` 只供 Dev/迁移兼容入口使用。正式系统必须使用两条带持久化幂等键的明确重排命令。两个兼容开关都不能作为长期运行模式。

```yaml
fileweft:
  sync:
    legacy-publish-handler-enabled: false
    legacy-delivery-retry-enabled: false
  outbox:
    worker-id: ${HOSTNAME}
    lease-duration-millis: 300000
    legacy-running-grace-millis: 300000
    backlog-metrics-interval-millis: 30000
```

## 后台任务租约与滚动升级

`V019` 为 `fw_task` 的既有 `lease_owner` 和 `lease_expire_time` 增加持久化 `lease_token`，并为 token 化与历史 `RUNNING` 任务分别建立恢复索引。新 Worker 每次只领取一条任务：在短事务内写入 Worker 标识、随机 token 和到期时间，随后在事务外执行任务处理器；确认成功、安排重试或标记失败时必须同时匹配该 token。这样，租约到期后被新 Worker 重新领取的任务，不会被旧 Worker 的迟到确认覆盖。

默认任务租约为 60 秒（`fileweft.task.lease-duration-millis=60000`）。它必须大于一次任务处理的最长预期耗时和必要余量；任务耗时超过租约、进程崩溃或确认前网络/数据库失败时，任务可能被重新执行。`fileweft.task.worker-id` 应在同时运行的 Worker 间唯一，建议使用 Pod 或主机实例 ID。未配置或空白时 Starter 保持生成 `fileweft-<UUID>` 的行为，适合单独启动但不适合跨重启关联诊断。

`fileweft.task.legacy-running-grace-millis` 默认 5 分钟（300000 毫秒），仅用于回收 `V019` 前没有 token 的历史 `RUNNING` 任务；它不是旧、新 Worker 并行运行的安全屏障。升级时必须先停止旧版本的任务轮询并等待正在执行的处理器排空，再执行 `V019` 并启动租约感知的新 Worker。不能只依赖 legacy grace：旧 Worker 可能仍在执行外部调用，而新 Worker 已回收同一任务。无法排空时，grace 至少应覆盖旧 Worker 的最长任务耗时，并持续观察 `RUNNING` 任务、幂等命中和失败告警。

后台任务语义是至少一次（at-least-once），不是恰好一次：`FileWeftTaskHandler` 必须把 `TaskExecution.id` 作为幂等依据；任务创建端的租户级 `idempotencyKey` 只负责折叠重复入队，不能替代处理器或外部系统的去重。租约 token 只保证陈旧 Worker 不能覆盖较新的持有者，不能阻止已发出的外部副作用重复执行。

任务状态确认的 token 围栏并不自动保护处理器写入的其他业务表。默认 Doctor 与 Agent 处理器因此还实现 `LeasedTaskHandler`：外部检查/Agent 调用仍在数据库事务外，写入 `fw_doctor_record` 或 `fw_agent_result` 前才开启短事务，通过 `TaskMutationRepository.findForMutation` 锁定任务，并精确复核 tenant、task ID、type、business ID、`RUNNING`、owner 与 token。失租结果不会覆盖新领取者；无 token 的 legacy lease 在调用检查器或 Agent 前即安全失败。报告/结果提交与随后任务确认之间仍是两个短事务，所以读取端必须以 `fw_task` 为权威：只有 `SUCCESS` Doctor 才展示报告，`FAILED` 也不复用可能来自旧失租尝试的暂存报告；Agent suggestion 同样只有在精确任务已为 `SUCCESS` 后才能读取确认。

JDBC 默认仓储已经同时实现 token 围栏与任务 mutation 行锁。自定义 `TaskProcessingRepository` 为兼容旧插件仍可继续运行，但只获得原有的 `lease_owner` 语义；要在多 Worker、重启或租约到期场景获得 token 围栏，必须同时实现可选的 `LeasedTaskProcessingRepository`，在领取和全部确认路径持久化并校验 `TaskLeaseClaim.leaseToken`。若使用 Starter 默认的 Doctor/Agent 业务投影，自定义任务仓储还必须实现唯一的 `TaskMutationRepository`，否则启动失败；不能把仅实现旧端口的仓储误认为已经具备 fencing。升级自定义仓储前应按至少一次语义验证任务 ID 和外部副作用幂等。

```yaml
fileweft:
  task:
    worker-id: ${HOSTNAME}
    lease-duration-millis: 60000
    legacy-running-grace-millis: 300000
```

## 并行审批与直接发布

同一工作流的最终审批或驳回会通过 `WorkflowInstanceRepository.findForDecision` 串行化。默认 PostgreSQL 实现对工作流父行使用 `SELECT … FOR UPDATE`，因此双人会签的第二位审批者会在第一位提交后读取最新任务状态，而不会用旧聚合快照覆盖已批准的任务。所有文档读改写用例则必须通过 `DocumentRepository.findForMutation` 获取同一文档的串行化读取；默认 PostgreSQL 实现对文档行使用 `SELECT … FOR UPDATE`。审批决策统一按“文档、工作流”顺序加锁，避免与提交、直接发布或版本更新形成反向锁顺序。

`V017` 还在数据库层建立 `(tenant_id, document_id) WHERE state = 'PENDING'` 的部分唯一索引，保证一份文档最多存在一个本地待审批流。升级前会主动检查历史重复数据并以可诊断的错误中止，绝不静默删除或关闭审批记录；运营人员必须先核实、处理这些历史工作流后再重试迁移。自定义文档/工作流仓储必须实现等价的行锁、CAS 或互斥语义，框架不会退化为普通读取。

`PublishDocumentService` 始终检查同租户、同文档是否存在本地 `PENDING` 工作流。有活动工作流时，直接发布会被拒绝，保留原审批任务和文档状态；没有本地工作流的 `PENDING_REVIEW` 文档仍可用于“外部审批已完成”的集成场景。若业务需要显式绕过本地审批，必须另行实现带专门权限、不可空原因、取消状态和审计记录的用例，不能复用普通 `document:publish`。

## 数据库迁移与查询索引

所有数据库变更只能新增版本化 Flyway 脚本，不能改写已发布迁移。`V016` 新增同步记录索引 `(tenant_id, document_id, connector_name, sync_status, updated_time DESC)` 和任务租户状态索引；`V019` 新增 token 化任务租约和历史任务回收索引；`V020` 新建持久化请求幂等表；`V021` 新增待办和审批历史的稳定 keyset 查询索引；`V022` 增加受理人前导的待办部分索引，避免大租户为一个用户取待办时扫描其他用户的任务；`V023` 为每个交付目标建立当前事件、操作类型和单调派发序号围栏。普通升级以事务内迁移执行，确保业务表结构与运行代码具有单一前进版本。

对于已有大量同步、任务或工作流数据的生产库，DBA 应在升级前以自动提交会话逐条运行 [V016 并发预建脚本](sql/postgresql-v016-concurrent-indexes.sql)、[V019 并发预建脚本](sql/postgresql-v019-concurrent-indexes.sql)、[V021 审批查询并发预建脚本](sql/postgresql-v021-concurrent-workflow-query-indexes.sql) 和 [V022 受理人待办并发预建脚本](sql/postgresql-v022-concurrent-workflow-assignee-inbox-index.sql)，并监控 `pg_stat_progress_create_index` 与磁盘余量。完成后 Flyway 会发现同名索引并跳过创建。旧的同步索引不会由应用自动删除；只有在 DBA 已核对查询计划、回滚窗口和磁盘预算后，才可使用脚本中注明的并发删除语句。

`V020` 只新增 `fw_idempotency_record`，不回填或重写现有业务表，因此升级前检查重点是新表、索引所需磁盘以及应用账号的建表权限。回滚到不认识 V020 的旧应用在尚未开放正式幂等写入口时可以保留该表；只有确认没有新版本写流量和待重放客户端后才可手工删除。幂等记录当前没有自动 TTL：不得以通用历史清理作业删除该表数据，否则迟到重试可能重复推进业务。正确事务不会提交 `IN_PROGRESS`；诊断索引一旦发现可见行，应按应用缺陷或自定义仓储不满足原子性处理，禁止自动改为完成、删除或接管。

`V021` 与 `V022` 只创建索引，不回填或重写工作流数据。高数据量数据库应先使用并发脚本预建同名索引，再由 Flyway 记录迁移；回滚旧应用可以保留这些兼容索引。V021 保留全局 `created_time + id` 有序路径，V022 为当前受理人和未分配池提供可检索路径；只有在目标环境比较真实查询计划、确认没有新版本查询流量且磁盘预算允许时，才可由 DBA 并发删除任一索引。

`V023` 是需要维护窗口的数据回填，不是可与旧 Worker 混跑的滚动迁移。执行前先停止发布、最终审批、离线和人工重排入口，停止旧版本交付 Worker，并运行 [V023 交付围栏预检脚本](sql/postgresql-v023-delivery-dispatch-fence-preflight.sql)；所有 `issue_count` 必须为零。预检与迁移使用相同的最新事件排序和交付/撤回状态匹配规则。迁移会拒绝相关 `RUNNING` 事件、同一目标的多个活动事件、缺少历史事件的目标、非法交付/撤回状态及最新事件终态不一致，绝不会生成虚构事件或静默选择冲突记录。修复历史数据必须保留原始审计证据，并在修复后重新执行预检。

`current_event_id` 故意不外键关联 Outbox：生产环境可能按保留策略归档终态事件；但任何 Outbox 清理作业都必须排除仍被 `fw_document_delivery_target.current_event_id` 引用的记录。当前事件缺失表示一致性损坏，正式重排必须 fail closed 并交给 Doctor/运营处理。V023 落库后不能回滚到不了解事件围栏的旧 Worker；旧二进制会忽略事件身份，数据库也无法从旧的整行更新推断它正在处理哪个事件。

## 断点续传与对象完整性

`ResumableUploadService` 把 multipart 状态持久化到 `fw_upload_session` 与 `fw_upload_session_part`，并以可信上下文中的租户和用户 ID 绑定用户操作。所有者 ID 只从 `UserRealmProvider.currentUser()` 的单次身份快照取得，是区分大小写且不做 trim、大小写折叠或 Unicode 归一化的不透明字符串；禁止从请求 DTO、Header、metadata、查询参数或浏览器检查点接收。接入值必须非空、最多 256 个 UTF-16 code unit、首尾无 Unicode whitespace，且不含 ISO control 或 FileWeft 固定拒绝表中的 Unicode format 字符；应用校验与 V024 数据库约束使用同一固定码点表，不受 JDK 8～25 内置 Unicode 版本差异影响。宿主若使用 `Long`、`Int`、UUID 或组合主键，应先在身份 SPI 中稳定转换为字符串，后续不得改变格式。接入方的 HTTP API 应仅把会话 ID、已确认分片号、过期时间和完成结果返回给浏览器；`ownerId`、`storageUploadId`、对象路径和对象存储凭据始终只能留在服务端。

`inspect`、`uploadPart`、`complete` 与用户 `abort` 会先执行所有者边界，再执行普通上传授权。同租户其他用户无论权限高低都收到与不存在会话相同的 404，且不会打开对象存储、写入分片或改变会话状态；所有者后来失去上传权限时才返回 403。租户内幂等键继续保持全局唯一：同一所有者与同一请求可重放，不同请求或不同所有者复用该键固定返回 409，不返回已有会话信息。系统 Worker 的过期清理和平台级卡滞检查不依赖用户所有者，因此仍能处理遗留会话。

推荐的服务端调用顺序是：`start` 创建或恢复幂等会话，`uploadPart` 逐片确认，`inspect` 在刷新或网络恢复后读取服务端确认点，`complete` 幂等完成，用户放弃时调用 `abort`。新建会话不会先提交未经验证的 `ACTIVE` 行：初始行使用带固定 creation-staging 标记的不可见 `ABORTING` 状态，在同一事务内完成 global/owner 四路精确核验后，只有 tenant、ID、预期 owner、staging 标记和 `expires_at > activationTime` 均匹配时才条件激活并清除标记。即使自定义 transaction 缺乏真实回滚，异常最多遗留用户不可见的 staging 行，不能被另一用户接管。Worker 只会自动清理仍可安全取消且经固定时间再次核验已过期的会话；仓储错误返回未来行、`COMPLETING`、`QUARANTINED` 或终态时固定零状态变更、零 Storage 调用。`COMPLETING` 状态意味着对象存储可能已经接受完成请求，清理任务不会删除其对象，以免把刚完成的文件变成悬空记录；运营者应通过会话检查接口和存储日志处理这类不确定状态。`QUARANTINED` 是 owner/不可变身份映射异常后的单调安全状态：用户路径永久按 404 隐藏，普通失败和 TTL 清理都不能把它改回可见状态；远端 multipart 可在已确认围栏后安全终止，但数据库行和固定诊断会一直保留，需由运营审计而不是通用 TTL 作业删除。

创建会话的数据库提交若丢失确认，`ApplicationTransactionOutcomeUnknownException` 表示“可能已提交”，不能按普通失败立即删除 multipart。服务会先按本次 session ID、所有者、幂等键和不可变存储身份重新对账：确认是同一次提交时返回已持久化会话；确认是另一竞争请求时才清理本次远端上传；数据库仍不可读或结果无法安全区分时保留远端状态并失败关闭。生产对象存储必须同时配置“未完成 multipart 生命周期”兜底，运维应结合应用 Trace、会话表和存储 upload ID 对账；客户端只重试原幂等键，不能收到 5xx 后换键盲目重传。

普通上传、文档初版、新版本以及续传的 `start/uploadPart/complete/abort/cleanupExpired` 都会组合数据库状态与非事务型 Storage 副作用，必须作为顶层 Application 边界调用。不要从外层 Spring `@Transactional`、手写 JDBC transaction 或另一个尚未完成的 FileWeft transaction 调用这些入口。官方 `JdbcApplicationTransaction` 实现 `ApplicationTransactionState`，能在生成 ID、访问仓储或调用 Storage 前拒绝同一 DataSource 的环境事务；不同 DataSource 按引用身份使用独立连接上下文。自定义 transaction 若不实现这个 additive capability 仍保持二进制兼容，但宿主必须自行保证顶层调用；测试辅助方法 `JdbcConnectionContext.withConnection(Connection)` 是匿名绑定，不代表生产宿主事务，Spring `@Transactional` 也不会被它自动感知。

同一规则覆盖普通上传、文档初版、新增版本和 multipart 完成：事务返回提交结果未知后，服务会按本次生成的 `FileObject`、`FileAsset`、文档和版本绑定重新读取。完整匹配时即使文档随后已改名、推进生命周期或新增版本，也返回当前已提交结果；读取失败、无记录、部分引用或冲突引用都不会触发删除。只有事务明确失败且权威回读确认本次生成的持久化引用全部不存在时，才删除远端对象。告警和故障处理必须保留原异常的 Trace，并以数据库引用与对象存储位置双向对账，不能仅凭一次 5xx 手工删除对象。

自定义 `ResumableUploadSessionRepository` 必须原样持久化 `ownerId`，并让按 ID、幂等键的租户查询返回相同的不可变会话；实现 `OwnerScopedResumableUploadSessionRepository` 时还必须在查询内同时约束租户和 owner。服务不会信任 owner capability 的单次结果，而会在同一事务中与 tenant-global 权威快照逐字段核对。新建会话还要求 repository 同时实现 additive 的 `StagedResumableUploadSessionRepository` 与 `QuarantinableResumableUploadSessionRepository`，在任何 multipart 创建前确认支持带 owner/标记/过期条件的 staging 激活，以及 `ABORTING → QUARANTINED` 单调围栏；缺少任一能力都会明确拒绝新会话。保存后服务会对全局 ID/key 与 owner ID/key 四路回读并再次校验。旧映射器丢弃 owner、错误改写 owner、owner capability 返回克隆对象或 `save` 静默 no-op 时，新会话不会发布给客户端；已提交 `ACTIVE` 的异常行会先在独立事务中 claim 并持久隐藏，随后才尝试隔离，因而隔离事务失败只会回到隐藏的 `ABORTING`，绝不会恢复 `ACTIVE`。只有围栏事务内的不可变身份和两条权威回读全部一致，才允许终止远端 multipart，任何提交未知、读取失败或矛盾状态都会保留远端状态并报告结果未知。升级自定义仓储应先完成 owner 字段迁移、staging/quarantine 能力和合约测试，再开放续传入口。

`V024` 为既有会话增加可空的 `owner_id`，安装固定 owner 校验约束，并把 `QUARANTINED` 加入状态约束。迁移无法可靠推导历史会话的创建者，因此不会自动认领旧行；`owner_id IS NULL` 的历史会话对所有用户路径一律不可见，只能由系统清理或运维检查处理。升级前应暂停新建续传、让可完成的活动会话完成或明确放弃，然后停止全部旧版 HTTP 入口节点，再执行迁移并一次性启动新节点。新旧入口节点不得滚动混跑：旧节点不了解所有者边界，即使数据库已有新列仍可能绕过隔离。被遗留行占用的租户级幂等键在清理前会固定冲突，客户端应等待清理或使用新的幂等键重新开始，不能把旧会话转交给新用户。

紧急回滚不能直接重新开放旧版续传入口。必须先在网关关闭全部续传 HTTP/RPC 路由，停止新版本入口节点，逐条对账并终止或完成非终态 multipart，再按审计和保留策略清除 `fw_upload_session_part` 与 `fw_upload_session` 中所有仍可被旧代码读取的会话记录。只有确认会话表为空后，才可让旧节点重新承接续传流量；若不能清空，就应保持续传入口关闭，直到恢复具备所有者校验的新版本。回滚时保留 V024 列和约束，不以降级表结构代替安全处置。

生产宿主可将 `inspectStalledCompletionsAsSystem(limit)` 封装为只授予平台运维角色的只读接口；`inspectStalledCompletions(limit)` 则会从可信的当前租户和 `file:upload:maintenance` 授权上下文中查询，适合租户管理员。开发验收 API 对应 `GET /api/resumable-uploads/maintenance`，它只读取当前认证租户的会话并返回会话 ID、文件名、长度、过期时间、更新时间和最后错误，不会返回 `storageUploadId`、存储路径或对象凭据。

普通上传、文档初版与新增版本都会在落库前检查对象存储返回的长度；调用方提供 `contentHash` 时还会校验 SHA-256。任一失配都会补偿删除对象，文件、资产、文档和 Outbox 都不会落库。该“明确校验失败”补偿不得扩展到数据库提交结果未知的场景；后者必须遵循上述持久化对账与保留策略。

网关必须显式允许单个续传分片加少量协议开销，不能沿用 Nginx 的默认 1 MiB 请求体限制。开发编排的页面上传分片上限为 512 MiB，因此 `.docker/nginx.dev.conf` 将 `client_max_body_size` 设为 513 MiB，并对 `/api/` 禁用代理请求体缓冲；生产网关应根据实际的 `fileweft` 分片上限、磁盘缓冲预算与超时策略作出同等或更严格的配置。仅增大网关限制不会绕过 FileWeft 的续传会话授权、长度校验和对象存储完整性校验。

## 下游连接器韧性

Starter 会为默认的交付解析器、兼容的单连接器同步路径和连接器 Doctor 共享同一个保护实例。它在进程内提供硬超时、有限并发/排队和每个连接器独立的熔断状态；Outbox 仍是唯一的重试调度者，不会在一次投递中隐藏式重放请求。

`fileweft.sync.connector-timeout-millis` 只限制一次 FileWeft 到下游的 RPC 调用；`fileweft.sync.source-access-url-ttl-millis` 则限制交给下游的对象存储访问 URL。两者不能复用：异步下游可能先确认接收、稍后才拉取文件。源 URL 有效期必须为正且不短于 RPC 超时，默认是 15 分钟（900000 毫秒）。异步平台应按其实际取文件队列的最长等待时间加上网络余量配置该值，同时保持预签名 URL 的最小必要权限；不要为了匹配一次 RPC 超时而把 URL 缩短到几十秒。

`delivery-profile` Doctor 会在发布前验证当前租户可见的每个交付档案及目标 `connectorId` 都能被 `DeliveryConnectorResolver` 解析。它只做解析，不调用下游连接器，也不写入交付记录；找不到连接器、解析器异常或租户没有可用档案都会作为 `ERROR` 给出明确的档案/目标证据。`connector` Doctor 则继续负责已解析连接器的实际健康检查。自定义解析器应让此查找保持快速、无副作用，远程策略读取需要自行设置超时和故障隔离。

发布或最终审批会先在数据库事务外调用 `DocumentDeliveryPlanner.prepare`，把档案和目标复制为不可变的租户快照并验证连接器 ID；随后短事务只推进生命周期、冻结目标和写入 Outbox。这样远程策略中心、配置服务或连接器注册表不会被放进数据库事务或审批行锁中。准备完成后发生的配置变化只影响后续发布，已冻结的目标继续以当次快照和各自的 Outbox 幂等键执行。

```yaml
fileweft:
  sync:
    connector-timeout-millis: 30000
    source-access-url-ttl-millis: 900000
    circuit-breaker-failure-threshold: 3
    circuit-breaker-open-duration-millis: 30000
    connector-max-concurrent-invocations: 16
    connector-invocation-queue-capacity: 256
```

连接器抛出异常、超过超时或返回 `RETRYABLE_FAILURE` 都会交给 Outbox 的退避重试，并计入该下游的连续失败阈值；达到阈值后，熔断器直接返回可重试结果而不触达下游。执行池饱和和线程中断同样会交给 Outbox 重试，但不会误开某个下游的熔断器，因为它们是本地容量信号。冷却窗口结束后只允许一个真实调用作为恢复探针。`PERMANENT_FAILURE` 不会打开熔断器，因为它通常表示下游已收到请求但拒绝了业务内容。已成功的其他目标不会被回滚。

若业务方替换了默认 `DeliveryConnectorResolver` 或 `DocumentSyncService`，应通过 `ConnectorResilienceRegistry.protect(connectorId, connector)` 获取连接器；否则该自定义入口会自行承担超时与熔断责任。

Doctor 的 `agent` 检查只验证已安装的 Agent 能力是否已被运行时登记，不会调用 AI、产生费用或修改文档。未安装 Agent 时结果为 `SKIPPED`，因为 Agent 是可选能力；安装后会报告已登记的能力列表。更细的第三方 AI 连通性检查应由对应插件提供 `DoctorChecker`。

恢复步骤：

1. 存储异常时，先运行 Doctor，核对对象引用和存储健康，再恢复连接并重试。
2. 下游异常时，检查交付记录的目标状态、错误和外部 ID；仅重排失败目标，避免重新推送已成功目标。
3. 怀疑租户越权时，立即停止该租户入口，按审计和操作日志中的 `tenantId`、资源 ID、`traceId` 排查，并确认仓储查询保持租户条件。
4. 仅在专用测试库上运行 `FILEWEFT_RUN_POSTGRES_TESTS=true`；该测试会重置 `public` schema，绝不能指向生产数据库。
