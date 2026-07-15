---
route: "concepts/outbox"
group: "concepts"
order: 5
locale: "zh"
nav: "Outbox 与 Worker"
title: "Outbox：永远不要在事务里调用下游"
lead: "毁掉一致性最快的方式，是在数据库提交里发送 HTTP 请求。FlowWeft 在同一个业务事务里把事件写入 Outbox 表，再由 Worker 异步交付。本页说明该模式、Worker 配置以及如何观察积压。"
format: "markdown"
---

## 01. 为什么需要 Outbox 模式

一次文档发布需要完成两件事：

1. 在 PostgreSQL 中更新文档状态。
2. 通知下游连接器（合规归档、检索索引、CDN）。

如果你在数据库事务里调用连接器，慢网络可能导致事务回滚，或者让提交与调用处于不一致状态。Outbox 模式把本地事务变成唯一事实来源，解决了这个问题。

```
业务事务写入状态 + Outbox 行
                ↓
            事务提交
                ↓
        Worker 拾取就绪事件
                ↓
        Handler 交付给连接器
```

规则很简单：**本地事务原子，远程状态显式收敛**。FlowWeft 承诺本地数据库的原子性，不承诺跨 PostgreSQL、对象存储与下游系统的分布式事务。

## 02. 事件生命周期

已提交的 Outbox 事件会经历多个状态：

1. **Ready** — 与业务状态变更在同一事务中写入。
2. **Leased** — Worker 在有限时间内（`lease-duration-millis`）取得所有权。
3. **Succeeded** — Handler 返回 `SUCCEEDED`。
4. **Retryable failure** — Handler 返回 `RETRYABLE_FAILURE`；事件带退避重新释放。
5. **Permanent failure** — 重试耗尽；调用 `onExhausted`，事件进入停放区。

> [!TIP]
> Handler 必须是幂等的。Worker 可能在租约到期后恢复或重试同一事件，因此同一事件处理两次必须是安全的。

## 03. Worker 配置

Worker 默认在 `application.yml` 中启用。同一进程可以同时跑 Outbox、Task 和上传清理 Worker，也可以按实例禁用某些角色。

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
  outbox:
    lease-duration-millis: 300000
    legacy-running-grace-millis: 300000
    backlog-metrics-enabled: true
    backlog-metrics-interval-millis: 30000
    backlog-metrics-query-timeout-seconds: 5
  task:
    lease-duration-millis: 60000
    legacy-running-grace-millis: 300000
```

> [!WARNING]
> 不要把 `lease-duration-millis` 设得太短。如果 Handler 运行时间超过租约，另一个 Worker 可能拾起同一事件，此时就完全依赖幂等性了。

## 04. 编写 Outbox 事件处理器

Outbox 事件处理器位于适配器层，通过 `FileWeftPlugin` 注册。它们必须按事件标识符幂等。

```kotlin
package ai.icen.fw.adapter.compliance

import ai.icen.fw.core.event.OutboxEvent
import ai.icen.fw.spi.event.OutboxEventHandler
import ai.icen.fw.spi.event.OutboxHandlingResult
import ai.icen.fw.spi.event.OutboxHandlingStatus
import org.springframework.stereotype.Component
import java.io.IOException

@Component
class ComplianceSyncHandler(
    private val complianceClient: ComplianceClient
) : OutboxEventHandler {

    override fun supports(event: OutboxEvent): Boolean =
        event.type == "document.delivery.target.sync.requested"

    override fun handle(event: OutboxEvent): OutboxHandlingResult {
        val location = event.payload["location"]
            ?: return OutboxHandlingResult(
                OutboxHandlingStatus.PERMANENT_FAILURE,
                "missing location"
            )

        return try {
            complianceClient.archive(location)
            OutboxHandlingResult(OutboxHandlingStatus.SUCCEEDED)
        } catch (e: IOException) {
            // 网络抖动，让 Worker 重试。
            OutboxHandlingResult(OutboxHandlingStatus.RETRYABLE_FAILURE, e.message)
        } catch (e: IllegalArgumentException) {
            // 负载有问题，不要无限重试。
            OutboxHandlingResult(OutboxHandlingStatus.PERMANENT_FAILURE, e.message)
        }
    }

    override fun onExhausted(event: OutboxEvent, message: String) {
        // 只持久化本地状态或发出告警，不要在此处启动新的外部副作用。
        complianceClient.recordExhausted(event.id, message)
    }
}
```

Handler 返回三种状态之一：

| 状态 | 含义 | 下一步 |
|---|---|---|
| `SUCCEEDED` | 副作用已确认 | 事件标记为完成 |
| `RETRYABLE_FAILURE` | 临时问题 | Worker 退避重试 |
| `PERMANENT_FAILURE` | 不可恢复问题 | 调用 `onExhausted`，事件停放 |

## 05. 任务也遵循同样的幂等规则

有些后台工作并不绑定 Outbox 事件。可以实现 `FileWeftTaskHandler` 处理清理、聚合等持久任务。

```kotlin
@Component
class ArchiveCleanupHandler : FileWeftTaskHandler {

    override fun supports(task: TaskExecution): Boolean =
        task.type == "archive.cleanup"

    override fun handle(task: TaskExecution): TaskHandlingResult {
        if (cleanupLog.alreadyProcessed(task.id)) {
            return TaskHandlingResult(TaskHandlingStatus.SUCCEEDED)
        }
        cleanupLog.process(task.id, task.payload)
        return TaskHandlingResult(TaskHandlingStatus.SUCCEEDED)
    }

    override fun onExhausted(task: TaskExecution, message: String) {
        logger.error { "Archive cleanup exhausted for task ${task.id}: $message" }
    }
}
```

## 06. 观察积压

FlowWeft 通过 Micrometer / Prometheus 暴露 Outbox 积压指标：

| 指标 | 含义 |
|---|---|
| `fileweft.outbox_backlog` | 按状态统计的事件数：`ready`、`delayed`、`running`、`expired`、`failed` |
| `fileweft.outbox_oldest_ready_age_seconds` | 最旧就绪事件的年龄 |
| `fileweft.outbox_backlog_observation_failure` | 积压查询失败次数 |

> [!NOTE]
> 指标标签绝不能包含 `tenantId`、文档 ID 或用户 ID。使用低基数、非敏感标签，如 `state` 或 `handler`。

健康系统的表现是 `ready` 接近零、`running` 受 Worker 数量约束。`failed` 持续增长说明 `onExhausted` 处理或运维需要介入。

## 07. 该做与不该做

| 应该 | 不应该 |
|---|---|
| 与业务状态同一事务写入 Outbox 事件 | 在 `@Transactional` 业务方法里调用连接器 |
| Handler 按事件或任务 ID 幂等 | 假设事件只会被处理一次 |
| 临时错误返回 `RETRYABLE_FAILURE` | 对畸形负载永远重试 |
| `onExhausted` 只用于本地状态/告警 | 在 `onExhausted` 中启动新的外部副作用 |
| 监控 `fileweft.outbox_backlog` 与年龄 | 忽视不断增长的 `failed` 队列 |

## 常见问题

**Q：应用代码能看到 Outbox 表吗？**
不能。应用代码通过领域与应用服务发出事件。Outbox 表由 `persistence` 拥有，由 Worker 读取。

**Q：可以禁用 Worker，改为同步调用连接器吗？**
可以禁用 `process-outbox`，但这样事件就不会被交付。没有支持的同步路径绕过 Outbox，因为那会重新引入双写问题。

**Q：如果 Handler 一直返回可重试失败会怎样？**
事件会带退避重试，直到达到重试上限，然后被停放并调用 `onExhausted`。运维人员可以通过 Outbox 表或可观测性工具查看。

## 下一步

- [生命周期与交付](./lifecycle-delivery.md)——文档发布如何映射为 Outbox 事件。
- [连接器](../extensions/connectors.md)——实现带超时、重试与幂等的 `FileConnector`。
- [Doctor 与可观测性](../operations/doctor-observability.md)——诊断积压与 Worker 健康。
