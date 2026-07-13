---
name: integrate-fileweft
description: Integrate and operate FileWeft 0.0.1 in a Kotlin, Java, Spring Boot 2, or Spring Boot 3 host application. Use when an AI must add FileWeft dependencies, implement tenant, identity, authorization, storage, catalog, workflow, or connector SPIs, expose the formal v1 HTTP API, configure PostgreSQL migrations and Outbox workers safely, or add FileWeft contract and integration tests.
---

# Integrate FileWeft

Use FileWeft as infrastructure owned by the host application. Keep host authentication, users, business folders, and vendor SDKs outside FileWeft core contracts.

## Inspect the source of truth

Before changing an integration, read:

- `README.md` for the current release, deployment, and migration guidance.
- `docs/releases/0.0.1.md` for release boundaries and known limitations.
- `docs/plugin-development.md` for SPI, catalog, connector, and TestKit rules.
- `docs/production-operations.md` before database, worker, retry, or rollout changes.
- The exact interface under `fileweft-spi/src/main/kotlin/ai/icen/fw/spi/` before implementing it.

Do not copy types or behavior from `fileweft-dev` into production. Use it only as a working assembly example.

## Select modules

Use version `0.0.1` and Maven group `ai.icen`. Never use the withdrawn `com.fileweft` coordinates.

For Spring Boot 3:

```kotlin
repositories {
    maven {
        url = uri("https://maven.cnb.cool/china.ai/maven/-/packages/")
    }
}

dependencies {
    implementation("ai.icen:fileweft-spring-boot3-starter:0.0.1")
    implementation("ai.icen:fileweft-web-spring-boot3-starter:0.0.1")
}
```

For Spring Boot 2, replace both `boot3` artifact names with `boot2`. Add only the modules required by the host:

- `fileweft-spi`: extension contracts without Spring.
- `fileweft-adapter-s3`: the official S3-compatible storage adapter.
- `fileweft-adapter-micrometer`: Micrometer metrics integration.
- `fileweft-testkit`: reusable JUnit 5 contract tests; add as `testImplementation`.
- `fileweft-web-api`: transport DTOs when a client needs them without the MVC starter.

Configure repository credentials outside source control if the registry requires authentication.

## Establish trusted request boundaries

Provide Spring beans for production integrations instead of enabling process-local defaults:

1. Implement `TenantProvider.currentTenant()` from the authenticated server-side context. Never accept a tenant ID from a query parameter, form field, or unverified token claim.
2. Implement `UserRealmProvider` from the host identity system. Convert numeric, UUID, or composite user IDs to opaque non-blank `Identifier` strings and preserve the display name snapshot used for audit.
3. Implement `AuthorizationProvider.authorize(AuthorizationRequest)`. Verify the subject, resource tenant, action, and environment; deny unknown actions and cross-tenant access.
4. Provide one `StorageAdapter`. Keep tenant separation in every object operation and never expose a vendor SDK type through an SPI.

FileWeft does not authenticate requests. Install host authentication before `/fileweft/**`, populate the trusted context before FileWeft controllers run, and clear request-local state after each request.

Use explicit opt-in defaults only for local experiments:

```yaml
fileweft:
  default-tenant-enabled: true
  default-tenant-id: local
  storage:
    local-enabled: true
    local-root: ./build/fileweft-local
```

Never use these fixed-tenant or local-storage defaults in a multi-tenant production service.

## Add optional host capabilities through SPI

- Implement one `DocumentCatalogProvider` for a host-owned file tree. Accept only an opaque `folderId`; resolve tenant and user from `DocumentCatalogAccessRequest`. Use stable canonical IDs, namespace multiple sources, enforce folder ACLs, and do not put folder names or IDs into storage keys.
- Implement `DocumentReviewRouteProvider` for approval routing. Resolve remote policy outside FileWeft transactions and apply a timeout, bounded retry, and diagnostics.
- Implement `FileConnector` for each downstream. Make `sync` and `remove` idempotent with `ConnectorInvocation.idempotencyKey`; bound timeouts; classify retryable and permanent failures; return credential-free health details.
- Implement `DocumentDeliveryProfileProvider` to define tenant-scoped REQUIRED and OPTIONAL targets. Keep target and connector IDs stable. FileWeft snapshots a selected profile for the release generation.
- Prefer one explicit aggregate provider or resolver when multiple systems exist. Do not depend on `@Primary` to choose a security boundary.

Do not call a downstream from a controller or inside a database transaction. Publishing, approval completion, offline, and archive operations persist Outbox work; a worker performs connector calls and records each target independently.

## Configure PostgreSQL migrations safely

PostgreSQL is the only persistence target validated by the 0.0.1 release. FileWeft migrations use only `classpath:ai/icen/fw/db/migration` and the dedicated `fileweft_schema_history` table. Do not add that path to the host Flyway configuration.

Pre-create the production schema and make the JDBC current schema match the FileWeft assertion:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://db:5432/app?currentSchema=fileweft

fileweft:
  persistence:
    schema: fileweft
    create-schema: false
    migration-mode: validate
```

Use these modes deliberately:

- `migrate`: run from a controlled migration job with DDL privileges.
- `validate`: use on API and worker processes after migration.
- `disabled`: use only when the host DBA or deployment system fully owns FileWeft DDL.

With multiple `DataSource` beans, provide a `FlywayMigrationRunner` bound to the intended source. Never baseline, repair, copy, or delete history rows to bypass a migration failure. Stop all processes, back up, and follow `docs/production-operations.md` when a database contains records from the withdrawn coordinates or old shared Flyway history.

## Separate API and worker roles

Keep request-serving nodes at the default `fileweft.worker.enabled=false`. Deploy at least one worker with the same database schema, storage, SPI implementations, and secrets:

```yaml
fileweft:
  persistence:
    migration-mode: validate
    schema: fileweft
  worker:
    enabled: true
    process-outbox: true
    process-tasks: true
    process-upload-cleanup: true
```

Assign a unique worker identity when the platform can supply one. Preserve lease settings across rolling upgrades unless an operational review explicitly changes them. Monitor Outbox backlog, retries, connector health, and Doctor results.

## Use the formal HTTP API

Use `/fileweft/v1`; do not create new routes that call repositories or low-level application primitives directly. A typical flow is:

1. Create a draft with `POST /fileweft/v1/documents` as multipart fields `documentNumber`, `title`, optional `folderId`, and exactly one `file`.
2. Submit it with `POST /fileweft/v1/documents/{documentId}/submit` and an optional JSON `reviewRouteId`.
3. Read assigned tasks from `GET /fileweft/v1/workflows/tasks` and approve or reject through `/fileweft/v1/workflows/{workflowId}/tasks/{taskId}/approve|reject`.
4. Query `GET /fileweft/v1/documents/{documentId}/sync-status` for per-target delivery state.
5. Diagnose with `GET /fileweft/v1/documents/{documentId}/doctor`; use the asynchronous Doctor task routes for worker-backed checks.

Supply `Idempotency-Key` exactly once on lifecycle, review, Doctor scheduling, and delivery-recovery commands that require it. Use 1-128 ASCII characters matching `[A-Za-z0-9][A-Za-z0-9._~:-]*`. Reuse a key only to replay the exact same operator, action, resource, and request.

Treat FileWeft authorization as server-side enforcement, not UI filtering. A UI may hide unavailable actions, but the `AuthorizationProvider` and catalog ACL remain authoritative.

## Test the integration

Add the TestKit:

```kotlin
dependencies {
    testImplementation("ai.icen:fileweft-testkit:0.0.1")
}
```

Extend the applicable JUnit 5 contracts:

- `StorageAdapterContractTest`
- `FileConnectorContractTest`
- `AuthorizationProviderContractTest`
- `DocumentDeliveryProfileProviderContractTest`

Test the host with at least two tenants and both allowed and denied users. Cover cross-tenant ID guessing, storage isolation, folder ACL revocation, idempotent concurrent connector replay, retryable failure, permanent failure, worker restart, and audit attribution. Use dedicated non-production storage and downstream fixtures.

Run the host test suite, then run these commands when changing the FileWeft repository itself:

```powershell
.\gradlew.bat check
.\gradlew.bat compatibilityCheck
.\gradlew.bat verifySbom --no-configuration-cache
```

Before a FileWeft release, start the complete `fw-dev` Compose topology, enable the PostgreSQL, RustFS, Dev API, and Playwright suites as documented in `README.md`, and run `releaseCheck --no-configuration-cache --no-parallel`. Do not weaken or skip a failed release gate.

## Preserve architecture

- Keep controllers limited to validation, DTO conversion, and application-service calls.
- Keep business rules in domain/application layers and persistence mapping in repositories.
- Keep external calls out of database transactions and route them through durable Outbox work.
- Keep all database, storage, event, task, cache, log, and diagnostic operations tenant-scoped.
- Keep public APIs Java-friendly and compatible with their module's JDK baseline.
- Prefer an existing SPI or an additive SPI extension over vendor-specific changes to core or domain modules.
