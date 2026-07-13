---
route: "concepts/lifecycle-delivery"
group: "concepts"
order: 2
locale: "en"
nav: "Lifecycle & delivery"
title: "Lifecycle & delivery"
lead: "A published document is not a single checkbox: it is a chain of evidence that must survive review, partial downstream failures, and later removal. This page explains FileWeft's state machine, how delivery to multiple systems is tracked, and why generation fencing matters."
format: "markdown"
---

## 01. The document state machine

A document moves through explicit states. Each transition is a command, not a raw field update.

| State | Meaning | Typical transition |
|---|---|---|
| `DRAFT` | Editable working copy | initial state |
| `PENDING_REVIEW` | Awaiting approval | `submit` from `DRAFT` |
| `REJECTED` | Returned for correction | `reject` from `PENDING_REVIEW` |
| `PUBLISHING` | Approved; delivery to downstream targets is in flight | internal transition after approval |
| `PUBLISHED` | All required targets have succeeded | `publish` command succeeds |
| `SYNC_ERROR` | One or more required targets failed | `sync_failed` from `PUBLISHING` |
| `OFFLINE` | Publicly removed but still recoverable | `offline` from `PUBLISHED` |
| `HISTORY` | Archived; not intended for further change | `archive` from `PUBLISHED` |

The happy path is:

```
DRAFT → PENDING_REVIEW → PUBLISHING → PUBLISHED
```

Rework uses `REJECTED → revise → DRAFT`. A published document can be taken `OFFLINE` and then restored to `DRAFT`. Archive is explicit and moves the document to `HISTORY`.

## 02. Lifecycle commands over HTTP

Controllers do not edit state fields directly. The caller sends a command, and FileWeft applies the domain rule.

Submit a draft for review:

```bash
curl -X POST "https://api.example.com/fileweft/v1/documents/{documentId}/submit" \
     -H "Authorization: Bearer $TOKEN"
```

Approve the review task:

```bash
curl -X POST "https://api.example.com/fileweft/v1/workflows/{workflowId}/tasks/{taskId}/approve" \
     -H "Authorization: Bearer $TOKEN"
```

Publish, offline, restore and archive follow the same pattern:

```bash
curl -X POST "https://api.example.com/fileweft/v1/documents/{documentId}/publish"  -H "Authorization: Bearer $TOKEN"
curl -X POST "https://api.example.com/fileweft/v1/documents/{documentId}/offline"  -H "Authorization: Bearer $TOKEN"
curl -X POST "https://api.example.com/fileweft/v1/documents/{documentId}/restore"  -H "Authorization: Bearer $TOKEN"
curl -X POST "https://api.example.com/fileweft/v1/documents/{documentId}/archive"  -H "Authorization: Bearer $TOKEN"
```

> [!NOTE]
> State change, audit log and Outbox events are committed in the same business transaction. If the transaction rolls back, no event is emitted.

## 03. Multi-target delivery

A single document often needs to reach more than one downstream system: a compliance archive, a search index, a content delivery network. FileWeft tracks each target separately.

| Target kind | Required? | Failure impact |
|---|---|---|
| Required | yes | Blocks `PUBLISHED`; document enters `SYNC_ERROR` and retries |
| Optional | no | Document can still become `PUBLISHED`; failure is recorded and can be retried |

Rules that protect downstream reliability:

1. **All required targets must succeed** before the document becomes `PUBLISHED`.
2. **Optional failures do not block publication.** The document is published and the failing target remains retryable.
3. **Successful targets are never rolled back.** If a later target fails, FileWeft does not undo the ones that already succeeded.
4. **Operators retry only the failed target.** Use the delivery retry endpoint, not a full republication.

## 04. Generation fencing

Each approval creates a new **delivery generation**. If a slow connector returns a result for generation 2 after generation 3 has already been published, that late result is ignored. It cannot overwrite the current state.

```
generation 1: draft-v1  →  published-v1
generation 2: draft-v2  →  published-v2  (current)
                ^ late response from generation 1 is discarded
```

Generation fencing prevents race conditions when multiple publishes happen close together.

## 05. The outbox keeps the transaction local

FileWeft never calls a downstream connector inside the database transaction that writes the document state. Instead it writes Outbox events to a PostgreSQL table in the same transaction, then a worker delivers them asynchronously.

```
Business transaction
        ↓
  Outbox event
        ↓
  Async worker
        ↓
   Connector
```

This gives three guarantees:

- The state change and the event are atomic on the local database.
- A connector crash cannot roll back the document state.
- Every target is retried independently.

## 06. Retry and removal

Check the current sync status:

```bash
curl "https://api.example.com/fileweft/v1/documents/{documentId}/sync-status" \
     -H "Authorization: Bearer $TOKEN"
```

Retry a failed delivery target:

```bash
curl -X POST "https://api.example.com/fileweft/v1/documents/{documentId}/deliveries/{deliveryId}/retry" \
     -H "Authorization: Bearer $TOKEN"
```

When a document is offlined or archived, FileWeft writes a `document.delivery.target.removal.requested` event for each previously delivered target. Retry a failed removal the same way:

```bash
curl -X POST "https://api.example.com/fileweft/v1/documents/{documentId}/deliveries/{deliveryId}/removal/retry" \
     -H "Authorization: Bearer $TOKEN"
```

## 07. Configure delivery targets

The `sync` section of `application.yml` declares which connectors are required and which are optional.

```yaml
fileweft:
  sync:
    connector-timeout-millis: 30000
    source-access-url-ttl-millis: 900000
    circuit-breaker-failure-threshold: 3
    circuit-breaker-open-duration-millis: 30000
    connector-max-concurrent-invocations: 16
    connector-invocation-queue-capacity: 256
    default-profile-id: regulated
    profiles:
      - id: regulated
        display-name: "Regulated publishing"
        targets:
          - id: compliance
            display-name: "Compliance archive"
            connector-id: complianceConnector
            required: true
            owner-ref: compliance-ops
          - id: search
            display-name: "Search index"
            connector-id: searchConnector
            required: false
            owner-ref: search-ops
```

> [!TIP]
> Give each target an `owner-ref`. When a delivery sticks in `SYNC_ERROR`, the operator knows exactly who to page.

## FAQ

**Q: Can I publish a document without any targets?**
Yes, if the configured profile has no required targets and the host allows it. FileWeft still records the publish event and audit log.

**Q: What happens if a required target is permanently down?**
The document stays in `SYNC_ERROR`. FileWeft continues to retry according to the task and outbox policy. The operator can also trigger a manual retry once the connector is healthy.

**Q: Does archiving undo deliveries?**
No. Archive writes removal requests, but it does not guarantee immediate removal. Each target's connector processes removal independently.

## Next steps

- [Outbox pattern](./outbox.md) — how FileWeft avoids dual writes between PostgreSQL and external systems.
- [Connectors](../extensions/connectors.md) — implement `FileConnector` with timeout, retry and idempotency.
- [Doctor & observability](../operations/doctor-observability.md) — diagnose stuck deliveries and connector health.
