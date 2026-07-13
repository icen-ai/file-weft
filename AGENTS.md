# FileWeft AI Agent Development Instructions

> This file is the root-level AI development contract for the FileWeft repository.
>
> Scope:
>
> - Codex
> - Claude Code
> - DeepSeek
> - Other coding agents
>
> Before modifying code, read:
>
> - `.ai/README_FINAL.md`
> - `.ai/FileWeft_Ultimate_Implementation_Manual_COMPLETE/`
>
> Then load directly relevant companion material from
> `.ai/FileWeft_Ultimate_Implementation_Manual_DATABASE_EXTENSION/`,
> `.ai/FileWeft_Ultimate_Implementation_Manual_Source_Extension_30/`, or
> `.ai/FileWeft_Ultimate_Implementation_Manual_Extension_40/` when the task
> touches databases, source blueprints, deployment, observability, security,
> or plugins. These checked-in directories are the complete FileWeft design
> documentation; the older `..._FINAL_PACKAGE` path is not present in this
> repository.

## Superseding Product Decision: FileWeft Agent Is Deferred

This decision has the highest priority. It supersedes every older Agent phase,
roadmap item, example, acceptance statement, and release-planning statement in
this repository when they conflict with it.

- FileWeft `0.0.2` does not provide Agent product capability.
- Agent will be redesigned later, but that work is deferred indefinitely. The
  earliest point at which the project may reassess it is **after `1.0.0` has
  been released**. This is not a commitment to `1.x`, the next release, or any
  other version.
- Keep the existing `fileweft-agent` artifact, Agent SPI/public ABI, and the
  Agent-related parts of migrations V012/V026 only for source, binary, and
  database compatibility. Their presence does not make Agent a supported
  product capability.
- The default runtime, Starters, Doctor inventory, plugin inventory, public
  HTTP API, and `fileweft-dev` must not register, advertise, exercise, or expose
  Agent capability. Any explicit legacy compatibility switch remains
  compatibility-only and must not be presented as a `0.0.2` feature.
- Until a new architecture decision is approved after the reassessment point,
  do not add Agent endpoints, UI, automatic registration, positive product
  tests/examples, or new Agent behavior. Compatibility/security fixes and
  negative tests that enforce default non-exposure are allowed.
- Do not delete historical Agent text. Mark it as historical/superseded so the
  reason for retained ABI and migrations remains auditable.

---

# 1. Project Identity

FileWeft is a Kotlin/JVM enterprise file intelligence infrastructure.

It is not:

- a simple file upload module
- a business-specific document system
- a Dify/ESE wrapper
- a cloud storage SDK

It is an extensible infrastructure framework.

---

# 2. Before Writing Code

Every AI agent MUST:

1. Read the implementation manuals under `.ai/`.
2. Understand module boundaries.
3. Check whether an existing SPI already solves the problem.
4. Prefer extension over modification.

Do not immediately create new classes.

First ask:

- Is this a Core responsibility?
- Is this an SPI responsibility?
- Is this an Adapter responsibility?
- Is this a Domain responsibility?

---

# 3. Architecture Rules

The dependency direction is fixed:

```
starter

    ↓

application

    ↓

domain

    ↓

core


adapter

    ↓

spi
```

Forbidden:

```
core -> Spring

core -> Database

domain -> MinIO SDK

domain -> Dify SDK

spi -> Vendor SDK
```

Any violation requires redesign before implementation.

---

# 4. Kotlin Rules

FileWeft is implemented in Kotlin.

Target:

- JDK 8 baseline
- JDK 25 compatible

Public APIs must remain Java friendly.

Forbidden in public API:

- suspend function
- Kotlin Flow
- value class
- sealed interface
- data object

Avoid exposing Kotlin-only concepts to SPI users.

---

# 5. Module Responsibility

## core

Contains only:

- identifiers
- result models
- errors
- events
- contexts

No Spring.
No ORM.
No external SDK.


## spi

Contains contracts:

- storage
- identity
- authorization
- tenant
- connector
- workflow
- AI


No implementation.


## domain

Contains business rules:

- Document
- FileAsset
- Lifecycle
- Version


No infrastructure calls.


## application

Contains use cases:

- upload
- publish
- offline
- doctor
- synchronization orchestration


## adapter

Contains external implementations:

- MinIO
- OSS
- S3
- Dify
- ESE
- AppBuilder


---

# 6. SPI First Rule

Before adding a new dependency:

Check if a SPI should exist.

Examples:

Wrong:

```
DocumentService
    |
    DifyClient
```

Correct:

```
DocumentService
    |
    FileConnector
    |
    DifyConnector
```

---

# 7. Database Rules

Never put business logic inside repositories.

Repository responsibilities:

- persistence
- mapping
- tenant filtering

Domain rules stay in domain.

All business tables require:

- id
- tenant_id
- created_time
- updated_time

---

# 8. Multi Tenant Rules

Tenant isolation is mandatory.

Tenant context affects:

- database queries
- storage paths
- events
- tasks
- logs
- caches

Never trust a tenant ID directly from request parameters.

---

# 9. External System Rules

External systems are unreliable.

All connectors require:

- timeout
- retry
- idempotency
- error recording
- health checking

Never call external systems inside database transactions.

Use:

```
Business Transaction

↓

Outbox Event

↓

Async Worker

↓

Connector
```

---

# 10. Doctor System

Doctor is a first-class feature.

Any major component should provide diagnostics.

Examples:

- StorageDoctorChecker
- PermissionDoctorChecker
- ConnectorDoctorChecker
- LifecycleDoctorChecker

When implementing new modules, consider:

"How will an operator diagnose failure?"

---

# 11. API Rules

Controllers only:

- validate request
- convert DTO
- call application service

Controllers must not:

- access database
- call storage
- call connectors
- contain business rules

---

# 12. Testing Requirements

Every feature requires:

## Core

Unit tests.

## SPI

Contract tests.

## Adapter

Integration tests.

## Starter

Spring context tests.

No untested infrastructure code.

## Change-Scoped Verification and CNB Closure

Before running verification, read `.ci/README.md` and select the union of the
narrowest named tasks that cover every changed boundary.

During implementation:

- run one affected test class first
- then run the affected module's `test` task
- run `fastCheck` once after a coherent code or build-logic change, not after
  every edit
- after a failure, rerun the focused failing task before one final relevant
  gate

Do not use an unqualified root `test` or `check` as the default development
entry point. Ordinary development must not run `compatibilityCheck`,
`externalAcceptanceCheck`, `releaseVerification`, `releaseCheck`, or
`releaseBundle`; these are CNB, nightly, or release responsibilities unless the
changed boundary explicitly requires one of them. Preserve local incremental
state: do not add `clean`, `--rerun-tasks`, `--no-build-cache`, or
`--no-daemon` without a demonstrated reason.

Run at most one Gradle invocation at a time in the same checkout. Agents must
coordinate before starting Gradle and must not run overlapping wrappers against
shared `build/` directories, Kotlin incremental caches, or test-result files.
Use one invocation and let Gradle parallelize its own task graph. Separate CNB
runners and genuinely separate worktrees may run in parallel because their
outputs are isolated.

External evidence is additive and fail-closed:

- persistence or PostgreSQL migration changes require
  `postgresIntegrationCheck`
- MySQL or Kingbase migration/dialect changes require the corresponding
  `mysqlIntegrationCheck` or `kingbaseIntegrationCheck`; PostgreSQL success is
  not evidence for another dialect
- S3-compatible storage changes require `rustFsIntegrationCheck`
- Dev API, UI, or Compose behavior changes require `devAcceptanceCheck`
- publication, POM, metadata, SBOM, or release bundle changes require
  `releaseArtifactCheck`

After an authorized push, CNB evidence must match the exact 40-character
commit SHA and the intended event and branch. An older SHA, pending, cancelled,
partially green, or unrelated build is not completion evidence. Inspect a
failed pipeline's stage result and log before changing code. Prefer the CNB
Pipeline skill when it is available; otherwise follow the read-only `cnb` CLI
runbook in `.ci/README.md`. Never bypass tag/SHA identity checks or guarded
publishing tasks to manufacture a successful release.

### CNB result retrieval without a skill

The CNB skill is optional; result retrieval must remain possible from a plain
terminal. For this repository, use the read-only `cnb` CLI flow below. Do not
trigger, stop, rerun, tag, or publish merely to inspect a result.

```powershell
$repo = "china.ai/file-weft"
$sha = (git rev-parse HEAD).Trim()

cnb status
cnb build get-build-logs `
  --repo $repo `
  --sha $sha `
  --page-size 100 `
  --verbose
```

If `cnb status` reports no login, a human may authorize `cnb login`; never ask
for or print their token. In `get-build-logs`, select the record whose full
`sha`, `event`, `sourceRef`, and `targetRef` match the intended delivery. Record
its `sn`; completion requires `status=success`, `pipelineFailCount=0`, and all
pipelines expected by the matching `.ci/*.yml` path rules to be present and
successful.

```powershell
cnb build get-build-status `
  --repo $repo `
  --sn <SN> `
  --verbose
```

On failure, take the first failed business pipeline/stage ID from that response
and retrieve its detail before editing code:

```powershell
cnb build get-build-stage `
  --repo $repo `
  --sn <SN> `
  --pipelineId <PIPELINE_ID> `
  --stageId <STAGE_ID> `
  --verbose
```

Only when stage detail is insufficient, use:

```powershell
cnb build build-runner-download-log `
  --repo $repo `
  --pipelineId <PIPELINE_ID> `
  --verbose
```

It returns a local log path. Read only the necessary tail and redact
credentials, headers, signed URLs, and environment variables. Current CLI
`1.10.x` has no `get-build-result`; use `get-build-logs` for the build summary,
`get-build-status` for pipelines/stages, and `get-build-stage` for the failure
log. If a future CLI differs, inspect `cnb build --help` rather than guessing.
Never set `NODE_TLS_REJECT_UNAUTHORIZED=0` or weaken TLS verification.

Changes to `.cnb.yml`, executable `.ci/*.yml`, CI Dockerfiles, Gradle
verification tasks, or release tasks require the CNB YAML, semantic, and Schema
validation described in `.ci/README.md`.

---

# 13. AI Change Control

Before changing architecture, provide:

1. Why existing extension points are insufficient.
2. Why the change is necessary.
3. Compatibility impact.
4. Migration strategy.
5. Test plan.

Do not silently redesign.

---

# 14. Implementation Order

Follow:

1. Core
2. SPI
3. Domain
4. Application
5. Persistence
6. Starter
7. Storage adapters
8. Connector adapters
9. Doctor
10. Production hardening

Do not skip foundational layers.

The former Agent implementation phase is superseded by the product decision at
the top of this file and must not be executed before a future approved redesign.

---

# 15. Coding Style

Prefer:

- small focused classes
- explicit dependencies
- immutable data models where compatible
- meaningful names
- comprehensive tests

Avoid:

- god classes
- magic configuration
- hidden coupling
- vendor leakage

---

# 16. Local Development Services

When local infrastructure is required, define it in:

```
.docker/docker-compose.dev.yaml
```

The compose file must preserve the top-level project name:

```
name: fw-dev
```

Do not create local containers before the relevant implementation phase requires them.

---

# Final Rule

When uncertain:

Choose the design that keeps FileWeft:

- more extensible
- more compatible
- less coupled
- easier to diagnose

The goal is not only to make current code work.

The goal is to build a long-lived enterprise infrastructure framework.
