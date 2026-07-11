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
10. Agent
11. Production hardening

Do not skip foundational layers.

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
