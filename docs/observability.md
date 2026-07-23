# 可观测性

FileWeft 的业务层只依赖 `FileWeftMetrics`、`FileWeftGaugeRecorder` 和 `TraceContextProvider` SPI。Core、Domain 和 Application 不依赖日志、Micrometer 或链路追踪厂商 SDK。

当 Spring 应用提供 Micrometer `MeterRegistry` 时，Starter 会自动装配 `MicrometerFileWeftMetrics`。指标名称以 `fileweft.` 为前缀，例如：

- `fileweft.upload_count`、`fileweft.upload_failure`
- `fileweft.sync_success`、`fileweft.sync_failure`
- `fileweft.delivery_removal_success`、`fileweft.delivery_removal_failure`
- `fileweft.doctor_failure`
- `fileweft.task_success`、`fileweft.task_failure`

默认适配器仅允许 `taskType`、`connector`、`outcome` 等有限标签；它会主动丢弃 `tenantId`、文档 ID、用户 ID 等敏感或高基数标签。租户、资源与请求关联应通过审计日志、操作日志和 Trace 查询，不应写入指标标签。

多下游发布会按每个实际目标投递尝试记录一次 `sync_success` 或 `sync_failure`，撤回则记录 `delivery_removal_success` 或 `delivery_removal_failure`；二者都附带连接器标识供默认适配器保留。已成功目标的幂等重放、已被新发布代次取代的历史事件，以及 Outbox 的重试耗尽回调都不会重复计数；耗尽前最后一次真实调用已记录其失败指标。

## 持久化 Outbox 积压仪表

在存在 Micrometer `MeterRegistry` 时，Starter 还会自动装配 `FileWeftGaugeRecorder` 的 Micrometer 实现，并暴露三组当前值（gauge）：

| 指标 | 标签 | 含义 |
| --- | --- | --- |
| `fileweft.outbox_backlog` | 仅 `state` | 持久化 Outbox 的固定状态计数 |
| `fileweft.outbox_oldest_ready_age_seconds` | 无 | 最早一条可立即领取事件距本次采样的秒数；没有 `ready` 事件时为 `0` |
| `fileweft.outbox_backlog_observation_failure` | 无 | 最近一次实际执行的聚合读取是否失败：`0` 为成功，`1` 为失败 |

`state` 只有以下五个互斥且固定的值，不能增加租户、文档、连接器、用户或任意业务标签：

- `ready`：`PENDING` 或 `RETRY` 且 `next_attempt_time` 已到；Worker 现在即可领取。
- `delayed`：`PENDING` 或 `RETRY` 但下一次尝试时间尚未到；通常是退避等待。
- `running`：尚未可回收的 `RUNNING` 事件。
- `expired`：可立即回收的 `RUNNING` 事件，包括 token 租约已到期的记录，以及没有 token、已超过 legacy grace 的升级前记录。
- `failed`：终态 `FAILED` 事件，保留给运维排查与既有人工重排流程。

这是一份跨全部租户的数据库运行快照，`state` 是默认 Micrometer 实现唯一保留的标签；它刻意不以租户维度输出，避免敏感信息和高基数时间序列。需要定位某个租户、文档或下游时，应从审计、操作日志、交付状态和 Trace 进入，而不是给指标补标签。

默认启用（`fileweft.outbox.backlog-metrics-enabled=true`）后，每个符合条件的 Worker 进程至多每 30 秒尝试一次，间隔由 `fileweft.outbox.backlog-metrics-interval-millis` 控制（必须为正数）。读取使用 `fileweft.outbox.backlog-metrics-query-timeout-seconds` 限制单条 JDBC 聚合语句，默认 5 秒。采样在 Outbox 轮询结束后提交到独立的单线程、零队列观察通道：Worker 不等待数据库聚合，通道已有慢查询时不会堆积第二个采样任务，而是跳过本轮。实际执行的读取在独立短数据库事务中完成，指标写入发生在事务结束之后；查询或指标后端失败都不会影响 Outbox 确认、重试或租约恢复。读取只聚合 `PENDING`、`RETRY`、`RUNNING` 和 `FAILED` 的活跃/需运维状态，不扫描 `SUCCESS` 历史记录。Web 节点默认 `fileweft.worker.enabled=false`，不会发布这组 gauge；只有同时启用 `fileweft.worker.enabled=true` 和 `fileweft.worker.process-outbox=true` 的 Outbox Worker 会发布。仅处理后台任务或续传清理的 Worker 不会采样这组指标。

未启用 Worker 的 Web 节点会以同一间隔采样积压用于提醒：当 `ready` 与 `delayed` 事件数之和大于 0 时输出一条 WARN 日志（"N outbox event(s) pending but no outbox worker is enabled in this process; deploy the worker to process deliveries."），防止只部署 API 而忘记部署 Worker 时事件永远滞留。该提醒按值节流——积压从 0 变为正数、或正数期间计数变化时各输出一次，归零后重新武装；它不发布任何 gauge，采样失败（含数据库不可用）保持静默并等待下一间隔，设置 `fileweft.outbox.backlog-metrics-enabled=false` 可同时关闭该提醒。

`fileweft.outbox_backlog_observation_failure=1` 表示最近一个已执行的读事务失败；下一次成功快照会写回 `0`。因零队列通道拒绝、进程正在退出或时钟不可用而未实际运行的采样不会在 Outbox Worker 线程调用任意客户 Gauge 适配器，因此应同时观察该值、Worker 可用性与 `ready` 年龄，而不能把“没有新样本”误判为成功。

多个 Outbox Worker 会各自观察同一个全局快照，因此监控查询不应按 `instance`、`pod` 等实例标签求和；应按 `state` 取最近值或最大值。若没有 `MeterRegistry` 或未提供 `FileWeftGaugeRecorder`，该可选观测能力安全地不输出任何 gauge，业务处理仍保持原有语义。要完全关闭默认查询和执行通道，设置 `fileweft.outbox.backlog-metrics-enabled=false`；宿主显式注册的自定义发布器仍由宿主自行决定是否调用。

默认读取实现是 PostgreSQL 的 `JdbcOutboxBacklogReader`，默认导出实现是 `MicrometerFileWeftGauges`。宿主可以分别提供 `OutboxBacklogReader` 或 `FileWeftGaugeRecorder` Bean 替换它们，例如使用分区感知的聚合视图或企业指标后端；自定义读取器必须继续返回上述五个互斥状态与最早 `ready` 创建时间，自定义导出器必须把同一指标与标签集视为“替换当前值”，并且不得让导出失败改变业务流程。自定义导出器应快速、非阻塞；Starter 不会在 Outbox Worker 线程直接调用它。

若没有 `MeterRegistry`，Starter 会为计数型 `FileWeftMetrics` 提供安全的 No-op 实现，并省略可选的 Gauge 导出器；指标导出失败也不会影响上传、审批、同步或任务确认。接入 OpenTelemetry、Micrometer Tracing 等追踪系统时，实现 `TraceContextScope`，使 Outbox Worker 能在异步处理时恢复原始 Trace。

连接器的诊断文本只能包含可供平台运维查看的、无凭据的信息。交付与撤回路径会去除空白文本，并在写入交付状态、审计和宿主诊断视图前限制为 1,024 个字符；超长内容会带截断标记。该限制是防止异常下游放大存储与日志的保护，不替代适配器侧的凭据脱敏责任。
