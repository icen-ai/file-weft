---
route: "guides/resumable-upload"
group: "guides"
order: 6
locale: "en"
nav: "Resumable upload"
title: "Resumable upload protocol"
lead: "Resume from server-acknowledged parts over the formal v1 upload resource."
format: "markdown"
---

## Protocol boundary

The formal resource lives at `/fileweft/v1/uploads` and has the same contract in the Spring Boot 2 and Spring Boot 3 Web Starters. It persists bytes as a `FileObject + FileAsset`; it does not create a document, version, catalog entry, or review workflow. After completion, a host can pass the returned asset identifier to a separate business command.

Clients cannot submit a tenant, owner, asset type, object key, storage upload ID, ETag, or arbitrary storage metadata. Tenant and owner always come from trusted host context. Cross-tenant, cross-owner, and absent uploads are all exposed as `404 NOT_FOUND`.

The protocol has five business operations. A servlet container or host may answer `HEAD`/`OPTIONS` using standard HTTP semantics; those are not additional FileWeft state-changing operations:

| Operation | Path | Success |
| --- | --- | --- |
| Create or replay | `POST /fileweft/v1/uploads` | `201` |
| Inspect the checkpoint | `GET /fileweft/v1/uploads/{uploadId}` | `200` |
| Upload or replace a part | `PUT /fileweft/v1/uploads/{uploadId}/parts/{partNumber}` | `200` |
| Complete | `POST /fileweft/v1/uploads/{uploadId}/complete` | `200` |
| Abort | `DELETE /fileweft/v1/uploads/{uploadId}` | `200` |

Every JSON success and failure uses the FileWeft v1 envelope. A separate `/status` endpoint is unnecessary: inspection already returns state, acknowledged parts, and the stable completion receipt.

## Create a session

Creation requires exactly one `Idempotency-Key`. It may contain ASCII letters, digits, `.`, `_`, `~`, `:`, and `-`, with a length of 1–128. FileWeft hashes it with the trusted tenant using a versioned SHA-256 representation; the raw value is never persisted or reflected in responses or errors.

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

At the root deployment path the response carries `Location: /fileweft/v1/uploads/{uploadId}`. With a context path or servlet path, `Location` preserves those host prefixes:

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

## Upload a part

Parts are raw byte streams, not multipart forms, and do not use another idempotency key. `partNumber` must be 1–10000. `X-FileWeft-Part-Length` must occur exactly once and equal the actual body length.

```bash
curl -X PUT "http://localhost:8080/fileweft/v1/uploads/fw-upload-7a8b9c/parts/1" \
  -H "Content-Type: application/octet-stream" \
  -H "X-FileWeft-Part-Length: 5242880" \
  --data-binary @part-0001.bin
```

The acknowledgement contains only the upload ID, part number, length, and acknowledgement time; storage ETags stay private. A client may PUT the same part number again before completion. The durable checkpoint changes only after storage acknowledges the part and the number of bytes actually consumed exactly matches the declared length.

## Resume after a disconnect

Read the resource after reconnecting and treat `uploadedParts` as the sole authoritative checkpoint:

```bash
curl http://localhost:8080/fileweft/v1/uploads/fw-upload-7a8b9c
```

Public states are `UPLOADING`, `FINALIZING`, `COMPLETED`, `FAILED`, `ABORTED`, and `EXPIRED`. Internal staging, abort fencing, and `QUARANTINED` states are not exposed. Before completion, acknowledged part numbers must form a consecutive sequence starting at 1, and their total length must equal the declared whole-file length.

## Complete and recover an unknown outcome

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

Completion is synchronous and replayable. If completion committed but the post-commit checkpoint read failed, this 200 may carry `completedAt: null`; a later GET/replay supplies the persisted time, so clients must not use that field as the idempotency identity. If the response is lost, first GET the same upload. `COMPLETED` includes the same resource IDs in `completion`; for `FINALIZING`, wait and retry completion with the same `uploadId`. When FileWeft cannot safely classify the database or object-storage outcome it returns `503 OUTCOME_UNKNOWN`; do not mint a new key or blindly delete the object. FileWeft non-destructively reconciles stale completion state. If storage definitively rejects the request and guarantees that it did not publish an object, FileWeft atomically clears stale acknowledgements, restores `UPLOADING`, renews a full session-TTL retry window, and returns `409 CONFLICT`; GET again and PUT every part missing from the returned checkpoint. Object-store minimum part sizes remain host policy, so repeated 409 responses require a compliant part size or an abort.

## Abort

```bash
curl -X DELETE http://localhost:8080/fileweft/v1/uploads/fw-upload-7a8b9c
```

Abort terminates a remote multipart upload only while doing so is known to be safe. An active session becomes `ABORTED`; a replay against a session that is already terminal returns that unchanged terminal view (for example, `COMPLETED` remains `COMPLETED`). Cleanup never destroys an object protected by an unknown-outcome fence.

## Production gateway

Disable request buffering on `/fileweft/v1/uploads/*/parts/*` and allow the selected maximum part size plus small protocol overhead. Body size, timeouts, rate limits, and object-store minimum part sizes remain host policy. Do not substitute a global multipart-form limit for a route-specific streaming policy.
