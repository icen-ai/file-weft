---
route: "guides/agent-handler"
group: "guides"
order: 5
locale: "zh"
nav: "Agent Handler"
title: "实现持久任务 Handler"
lead: "通过实现 FileWeftTaskHandler 添加后台工作。Worker 使用租约和幂等任务 ID。"
format: "markdown"
---

## 任务 Handler 契约

Handler 声明支持的任务类型，并返回三种状态之一：

| 状态 | 含义 |
|------|------|
| SUCCEEDED | 成功终态。 |
| RETRYABLE_FAILURE | Worker 会指数退避重试。 |
| PERMANENT_FAILURE | Worker 停止重试并调用 `onExhausted`。 |

## 示例：文档 OCR Handler

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

## 调度任务

任务通过应用层或 Outbox 创建。控制器只发送事件，由 Worker 消费：

```bash
curl -X POST http://localhost:8080/fileweft/v1/documents/DOC-001/ocr \
  -H "Idempotency-Key: ocr-DOC-001-2026-07-13"
```

> [!WARNING]
> Handler 必须对 task id 幂等。租约过期后，Worker 可能重新执行同一任务。
