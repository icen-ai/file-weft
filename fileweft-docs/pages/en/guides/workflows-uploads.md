---
route: "guides/workflows-uploads"
group: "guides"
order: 2
locale: "en"
nav: "Workflow & uploads"
title: "Approval, resumable uploads, and durable agents"
lead: "Understand how FileWeft handles long-running work—document review, multipart uploads, and background AI tasks—without weakening transaction boundaries or leaking storage internals."
format: "markdown"
---

Enterprise files rarely move in a single request. A document may need review, a 2 GB video may need resumable upload, and an AI model may need to process content asynchronously. FileWeft keeps each of these concerns explicit, persistent, and fenced.

## 1. Approval routing

The `DocumentReviewRouteProvider` SPI lets the host decide who must approve a document before it is published. Resolution happens outside the FileWeft database transaction, so your HR or BPM system can be queried safely.

```kotlin
@Component
class ComplianceRouteProvider : DocumentReviewRouteProvider {

    override fun id(): String = "compliance"

    override fun resolve(request: DocumentReviewRouteRequest): DocumentReviewRoute {
        // Host decides approvers based on the document type, tenant, or other request properties.
        val approvers = listOf("compliance-lead", "legal-lead")
        return DocumentReviewRoute(
            tasks = approvers.map { userId ->
                DocumentReviewTask(
                    assignee = Identifier(userId),
                    operation = "APPROVE",
                )
            },
        )
    }
}
```

Behavior:

1. When `submit` is called, FileWeft asks every registered provider to resolve tasks.
2. Parallel tasks run concurrently. All must approve for parallel sign-off.
3. One rejection ends the workflow.
4. The final transaction rechecks the document state before committing.

## 2. Resumable upload

Large files are uploaded through the formal `/fileweft/v1/uploads` resource. The protocol is stateful, idempotent, and never exposes storage upload IDs or object paths to the browser.

| Step | HTTP | Purpose |
| --- | --- | --- |
| 1. Create session | `POST /uploads` | Reserve an upload ID with an idempotency key. |
| 2. Upload part | `PUT /uploads/{id}/parts/{n}` | Send raw bytes for one numbered part. |
| 3. Inspect | `GET /uploads/{id}` | Resume from the server-acknowledged checkpoint. |
| 4. Complete | `POST /uploads/{id}/complete` | Assemble parts into a `FileObject + FileAsset`. |
| 5. Abort | `DELETE /uploads/{id}` | Safely cancel an in-flight session. |

```bash
curl -i -X POST http://localhost:8080/fileweft/v1/uploads \
  -H "Idempotency-Key: upload-report-001" \
  -H "Content-Type: application/json" \
  -d '{
    "fileName": "report.pdf",
    "contentLength": 104857600,
    "contentType": "application/pdf"
  }'
```

After completion the returned `fileAssetId` can be passed to a business command such as `POST /documents` or `POST /documents/{id}/versions`.

> [!TIP]
> Always treat `uploadedParts` from the inspection response as the only authoritative checkpoint. Client-side state can be lost; server-side state cannot.

## 3. Agent and background tasks

AI extraction, OCR, virus scanning, and custom diagnostics belong in durable `fw_task` handlers. They run outside the request thread and are retried with leases.

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
}
```

A task is created by the application layer or emitted as an outbox event. The worker then picks it up and guarantees at-least-once execution with idempotent task IDs.

## 4. How the pieces fit together

A typical flow looks like this:

1. User creates a resumable upload session and sends all parts.
2. Completion returns a `fileAssetId`.
3. Host calls `POST /documents` with the asset to create a draft document.
4. Host calls `POST /documents/{id}/submit` to start review.
5. Review route provider returns assignees.
6. After all approvals, FileWeft publishes and triggers delivery connectors.
7. Optional: an outbox event schedules an OCR task for the published document.

At no point does a database transaction call an external system. All external calls flow through the outbox and async workers.

> [!WARNING]
> Do not call connectors, storage, or AI services inside a document lifecycle transaction. Use outbox events and task handlers instead.

## FAQ

**Q: Can I skip review for internal documents?**
Yes. Register a route provider that returns an empty task list for the document types you consider pre-approved.

**Q: What happens if a worker dies mid-task?**
The task lease expires, and another worker picks it up. Handlers must be idempotent for the task ID.

**Q: Can clients choose the storage upload ID?**
No. Storage upload IDs, ETags, and object keys are internal to the storage adapter.

## Next steps

- [Resumable upload protocol](resumable-upload.md) for complete byte-level semantics.
- [Implement a durable task handler](agent-handler.md) to add OCR, scan, or AI agents.
- [Lifecycle and delivery concepts](../concepts/lifecycle-delivery.md) for publish/offline/archive behavior.
