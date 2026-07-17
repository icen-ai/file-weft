---
route: "operations/troubleshooting"
group: "operations"
order: 4
locale: "en"
nav: "Troubleshooting"
title: "Diagnose common symptoms"
lead: "When FlowWeft behaves unexpectedly, follow a structured checklist — check Doctor, metrics, outbox state and trace IDs before changing code or configuration."
format: "markdown"
---

## What this page solves

Errors in a file system span uploads, storage, approval, delivery and background workers. This page maps common symptoms to the first investigations an operator should run.

## Symptom matrix

| Symptom | First check | Likely cause |
|---------|-------------|--------------|
| Upload returns 400 or 409 | Idempotency key and part number | Reused key with different payload, or part out of order |
| Upload part fails mid-stream | Worker / storage health | Storage adapter timeout or connection reset |
| Document stuck in `PENDING_REVIEW` | Workflow tasks inbox | Missing approval or rejected task |
| Publish returns `SYNC_ERROR` | Delivery sync status | Required connector failed; optional failures do not block publish |
| Published document missing downstream | Connector health and logs | Connector returned retryable failure |
| Worker CPU low but backlog grows | Outbox gauges and leases | Lease expired without worker claiming rows |
| `/fileweft/v1/health` returns error | Migration mode and schema | Schema version mismatch in `validate` mode |
| 403 on every call | Tenant provider and authorization SPI | Missing or untrusted tenant context |

## Upload failures

### Verify the session

```bash
curl -sf http://api:8080/fileweft/v1/uploads/${UPLOAD_ID} \
  -H "Authorization: Bearer ${HOST_TOKEN}"
```

Look for:

- `uploadId` exists and belongs to the caller's tenant.
- `partSize`, `totalParts` and uploaded part numbers match.
- Session has not exceeded `resumable-session-ttl-millis`.

### Common mistakes

1. **Reusing an idempotency key with a different file.** The server stores a SHA-256 digest of the payload; a mismatched replay returns `CONFLICT`.
2. **Uploading parts out of order.** Parts can be retried, but `complete` expects every part number from 1 to total to exist.
3. **Sending the wrong `Content-Length`.** The adapter trusts the declared length for multipart boundaries.

## Sync stuck or failed

### Check delivery status

```bash
curl -sf http://api:8080/fileweft/v1/documents/doc_123/sync-status \
  -H "Authorization: Bearer ${HOST_TOKEN}"
```

Response fields to read:

- `targets[].state` — `SUCCESS`, `PENDING`, `FAILED`, `REMOVAL_PENDING`
- `targets[].lastError` — safe, connector-supplied error summary
- `document.state` — `PUBLISHED`, `SYNC_ERROR`, `OFFLINE`

### Retry a failed target

```bash
curl -sf -X POST \
  http://api:8080/fileweft/v1/documents/doc_123/deliveries/dlv_456/retry \
  -H "Authorization: Bearer ${HOST_TOKEN}" \
  -H "Idempotency-Key: $(uuidgen)"
```

> [!NOTE]
> FlowWeft never rolls back already-succeeded targets. Required target failures move the document to `SYNC_ERROR`; optional failures leave it `PUBLISHED` with recorded errors.

## Outbox backlog

Query the backlog through metrics, not by scanning tables:

```promql
fileweft.outbox_backlog{state="ready"}
fileweft.outbox_backlog{state="running"}
fileweft.outbox_oldest_ready_age_seconds
```

If `running` rows are stuck:

1. Check whether the worker process is alive.
2. Verify the worker has `process-outbox: true`.
3. Look for lease-duration exceeded; a crashed worker leaves rows in `running` until `legacy-running-grace-millis` expires.

## Doctor failures

Run document-level Doctor before guessing:

```bash
curl -sf http://api:8080/fileweft/v1/documents/doc_123/doctor \
  -H "Authorization: Bearer ${HOST_TOKEN}" \
  -H "Idempotency-Key: $(uuidgen)"
```

If a checker returns `UNHEALTHY`:

1. Read its `detail` field — it must be actionable.
2. Check the corresponding metric: `fileweft.doctor_failure`.
3. Look at logs for the checker `name` and the `traceId` from the response.

## Authentication and 403

FlowWeft fails closed. A 403 usually means one of:

- `TenantProvider.currentTenant()` returned an untrusted or missing context.
- `AuthorizationProvider.authorize()` denied the action on the resource.
- The user lacks catalog visibility for the document's folder.

Verify in the host:

```kotlin
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.authorization.AuthorizationAction
import ai.icen.fw.spi.authorization.AuthorizationRequest
import ai.icen.fw.spi.authorization.AuthorizationResource
import ai.icen.fw.spi.authorization.AuthorizationSubject
import ai.icen.fw.spi.authorization.AuthorizationEnvironment

val tenant = tenantProvider.currentTenant()
val user = userRealmProvider.currentUser()
    ?: throw IllegalStateException("No current user")
val decision = authorizationProvider.authorize(
    AuthorizationRequest(
        subject = AuthorizationSubject(id = user.id, type = "user"),
        resource = AuthorizationResource(
            id = Identifier("doc_123"),
            type = "document",
            tenantId = tenant.id
        ),
        action = AuthorizationAction("document:read"),
        environment = AuthorizationEnvironment()
    )
)
```

> [!WARNING]
> Never trust a tenant ID directly from request parameters. Tenant context must come from the host's trusted identity system.

## FAQ

**Why are my workers not picking up tasks?**
Confirm the role has `process-tasks: true`, the lease duration is not shorter than handler execution time, and there is only one active task worker cluster per tenant-scoped queue.

**Can I delete failed outbox rows to clear the alarm?**
No. Failed rows are evidence. Retry the root cause; use the formal retry endpoints or wait for the worker's backoff and exhaustion handling.

## Next steps

- Understand the runtime roles: [Production deployment](deployment)
- Read all available metrics: [Doctor & observability](doctor-observability)
- Review the HTTP API error codes: [HTTP API v1](../reference/http-api)
