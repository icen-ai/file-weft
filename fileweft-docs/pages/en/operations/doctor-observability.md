---
route: "operations/doctor-observability"
group: "operations"
order: 2
locale: "en"
nav: "Doctor & observability"
title: "Operate from evidence"
lead: "Diagnose FileWeft without guessing: Doctor runs safe, authorized checks; metrics expose bounded trends; audit logs and trace IDs carry resource evidence without leaking high-cardinality labels."
format: "markdown"
---

## What this page solves

When uploads stall, deliveries fail or workers drift, you need three independent signals: a focused diagnosis, aggregate metrics and resource-level evidence. This page shows how FileWeft keeps those signals separated, bounded and actionable.

## Three Doctor paths

| Path | Endpoint | Use when | Authorization |
|------|----------|----------|---------------|
| Immediate document | `GET /documents/{id}/doctor` | A single document looks wrong and you want an interactive answer | Document read + catalog visibility |
| Asynchronous document | `POST /documents/{id}/doctor/tasks` | The check is expensive, crosses workers, or must be durable | Document read + catalog visibility |
| System | `GET /doctor` | You want runtime health across the tenant | `system:doctor:read` |

A `DoctorChecker` must be side-effect free and return an actionable result instead of throwing exceptions:

```kotlin
import ai.icen.fw.core.result.DoctorCheckResult
import ai.icen.fw.core.result.DoctorStatus
import ai.icen.fw.spi.doctor.DoctorChecker

@Component
class StorageDoctorChecker(private val storageAdapter: StorageAdapter) : DoctorChecker {

    override fun name(): String = "storage"

    override fun check(context: DoctorCheckContext): DoctorCheckResult {
        val location = StorageObjectLocation("s3", "probe/${context.tenantId.value}/doctor-${UUID.randomUUID()}")
        return try {
            storageAdapter.exists(location)
            DoctorCheckResult(name(), DoctorStatus.HEALTHY, "Storage location is reachable.")
        } catch (e: Exception) {
            DoctorCheckResult(
                name(),
                DoctorStatus.ERROR,
                "Storage check failed: ${e.message}",
                repairSuggestion = "Verify storage credentials, network path and bucket policy."
            )
        }
    }
}
```

## Calling Doctor

### Immediate document check

```bash
curl -sf http://api:8080/fileweft/v1/documents/doc_123/doctor \
  -H "Authorization: Bearer ${HOST_TOKEN}" \
  -H "Idempotency-Key: $(uuidgen)"
```

Response envelope:

```json
{
  "code": "OK",
  "message": "OK",
  "data": {
    "documentId": "doc_123",
    "checks": [
      { "name": "storage", "status": "HEALTHY", "detail": "Storage location is reachable." },
      { "name": "lifecycle", "status": "HEALTHY", "detail": "Lifecycle state is consistent." }
    ]
  },
  "error": null,
  "traceId": "abc-123"
}
```

### Asynchronous document check

```bash
# Schedule
curl -sf -X POST http://api:8080/fileweft/v1/documents/doc_123/doctor/tasks \
  -H "Authorization: Bearer ${HOST_TOKEN}" \
  -H "Idempotency-Key: $(uuidgen)"

# Poll
TASK_ID="task_456"
curl -sf http://api:8080/fileweft/v1/documents/doc_123/doctor/tasks/${TASK_ID} \
  -H "Authorization: Bearer ${HOST_TOKEN}"
```

### System check

```bash
curl -sf http://api:8080/fileweft/v1/doctor \
  -H "Authorization: Bearer ${HOST_TOKEN}"
```

## Core metrics

All counters live under the `fileweft.` prefix. Do not attach tenant, document or user IDs to metric labels.

| Metric | Type | Meaning |
|--------|------|---------|
| `fileweft.upload_count` | Counter | Successful uploads |
| `fileweft.upload_failure` | Counter | Failed uploads |
| `fileweft.sync_success` | Counter | Successful connector deliveries |
| `fileweft.sync_failure` | Counter | Failed connector deliveries |
| `fileweft.delivery_removal_success` | Counter | Successful removal acknowledgments |
| `fileweft.delivery_removal_failure` | Counter | Failed removal acknowledgments |
| `fileweft.doctor_failure` | Counter | Doctor checks that returned failure |
| `fileweft.task_success` | Counter | Successful task executions |
| `fileweft.task_failure` | Counter | Failed task executions |
| `fileweft.outbox_backlog` | Gauge | Ready, delayed, running, expired and failed outbox rows |
| `fileweft.outbox_oldest_ready_age_seconds` | Gauge | Age of the oldest ready outbox row |
| `fileweft.outbox_backlog_observation_failure` | Gauge | Failed metric observation attempts |

> [!TIP]
> Use `state` as the only label on `fileweft.outbox_backlog`. Resource-level investigation belongs in audit logs and traces, not in metric cardinality.

## Example PromQL alerts

```promql
# Deliveries are failing repeatedly
rate(fileweft.sync_failure[5m]) > 0.1

# Outbox is backing up
fileweft.outbox_backlog{state="ready"} > 1000

# Oldest ready row is aging
fileweft.outbox_oldest_ready_age_seconds > 300
```

## Audit and trace

Every formal API response includes a `traceId`. Use it to correlate:

- Application logs from API and Worker roles.
- Database audit rows in `fileweft.*_log` tables.
- Connector invocation records.

> [!NOTE]
> Download endpoints return binary streams with `private, no-store` caching headers. They do not support Range, HEAD, ETag or pre-signed storage URLs.

## FAQ

**Should I expose `/fileweft/v1/doctor` to all users?**
No. System Doctor requires `system:doctor:read`. Document Doctor respects document-level authorization.

**Why can't I see tenant IDs in metrics?**
Tenant, document and user identifiers are high-cardinality or sensitive. Metrics expose bounded trends; use audit logs and trace IDs for per-resource evidence.

## Next steps

- Read the full HTTP API surface: [HTTP API v1](../reference/http-api)
- Build your own checker: [Implement a durable task handler](../guides/agent-handler)
- Plan safe releases: [Migrations & releases](migrations-release)
