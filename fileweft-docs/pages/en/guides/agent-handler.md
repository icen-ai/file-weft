---
route: "guides/agent-handler"
group: "guides"
order: 5
locale: "en"
nav: "Agent handler"
title: "Implement a durable task handler"
lead: "Add background work by implementing FileWeftTaskHandler. Workers use leases and idempotent task IDs."
format: "markdown"
---

## Task handler contract

A handler declares which task types it supports and returns one of three statuses:

| Status | Meaning |
|--------|---------|
| SUCCEEDED | Terminal success. |
| RETRYABLE_FAILURE | Worker will retry with exponential backoff. |
| PERMANENT_FAILURE | Worker stops retrying and calls `onExhausted`. |

## Example: document OCR handler

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

## Schedule the task

A task is created through the application layer or outbox. From a controller you only emit an event; a worker picks it up:

```bash
curl -X POST http://localhost:8080/fileweft/v1/documents/DOC-001/ocr \
  -H "Idempotency-Key: ocr-DOC-001-2026-07-13"
```

> [!WARNING]
> The handler must be idempotent for the task id. A worker may resume a task after a lease expires.
