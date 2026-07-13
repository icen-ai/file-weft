---
route: "architecture/event-driven"
group: "architecture"
order: 3
locale: "en"
nav: "Event-driven delivery"
title: "Event-driven delivery with Outbox and workers"
lead: "FileWeft turns every external side effect into a durable, tenant-scoped event. Workers process those events at-least-once, connectors converge downstream systems, and operators can observe the whole pipeline without peeking at internals."
format: "markdown"
---

## The problem

Publishing a document often means touching a search index, a compliance archive, an AI knowledge base, or all three. If the application service made those calls directly:

- A downstream timeout would roll back a successful business transaction.
- A partial failure would leave some systems updated and others stale.
- A retry would duplicate data because the original response was lost.

FileWeft solves this by moving every external side effect behind an Outbox and a set of asynchronous workers.

## The event flow

A publish command does not call connectors. It writes events and lets the worker pool converge the world:

```
Business transaction
    ↓
Outbox event (same PostgreSQL transaction)
    ↓
Async worker claims with lease
    ↓
Connector sync/remove call
    ↓
Result written back to delivery record
```

Because the event and the business record commit together, the local state is always consistent. The worker may lag, but it will never observe an event whose business transaction did not commit.

## Outbox events

Outbox events are small, typed records that describe what must happen outside the transaction. Examples include:

| Event type | Trigger | Handler |
| --- | --- | --- |
| `document.delivery.target.sync.requested` | Document published to a profile target | Worker calls `FileConnector.sync` |
| `document.delivery.target.removal.requested` | Document offlined or archived | Worker calls `FileConnector.remove` |
| `document.lifecycle.transition.requested` | State machine needs external confirmation | Lifecycle guard or worker |

Events carry a stable idempotency identity, a tenant context, and a reference to the owning document. They do **not** carry storage URLs, connector credentials, or arbitrary diagnostic text.

```yaml
fileweft:
  outbox:
    lease-duration-millis: 300000
    legacy-running-grace-millis: 300000
    backlog-metrics-enabled: true
    backlog-metrics-interval-millis: 30000
    backlog-metrics-query-timeout-seconds: 5
```

> **NOTE**
> Outbox records are committed in the same PostgreSQL transaction as the business write. If the transaction rolls back, the event disappears with it.

## Worker configuration

The worker is a single background component that claims Outbox records and task records in batches:

```yaml
fileweft:
  worker:
    enabled: true
    fixed-delay-millis: 1000
    outbox-batch-size: 50
    task-batch-size: 50
    process-outbox: true
    process-tasks: true
    process-upload-cleanup: true
```

| Property | Effect |
| --- | --- |
| `fixed-delay-millis` | Pause between polling rounds |
| `outbox-batch-size` | Maximum Outbox records claimed per round |
| `task-batch-size` | Maximum task records claimed per round |
| `process-upload-cleanup` | Removes orphaned resumable upload sessions |

Set `enabled: false` if you run workers on dedicated nodes and want the web nodes to stay read-only.

## Task handlers

Some side effects are too long or too important to run inline. FileWeft represents them as tasks and routes them to registered `FileWeftTaskHandler` beans:

```kotlin
interface FileWeftTaskHandler {
    fun supports(task: TaskExecution): Boolean
    fun handle(task: TaskExecution): TaskHandlingResult
    fun onExhausted(task: TaskExecution, message: String) = Unit
}
```

Tasks are processed with at-least-once semantics. The handler must be idempotent by `task.id`:

```kotlin
import ai.icen.fw.spi.task.FileWeftTaskHandler
import ai.icen.fw.spi.task.TaskExecution
import ai.icen.fw.spi.task.TaskHandlingResult
import ai.icen.fw.spi.task.TaskHandlingStatus
import org.springframework.stereotype.Component

@Component
class ComplianceArchiveTaskHandler : FileWeftTaskHandler {

    override fun supports(task: TaskExecution): Boolean =
        task.type == "compliance.archive"

    override fun handle(task: TaskExecution): TaskHandlingResult {
        // Use task.id as the idempotency key
        val alreadyDone = archiveDao.isCompleted(task.id)
        if (alreadyDone) {
            return TaskHandlingResult(TaskHandlingStatus.SUCCEEDED)
        }
        archiveDao.archive(task.payload)
        return TaskHandlingResult(TaskHandlingStatus.SUCCEEDED)
    }
}
```

```yaml
fileweft:
  task:
    lease-duration-millis: 60000
    legacy-running-grace-millis: 300000
```

> **TIP**
> Store the completion flag under the same `task.id` key you receive. Do not generate a new idempotency key inside the handler, because a retry will present the same `task.id` again.

## Connectors are idempotent projections

A connector receives a sync or remove request and returns one of three outcomes:

```kotlin
interface FileConnector {
    fun sync(request: ConnectorSyncRequest): ConnectorSyncResult
    fun remove(request: ConnectorRemoveRequest): ConnectorSyncResult
    fun health(): ConnectorHealth
}
```

| Result status | Meaning | Worker behavior |
| --- | --- | --- |
| `SUCCESS` | Downstream has converged | Mark delivery record complete |
| `RETRYABLE_FAILURE` | Temporary error | Retry with exponential backoff |
| `PERMANENT_FAILURE` | Logical error or rejected payload | Stop retrying, surface to operators |

Connectors must also implement health checks so FileWeft can degrade gracefully:

```kotlin
enum class ConnectorHealth { HEALTHY, DEGRADED, UNHEALTHY }
```

> **WARNING**
> A connector must never mutate its input request or rely on caller-side state. Each invocation should be self-contained and safe to replay.

## Observability

The framework exposes counters and gauges that let you watch the pipeline without adding tenant or document labels:

```bash
# Outbox backlog by state
curl -s http://localhost:8080/actuator/metrics/fileweft.outbox_backlog \
  | jq '.measurements[] | select(.statistic == "VALUE")'

# Sync successes and failures
curl -s http://localhost:8080/actuator/metrics/fileweft.sync_success
curl -s http://localhost:8080/actuator/metrics/fileweft.sync_failure
```

| Metric | Type | Labels |
| --- | --- | --- |
| `fileweft.outbox_backlog` | Gauge | `state`: ready, delayed, running, expired, failed |
| `fileweft.outbox_oldest_ready_age_seconds` | Gauge | none |
| `fileweft.sync_success` | Counter | none |
| `fileweft.sync_failure` | Counter | none |
| `fileweft.task_success` | Counter | none |
| `fileweft.task_failure` | Counter | none |

> **NOTE**
> Labels must not include `tenantId`, document IDs, or user IDs. FileWeft keeps metrics cardinality low and avoids exposing high-cardinality or sensitive dimensions.

## Example: publishing through the Outbox

1. A client calls `POST /fileweft/v1/documents/{documentId}/publish`.
2. The application service validates the command and writes the document state plus one Outbox event per sync target.
3. The worker claims the events and invokes each connector.
4. Connectors report `SUCCESS`, `RETRYABLE_FAILURE`, or `PERMANENT_FAILURE`.
5. The document transitions to `PUBLISHED` only when all required targets succeed.

```bash
# Trigger publish
curl -X POST http://localhost:8080/fileweft/v1/documents/fw-doc-123/publish

# Watch delivery status
curl http://localhost:8080/fileweft/v1/documents/fw-doc-123/sync-status
```

## FAQ

**Q: Can I lose an Outbox event?**

No, as long as the business transaction commits. The event is written in the same transaction as the business record. If PostgreSQL commits, the event is durable.

**Q: What happens if a connector is permanently unhealthy?**

The worker stops retrying after the configured policy and records a `PERMANENT_FAILURE`. Required-target failures block the document transition; optional-target failures are logged but do not block publication.

**Q: How do I add a new event type?**

Introduce a new Outbox event type and either a dedicated `FileWeftTaskHandler` or an `OutboxEventHandler` bean. Register it through a plugin or as a Spring bean. Keep the handler idempotent and tenant-aware.

## Next steps

- [Consistency model](/architecture/consistency) — why local atomicity matters and how storage compensates.
- [Security architecture](/architecture/security) — how tenant and authorization boundaries stay intact during event processing.
- [Storage adapter guide](/guides/storage-adapter) — implement the storage side of the same projection model.
