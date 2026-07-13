---
route: "project/release-0-0-2-development"
group: "project"
order: 4
locale: "en"
nav: "0.0.2 development"
title: "0.0.2 development line"
lead: "This page tracks the 0.0.2-SNAPSHOT development line, the migration work it introduces, and what you must verify before treating any 0.0.2 artifact as stable."
format: "markdown"
---

## What 0.0.2-SNAPSHOT changes

> [!WARNING] Not a stable release
> `0.0.2-SNAPSHOT` is an unreleased development line. The stable published version remains `ai.icen:*:0.0.1` until release gates and remote artifact verification finish.

The 0.0.2 line focuses on closing the gaps that appeared after the first public release:

1. **Workflow decision evidence** — approvals and rejections record an immutable operator ID, optional safe display-name snapshot and `decidedTime`.
2. **Identity contract tightening** — host user IDs are opaque, case-sensitive strings with a 256 UTF-16-code-unit limit and a fixed safe-character contract.
3. **Formal HTTP resources** — resumable upload, catalog and agent endpoints move from internal/dev shapes to the formal v1 surface.
4. **Release hardening** — runtime-closure SBOM, SNAPSHOT release verifier and reproducible build metadata.

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

## What is NOT stable yet

The following items remain open in the 0.0.2-SNAPSHOT line. Do not build production behavior on them until the release gate closes.

- Release/SNAPSHOT fixture tests and negative cases for corrupt, duplicate, XXE, path-traversal and mixed-build artifacts.
- Exact repository inventory, artifact-level metadata/checksum and dangerous JAR-entry checks.
- Formal catalog and agent HTTP resources with dual-Boot and browser acceptance.
- Final clean release gate.

## How to test a SNAPSHOT safely

If you want to evaluate 0.0.2-SNAPSHOT, isolate it from production data and traffic:

1. Use a separate database schema and object-storage bucket.
2. Enable `fileweft.persistence.migration-mode=validate` first to confirm schema expectations.
3. Run the Doctor endpoints and integration tests before exercising real workflows.
4. Do not promote the SNAPSHOT to production until the maintainers publish a stable `0.0.2`.

## FAQ

**When will 0.0.2 be stable?**
Only when all acceptance evidence in the [roadmap](project/roadmap) is reproducible from a clean environment and remote artifacts have been verified.

**Can I skip the V026 preflight?**
No. The preflight detects unsafe user-ID mappings that would violate the tightened identity contract after migration.

**Will 0.0.2 break my SPI implementations?**
Public SPI contracts are expected to remain compatible, but you must recompile and rerun contract tests against the released artifacts.

## Next steps

- Read the [roadmap](project/roadmap) for the full 0.0.2 acceptance criteria.
- Review the [HTTP API v1 reference](reference/http-api) to confirm which endpoints are already formal.
- Follow [migrations and releases](operations/migrations-release) for safe upgrade practices.
