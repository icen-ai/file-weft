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

| Resource | Location | Why it matters |
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

## Current migration and Flyway matrix

The current 0.0.3 line contains all 30 migrations, V001–V030, for PostgreSQL, MySQL and KingbaseES. V029 only appends nullable workflow submitter evidence and V030 only appends a nullable idempotency result-subresource column; neither rewrites an earlier resource or checksum. The `v0.0.2` V001–V028 sets remain immutable release resources. Historically, `v0.0.1` contained only PostgreSQL V001–V025; MySQL, KingbaseES and V026–V028 first entered the release contract in `v0.0.2`.

`FlywayMigrationRunner` is verified against three host-resolved runtime sets:

| Runtime owner | Flyway version | Required modules |
|---|---:|---|
| Spring Boot 2 dependency management | 8.5.13 | `flyway-core`, `flyway-mysql` |
| FileWeft persistence itself | 9.22.3 | FileWeft's locked core/database set |
| Spring Boot 3 dependency management | 11.7.2 | `flyway-core`, `flyway-mysql`, `flyway-database-postgresql`, all at 11.7.2 |

The Kingbase Starter compatibility customizer wraps only the DataSource Spring Boot already selected for Flyway. The application's primary DataSource remains the real Kingbase DataSource. Set `fileweft.persistence.kingbase-flyway-compatibility-enabled=false` only when the host supplies and tests an equivalent Kingbase/Flyway integration; it is not a general bypass switch. Spring Boot 2 hosts must not use `{vendor}` in `spring.flyway.locations`: Boot resolves that placeholder from the original `jdbc:kingbase8:` URL before the FileWeft customizer runs and cannot reliably map it to PostgreSQL. Configure an explicit host migration location instead.

## 0.0.2 MySQL migration boundary

MySQL support starts at 8.0.17 and is limited to the native MySQL 8.x line. The release evidence runs on MySQL 8.0.46; that result does not establish support for MariaDB or MySQL 9, nor prove every 8.x minor version, collation and topology.

In MySQL, a schema is a database. Have the DBA create it first, select it explicitly in the JDBC URL, and keep `create-schema: false`. A connection without a current database does not switch new connections after `CREATE DATABASE`; `SELECT DATABASE()` remains `null`. FileWeft therefore rejects `create-schema: true` in that state before executing any DDL, avoiding a changed-but-failed startup.

Do not describe the MySQL work as rewriting every historical migration. The MySQL `V001` resource remains byte-for-byte identical to the pre-0.0.2 checked-in resource and retains its Flyway checksum for teams that already tried a 0.0.2-SNAPSHOT; the 0.0.1 release tags did not yet contain MySQL migrations. MySQL-specific corrections begin at V016: they fix syntax and duplicate-column definitions that prevented the former chain from completing on real MySQL 8. The 0.0.2 line is therefore the first one backed by a complete real-MySQL migration and JDBC repository verification loop.

If an existing database has a checksum mismatch or a partial history from an earlier MySQL attempt, stop writes, take a backup, and compare the exact resources with `fileweft_schema_history`. Do not run an unconditional `flyway repair`, and never use repair to bless an unexplained checksum. Any remediation must be an explicit DBA-reviewed migration decision.

## V017, V027 and V028 operations

V017 enforces at database level that a tenant/document has at most one local `PENDING` workflow. PostgreSQL and KingbaseES use a partial unique index. MySQL uses nullable stored generated tenant/document columns plus a unique index, which provides the same invariant without concatenating opaque IDs. Existing duplicates make migration fail; they are never deleted or resolved automatically.

V027 adds non-unique `(created_time, id)` claim-order indexes to `fw_outbox_event` and `fw_task`. V028 converts all 18 FileWeft-owned MySQL business tables to the NO PAD `utf8mb4_0900_bin` collation, so tenant IDs, user IDs, idempotency identities and every other opaque ID compare by exact Unicode scalar/text value, including case, accents and trailing spaces. This does not promise arbitrary raw-byte identity. Display text inherits the same ordering; this is the security-first cost of keeping future textual key columns from inheriting a default that folds identifier values. PostgreSQL and KingbaseES V028 scripts only keep migration versions aligned.

Both changes require a maintenance window. V027 uses ordinary index creation and does not promise concurrent/online DDL. MySQL V028 may rebuild every table and text index. Stop Workers and every API/scheduler writer to the affected tables, prevent old/new nodes from overlapping, take and test a backup, and reserve space for the original tables, rebuilt copies, indexes, temporary sort/rebuild data, redo/binlog and replication backlog. Monitor metadata/row locks, replication delay and free disk throughout.

Retry forward only. If V027 fails before Flyway records success, inspect the same-name indexes and have a DBA remove any invalid or incompatible partial result before rerunning; never manufacture history or use unconditional repair. Application rollback keeps V027 indexes and MySQL NO PAD `utf8mb4_0900_bin`. Never convert V028 tables back to PAD SPACE `utf8mb4_bin`, a `*_ci`, or another collation that folds case, accents or trailing spaces: doing so can collapse distinct tenants or opaque IDs, produce false unique conflicts, and change idempotency identity.

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
- MySQL 8 integration tests
- KingbaseES V8 integration tests
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

- Schema migrations are forward-only. Test them in a copy of production data before release; application rollback retains V027 indexes and V028 NO PAD exact-text comparison semantics.
- Application jars can be rolled back to the previous `ai.icen:*:0.0.1` patch if the schema did not change.
- If a connector starts failing after release, use delivery retry and removal retry endpoints rather than restarting workers.

```bash
# Retry a failed delivery
curl -sf -X POST \
  http://api:8080/fileweft/v1/documents/doc_123/deliveries/dlv_456/retry \
  -H "Authorization: Bearer ${HOST_TOKEN}" \
  -H "Idempotency-Key: $(uuidgen)"
```

## FAQ

**Can I skip the migration job and let API nodes migrate on startup?**
No. Long-running API nodes must run in `validate` mode. Schema changes require a controlled, one-shot migration job.

**When is 0.0.3 safe to consume?**
Use `ai.icen:*:0.0.3` only after the guarded `v0.0.3` tag matches the protected remote `main` HEAD, every required lane for that exact commit succeeds, and anonymous cold-cache readback covers all 19 coordinates plus the Boot 2, Boot 3, and pure-SPI consumers. Do not infer remote availability from source, this page, a tag name, or partial green evidence.

## Next steps

- Deploy with separate runtime roles: [Production deployment](deployment)
- Monitor after release: [Doctor & observability](doctor-observability)
- Read the current release contract: [Release 0.0.3](../project/release-0-0-3), and retain [Release 0.0.2](../project/release-0-0-2-development) and [Release 0.0.1](../project/release-0-0-1) for historical upgrade boundaries.
