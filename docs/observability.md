# 可观测性

FileWeft 的业务层只依赖 `FileWeftMetrics` 和 `TraceContextProvider` SPI。Core、Domain 和 Application 不依赖日志、Micrometer 或链路追踪厂商 SDK。

当 Spring 应用提供 Micrometer `MeterRegistry` 时，Starter 会自动装配 `MicrometerFileWeftMetrics`。指标名称以 `fileweft.` 为前缀，例如：

- `fileweft.upload_count`、`fileweft.upload_failure`
- `fileweft.sync_success`、`fileweft.sync_failure`
- `fileweft.doctor_failure`
- `fileweft.task_success`、`fileweft.task_failure`

默认适配器仅允许 `taskType`、`connector`、`outcome` 等有限标签；它会主动丢弃 `tenantId`、文档 ID、用户 ID 等敏感或高基数标签。租户、资源与请求关联应通过审计日志、操作日志和 Trace 查询，不应写入指标标签。

若没有 `MeterRegistry`，Starter 会提供安全的 No-op 实现；指标导出失败也不会影响上传、审批、同步或任务确认。接入 OpenTelemetry、Micrometer Tracing 等追踪系统时，实现 `TraceContextScope`，使 Outbox Worker 能在异步处理时恢复原始 Trace。
