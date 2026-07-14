---
route: "reference/error-codes"
group: "reference"
order: 4
locale: "en"
nav: "Error codes"
title: "Stable error codes"
lead: "When something goes wrong, FileWeft returns a stable machine-readable code inside the JSON envelope. This page explains each code and the recovery action to take."
format: "markdown"
---

## How errors are returned

All v1 endpoints return the same outer shape. On failure `code` is not `OK` and `error` is populated:

```json
{
  "code": "INVALID_REQUEST",
  "message": "Missing required field",
  "data": {},
  "error": {
    "code": "INVALID_REQUEST",
    "message": "Missing required field",
    "details": {
      "...": "additional context"
    }
  },
  "traceId": "trace-abc-123"
}
```

> [!TIP]
> Log `traceId` and the full error body before contacting operators. The code is stable across releases; free-form messages may change.

## Stable error codes

| Code | Meaning | What to do |
|------|---------|------------|
| `INVALID_REQUEST` | The request body, query parameter, or path variable is malformed or fails validation. | Fix the request and retry. |
| `UNAUTHENTICATED` | No valid credentials were provided. | Refresh or obtain a token, then retry. |
| `FORBIDDEN` | The caller is authenticated but not authorized for this action or resource. | Check roles, catalog ACL, and tenant scope. |
| `NOT_FOUND` | The requested document, version, upload, workflow task, or delivery does not exist. | Verify the identifier and tenant context. |
| `METHOD_NOT_ALLOWED` | The HTTP method is not supported on this path. | Use the method listed in the API reference. |
| `NOT_ACCEPTABLE` | The `Accept` header cannot be satisfied. | Use `application/json` or omit the header. |
| `UNSUPPORTED_MEDIA_TYPE` | The `Content-Type` header is not supported. | Use `application/json` or the required binary type. |
| `RANGE_NOT_SUPPORTED` | Range requests are not supported for FileWeft downloads. | Request the full resource. |
| `CONFLICT` | The operation conflicts with the current state, such as publishing a document that is already published. | Read the current state and reconcile. |
| `FEATURE_UNAVAILABLE` | The feature is disabled or not configured, such as a missing connector profile. | Enable the feature or configure the required SPI/profile. |
| `CONTENT_UNAVAILABLE` | The content exists but is not accessible in the current state, such as an offline document. | Change lifecycle state or wait for delivery to complete. |
| `OUTCOME_UNKNOWN` | The server accepted the command but cannot confirm the final outcome, often after a timeout. | Use idempotency keys and query the resource state. |
| `INTERNAL_ERROR` | An unexpected failure occurred inside FileWeft. | Retry with backoff and contact operators if it persists. |

> [!NOTE]
> The HTTP status code is transport-level and may vary by endpoint. Always use the `error.code` field for programmatic handling.

## Idempotency conflicts

When you replay a command with the same `Idempotency-Key`, FileWeft returns the original result if the fingerprint matches. If the key matches but the command differs, you receive `CONFLICT`:

```json
{
  "code": "CONFLICT",
  "message": "Idempotency key already used with different parameters",
  "data": {},
  "error": {
    "code": "CONFLICT",
    "message": "Idempotency key already used with different parameters",
    "details": {
      "...": "additional context"
    }
  },
  "traceId": "trace-def-456"
}
```

To avoid this, include a unique scope in each idempotency key, such as the resource id, action, and a date or UUID.

## State-specific errors

Several codes depend on the document lifecycle:

- `CONFLICT` — publishing a document that is not in `PENDING_REVIEW` or `DRAFT`.
- `FEATURE_UNAVAILABLE` — submitting a document when no `DocumentReviewRouteProvider` is registered.
- `CONTENT_UNAVAILABLE` — downloading content of an `OFFLINE` or `ARCHIVED` document.
- `OUTCOME_UNKNOWN` — a connector timed out before returning a definitive result.

Query `GET /documents/{documentId}` or `GET /documents/{documentId}/sync-status` to inspect the current state before retrying.

## Frequently asked questions

**Will error codes change between 0.0.3 and future releases?**
The listed codes are stable. New codes may be added, but existing codes keep their meaning.

**Should I retry `INTERNAL_ERROR` immediately?**
Retry with exponential backoff. If the error persists, check the Doctor endpoint and server logs.

**Why did I get `FORBIDDEN` for a resource I just created?**
Authorization runs against the trusted tenant from your `TenantProvider`, not from request parameters. Verify that the tenant header or token maps to the expected scope.

## Next steps

- [HTTP API v1 reference](./http-api.md)
- [Lifecycle & delivery concepts](../concepts/lifecycle-delivery.md)
- [Doctor observability](../operations/doctor-observability.md)
