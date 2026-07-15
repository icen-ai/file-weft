---
route: "concepts/outbox"
group: "concepts"
order: 5
locale: "en"
nav: "Outbox & workers"
title: "Outbox: never call downstream inside a transaction"
lead: "The fastest way to lose consistency is to send an HTTP request inside a database commit. FlowWeft writes events to an outbox table in the same business transaction, then lets workers deliver them asynchronously. This page explains the pattern, the worker configuration, and how to observe backlog."
format: "markdown"
---

## 01. Why the outbox pattern

A document publish needs two things to happen:

1. Update the document state in PostgreSQL.
2. Notify downstream connectors (compliance archive, search index, CDN).

If you call the connector inside the database transaction, a slow network can roll back the transaction or leave the commit and the call inconsistent. The outbox pattern solves this by making the local transaction the single source of truth.

```
Business transaction writes state + outbox rows
                    ↓
            Transaction commits
                    ↓
        Worker picks up ready events
                    ↓
        Handler delivers to connector
```

The rule is simple: **local atomic, explicit convergence**. FlowWeft promises atomicity on the local database; it does not promise a distributed transaction across PostgreSQL, object storage and downstream systems.

## 02. Event lifecycle

A committed outbox event moves through several states:

1. **Ready** — written in the same transaction as the business state change.
2. **Leased** — a worker takes ownership for a bounded time (`lease-duration-millis`).
3. **Succeeded** — the handler returns `SUCCEEDED`.
4. **Retryable failure** — the handler returns `RETRYABLE_FAILURE`; the event is released with a back-off.
5. **Permanent failure** — retries are exhausted; `onExhausted` is invoked and the event is parked.

> [!TIP]
> Handlers must be idempotent. A worker may resume or retry an event after a lease expires, so processing the same event twice must be safe.

## 03. Worker configuration

Workers are enabled by default in `application.yml`. The same process can run outbox, task and upload-cleanup workers, or you can disable roles per instance.

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
  outbox:
    lease-duration-millis: 300000
    legacy-running-grace-millis: 300000
    backlog-metrics-enabled: true
    backlog-metrics-interval-millis: 30000
    backlog-metrics-query-timeout-seconds: 5
  task:
    lease-duration-millis: 60000
    legacy-running-grace-millis: 300000
```

> [!WARNING]
> Do not set `lease-duration-millis` too low. If a handler runs longer than the lease, another worker may pick the same event up and you will rely entirely on idempotency.

## 04. Writing an outbox event handler

Outbox event handlers live in adapters and are registered through `FileWeftPlugin`. They must be idempotent for the event identifier.

```kotlin
package ai.icen.fw.adapter.compliance

import ai.icen.fw.core.event.OutboxEvent
import ai.icen.fw.spi.event.OutboxEventHandler
import ai.icen.fw.spi.event.OutboxHandlingResult
import ai.icen.fw.spi.event.OutboxHandlingStatus
import org.springframework.stereotype.Component
import java.io.IOException

@Component
class ComplianceSyncHandler(
    private val complianceClient: ComplianceClient
) : OutboxEventHandler {

    override fun supports(event: OutboxEvent): Boolean =
        event.type == "document.delivery.target.sync.requested"

    override fun handle(event: OutboxEvent): OutboxHandlingResult {
        val location = event.payload["location"]
            ?: return OutboxHandlingResult(
                OutboxHandlingStatus.PERMANENT_FAILURE,
                "missing location"
            )

        return try {
            complianceClient.archive(location)
            OutboxHandlingResult(OutboxHandlingStatus.SUCCEEDED)
        } catch (e: IOException) {
            // Network blip; let the worker retry.
            OutboxHandlingResult(OutboxHandlingStatus.RETRYABLE_FAILURE, e.message)
        } catch (e: IllegalArgumentException) {
            // Bad payload; do not retry forever.
            OutboxHandlingResult(OutboxHandlingStatus.PERMANENT_FAILURE, e.message)
        }
    }

    override fun onExhausted(event: OutboxEvent, message: String) {
        // Persist local state only. Do not start a new external side effect here.
        complianceClient.recordExhausted(event.id, message)
    }
}
```

The handler returns one of three statuses:

| Status | Meaning | Next action |
|---|---|---|
| `SUCCEEDED` | Side effect confirmed | Event is marked complete |
| `RETRYABLE_FAILURE` | Transient problem | Worker retries with back-off |
| `PERMANENT_FAILURE` | Unrecoverable problem | `onExhausted` is called, event is parked |

## 05. Tasks use the same idempotency rule

Some background work is not tied to an outbox event. Implement `FileWeftTaskHandler` for durable tasks such as cleanup or aggregation.

```kotlin
@Component
class ArchiveCleanupHandler : FileWeftTaskHandler {

    override fun supports(task: TaskExecution): Boolean =
        task.type == "archive.cleanup"

    override fun handle(task: TaskExecution): TaskHandlingResult {
        if (cleanupLog.alreadyProcessed(task.id)) {
            return TaskHandlingResult(TaskHandlingStatus.SUCCEEDED)
        }
        cleanupLog.process(task.id, task.payload)
        return TaskHandlingResult(TaskHandlingStatus.SUCCEEDED)
    }

    override fun onExhausted(task: TaskExecution, message: String) {
        logger.error { "Archive cleanup exhausted for task ${task.id}: $message" }
    }
}
```

## 06. Observing backlog

FlowWeft exposes outbox backlog through Micrometer / Prometheus gauges:

| Metric | Meaning |
|---|---|
| `fileweft.outbox_backlog` | Count of events by state: `ready`, `delayed`, `running`, `expired`, `failed` |
| `fileweft.outbox_oldest_ready_age_seconds` | Age of the oldest ready event |
| `fileweft.outbox_backlog_observation_failure` | Count of failed backlog queries |

> [!NOTE]
> Metric tags must never include `tenantId`, document IDs or user IDs. Use low-cardinality, non-sensitive tags such as `state` or `handler`.

A healthy system shows `ready` near zero and `running` bounded by your worker count. A growing `failed` state means `onExhausted` handlers or operators need attention.

## 07. Do's and don'ts

| Do | Don't |
|---|---|
| Write outbox events in the same transaction as business state | Call a connector inside a `@Transactional` business method |
| Make handlers idempotent by event or task id | Assume an event is processed exactly once |
| Return `RETRYABLE_FAILURE` for transient errors | Retry forever on a malformed payload |
| Use `onExhausted` only for local state / alerting | Start a new external side effect from `onExhausted` |
| Monitor `fileweft.outbox_backlog` and age | Ignore a growing `failed` queue |

## FAQ

**Q: Is the outbox table visible to application code?**
No. Application code emits events through domain and application services. The outbox table is owned by `persistence` and read by the worker.

**Q: Can I disable workers and call connectors synchronously?**
You can disable `process-outbox`, but then nothing delivers the events. There is no supported synchronous path that bypasses the outbox, because that would re-introduce the dual-write problem.

**Q: What happens if a handler keeps returning retryable failures?**
The event is retried with back-off until the retry limit is reached, then it is parked and `onExhausted` is invoked. Operators can inspect it through the outbox table or observability tooling.

## Next steps

- [Lifecycle & delivery](./lifecycle-delivery.md) — how document publish maps to outbox events.
- [Connectors](../extensions/connectors.md) — implement `FileConnector` with timeout, retry and idempotency.
- [Doctor & observability](../operations/doctor-observability.md) — diagnose backlog and worker health.
