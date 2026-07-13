---
route: "reference/configuration"
group: "reference"
order: 3
locale: "en"
nav: "Configuration"
title: "Configuration reference"
lead: "FileWeft production defaults are conservative: schema validation, no implicit tenant, no local storage, and no hidden migrations. Enable every fallback and runtime role deliberately."
format: "markdown"
---

## How to read this page

Configuration is grouped by subsystem. Each key shown below uses the YAML form; the same keys work as `fileweft.<group>.<key>` in `.properties` files.

> [!TIP]
> Start with the minimal production YAML at the bottom of this page, then add adapters and connector profiles for your environment.

## Persistence

Controls Flyway migrations and the database schema.

```yaml
fileweft:
  persistence:
    migration-mode: validate # migrate | validate | disabled
    schema: fileweft
    create-schema: false
```

| Value | When to use |
|-------|-------------|
| `validate` | Production and CI. FileWeft checks that the schema matches the expected version but does not change it. |
| `migrate` | Fresh installations or local development where the process may own schema creation. |
| `disabled` | External schema management or blue/green deployments where migrations run out-of-band. |

## Worker

The worker processes Outbox events, scheduled tasks, and upload cleanup.

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

> [!NOTE]
> `fixed-delay-millis` is the quiet period between polling rounds, not a deadline for individual handlers.

## Outbox

Outbox leases prevent multiple workers from processing the same event.

```yaml
fileweft:
  outbox:
    lease-duration-millis: 300000
    legacy-running-grace-millis: 300000
    backlog-metrics-enabled: true
    backlog-metrics-interval-millis: 30000
    backlog-metrics-query-timeout-seconds: 5
```

## Task

Background tasks use their own lease so a crashed worker does not hold events forever.

```yaml
fileweft:
  task:
    lease-duration-millis: 60000
    legacy-running-grace-millis: 300000
```

## Sync and delivery profiles

A profile groups downstream targets. Required targets block publication until they succeed; optional targets may fail without blocking.

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
        display-name: Regulated publishing
        targets:
          - id: compliance
            display-name: Compliance archive
            connector-id: complianceConnector
            required: true
            owner-ref: compliance-ops
          - id: search
            display-name: Search index
            connector-id: searchConnector
            required: false
            owner-ref: search-ops
```

| Field | Meaning |
|-------|---------|
| `connector-id` | The Spring bean name of a `FileConnector` implementation |
| `required` | If `true`, failure keeps the document in `SYNC_ERROR` and blocks `PUBLISHED` |
| `owner-ref` | Free-form operational owner shown in sync status and Doctor output |

## Upload

Resumable upload sessions are durable but expire after a configured TTL.

```yaml
fileweft:
  upload:
    resumable-session-ttl-millis: 86400000
    resumable-cleanup-batch-size: 100
```

## Development fallbacks

These properties are convenient for fixed single-tenant or single-node development. They are not a production multi-tenant strategy.

```properties
fileweft.default-tenant-enabled=true
fileweft.default-tenant-id=tenant-a
fileweft.storage.local-enabled=true
fileweft.storage.local-root=/var/lib/fileweft
```

> [!WARNING]
> `default-tenant-enabled` and `local-enabled` are reviewed development shortcuts. Doctor reports them as warnings. Do not use them in multi-node production deployments.

## Minimal production YAML

```yaml
fileweft:
  persistence:
    migration-mode: validate
    schema: fileweft
    create-schema: false
  worker:
    enabled: true
    process-outbox: true
    process-tasks: true
    process-upload-cleanup: true
  outbox:
    lease-duration-millis: 300000
    backlog-metrics-enabled: true
  task:
    lease-duration-millis: 60000
  sync:
    connector-timeout-millis: 30000
    source-access-url-ttl-millis: 900000
    default-profile-id: regulated
    profiles:
      - id: regulated
        display-name: Regulated publishing
        targets:
          - id: compliance
            display-name: Compliance archive
            connector-id: complianceConnector
            required: true
            owner-ref: compliance-ops
  upload:
    resumable-session-ttl-millis: 86400000
```

## Frequently asked questions

**Should I use `migrate` in production?**
Usually no. Run migrations during deployment with `validate` at runtime so the application fails fast if the schema is out of sync.

**Can I disable the worker?**
Yes, but Outbox events and scheduled tasks will not progress. Disable only when an external worker process owns the same database.

**What happens if no sync profile is configured?**
Publication succeeds locally but no downstream delivery is attempted. Add at least one profile to integrate with external systems.

## Next steps

- [Implement a storage adapter](../guides/storage-adapter.md)
- [Build a connector](../extensions/connectors.md)
- [Read the SPI overview](./spi.md)
