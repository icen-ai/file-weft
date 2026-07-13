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

When `code` is not `OK`, `error` contains exactly the same stable `code` and safe `message` as the outer envelope; it has no arbitrary detail attributes. See [Error codes](./error-codes.md) for the stable error code catalog.

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

## Command-specific idempotency

Idempotency requirements are part of each command contract; they are not inferred merely because an HTTP method changes state. For the upload resource, the requirements are exact:

| Command | Required request contract |
| --- | --- |
| `POST /uploads` | Exactly one `Idempotency-Key`; JSON body with `fileName`, `contentLength`, optional `contentType`, and optional `contentHash` only |
| `GET /uploads/{uploadId}` | No idempotency key; returns the authoritative checkpoint and, once complete, the completion receipt |
| `PUT /uploads/{uploadId}/parts/{partNumber}` | `Content-Type: application/octet-stream`, exactly one `X-FileWeft-Part-Length`, and raw non-empty bytes; no idempotency key |
| `POST /uploads/{uploadId}/complete` | No body and no idempotency key |
| `DELETE /uploads/{uploadId}` | No body and no idempotency key |

Other commands declare their own requirements. For example, document publication uses an `Idempotency-Key`:

```bash
curl -X POST "https://fileweft.example.com/fileweft/v1/documents/doc_123/publish" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Idempotency-Key: publish-doc_123-20250713" \
  -H "Content-Type: application/json" \
  -d '{"deliveryProfileId": "regulated"}'
```

The publication body is optional. When supplied, `deliveryProfileId` is its only supported field; publication does not accept a review `comment`.

> [!WARNING]
> Replay still re-runs authentication, action permission, and catalog visibility checks. An idempotency record is not an authorization cache.

## Complete resumable-upload example

### 1. Create an upload session

```bash
curl -X POST "https://fileweft.example.com/fileweft/v1/uploads" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Idempotency-Key: annual-report-upload-2025" \
  -H "Content-Type: application/json" \
  -d '{
    "fileName": "annual-report-2025.pdf",
    "contentLength": 104857600,
    "contentType": "application/pdf",
    "contentHash": "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
  }'
```

Use a key that is unique to this logical upload creation. The JSON object cannot select a tenant, owner, asset type, storage key, storage upload ID, ETag, or arbitrary metadata.

Response:

```json
{
  "code": "OK",
  "message": "OK",
  "data": {
    "uploadId": "upl_7a8b9c",
    "fileName": "annual-report-2025.pdf",
    "contentLength": 104857600,
    "contentType": "application/pdf",
    "contentHash": "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
    "status": "UPLOADING",
    "expiresAt": 1752393600000,
    "createdTime": 1752307200000,
    "updatedTime": 1752307200000,
    "uploadedParts": [],
    "completion": null
  },
  "error": null,
  "traceId": "trace-abc-123"
}
```

### 2. Upload each part

```bash
PART_FILE="part-1.bin"
PART_LENGTH="$(wc -c < "${PART_FILE}" | tr -d ' ')"

curl -X PUT "https://fileweft.example.com/fileweft/v1/uploads/upl_7a8b9c/parts/1" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/octet-stream" \
  -H "X-FileWeft-Part-Length: ${PART_LENGTH}" \
  --data-binary @"${PART_FILE}"
```

Repeat with the next positive `partNumber`. `X-FileWeft-Part-Length` must appear exactly once and must equal the bytes sent for that part.

### 3. Resume from the authoritative checkpoint

```bash
curl "https://fileweft.example.com/fileweft/v1/uploads/upl_7a8b9c" \
  -H "Authorization: Bearer ${TOKEN}"
```

Read `data.uploadedParts` before resuming after a disconnect. The same `uploadId` remains the query key after completion, when `status` becomes `COMPLETED` and `data.completion` contains the stable receipt.

### 4. Complete the upload

```bash
curl -X POST "https://fileweft.example.com/fileweft/v1/uploads/upl_7a8b9c/complete" \
  -H "Authorization: Bearer ${TOKEN}"
```

The completion command has no request body. It completes the upload synchronously and returns an opaque receipt:

```json
{
  "code": "OK",
  "message": "OK",
  "data": {
    "uploadId": "upl_7a8b9c",
    "fileObjectId": "file_123",
    "fileAssetId": "asset_456"
  },
  "error": null,
  "traceId": "trace-abc-123"
}
```

It does not create a document. Creating a document or version from the completed asset is a separate, host-owned application command. The current formal `POST /fileweft/v1/documents` and `POST /fileweft/v1/documents/{documentId}/versions` endpoints accept multipart content, not `fileAssetId`. This deliberate boundary is not a defect or a 0.0.2 blocker for the resumable-upload resource.

### 5. Query the completed upload again

```bash
curl "https://fileweft.example.com/fileweft/v1/uploads/upl_7a8b9c" \
  -H "Authorization: Bearer ${TOKEN}"
```

The inspection response now carries the same three IDs under `data.completion`, so a lost completion response can be recovered without completing the upload again.

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

**Does every write request need an idempotency key?**
No. Follow the individual endpoint contract. Upload creation requires exactly one; upload-part, complete and abort do not. GET never requires one.

**Can I use the same idempotency key for different actions?**
Do not do so. For a command that accepts the header, use a key unique to the intended logical command; the server binds its digest to the trusted context and command fingerprint.

## Next steps

- [Error codes](./error-codes.md)
- [Configuration reference](./configuration.md)
- [Lifecycle & delivery concepts](../concepts/lifecycle-delivery.md)
