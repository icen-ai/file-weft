---
route: "architecture/consistency"
group: "architecture"
order: 1
locale: "en"
nav: "Consistency model"
title: "Local atomicity, explicit convergence"
lead: "FileWeft does not promise a distributed transaction across PostgreSQL, object storage, and downstream systems. It keeps every local state change atomic and makes remote convergence observable, retryable, and safe to reason about."
format: "markdown"
---

## The problem

A document publish operation touches at least three independent systems:

1. PostgreSQL stores the document, version, workflow, and delivery records.
2. Object storage holds the immutable asset bytes.
3. One or more downstream connectors propagate the document to search indexes, compliance archives, or AI pipelines.

If any step fails halfway through, a naive distributed transaction would either lock all three systems or leave them inconsistent. FileWeft avoids both by making the local database the single source of truth and treating every remote call as an eventually consistent projection.

## Core idea: local atomicity first

The only transaction FileWeft trusts is the local PostgreSQL transaction. Everything else is modeled as a durable event that the framework converges later.

| What you see | Guarantee |
| --- | --- |
| Document state in PostgreSQL | Strongly consistent inside the local transaction |
| Object bytes in storage | Compensated or referenced, never the source of truth |
| Connector delivery | At-least-once, idempotent, observable via Outbox |

> **NOTE**
> FileWeft never invokes a downstream connector inside a database transaction. The transaction only writes the Outbox record; workers execute the remote call afterwards.

## The Transactional Outbox pattern

A publish command follows four explicit stages:

1. **Validate and prepare** — check permissions, lifecycle rules, and connector profiles.
2. **Write local truth** — commit the document version, delivery records, and one or more Outbox events in a single PostgreSQL transaction.
3. **Worker claims with lease** — the async worker picks up the Outbox record using a time-bound lease so multiple nodes never race.
4. **Connector converges** — the worker calls the connector with a stable idempotency identity and records the result.

```yaml
fileweft:
  worker:
    enabled: true
    fixed-delay-millis: 1000
    outbox-batch-size: 50
    process-outbox: true
  outbox:
    lease-duration-millis: 300000
    legacy-running-grace-millis: 300000
```

```kotlin
// The worker invokes connectors outside the business transaction.
interface FileConnector {
    fun sync(request: ConnectorSyncRequest): ConnectorSyncResult
    fun remove(request: ConnectorRemoveRequest): ConnectorSyncResult
    fun health(): ConnectorHealth
}
```

> **TIP**
> A connector implementation should treat the stable idempotency identity inside `ConnectorSyncRequest` as the key. Replaying the same request must produce the same logical outcome, even if the previous HTTP response was lost.

## Storage compensation

Uploads create bytes before the business record exists. FileWeft compensates storage when it can prove the bytes are unreferenced:

| Scenario | Action |
| --- | --- |
| Bytes fail validation (malware, hash mismatch, unsupported format) | Delete the object immediately. |
| Local transaction rolls back and no durable reference was written | Delete the object safely. |
| Commit outcome is unknown (network partition, JVM crash) | Reconcile first, preserve evidence, never guess. |

> **WARNING**
> Do not build a cleanup job that deletes objects by age alone. Always reconcile against PostgreSQL references first; otherwise you risk destroying data whose commit record was delayed.

## Lock order inside a command

Guarded commands acquire locks in a stable order to avoid deadlocks across concurrent publishes, reviews, and lifecycle transitions:

```
idempotency claim → document → asset → workflow
```

External calls to catalog providers, review-route providers, and delivery policies happen **outside** this final short transaction. They may influence the command, but they do not participate in the lock sequence.

## Convergence states for delivery

When a document is published, FileWeft fans out to every target in the active sync profile:

| Required target | Optional target | Final document state |
| --- | --- | --- |
| All succeed | Any outcome | `PUBLISHED` |
| One fails and is retryable | Not decisive | `SYNC_ERROR`, worker keeps retrying |
| One fails permanently | Not decisive | Transition fails, document stays in prior state |
| Any outcome | One fails | Still `PUBLISHED`; failure is recorded for operator triage |

> **NOTE**
> FileWeft never rolls back a connector that already succeeded. Delivery is append-only: successful targets stay published, failed targets are retried or reported.

## Observing convergence

Use the Doctor endpoint or Outbox metrics to observe whether the system has converged:

```bash
# Check the document-level Doctor report
curl http://localhost:8080/fileweft/v1/documents/{documentId}/doctor

# Inspect Outbox backlog via metrics (no tenantId or documentId labels)
curl http://localhost:8080/actuator/metrics/fileweft.outbox_backlog
```

The `fileweft.outbox_backlog` gauge exposes states such as `ready`, `delayed`, `running`, `expired`, and `failed`. A sustained non-zero `expired` or `failed` count means a connector or worker needs attention.

## FAQ

**Q: Does FileWeft support two-phase commit across object storage and PostgreSQL?**

No. FileWeft commits PostgreSQL first and converges object storage and connectors afterwards. This avoids holding locks on remote systems and keeps the architecture operational under partitions.

**Q: What happens if a worker crashes after committing the connector but before marking the Outbox record done?**

The next worker that claims the lease will replay the connector call. Because connectors must be idempotent, the second call is safe and produces the same logical result.

**Q: Can I call a connector synchronously from my application service?**

You should not. Application services write Outbox events; workers call connectors. This preserves the local-atomicity guarantee and keeps external failures from rolling back business transactions.

## Next steps

- [Security architecture](/architecture/security) — how FileWeft fails closed when boundaries are incomplete.
- [Event-driven delivery](/architecture/event-driven) — the Outbox, worker, and task-handling mechanics in detail.
- [Resumable upload](/guides/resumable-upload) — a concrete protocol that relies on the same local-atomicity model.
