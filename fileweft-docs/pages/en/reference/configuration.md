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
    kingbase-flyway-compatibility-enabled: true
```

| Value | When to use |
|-------|-------------|
| `validate` | Production and CI. FileWeft checks that the schema matches the expected version but does not change it. |
| `migrate` | Fresh installations or local development where the process may own schema creation. |
| `disabled` | External schema management or blue/green deployments where migrations run out-of-band. |

The runner is verified with Spring Boot 2 managed Flyway 8.5.13, FileWeft's own Flyway 9.22.3, and Spring Boot 3 managed Flyway 11.7.2. On Boot 3, `flyway-core`, `flyway-mysql` and `flyway-database-postgresql` must all resolve to 11.7.2.

`fileweft.persistence.kingbase-flyway-compatibility-enabled` defaults to `true` (the YAML block above uses its nested form). It adapts only the DataSource Spring Boot selected for Flyway; the application's primary DataSource remains the real Kingbase DataSource. Disable it only when the host provides and verifies an equivalent Kingbase/Flyway integration. A Spring Boot 2 Kingbase host must configure an explicit `spring.flyway.locations` path and must not use the `{vendor}` placeholder, which Boot resolves from the original JDBC URL before the FileWeft customizer runs.

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
| `connector-name` | Connector ID used by the required target in the synthesized `default` profile when `profiles` is empty |
| `default-profile-id` | Profile selected for publication; explicit non-sentinel values must match a configured profile |
| `connector-id` | The Spring bean name of a `FileConnector` implementation |
| `required` | If `true`, failure keeps the document in `SYNC_ERROR` and blocks `PUBLISHED` |
| `owner-ref` | Free-form operational owner shown in sync status and Doctor output |

Profile selection rules are deliberately fail-safe:

1. With no custom `profiles`, the Starter synthesizes profile `default` with one **required** target. That target's connector ID is `fileweft.sync.connector-name` (also `default` unless configured), so publication depends on a matching `FileConnector`; it is not a local-only success path.
2. With custom profiles, a non-sentinel `default-profile-id` must exactly match one configured profile ID or startup fails.
3. For backward compatibility, the untouched sentinel value `default` selects the first custom profile when no custom profile is actually named `default`. New configurations should set an explicit matching ID instead of relying on ordering.
4. Every target `connector-id` must exactly match a Spring `FileConnector` bean name or a plugin `connectors()` map key.

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
    kingbase-flyway-compatibility-enabled: true
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
The Starter synthesizes a `default` profile with one required target whose connector ID comes from `fileweft.sync.connector-name`. Register a matching `FileConnector` (or configure custom profiles); otherwise the required delivery cannot complete and publication does not become a local-only success.

**What if `default-profile-id` does not match a configured profile?**
Startup fails for any explicit non-sentinel value. The only compatibility exception is the untouched sentinel `default`: with custom profiles and no profile actually named `default`, FileWeft selects the first configured profile. Prefer an explicit matching ID.

## Next steps

- [Implement a storage adapter](../guides/storage-adapter.md)
- [Build a connector](../extensions/connectors.md)
- [Read the SPI overview](./spi.md)
