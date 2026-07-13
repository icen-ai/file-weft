---
route: "project/release-0-0-1"
group: "project"
order: 3
locale: "en"
nav: "Release 0.0.1"
title: "Release 0.0.1"
lead: "This page documents what is actually available in the stable 0.0.1 line, including coordinates, module boundaries, delivered capabilities and the limits you should know before integrating."
format: "markdown"
---

## What 0.0.1 delivers

FileWeft `0.0.1` is the first stable public line. It establishes the module chain, Maven coordinates, namespaced Flyway migrations and the production-oriented document lifecycle, workflow, delivery, Doctor and Web foundations.

1. **Layered module chain** — `core` → `spi` → `domain` → `application` → `persistence` → `starter` → `adapter`.
2. **Spring Boot starters** — runtime and Web starters for both Boot 2 and Boot 3, with mirrored behavior.
3. **Persistence** — PostgreSQL with Flyway migrations scoped to a dedicated schema and history table.
4. **Storage paths** — shared persistent `StorageAdapter` contract with local filesystem and S3-compatible adapters.
5. **Resilient work** — durable Outbox, leased background tasks, parallel review routing and multi-target delivery.
6. **Formal HTTP surface** — `/fileweft/v1` endpoints for uploads, documents, workflows, deliveries, audit logs and Doctor.
7. **Security boundaries** — tenant-aware ACL, authorization decisions, audit and trace propagation.

## Maven coordinates

Use the `ai.icen` group. The old `com.fileweft:*` trial artifacts have been withdrawn and are not automatically adopted.

```kotlin
// build.gradle.kts
implementation("ai.icen:fileweft-spring-boot3-starter:0.0.1")
```

```xml
<!-- pom.xml -->
<dependency>
  <groupId>ai.icen</groupId>
  <artifactId>fileweft-spring-boot3-starter</artifactId>
  <version>0.0.1</version>
</dependency>
```

JVM package root is `ai.icen.fw`. Public APIs remain Java-friendly: no `suspend`, `Flow`, `value class`, `sealed interface` or `data object` in SPI surfaces.

## Module map

| Module | Responsibility in 0.0.1 |
| --- | --- |
| `fileweft-core` | Identifiers, results, errors, events, contexts |
| `fileweft-spi` | Contracts for tenant, identity, authorization, storage, connector, workflow, task, doctor |
| `fileweft-domain` | Document, FileAsset, lifecycle, version, audit rules |
| `fileweft-application` | Upload, publish, offline, approve, sync orchestration |
| `fileweft-persistence` | PostgreSQL mappings, repositories, Flyway migrations |
| `fileweft-runtime` | Runtime assembly and worker machinery |
| `fileweft-web-api` / `fileweft-web-runtime` | Formal HTTP v1 surface and Boot adapters |
| `fileweft-spring-boot2-starter` / `fileweft-spring-boot3-starter` | Auto-configuration for each Boot generation |
| `fileweft-adapter-*` | External implementations: S3, MinIO, Micrometer, OpenTelemetry |

## Included capabilities

### Document lifecycle

```text
DRAFT → PENDING_REVIEW → PUBLISHED → OFFLINE → ARCHIVED
```

Use `restore` to move from `OFFLINE` back to `DRAFT`.

### Multi-target delivery

A document can be delivered to multiple downstream systems. Required targets must succeed before the document becomes `PUBLISHED`; optional targets fail without blocking publication. Already-succeeded targets are never rolled back.

### Resumable uploads

Large files are uploaded through caller-stable idempotency keys and numbered parts. The upload session endpoints are:

- `POST /fileweft/v1/uploads`
- `GET /fileweft/v1/uploads/{uploadId}`
- `PUT /fileweft/v1/uploads/{uploadId}/parts/{partNumber}`
- `POST /fileweft/v1/uploads/{uploadId}/complete`
- `DELETE /fileweft/v1/uploads/{uploadId}`

### Doctor

Every major component exposes diagnostics through the `DoctorChecker` SPI. Query component health at `GET /fileweft/v1/doctor` or per-document at `GET /fileweft/v1/documents/{id}/doctor`.

## Known limits

- At the time of this historical release, `0.0.1` was the stable line and `0.0.2-SNAPSHOT` was unreleased. Current consumption guidance lives in the 0.0.2 release page.
- The local filesystem storage fallback and default-tenant fallback are for development only. They are not production multi-tenant solutions.
- Official vendor adapters for OSS, Dify, ESE and AppBuilder are on the roadmap; the S3-compatible adapter covers S3-like storage in 0.0.1.
- Dev-only `/api/**` endpoints are not part of the formal public HTTP protocol.

> [!WARNING] Do not use development fallbacks in production
> `fileweft.default-tenant-enabled=true` and `fileweft.storage.local-enabled=true` are convenient for a single-node laptop, but they do not provide tenant isolation or durable shared storage.

## License

FileWeft is available under the Apache License 2.0. Copyright belongs to icen.ai. See the repository `LICENSE` and `NOTICE` files for authoritative terms.

## FAQ

**Can I use `com.fileweft:*` artifacts?**
No. Those trial artifacts have been withdrawn. Existing `ai.icen:*:0.0.1` installations retain their historical upgrade boundary; new integrations use `ai.icen:*:0.0.2` only after its remote publication evidence is complete.

**Is 0.0.1 production-ready?**
It is the stable published line, but you must provide production identity, tenant, authorization, storage and database infrastructure. FileWeft does not turn development fallbacks into production multi-tenancy.

**Does 0.0.1 support MySQL?**
Not yet. 0.0.1 supports PostgreSQL. MySQL 8 is planned for a later line.

## Next steps

- [Install the current verified release](getting-started/installation), or keep this page for the historical 0.0.1 contract.
- [Wire a trustworthy host](getting-started/first-integration) with real tenant and storage providers.
- Read the [HTTP API v1 reference](reference/http-api).
