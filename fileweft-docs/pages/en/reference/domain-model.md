---
route: "reference/domain-model"
group: "reference"
order: 5
locale: "en"
nav: "Domain model"
title: "Core domain model"
lead: "FileWeft separates business concepts from infrastructure. This page shows the core entities, their responsibilities, and how they connect to storage, workflow, and delivery."
format: "markdown"
---

## Model overview

```
TenantContext ──► Document ──► Version ──► FileAsset ──► StorageObjectLocation
     │              │            │
     │              ▼            ▼
     │         Lifecycle    Workflow / ReviewTask
     │              │            │
     ▼              ▼            ▼
AuthorizationDecision   OutboxEvent   DeliveryTarget
```

The diagram is conceptual. The domain layer contains no Spring, no ORM, and no vendor SDKs. Adapters and persistence map these concepts to real infrastructure.

## Document

A `Document` is the top-level business record. It carries an opaque identifier, a trusted tenant scope, business metadata, the current lifecycle state, and a reference to the latest `Version`.

The document does not store bytes. Content lives in a `FileAsset` linked through a `Version`.

## Version

A `Version` represents an immutable generation of a document. Creating a new version preserves history and starts a new delivery generation.

Each version references:

- its parent document
- a sequence or generation marker
- the linked `FileAsset`
- business metadata extracted at upload time

> [!NOTE]
> Delivery is generation-fenced. A late result from an older version cannot overwrite the current document state.

## FileAsset and storage location

A `FileAsset` records the result of a successful upload, including the content length, content type, and a `StorageObjectLocation` that is opaque to the domain.

The domain never opens an `InputStream` or talks to a bucket. It only passes the location to a `StorageAdapter` when the application layer needs bytes.

## Lifecycle

The document lifecycle is evidence, not a flag. The common path is:

```
DRAFT → PENDING_REVIEW → PUBLISHED → OFFLINE → ARCHIVED
```

| Transition | Command | Result |
|------------|---------|--------|
| Start review | `submit` | `DRAFT` → `PENDING_REVIEW` |
| Direct release | `publish` | `DRAFT` or review success → `PUBLISHED` |
| Withdraw | `offline` | `PUBLISHED` → `OFFLINE` |
| Rework | `restore` | `OFFLINE` → `DRAFT` |
| Long-term archive | `archive` | `OFFLINE` → `ARCHIVED` |
| New generation | `revise` | Creates a new `Version` while preserving state rules |

State changes, audit records, and Outbox events commit together in a single business transaction.

## Workflow and review task

A `DocumentReviewRouteProvider` resolves a route into one or more `ReviewTask` items. Tasks are parallel by default; all must approve for publication, and any rejection ends the review.

```kotlin
interface DocumentReviewRouteProvider {
    fun id(): String
    fun resolve(request: DocumentReviewRouteRequest): DocumentReviewRoute
}
```

Route resolution runs outside the FileWeft database transaction so host policy can query external systems safely.

## Delivery target

When a document is published, FileWeft creates a delivery attempt for each target in the active sync profile.

| Field | Meaning |
|-------|---------|
| `targetId` | Profile target identifier |
| `connectorId` | Bean name of the responsible `FileConnector` |
| `required` | Whether failure blocks `PUBLISHED` |
| `externalId` | Identifier returned by the downstream system |
| `status` | `SUCCESS`, `RETRYABLE_FAILURE`, or `PERMANENT_FAILURE` |

Required targets must all succeed before the document becomes `PUBLISHED`. Optional targets may fail without blocking publication. Successful targets are never rolled back.

## Outbox and task

The domain emits Outbox events instead of calling external systems directly. For example, when a document is taken offline or archived, FileWeft writes `document.delivery.target.removal.requested` events so each downstream target can be cleaned up asynchronously.

A `FileWeftTaskHandler` processes durable tasks with at-least-once semantics. Handlers must be idempotent by task id.

```kotlin
interface FileWeftTaskHandler {
    fun supports(task: TaskExecution): Boolean
    fun handle(task: TaskExecution): TaskHandlingResult
    fun onExhausted(task: TaskExecution, message: String) = Unit
}
```

## Audit and tenant context

Every state change produces an audit record bound to the trusted `UserIdentity` and `TenantContext`. The tenant context affects queries, storage paths, events, tasks, logs, and caches.

> [!WARNING]
> Do not trust `tenantId` from request parameters. FileWeft always asks the `TenantProvider` for the current trusted tenant.

## Entity ownership by layer

| Layer | Owns |
|-------|------|
| `core` | Identifiers, results, errors, events, contexts |
| `spi` | Contracts for tenant, identity, storage, catalog, workflow, connector, doctor, task, agent |
| `domain` | Document, FileAsset, Lifecycle, Version, Workflow, Audit rules |
| `application` | Upload, publish, offline, review, Doctor, sync orchestration |
| `adapter` | MinIO, OSS, S3, Dify, ESE, AppBuilder implementations |
| `persistence` | Repository implementations and Flyway migrations |

## Frequently asked questions

**Can a document have multiple current versions?**
No. A document points to one current version, but the full version history is retained.

**Where is the actual file content stored?**
In the object storage backend behind a `StorageAdapter`. The domain only knows the opaque `StorageObjectLocation`.

**Why are workflows resolved outside the database transaction?**
So host policy can call external directories or identity systems without holding database locks or risking rollback side effects.

## Next steps

- [SPI overview](./spi.md)
- [Lifecycle & delivery concepts](../concepts/lifecycle-delivery.md)
- [Module boundaries](../concepts/module-boundaries.md)
