---
route: "operations/deployment"
group: "operations"
order: 1
locale: "en"
nav: "Production deployment"
title: "Deploy distinct runtime roles"
lead: "Run one validated FileWeft artifact as four intentionally different roles — API, Outbox worker, Task worker and migration job — so each process gets only the credentials, configuration and blast radius it needs."
format: "markdown"
---

## What this page solves

Production FileWeft is not a single jar with every capability turned on. This page shows how to deploy the same verified `ai.icen:*:0.0.2` artifact as separate runtime roles that share database and object storage but never share privileges they do not need.

## Recommended topology

Think of a small control plane: one role writes schema, one serves traffic, one drains queues and one executes tasks.

| Role | Flyway mode | Typical credentials | Reason |
|------|-------------|---------------------|--------|
| Migration job | `migrate` | DDL-capable, short-lived | Schema changes happen once, under human control |
| API nodes | `validate` | Application read/write | Faces untrusted traffic; no queue polling |
| Outbox worker | `validate` | Queue + connector secrets | Invokes downstream systems; no HTTP listener |
| Task worker | `validate` | Task resources only | Runs background handlers; no HTTP listener |

> [!WARNING]
> Do not run long-lived API or Worker processes with schema-creation credentials. Connector credentials belong only to the Worker roles that actually invoke those connectors.

## Rollout order

1. Back up the database and verify a restore.
2. Stop conflicting writers; run the migration job as the sole owner of schema changes.
3. Start API and Worker roles in `validate` mode.
4. Observe `/fileweft/v1/health`, Doctor results, Outbox ready age and lease recovery.
5. Enable traffic only after validation succeeds.

## Per-role Spring Boot configuration

### API role

```yaml
# API role: no queue polling, no schema changes
fileweft:
  persistence:
    migration-mode: validate
    schema: fileweft
    create-schema: false
  worker:
    enabled: false
  upload:
    resumable-session-ttl-millis: 86400000
```

### Outbox worker role

```yaml
# Outbox worker role: drains queue and invokes connectors
fileweft:
  persistence:
    migration-mode: validate
  worker:
    enabled: true
    fixed-delay-millis: 1000
    outbox-batch-size: 50
    process-outbox: true
    process-tasks: false
    process-upload-cleanup: false
  sync:
    connector-timeout-millis: 30000
    connector-max-concurrent-invocations: 16
```

### Task worker role

```yaml
# Task worker role: executes background handlers
fileweft:
  persistence:
    migration-mode: validate
  worker:
    enabled: true
    task-batch-size: 50
    process-outbox: false
    process-tasks: true
    process-upload-cleanup: true
  task:
    lease-duration-millis: 60000
```

### Migration job role

```yaml
# Migration job role: one-shot schema change
fileweft:
  persistence:
    migration-mode: migrate
    schema: fileweft
    create-schema: true
  worker:
    enabled: false
```

FileWeft's startup initializer runs the migration, but it does not automatically terminate the Spring process after success. Adding these flags to an ordinary long-running Web host therefore does not make it a one-shot migration job. Provide a host-owned non-Web executable or dedicated profile that explicitly closes the Spring context and exits with status 0 after initialization succeeds, for example:

```bash
java -jar fileweft-migration-job-0.0.2.jar \
  --spring.main.web-application-type=none \
  --fileweft.persistence.migration-mode=migrate \
  --fileweft.worker.enabled=false
```

`fileweft-migration-job-0.0.2.jar` is an illustrative host-provided executable, not a standalone application published by FileWeft. The command is a true one-shot job only when the host implements that explicit post-migration exit.

## Health and readiness checks

Use the formal HTTP surface, not Dev-only `/api/**` endpoints:

```bash
# Liveness
curl -sf http://api:8080/fileweft/v1/health

# Document-level Doctor (after authentication)
curl -sf http://api:8080/fileweft/v1/documents/doc_123/doctor \
  -H "Authorization: Bearer ${HOST_TOKEN}" \
  -H "Idempotency-Key: $(uuidgen)"
```

## Credential boundaries

- Browser clients never receive object-storage credentials or downstream secrets.
- API nodes do not need connector secrets.
- Worker nodes do not open HTTP ports if they do not serve traffic.
- Use separate database identities for DDL (migration job) and DML (runtime roles).

## FAQ

**Can I run all roles in one process for local development?**
Yes, but only with reviewed single-tenant or development fallbacks. Do not present that setup as a production multi-tenant solution.

**What happens if a Worker starts before the migration job finishes?**
It will fail `validate` mode and exit. This is intentional: no runtime process silently runs against an unknown schema version.

## Next steps

- Learn how Doctor and metrics guide daily operations: [Doctor & observability](doctor-observability)
- See the full configuration map: [Configuration](../reference/configuration)
- Understand the migration namespace before your first release: [Migrations & releases](migrations-release)
