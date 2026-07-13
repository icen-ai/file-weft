---
route: "architecture/event-driven"
group: "architecture"
order: 3
locale: "zh"
nav: "事件驱动交付"
title: "基于 Outbox 与 Worker 的事件驱动交付"
lead: "FileWeft 把每一个外部副作用都变成持久、带租户作用域的事件。Worker 以至少一次语义处理这些事件，连接器收敛下游系统，运维人员无需窥探内部即可观测整条流水线。"
format: "markdown"
---

## 问题背景

发布一份文档通常意味着要触碰检索索引、合规归档、AI 知识库，或同时触碰多个系统。如果应用服务直接发起这些调用：

- 下游超时会回滚已经成功的业务事务。
- 部分失败会让部分系统已更新、部分系统仍陈旧。
- 重放会因原始响应丢失而产生重复数据。

FileWeft 通过把每个外部副作用移到 Outbox 和一组异步 Worker 之后来解决这些问题。

## 事件流

发布命令不会调用连接器，它只写入事件，然后由 Worker 池收敛世界：

```
业务事务
    ↓
Outbox 事件（同一 PostgreSQL 事务）
    ↓
异步 Worker 带租约领取
    ↓
连接器 sync/remove 调用
    ↓
结果写回交付记录
```

由于事件与业务记录一起提交，本地状态始终一致。Worker 可能滞后，但它永远不会观察到业务事务未提交的事件。

## Outbox 事件

Outbox 事件是小型、带类型的记录，描述事务外必须发生的事。例如：

| 事件类型 | 触发时机 | 处理方 |
| --- | --- | --- |
| `document.delivery.target.sync.requested` | 文档发布到某个同步目标 | Worker 调用 `FileConnector.sync` |
| `document.delivery.target.removal.requested` | 文档下线或归档 | Worker 调用 `FileConnector.remove` |
| `document.lifecycle.transition.requested` | 状态机需要外部确认 | 生命周期守卫或 Worker |

事件携带稳定的幂等身份、租户上下文和所属文档引用。它们不携带存储 URL、连接器凭证或任意诊断文本。

```yaml
fileweft:
  outbox:
    lease-duration-millis: 300000
    legacy-running-grace-millis: 300000
    backlog-metrics-enabled: true
    backlog-metrics-interval-millis: 30000
    backlog-metrics-query-timeout-seconds: 5
```

> **NOTE**
> Outbox 记录与业务写入在同一 PostgreSQL 事务中提交。如果事务回滚，事件会随之消失。

## Worker 配置

Worker 是一个后台组件，按批次领取 Outbox 记录和任务记录：

```yaml
fileweft:
  worker:
    enabled: true
    fixed-delay-millis: 1000
    outbox-batch-size: 50
    task-batch-size: 50
    process-outbox: true
    process-tasks: true
    process-upload-cleanup: true
```

| 属性 | 作用 |
| --- | --- |
| `fixed-delay-millis` | 轮询间隔 |
| `outbox-batch-size` | 每轮领取的最大 Outbox 记录数 |
| `task-batch-size` | 每轮领取的最大任务记录数 |
| `process-upload-cleanup` | 清理孤立的断点续传会话 |

如果你在专用节点上运行 Worker，希望 Web 节点只读，可将 `enabled` 设为 `false`。

## 任务处理器

某些副作用太长或太重要，不能内联执行。FileWeft 把它们表示为任务，并路由给注册的 `FileWeftTaskHandler` Bean：

```kotlin
interface FileWeftTaskHandler {
    fun supports(task: TaskExecution): Boolean
    fun handle(task: TaskExecution): TaskHandlingResult
    fun onExhausted(task: TaskExecution, message: String) = Unit
}
```

任务按至少一次语义处理。处理器必须按 `task.id` 幂等：

```kotlin
import ai.icen.fw.spi.task.FileWeftTaskHandler
import ai.icen.fw.spi.task.TaskExecution
import ai.icen.fw.spi.task.TaskHandlingResult
import ai.icen.fw.spi.task.TaskHandlingStatus
import org.springframework.stereotype.Component

@Component
class ComplianceArchiveTaskHandler : FileWeftTaskHandler {

    override fun supports(task: TaskExecution): Boolean =
        task.type == "compliance.archive"

    override fun handle(task: TaskExecution): TaskHandlingResult {
        // 以 task.id 作为幂等键
        val alreadyDone = archiveDao.isCompleted(task.id)
        if (alreadyDone) {
            return TaskHandlingResult(TaskHandlingStatus.SUCCEEDED)
        }
        archiveDao.archive(task.payload)
        return TaskHandlingResult(TaskHandlingStatus.SUCCEEDED)
    }
}
```

```yaml
fileweft:
  task:
    lease-duration-millis: 60000
    legacy-running-grace-millis: 300000
```

> **TIP**
> 用收到的同一个 `task.id` 存储完成标记。不要在处理器内部生成新的幂等键，因为重试会再次传入相同的 `task.id`。

## 连接器是幂等投影

连接器收到 sync 或 remove 请求，返回三种结果之一：

```kotlin
interface FileConnector {
    fun sync(request: ConnectorSyncRequest): ConnectorSyncResult
    fun remove(request: ConnectorRemoveRequest): ConnectorSyncResult
    fun health(): ConnectorHealth
}
```

| 结果状态 | 含义 | Worker 行为 |
| --- | --- | --- |
| `SUCCESS` | 下游已收敛 | 标记交付记录完成 |
| `RETRYABLE_FAILURE` | 临时错误 | 指数退避重试 |
| `PERMANENT_FAILURE` | 逻辑错误或拒绝的负载 | 停止重试，上报运维 |

连接器还必须实现健康检查，以便 FileWeft 优雅降级：

```kotlin
enum class ConnectorHealth { HEALTHY, DEGRADED, UNHEALTHY }
```

> **WARNING**
> 连接器不能修改输入请求，也不能依赖调用方状态。每次调用都应该是自包含且可安全重放的。

## 可观测性

框架暴露了计数器和仪表，让你在不添加租户或文档标签的情况下观测流水线：

```bash
# 按状态查看 Outbox 积压
curl -s http://localhost:8080/actuator/metrics/fileweft.outbox_backlog \
  | jq '.measurements[] | select(.statistic == "VALUE")'

# 同步成功与失败
curl -s http://localhost:8080/actuator/metrics/fileweft.sync_success
curl -s http://localhost:8080/actuator/metrics/fileweft.sync_failure
```

| 指标 | 类型 | 标签 |
| --- | --- | --- |
| `fileweft.outbox_backlog` | Gauge | `state`: ready, delayed, running, expired, failed |
| `fileweft.outbox_oldest_ready_age_seconds` | Gauge | 无 |
| `fileweft.sync_success` | Counter | 无 |
| `fileweft.sync_failure` | Counter | 无 |
| `fileweft.task_success` | Counter | 无 |
| `fileweft.task_failure` | Counter | 无 |

> **NOTE**
> 标签中不能包含 `tenantId`、文档 ID 或用户 ID。FileWeft 保持指标基数较低，避免暴露高基数或敏感维度。

## 示例：通过 Outbox 发布

1. 客户端调用 `POST /fileweft/v1/documents/{documentId}/publish`。
2. 应用服务校验命令，写入文档状态以及每个同步目标对应的 Outbox 事件。
3. Worker 领取事件并调用各连接器。
4. 连接器返回 `SUCCESS`、`RETRYABLE_FAILURE` 或 `PERMANENT_FAILURE`。
5. 只有当所有必达目标都成功时，文档才会转换为 `PUBLISHED`。

```bash
# 触发发布
curl -X POST http://localhost:8080/fileweft/v1/documents/fw-doc-123/publish

# 查看交付状态
curl http://localhost:8080/fileweft/v1/documents/fw-doc-123/sync-status
```

## 常见问题

**Q: Outbox 事件会丢失吗？**

只要业务事务提交就不会。事件与业务记录在同一事务中写入。如果 PostgreSQL 已提交，事件就是持久的。

**Q: 连接器永久不健康会怎样？**

Worker 按配置策略停止重试，并记录 `PERMANENT_FAILURE`。必达目标失败会阻塞文档状态转换；可选目标失败会被记录，但不会阻止发布。

**Q: 如何添加新事件类型？**

引入新的 Outbox 事件类型，并搭配一个专用的 `FileWeftTaskHandler` 或 `OutboxEventHandler` Bean。通过插件或 Spring Bean 注册。保持处理器幂等且租户感知。

## 下一步

- [一致性模型](/architecture/consistency) — 本地原子性为何重要，以及存储如何补偿。
- [安全架构](/architecture/security) — 事件处理过程中租户和授权边界如何保持不变。
- [存储适配器指南](/guides/storage-adapter) — 实现同一投影模型的存储侧。
