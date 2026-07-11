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

Worker 每轮失败只记录日志，不会丢弃待处理记录；下一轮会继续领取符合重试时间或租约已过期的工作。生产报警应至少覆盖同步失败、任务失败、Doctor 失败和持久化 Outbox 积压。

## 数据库迁移与查询索引

所有数据库变更只能新增版本化 Flyway 脚本，不能改写已发布迁移。`V016` 新增同步记录索引 `(tenant_id, document_id, connector_name, sync_status, updated_time DESC)` 和任务租户状态索引；普通升级以事务内 `IF NOT EXISTS` 执行，确保 Flyway 的 schema-history 锁不会与 PostgreSQL 的并发索引构建互相等待。

对于已有大量 `fw_sync_record` 或 `fw_task` 数据的生产库，DBA 应在升级前以自动提交会话逐条运行 [V016 并发预建脚本](sql/postgresql-v016-concurrent-indexes.sql)，并监控 `pg_stat_progress_create_index` 与磁盘余量。完成后 Flyway 会发现同名索引并跳过创建。旧的同步索引不会由应用自动删除；只有在 DBA 已核对查询计划、回滚窗口和磁盘预算后，才可使用脚本中注明的并发删除语句。

## 断点续传与对象完整性

`ResumableUploadService` 把 multipart 状态持久化到 `fw_upload_session` 与 `fw_upload_session_part`，并以租户和调用方幂等键隔离。接入方的 HTTP API 应仅把会话 ID、已确认分片号、过期时间和完成结果返回给浏览器；`storageUploadId`、对象路径和对象存储凭据始终只能留在服务端。

推荐的服务端调用顺序是：`start` 创建或恢复幂等会话，`uploadPart` 逐片确认，`inspect` 在刷新或网络恢复后读取服务端确认点，`complete` 幂等完成，用户放弃时调用 `abort`。Worker 只会自动清理仍可安全取消的过期会话。`COMPLETING` 状态意味着对象存储可能已经接受完成请求，清理任务不会删除其对象，以免把刚完成的文件变成悬空记录；运营者应通过会话检查接口和存储日志处理这类不确定状态。

生产宿主可将 `inspectStalledCompletionsAsSystem(limit)` 封装为只授予平台运维角色的只读接口；`inspectStalledCompletions(limit)` 则会从可信的当前租户和 `file:upload:maintenance` 授权上下文中查询，适合租户管理员。开发验收 API 对应 `GET /api/resumable-uploads/maintenance`，它只读取当前认证租户的会话并返回会话 ID、文件名、长度、过期时间、更新时间和最后错误，不会返回 `storageUploadId`、存储路径或对象凭据。

普通上传、文档初版与新增版本都会在落库前检查对象存储返回的长度；调用方提供 `contentHash` 时还会校验 SHA-256。任一失配都会补偿删除对象，文件、资产、文档和 Outbox 都不会落库。

网关必须显式允许单个续传分片加少量协议开销，不能沿用 Nginx 的默认 1 MiB 请求体限制。开发编排的页面上传分片上限为 512 MiB，因此 `.docker/nginx.dev.conf` 将 `client_max_body_size` 设为 513 MiB，并对 `/api/` 禁用代理请求体缓冲；生产网关应根据实际的 `fileweft` 分片上限、磁盘缓冲预算与超时策略作出同等或更严格的配置。仅增大网关限制不会绕过 FileWeft 的续传会话授权、长度校验和对象存储完整性校验。

## 下游连接器韧性

Starter 会为默认的交付解析器、兼容的单连接器同步路径和连接器 Doctor 共享同一个保护实例。它在进程内提供硬超时、有限并发/排队和每个连接器独立的熔断状态；Outbox 仍是唯一的重试调度者，不会在一次投递中隐藏式重放请求。

`delivery-profile` Doctor 会在发布前验证当前租户可见的每个交付档案及目标 `connectorId` 都能被 `DeliveryConnectorResolver` 解析。它只做解析，不调用下游连接器，也不写入交付记录；找不到连接器、解析器异常或租户没有可用档案都会作为 `ERROR` 给出明确的档案/目标证据。`connector` Doctor 则继续负责已解析连接器的实际健康检查。自定义解析器应让此查找保持快速、无副作用，远程策略读取需要自行设置超时和故障隔离。

```yaml
fileweft:
  sync:
    connector-timeout-millis: 30000
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
