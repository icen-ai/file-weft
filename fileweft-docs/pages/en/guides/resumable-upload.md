---
route: "guides/resumable-upload"
group: "guides"
order: 6
locale: "en"
nav: "Resumable upload"
title: "Resumable upload protocol"
lead: "Upload large files over unreliable networks using caller-stable idempotency keys and numbered parts."
format: "markdown"
---

## Protocol overview

1. **Start** a session with a caller-stable idempotency key.
2. **Upload** numbered parts and persist each acknowledgement.
3. **Inspect** the session after reconnecting.
4. **Complete** once to create the object, asset and event.
5. **Abort** when intentionally abandoning the upload.

The underlying storage upload ID and object path never reach the browser.

## Start a session

```bash
curl -X POST http://localhost:8080/fileweft/v1/uploads \
  -H "Idempotency-Key: upload-report-001" \
  -H "Content-Type: application/json" \
  -d '{
    "documentNumber": "DOC-003",
    "title": "Large report",
    "fileName": "report.pdf",
    "contentLength": 104857600,
    "totalParts": 10
  }'
```

Response:

```json
{
  "uploadId": "fw-upload-7a8b9c",
  "uploadedParts": []
}
```

## Upload a part

```bash
curl -X POST "http://localhost:8080/fileweft/v1/uploads/fw-upload-7a8b9c/parts?partNumber=1" \
  -H "Idempotency-Key: upload-report-001-part-1" \
  -F "file=@part1.bin"
```

## Complete the upload

```bash
curl -X POST http://localhost:8080/fileweft/v1/uploads/fw-upload-7a8b9c/complete \
  -H "Idempotency-Key: upload-report-001-complete"
```

> [!NOTE]
> Replaying the complete call with the same idempotency key returns the same document and version, preventing duplicate assets.
