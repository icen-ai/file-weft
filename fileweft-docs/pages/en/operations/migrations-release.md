---
route: "operations/migrations-release"
group: "operations"
order: 3
locale: "en"
nav: "Migrations & releases"
title: "Migrate and release deliberately"
lead: "FileWeft owns a namespaced Flyway location and history table. Release safely by validating schema compatibility, real infrastructure paths, SBOM integrity and reproducible dependency state."
format: "markdown"
---

## What this page solves

Upgrading FileWeft is not just swapping a jar. This page describes the safe path from schema migration to production release, including how to handle the withdrawn `com.fileweft` trial artifacts.

## Migration namespace

FileWeft migrations live in a single, isolated namespace:

| Asset | Location | Why it matters |
|-------|----------|----------------|
| Migration resources | `classpath:ai/icen/fw/db/migration` | Framework-owned, versioned scripts |
| History table | `fileweft_schema_history` | Separated from host schema history |

> [!WARNING]
> Do not append FileWeft resources to the host's Flyway `locations` and do not merge `fileweft_schema_history` into `flyway_schema_history`. Doing so breaks ownership and rollback.

## Migration modes

Choose a mode per runtime role:

| Mode | Meaning | Use in |
|------|---------|--------|
| `migrate` | Apply pending migrations | One-shot migration job only |
| `validate` | Fail if schema does not match | API and Worker roles |
| `disabled` | Skip Flyway entirely | Special maintenance windows |

```yaml
fileweft:
  persistence:
    migration-mode: validate
    schema: fileweft
    create-schema: false
```

## Release gates

Before any artifact reaches production, run these checks against the exact build:

```powershell
# Windows
.\gradlew.bat check --no-daemon
.\gradlew.bat compatibilityCheck --no-daemon
.\gradlew.bat verifySbom --no-daemon
```

```bash
# Linux / macOS
./gradlew check --no-daemon
./gradlew compatibilityCheck --no-daemon
./gradlew verifySbom --no-daemon
```

The formal pipeline also enables:

- PostgreSQL integration tests
- RustFS / object storage tests
- Dev API acceptance tests
- Browser acceptance tests

All against the same healthy development stack.

## Old trial databases

The coordinates `com.fileweft:*:0.0.1` were withdrawn. If a database was ever run with those trial artifacts:

1. Stop the application.
2. Back up the database.
3. Have a DBA inspect schema ownership and history rows.
4. Do not baseline, repair, copy or delete history rows to bypass the analysis.

> [!WARNING]
> FileWeft will not automatically adopt a trial database. Treat migration ownership as a data-ownership decision.

## Reproducible builds

The project uses Gradle dependency locking and a verified SBOM. Keep these artifacts with every release:

- `build/repository/` containing the signed artifacts
- `build/reports/sbom/` or equivalent SBOM output
- `gradle.lockfile` from every published module

## Rollback policy

- Schema migrations are forward-only. Test them in a copy of production data before release.
- Application jars can be rolled back to the previous `ai.icen:*:0.0.1` patch if the schema did not change.
- If a connector starts failing after release, use delivery retry and removal retry endpoints rather than restarting workers.

```bash
# Retry a failed delivery
curl -sf -X POST \
  http://api:8080/fileweft/v1/documents/doc_123/deliveries/dlv_456/retry \
  -H "Authorization: Bearer ${HOST_TOKEN}" \
  -H "X-Idempotency-Key: $(uuidgen)"
```

## FAQ

**Can I skip the migration job and let API nodes migrate on startup?**
No. Long-running API nodes must run in `validate` mode. Schema changes require a controlled, one-shot migration job.

**What is the stable version?**
`ai.icen:*:0.0.1`. The `0.0.2-SNAPSHOT` line is unreleased and must not be presented as stable.

## Next steps

- Deploy with separate runtime roles: [Production deployment](deployment)
- Monitor after release: [Doctor & observability](doctor-observability)
- Read the release notes: [Release 0.0.1](../project/release-0-0-1)
