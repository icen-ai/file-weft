---
route: "guides/agent-handler"
group: "guides"
order: 5
locale: "en"
nav: "Task handler"
title: "Implement a durable task handler"
lead: "Add background work such as OCR, virus scanning, or host-owned extraction by implementing FileWeftTaskHandler. Workers use leases and idempotent task IDs."
format: "markdown"
---

Not every file operation fits inside an HTTP request. OCR, machine-learning inference, virus scanning, and bulk synchronization can take seconds or minutes. FileWeft pushes this work into durable `fw_task` records handled by background workers.

> [!CAUTION]
> This page covers the generic `FileWeftTaskHandler`, not FileWeft Agent product capability. Neither 0.0.2 nor 0.0.3 registers, advertises, or exposes Agent by default; do not use the compatibility-only Agent SPI/ABI for new integrations.

## 1. Task handler contract

A handler declares which task types it supports and returns one of three statuses:

| Status | Meaning | Worker behavior |
| --- | --- | --- |
| `SUCCEEDED` | Terminal success. | Mark task complete. |
| `RETRYABLE_FAILURE` | Transient failure. | Retry with exponential backoff and lease. |
| `PERMANENT_FAILURE` | Unrecoverable failure. | Stop retrying and call `onExhausted`. |

The semantics are at-least-once. A worker may resume a task after its lease expires, so the handler must be idempotent for the task ID.

## 2. Example: document OCR handler

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
        // Persist a dead-letter marker. Do not call remote systems here.
        deadLetterStore.mark(task.id, message)
    }
}
```

> [!WARNING]
> `onExhausted` must not call remote systems. It is for local dead-letter or metrics bookkeeping only.

## 3. Schedule a task

Tasks are created by the application layer or emitted as outbox events. A controller should only validate the request and call an application service; it should never queue a task directly.

```kotlin
@Service
class DocumentService {

    fun requestOcr(documentId: Identifier) {
        // Controllers delegate to application services.
        // The application layer emits an outbox event, which a worker converts
        // into a durable fw_task and routes to the matching FileWeftTaskHandler.
    }
}
```

The outbox pattern keeps database transactions local: the document state change and the outbox event are committed together, and a worker invokes the handler asynchronously.

## 4. Configure workers

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

The lease duration must be longer than the longest expected single-task execution. If a handler runs longer than its lease, another worker may start the same task.

## 5. Idempotency rules

Follow these rules so at-least-once execution remains safe:

1. Read the current state before writing. Only act if the state is still pending.
2. Use the task ID as the idempotency key for downstream calls.
3. Store results with the task ID, not just the document ID.
4. Treat `PERMANENT_FAILURE` as final; do not expect the same task to run again.

> [!TIP]
> Design handlers as state machines. A handler that reads, decides, and writes in one small unit is easier to make idempotent than one that chains many remote calls.

## FAQ

**Q: Can a task handler call another task handler?**
It can emit an outbox event, but it should not synchronously invoke another handler. Keep each handler focused.

**Q: How do I test a handler?**
Use the in-memory task test utilities from `fileweft-testkit` and assert on `TaskHandlingResult` and side effects.

**Q: What happens if all retries are exhausted?**
The worker calls `onExhausted` and stops. An operator can inspect the dead-letter store and either fix the cause or delete the task.

## Next steps

- [Workflows and uploads](workflows-uploads.md) to connect tasks with document lifecycle.
- [Plugins](../extensions/plugins.md) to package handlers as reusable modules.
