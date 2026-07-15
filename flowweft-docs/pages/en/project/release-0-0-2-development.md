---
route: "project/release-0-0-2-development"
group: "project"
order: 4
locale: "en"
nav: "Release 0.0.2"
title: "Release 0.0.2"
lead: "This page records the exact 0.0.2 release contract and the protected-tag plus anonymous remote-resolution evidence required before its Maven coordinates are consumable."
format: "markdown"
---

> [!IMPORTANT] Current FlowWeft decision supersedes the old deferral below
> The Agent paragraph on this page remains unchanged because it records the exact `0.0.2` product boundary. It no longer controls development after `0.0.3`: root `AGENTS.md`, ADR 0001, and the [current roadmap](./roadmap.md) approve a redesigned Agent and generic workflow platform for FlowWeft 1.0. Do not interpret the historical wording as a current prohibition.

## What 0.0.2 delivers

> [!IMPORTANT] Verify remote availability
> This page corresponds to `v0.0.2`, but it does not claim that remote publication has already succeeded. Consume `ai.icen:*:0.0.2` only after the protected tag pipeline and anonymous cold-cache resolution of the exact artifacts succeed.

The 0.0.2 line focuses on closing the gaps that appeared after the first public release:

1. **Workflow decision evidence** — approvals and rejections record an immutable operator ID, optional safe display-name snapshot and `decidedTime`.
2. **Identity contract tightening** — host user IDs are opaque, case-sensitive strings with a 256 UTF-16-code-unit limit and a fixed safe-character contract.
3. **Formal HTTP resources** — the five-operation resumable-upload resource moves from internal/dev shapes to the formal v1 surface.
4. **Real database evidence** — supported MySQL 8 and KingbaseES each pass real migration and JDBC repository suites behind an independent change-scoped gate.
5. **Release hardening** — runtime-closure SBOM, SNAPSHOT release verifier and reproducible build metadata.

> [!CAUTION] 0.0.2 does not provide Agent product capability
> `fileweft-agent`, the Agent SPI/public ABI, and Agent-related structures in V012/V026 remain only for source, binary and database compatibility. The default runtime, Starters, Doctor/plugin inventory, public HTTP API and `fileweft-dev` do not register, advertise or expose Agent; an explicit legacy compatibility switch is not a 0.0.2 feature either. Agent is deferred indefinitely and may be reassessed only after 1.0.0 has been released. That is not a commitment to 1.x, the next release or any other version.

## Real MySQL 8 and KingbaseES evidence

0.0.2 gives each database an independent, fail-closed verification entry point:

- `mysqlIntegrationCheck` runs the fresh Flyway migration chain and JDBC repository suite against native MySQL 8.0.17 or later in the 8.x line; the evidence used for this release line is MySQL 8.0.46;
- `kingbaseIntegrationCheck` runs the equivalent migration and repository scope against official KingbaseES V008R006C009B0014 in PostgreSQL-compatible mode;
- local development selects a task only when a change touches that migration, dialect or persistence boundary; CNB applies the same path-scoped scheduling to ordinary changes, while nightly full acceptance and release events run both.

When the named real environment is missing, the dedicated task must fail instead of skipping. Success on PostgreSQL, H2, mocks or the other database cannot substitute for target-database evidence. MySQL support is limited to native MySQL 8.0.17+ within 8.x; it does not include MariaDB or MySQL 9, nor establish evidence for every 8.x minor release, collation or topology. The KingbaseES evidence likewise applies only to the named tested version and scope.

The MySQL migration history has a narrower compatibility boundary than “all old migrations were rewritten.” MySQL `V001` is byte-for-byte identical to the pre-0.0.2 checked-in resource, so its Flyway checksum remains stable for teams that already tried a 0.0.2-SNAPSHOT; the 0.0.1 release tags did not yet contain MySQL migrations. The MySQL-specific corrections start at V016 and repair syntax and duplicate-column definitions that prevented the old chain from completing on real MySQL 8. Consequently, 0.0.2 is the first line with a closed real-MySQL migration and repository verification loop.

Do not run an unconditional `flyway repair` if an existing database reports a checksum mismatch or carries a partial old MySQL attempt. Stop writes, back up the database, compare the exact migration resource and `fileweft_schema_history`, then choose a reviewed DBA remediation. Repair must never be used to make an unexplained history look green.

## Migration inventory, Flyway hosts, V027 and V028

The `v0.0.2` inventory contains all 28 migrations, V001–V028, for PostgreSQL, MySQL and KingbaseES. Once the protected tag publication succeeds, all three sets become immutable release resources. Historically, `v0.0.1` contained only PostgreSQL V001–V025; MySQL, KingbaseES and V026–V028 first enter the release contract in `v0.0.2`. V026 remains the dedicated workflow-decision-evidence migration described below; V027 creates stable `(created_time, id)` claim-order indexes for `fw_outbox_event` and `fw_task`. V028 converts all 18 FileWeft-owned MySQL business tables to the NO PAD `utf8mb4_0900_bin` collation; PostgreSQL and KingbaseES use no-op V028 scripts for version alignment.

`FlywayMigrationRunner` is verified with the Spring Boot 2 managed Flyway 8.5.13 runtime, FileWeft's own Flyway 9.22.3 dependency set, and the Spring Boot 3 managed Flyway 11.7.2 runtime. On Boot 3, `flyway-core`, `flyway-mysql` and `flyway-database-postgresql` must resolve to the same 11.7.2 version; do not mix them.

V027 uses ordinary index creation, so treat it as a maintenance-window migration. V028 can rebuild every MySQL table and text index. Stop Worker and application writes, prevent old/new nodes from overlapping, reserve space for originals, rebuilt copies, indexes, temporary work, redo/binlog and replication backlog, and monitor locks, replication and disk usage. V028 makes tenant, user and every other opaque ID compare by exact Unicode scalar/text value, including case, accents and trailing spaces; it does not promise arbitrary raw-byte identity. Application rollback keeps the V027 indexes and NO PAD `utf8mb4_0900_bin`; never return to `utf8mb4_bin`, `*_ci`, or another PAD SPACE/folding collation. See [Migrations & releases](operations/migrations-release) for the forward-retry and rollback procedure.

## V026 workflow decision evidence migration

Version 026 changes how workflow decisions are stored and projected. Follow the migration order exactly; skipping a step can leave the database in a state that old binaries cannot read and new binaries cannot trust.

1. Run `docs/sql/postgresql-v026-workflow-decision-evidence-preflight.sql`.
2. Repair any unsafe host user-ID mappings without truncating, padding or guessing.
3. Close review commands, stop every old API node and wait for in-flight decisions to finish.
4. Rerun the preflight script, apply Flyway migrations, then validate columns and constraints.
5. Start only V026-aware nodes.

During rollback, keep the V026 columns, constraints and evidence. Do not shrink identity columns or reopen review writes on an old binary.

> [!CAUTION] Legacy evidence stays UNKNOWN
> V026 does not infer an actor from an assignee, current directory entry or optional audit row. Completed legacy tasks remain `UNKNOWN`.

V026 also updates the Agent compatibility tables created by V012. Even though 0.0.2 does not expose Agent by default, do not delete, skip or rewrite that migration scope: it exists only to keep existing databases upgradeable and does not reactivate Agent product capability.

## Identity contract tightening

Host user IDs are now required to be:

- opaque strings,
- case-sensitive,
- at most 256 UTF-16 code units,
- trimmed of leading/trailing Unicode whitespace,
- free of ISO control and format characters.

If your host currently stores user IDs as `Long`, `Int`, `UUID` or arbitrary directory identifiers, convert them to one permanently stable string representation before migrating to 0.0.2.

```kotlin
// Do not do this
val userId = rawUserId.toString().trim().lowercase()

// Do this instead
val userId = encodeStableString(rawUserId) // same algorithm forever
```

> [!NOTE] Why no normalization?
> FileWeft treats user IDs as opaque. Lower-casing, trimming or reformatting inside the framework would silently change authorization decisions.

## Release evidence and excluded scope

The implementation scope is closed. Stable consumption still depends on evidence outside this document: a clean build of the exact commit, every matching CNB lane, guarded tag publication, and anonymous cold-cache consumer verification of the remote artifacts. Do not infer that those steps succeeded from the source tree alone.

> [!NOTE] Formal catalog HTTP is outside 0.0.2
> The host-owned `DocumentCatalogProvider` SPI and catalog-aware authorization guards remain available, but an independent formal catalog-tree HTTP resource is explicitly out of the 0.0.2 scope. It has no committed target version and is not a hidden release blocker.

> [!NOTE] Vendor connector boundary
> 0.0.2 does not claim official OSS, Dify, ESE or AppBuilder adapters. Hosts may implement the generic `StorageAdapter` / `FileConnector` SPIs; only a future adapter with repeatable real-vendor-service acceptance can be published as an officially supported capability.

## How to adopt 0.0.2 safely

After remote publication is verified, adopt 0.0.2 in an isolated environment before production traffic:

1. Use a separate database schema and object-storage bucket.
2. Enable `fileweft.persistence.migration-mode=validate` first to confirm schema expectations.
3. Run the Doctor endpoints and integration tests before exercising real workflows.
4. Confirm the exact `v0.0.2` tag event and anonymous cold-cache resolution before production promotion.

## FAQ

**How do I know 0.0.2 is consumable?**
Require all acceptance evidence in the [roadmap](project/roadmap), the protected tag publication, and anonymous resolution of the exact remote artifacts from a cold cache.

**Can I skip the V026 preflight?**
No. The preflight detects unsafe user-ID mappings that would violate the tightened identity contract after migration.

**Will 0.0.2 break my SPI implementations?**
Public SPI contracts are expected to remain compatible, but you must recompile and rerun contract tests against the released artifacts.

**Agent types still exist in compatibility artifacts. Does that mean 0.0.2 supports Agent?**
No. Those types and migrations are retained only for compatibility, and the default product surface does not expose Agent. Do not build a new 0.0.2 integration on them.

## Next steps

- Read the [roadmap](project/roadmap) for the full 0.0.2 acceptance criteria.
- Review the [HTTP API v1 reference](reference/http-api) to confirm which endpoints are already formal.
- Follow [migrations and releases](operations/migrations-release) for safe upgrade practices.
