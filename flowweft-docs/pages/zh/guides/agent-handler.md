---
route: "guides/agent-handler"
group: "guides"
order: 5
locale: "zh"
nav: "任务 Handler"
title: "实现持久任务 Handler"
lead: "通过实现 FileWeftTaskHandler 添加 OCR、病毒扫描或宿主自有抽取等后台工作。Worker 使用租约和幂等任务 ID。"
format: "markdown"
---

并不是每个文件操作都能放进 HTTP 请求里。OCR、机器学习推理、病毒扫描和批量同步可能耗时数秒甚至数分钟。FlowWeft 把这些工作推入持久化 `fw_task` 记录，由后台 Worker 处理。

> [!CAUTION]
> 本页讲的是通用 `FileWeftTaskHandler`，不是 FlowWeft Agent 产品能力。0.0.2 与 0.0.3 默认都不注册、宣传或暴露 Agent；兼容制品中的 Agent SPI/ABI 不应用于新集成。

## 1. 任务 Handler 契约

Handler 声明支持的任务类型，并返回三种状态之一：

| 状态 | 含义 | Worker 行为 |
| --- | --- | --- |
| `SUCCEEDED` | 成功终态。 | 标记任务完成。 |
| `RETRYABLE_FAILURE` | 临时失败。 | 按指数退避重试并重新租约。 |
| `PERMANENT_FAILURE` | 不可恢复失败。 | 停止重试并调用 `onExhausted`。 |

语义为至少执行一次。租约到期后，其他 Worker 可能重新执行同一任务，因此 Handler 必须对 task id 幂等。

## 2. 示例：文档 OCR Handler

```kotlin
@Component
class DocumentOcrHandler : FileWeftTaskHandler {

    override fun supports(task: TaskExecution): Boolean =
        task.type == "document.ocr"

    override fun handle(task: TaskExecution): TaskHandlingResult {
        val documentId = task.payload["documentId"]
            ?: return TaskHandlingResult(TaskHandlingStatus.PERMANENT_FAILURE, "missing documentId")

        return try {
            val text = ocrClient.extractText(documentId)
            suggestionStore.save(task.tenantId, documentId, text)
            TaskHandlingResult(TaskHandlingStatus.SUCCEEDED)
        } catch (failure: IOException) {
            TaskHandlingResult(TaskHandlingStatus.RETRYABLE_FAILURE, failure.message)
        }
    }

    override fun onExhausted(task: TaskExecution, message: String) {
        // 持久化死信标记。这里不要调用远程系统。
        deadLetterStore.mark(task.id, message)
    }
}
```

> [!WARNING]
> `onExhausted` 不能调用远程系统，只能做本地死信或指标记录。

## 3. 调度任务

任务由应用层创建，或通过 Outbox 事件发出。控制器只应校验请求并调用应用服务，不要直接入队任务。

```kotlin
@Service
class DocumentService {

    fun requestOcr(documentId: Identifier) {
        // 控制器委托给应用服务。
        // 应用层发出 Outbox 事件，Worker 将其转换为持久化 fw_task，
        // 再路由给匹配的 FileWeftTaskHandler。
    }
}
```

Outbox 模式让数据库事务保持本地：文档状态变更与 Outbox 事件一起提交，Worker 再异步调用 Handler。

## 4. 配置 Worker

```yaml
fileweft:
  worker:
    enabled: true
    fixed-delay-millis: 1000
    task-batch-size: 50
  task:
    lease-duration-millis: 60000
    legacy-running-grace-millis: 300000
```

租约时长必须大于最长单次任务执行时间。如果 Handler 执行超过租约，其他 Worker 可能启动同一任务。

## 5. 幂等规则

为保证至少执行一次仍然安全，请遵守：

1. 写之前先读当前状态，只在状态仍为 pending 时执行。
2. 用 task id 作为下游调用的幂等键。
3. 用 task id 存储结果，而不是只用 document id。
4. 把 `PERMANENT_FAILURE` 视为终态，不要期望同一任务再次运行。

> [!TIP]
> 把 Handler 设计成状态机。一个“读-判断-写”的小单元，比串联多个远程调用的 Handler 更容易幂等。

## 常见问题

**Q：任务 Handler 可以调用另一个 Handler 吗？**
可以发出 Outbox 事件，但不应同步调用另一个 Handler。保持每个 Handler 职责单一。

**Q：如何测试 Handler？**
使用 `fileweft-testkit` 中的内存任务测试工具，断言 `TaskHandlingResult` 和副作用。

**Q：所有重试耗尽后会怎样？**
Worker 调用 `onExhausted` 后停止。运维可以检查死信存储，修复原因或删除任务。

## 下一步

- [工作流与上传](workflows-uploads.md) 把任务与文档生命周期连接起来。
- [插件](../extensions/plugins.md) 把 Handler 打包成可复用模块。
