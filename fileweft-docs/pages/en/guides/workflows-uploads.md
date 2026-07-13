---
route: "guides/workflows-uploads"
group: "guides"
order: 2
locale: "en"
nav: "Workflow & uploads"
title: "Approval, resumable uploads, and durable tasks"
lead: "Understand how FileWeft handles long-running work—document review, multipart uploads, and generic background tasks—without weakening transaction boundaries or leaking storage internals."
format: "markdown"
---

Enterprise files rarely move in a single request. A document may need review, a 2 GB video may need resumable upload, and OCR or scanning may need to process content asynchronously. FileWeft keeps each concern explicit, persistent, and isolated.

## 1. Approval routing

The `DocumentReviewRouteProvider` SPI lets the host decide who must approve a document before it is published. Resolution happens outside the FileWeft database transaction, so your HR or BPM system can be queried safely.

```kotlin
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.workflow.DocumentReviewRoute
import ai.icen.fw.spi.workflow.DocumentReviewRouteProvider
import ai.icen.fw.spi.workflow.DocumentReviewRouteRequest
import ai.icen.fw.spi.workflow.DocumentReviewRouteTask
import org.springframework.stereotype.Component

@Component
class ComplianceRouteProvider : DocumentReviewRouteProvider {

    override fun id(): String = "compliance"

    override fun resolve(request: DocumentReviewRouteRequest): DocumentReviewRoute {
        // Host decides approvers based on the document type, tenant, or other request properties.
        val approvers = listOf("compliance-lead", "legal-lead")
        return DocumentReviewRoute(
            workflowType = "COMPLIANCE_REVIEW",
            tasks = approvers.map { userId ->
                DocumentReviewRouteTask(assigneeId = Identifier(userId))
            },
        )
    }
}
```

Behavior:

1. On `submit`, FileWeft selects exactly one provider: the request's `reviewRouteId`, or the route configured by `fileweft.workflow.default-review-route-id` when the request omits it.
2. Parallel tasks run concurrently. All must approve.
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

The completion receipt's `fileAssetId` identifies the persisted asset. The current formal `POST /fileweft/v1/documents` and `POST /fileweft/v1/documents/{id}/versions` endpoints still accept multipart file content; they do not accept this ID. Reusing the asset requires a host-owned application-layer binding. That binding is not currently part of the formal document HTTP resource.

> [!TIP]
> Always treat `uploadedParts` from the inspection response as the only authoritative checkpoint. Client-side state can be lost; server-side state cannot.

## 3. Generic background tasks

OCR, virus scanning, host-owned extraction, and custom diagnostics belong in durable `fw_task` handlers. They run outside the request thread and are retried with leases.

> [!CAUTION]
> `FileWeftTaskHandler` is the generic durable-task capability in 0.0.2; it is not FileWeft Agent. The `fileweft-agent` artifact, Agent SPI/ABI and related migrations remain only for compatibility, and the default product surface does not register or expose Agent.

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
3. A host-owned application integration binds that asset as a document or version; the current formal document HTTP resource does not perform this step.
4. Host calls `POST /documents/{id}/submit` to start review.
5. Review route provider returns assignees.
6. After all approvals, FileWeft publishes and triggers delivery connectors.
7. Optional: an outbox event schedules an OCR task for the published document.

At no point does a database transaction call an external system. All external calls flow through the outbox and async workers.

> [!WARNING]
> Do not call connectors, storage, or AI services inside a document lifecycle transaction. Use outbox events and task handlers instead.

## FAQ

**Q: Can I skip review for internal documents?**
Not by returning an empty route. `DocumentReviewRoute` requires a non-blank `workflowType`, at least one task, and no duplicate tasks; the current formal `submit` HTTP path also creates local review tasks. If the business permits bypassing local review, the host must define a separate authorized lifecycle path instead of returning an empty list from this provider.

**Q: What happens if a worker dies mid-task?**
The task lease expires, and another worker picks it up. Handlers must be idempotent for the task ID.

**Q: Can clients choose the storage upload ID?**
No. Storage upload IDs, ETags, and object keys are internal to the storage adapter.

## Next steps

- [Resumable upload protocol](resumable-upload.md) for complete byte-level semantics.
- [Implement a durable task handler](agent-handler.md) to add OCR, scanning, or other host background work.
- [Lifecycle and delivery concepts](../concepts/lifecycle-delivery.md) for publish/offline/archive behavior.
