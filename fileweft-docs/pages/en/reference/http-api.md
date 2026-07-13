---
route: "reference/http-api"
group: "reference"
order: 2
locale: "en"
nav: "HTTP API v1"
title: "HTTP API v1"
lead: "The formal public protocol lives under /fileweft/v1. Every response uses a stable JSON envelope, except for authorized binary downloads which stream bytes with fixed security headers."
format: "markdown"
---

## Base URL and envelope

All endpoints share the prefix `/fileweft/v1`. The response envelope is consistent across success and failure:

```json
{
  "code": "OK",
  "message": "OK",
  "data": {},
  "error": null,
  "traceId": "optional-host-trace-id"
}
```

When `code` is not `OK`, `error` contains a structured object with `code`, `message`, and optional `details`. See [Error codes](./error-codes.md) for the stable error code catalog.

> [!NOTE]
> FileWeft does not expose storage URLs through the public protocol. Downloads return binary streams with `attachment`, `nosniff`, and `private, no-store` headers. Range, HEAD, ETag, and Content-Range are not supported.

## Resource families

| Family | Routes | Purpose |
|--------|--------|---------|
| Upload sessions | `POST /uploads`<br>`GET /uploads/{uploadId}`<br>`PUT /uploads/{uploadId}/parts/{partNumber}`<br>`POST /uploads/{uploadId}/complete`<br>`DELETE /uploads/{uploadId}` | Resumable multipart uploads |
| Documents | `GET /documents`<br>`POST /documents`<br>`GET /documents/{documentId}`<br>`PATCH /documents/{documentId}`<br>`POST /documents/{documentId}/versions`<br>`GET /documents/{documentId}/content`<br>`GET /documents/{documentId}/versions/{versionId}/content` | Document lifecycle and content access |
| Lifecycle & workflow | `POST /documents/{documentId}/revise`<br>`POST /documents/{documentId}/publish`<br>`POST /documents/{documentId}/offline`<br>`POST /documents/{documentId}/restore`<br>`POST /documents/{documentId}/archive`<br>`POST /documents/{documentId}/submit`<br>`POST /workflows/{workflowId}/tasks/{taskId}/approve`<br>`POST /workflows/{workflowId}/tasks/{taskId}/reject`<br>`GET /workflows/tasks`<br>`GET /documents/{documentId}/workflows`<br>`GET /documents/{documentId}/workflow-decisions` | State transitions and review tasks |
| Delivery | `GET /documents/{documentId}/sync-status`<br>`POST /documents/{documentId}/deliveries/{deliveryId}/retry`<br>`POST /documents/{documentId}/deliveries/{deliveryId}/removal/retry` | Track and recover downstream delivery |
| Audit | `GET /documents/{documentId}/logs` | Document audit trail |
| Doctor | `GET /documents/{documentId}/doctor`<br>`POST /documents/{documentId}/doctor/tasks`<br>`GET /documents/{documentId}/doctor/tasks/{taskId}`<br>`GET /doctor` | Document and system diagnostics |
| System | `GET /plugins`<br>`GET /health` | Plugin inventory and liveness |

## Idempotent commands

State-changing commands require exactly one `Idempotency-Key` header. The server stores a tenant-scoped SHA-256 digest bound to the trusted operator, action, resource, and command fingerprint.

```bash
curl -X POST "https://fileweft.example.com/fileweft/v1/documents/doc_123/publish" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Idempotency-Key: publish-doc_123-20250713" \
  -H "Content-Type: application/json" \
  -d '{"comment": "Approved for release"}'
```

> [!WARNING]
> Replay still re-runs authentication, action permission, and catalog visibility checks. An idempotency record is not an authorization cache.

## Complete example: upload and publish

The JSON bodies below use representative field names to show the command shape. Exact request schemas are owned by the v1 contract and may be stricter than shown.

### 1. Create an upload session

```bash
curl -X POST "https://fileweft.example.com/fileweft/v1/uploads" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{
    "...": "file metadata and multipart plan"
  }'
```

Response:

```json
{
  "code": "OK",
  "message": "OK",
  "data": {
    "uploadId": "upl_7a8b9c",
    "...": "session details"
  },
  "error": null,
  "traceId": "trace-abc-123"
}
```

### 2. Upload each part

```bash
for part in {1..5}; do
  curl -X PUT "https://fileweft.example.com/fileweft/v1/uploads/upl_7a8b9c/parts/${part}" \
    -H "Authorization: Bearer ${TOKEN}" \
    -H "Content-Type: application/octet-stream" \
    --data-binary @part-${part}.bin
done
```

### 3. Complete the upload and create a document

```bash
curl -X POST "https://fileweft.example.com/fileweft/v1/uploads/upl_7a8b9c/complete" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{
    "...": "completion and document metadata"
  }'
```

### 4. Submit for review

```bash
curl -X POST "https://fileweft.example.com/fileweft/v1/documents/doc_456/submit" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Idempotency-Key: submit-doc_456-20250713" \
  -H "Content-Type: application/json" \
  -d '{"...": "review route selection"}'
```

### 5. Approve and publish

```bash
curl -X POST "https://fileweft.example.com/fileweft/v1/workflows/wf_789/tasks/task_111/approve" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Idempotency-Key: approve-task_111-20250713" \
  -H "Content-Type: application/json" \
  -d '{"...": "approval payload"}'
```

## Binary downloads

Content endpoints return raw bytes. Do not expect JSON.

```bash
curl -O -J "https://fileweft.example.com/fileweft/v1/documents/doc_456/content" \
  -H "Authorization: Bearer ${TOKEN}"
```

Response headers include:

```
Content-Disposition: attachment; filename="annual-report-2025.pdf"
X-Content-Type-Options: nosniff
Cache-Control: private, no-store
```

## Frequently asked questions

**Are internal development routes part of the public protocol?**
No. Routes under `/api/**` are development or internal endpoints and may change without notice. Build integrations against `/fileweft/v1` only.

**Do I need an idempotency key for GET requests?**
No. Only state-changing commands such as publish, approve, retry, and Doctor task scheduling require one.

**Can I use the same idempotency key for different actions?**
No. The key is bound to the operator, action, resource, and command fingerprint. Reusing it for a different action creates a new record.

## Next steps

- [Error codes](./error-codes.md)
- [Configuration reference](./configuration.md)
- [Lifecycle & delivery concepts](../concepts/lifecycle-delivery.md)
