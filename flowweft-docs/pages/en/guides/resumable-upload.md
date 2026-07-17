---
route: "guides/resumable-upload"
group: "guides"
order: 6
locale: "en"
nav: "Resumable upload"
title: "Resumable upload protocol"
lead: "Transfer large files over unreliable networks by resuming from server-acknowledged parts through the formal v1 upload resource."
format: "markdown"
---

The formal resumable upload resource lives at `/fileweft/v1/uploads`. It has the same contract in both Spring Boot 2 and Spring Boot 3 Web Starters. The resource persists bytes as a `FileObject + FileAsset`; it does not create a document, version, catalog entry, or review workflow.

## 1. Protocol boundary

The protocol has five business operations:

| Operation | Path | Success |
| --- | --- | --- |
| Create or replay | `POST /fileweft/v1/uploads` | `201` |
| Inspect the checkpoint | `GET /fileweft/v1/uploads/{uploadId}` | `200` |
| Upload or replace a part | `PUT /fileweft/v1/uploads/{uploadId}/parts/{partNumber}` | `200` |
| Complete | `POST /fileweft/v1/uploads/{uploadId}/complete` | `200` |
| Abort | `DELETE /fileweft/v1/uploads/{uploadId}` | `200` |

Clients cannot submit a tenant, owner, asset type, object key, storage upload ID, ETag, or arbitrary storage metadata. Tenant and owner always come from the trusted host context. Cross-tenant, cross-owner, and absent uploads are all exposed as `404 NOT_FOUND`.

## 2. Create a session

Creation requires exactly one `Idempotency-Key`. It may contain ASCII letters, digits, `.`, `_`, `~`, `:`, and `-`, with a length of 1–128. FlowWeft hashes it with the trusted tenant using a versioned SHA-256 representation; the raw value is never persisted or reflected in responses or errors.

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

`contentType` and `contentHash` are optional. A supplied hash must use the exact lowercase form `sha256:<64 lowercase hex characters>`, and the completed object's hash must match. `documentNumber`, `title`, `totalParts`, `assetType`, and `metadata` are not part of this resource.

A successful response carries `Location: /fileweft/v1/uploads/{uploadId}` and a JSON envelope:

```json
{
  "code": "OK",
  "message": "OK",
  "data": {
    "uploadId": "fw-upload-7a8b9c",
    "fileName": "report.pdf",
    "contentLength": 104857600,
    "contentType": "application/pdf",
    "contentHash": null,
    "status": "UPLOADING",
    "expiresAt": 1784000000000,
    "createdTime": 1783900000000,
    "updatedTime": 1783900000000,
    "uploadedParts": [],
    "completion": null
  },
  "error": null,
  "traceId": null
}
```

Replaying the same request with the same key, trusted tenant, and owner returns the original session with its latest checkpoint and does not allocate another remote multipart upload. Changing the request or reusing the key as another owner returns a non-disclosing `409 CONFLICT`.

## 3. Upload a part

Parts are raw byte streams, not multipart forms, and do not use another idempotency key. `partNumber` must be 1–10000. `X-FileWeft-Part-Length` must occur exactly once and equal the actual body length.

```bash
curl -X PUT "http://localhost:8080/fileweft/v1/uploads/fw-upload-7a8b9c/parts/1" \
  -H "Content-Type: application/octet-stream" \
  -H "X-FileWeft-Part-Length: 5242880" \
  --data-binary @part-0001.bin
```

The acknowledgement contains only the upload ID, part number, length, and acknowledgement time; storage ETags stay private. A client may PUT the same part number again before completion. The durable checkpoint changes only after storage acknowledges the part and the number of bytes actually consumed exactly matches the declared length.

## 4. Resume after a disconnect

Read the resource after reconnecting and treat `uploadedParts` as the sole authoritative checkpoint:

```bash
curl http://localhost:8080/fileweft/v1/uploads/fw-upload-7a8b9c
```

Public states are `UPLOADING`, `FINALIZING`, `COMPLETED`, `FAILED`, `ABORTED`, and `EXPIRED`. Internal staging, abort fencing, and `QUARANTINED` states are not exposed. Before completion, acknowledged part numbers must form a consecutive sequence starting at 1, and their total length must equal the declared whole-file length.

> [!TIP]
> Reconnecting clients should PUT only the parts missing from `uploadedParts`. Do not assume local progress is authoritative.

## 5. Complete and recover an unknown outcome

```bash
curl -X POST http://localhost:8080/fileweft/v1/uploads/fw-upload-7a8b9c/complete
```

A successful completion returns an opaque receipt. Its three resource IDs are stable; `completedAt` is a nullable post-commit observation:

```json
{
  "code": "OK",
  "message": "OK",
  "data": {
    "uploadId": "fw-upload-7a8b9c",
    "fileObjectId": "fw-object-123",
    "fileAssetId": "fw-asset-123",
    "completedAt": 1783900100000
  },
  "error": null,
  "traceId": null
}
```

Completion is synchronous and replayable. If completion committed but the post-commit checkpoint read failed, this 200 may carry `completedAt: null`; a later GET/replay supplies the persisted time, so clients must not use that field as the idempotency identity.

If the response is lost, first GET the same upload. `COMPLETED` includes the same resource IDs in `completion`; for `FINALIZING`, wait and retry completion with the same `uploadId`. When FlowWeft cannot safely classify the database or object-storage outcome it returns `503 OUTCOME_UNKNOWN`; do not mint a new key or blindly delete the object. FlowWeft non-destructively reconciles stale completion state.

If storage definitively rejects the request and guarantees that it did not publish an object, FlowWeft atomically clears stale acknowledgements, restores `UPLOADING`, renews a full session-TTL retry window, and returns `409 CONFLICT`; GET again and PUT every part missing from the returned checkpoint.

> [!WARNING]
> Object-store minimum part sizes remain host policy. Repeated 409 responses require a compliant part size or an abort.

## 6. Abort

```bash
curl -X DELETE http://localhost:8080/fileweft/v1/uploads/fw-upload-7a8b9c
```

Abort terminates a remote multipart upload only while doing so is known to be safe. An active session becomes `ABORTED`; a replay against a session that is already terminal returns that unchanged terminal view (for example, `COMPLETED` remains `COMPLETED`). Cleanup never destroys an object protected by an unknown-outcome fence.

## 7. Production gateway

Disable request buffering on `/fileweft/v1/uploads/*/parts/*` and allow the selected maximum part size plus small protocol overhead. Body size, timeouts, rate limits, and object-store minimum part sizes remain host policy. Do not substitute a global multipart-form limit for a route-specific streaming policy.

## FAQ

**Q: Should I create one session per file?**
Yes. The idempotency key identifies one logical file transfer. Reuse it only to resume the same file.

**Q: Can I change the file name after creating the session?**
No. Changing the request body and reusing the idempotency key returns `409 CONFLICT`.

**Q: How do I turn an uploaded asset into a document?**
The completion receipt provides a stable `fileAssetId`, but the current formal `POST /fileweft/v1/documents` and `POST /fileweft/v1/documents/{id}/versions` endpoints accept multipart file content rather than that ID. The host must bind the asset to a document or version through its own application-layer integration; this step is not yet part of the formal HTTP resource.

## Next steps

- [Workflows and uploads](workflows-uploads.md) to connect uploads with review and publish.
- [Implement a storage adapter](storage-adapter.md) to customize where multipart parts are assembled.
