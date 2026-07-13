---
route: "architecture/security"
group: "architecture"
order: 2
locale: "en"
nav: "Security architecture"
title: "Fail closed at every boundary"
lead: "FileWeft assembles a capability only when its complete security boundary is present. Missing context, ambiguous providers, or unverified custom persistence make the operation unavailable instead of silently widening access."
format: "markdown"
---

## The problem

Enterprise file infrastructure sits between untrusted callers and sensitive bytes. A small mistake in provider resolution, a leaked object key, or an over-shared diagnostic message can expose one tenant's data to another. FileWeft's default posture is to refuse service when any required boundary is unclear.

## Fail-closed capability assembly

FileWeft exposes a capability only after it can identify a single, unambiguous provider for every boundary that affects security:

| Boundary | Provider | Why it matters |
| --- | --- | --- |
| Tenant isolation | `TenantProvider` | Every query and storage path is scoped to the current tenant. |
| Identity | `UserRealmProvider` | Actions are attributed to a real, validated user. |
| Authorization | `AuthorizationProvider` | Every guarded action asks an explicit policy decision. |
| Catalog structure | `DocumentCatalogProvider` | Folder visibility affects what documents a user can reach. |
| Lifecycle transitions | Lifecycle guards | State changes enforce business-approved paths. |

If two catalog providers or two lifecycle candidates are both visible, FileWeft does **not** pick one by guessing, by `@Primary`, or by classpath order when that choice could change security semantics. The operation reports an error and stops.

> **WARNING**
> Do not rely on Spring `@Primary` to resolve security-sensitive providers. FileWeft considers multiple eligible candidates an assembly failure.

## Custom persistence must earn trust

You can replace the built-in persistence with your own implementation, but guarded writes are disabled until the custom layer proves it can:

1. Hold a real mutation lock for the duration of the business transaction.
2. Make idempotency claims atomically with the business write.
3. Enforce tenant filtering at the lowest query layer.

Without these guarantees, FileWeft will expose read-only projections and reject commands rather than risk concurrent mutation or cross-tenant writes.

## Public projections hide internals

HTTP DTOs are deliberately minimal. The following never leak to API consumers:

- Pre-signed storage URLs or object keys.
- Connector internals such as remote document IDs or credentials.
- Raw Doctor evidence.
- Tenant identifiers.
- Unsafe diagnostic text from exceptions.

Audit views expose stable action names and operator snapshots, not unrestricted `details` JSON that could carry sensitive metadata.

```bash
# Safe: the document response only exposes business identifiers
curl http://localhost:8080/fileweft/v1/documents/fw-doc-123
```

```json
{
  "code": "OK",
  "message": "OK",
  "data": {
    "documentId": "fw-doc-123",
    "documentNumber": "POL-2025-001",
    "status": "PUBLISHED",
    "versionId": "fw-ver-456",
    "createdBy": "user-alice",
    "updatedTime": 1783900000000
  },
  "error": null,
  "traceId": "trace-abc"
}
```

> **NOTE**
> Downloads are returned as binary streams with `attachment`, `nosniff`, and `private, no-store` caching headers. FileWeft does not expose storage URLs, Range negotiation, or ETags to API clients.

## Plugins are trusted code

A FileWeft plugin runs in the same JVM as the host application. It shares the classloader, permissions, and memory space. There is no in-process sandbox.

```kotlin
interface FileWeftPlugin {
    fun id(): String
    fun storageAdapters(): List<StorageAdapter> = emptyList()
    fun connectors(): Map<String, FileConnector> = emptyMap()
    fun doctorCheckers(): List<DoctorChecker> = emptyList()
    // ...
}
```

| Do this | Avoid this |
| --- | --- |
| Install plugins from reviewed, signed artifacts. | Download and load plugins from untrusted networks. |
| Run third-party extensions in a separate process behind authenticated protocols. | Grant a plugin unlimited file system or network access by default. |
| Audit plugin IDs and bean priority at startup. | Assume classpath isolation protects you. |

> **WARNING**
> **No in-process sandbox.** A malicious plugin can read memory, exfiltrate credentials, and modify host state. Treat every plugin as part of your trusted computing base.

## Tenant context is not a request parameter

The current tenant always comes from a trusted `TenantProvider`, never from a query string or JSON field. Request parameters may influence business logic, but they cannot override isolation:

```kotlin
interface TenantProvider {
    fun currentTenant(): TenantContext
}
```

This rule applies to database queries, storage path prefixes, event routing, task claims, log context, and cache keys.

## Authorization checks are explicit

Every guarded command builds an `AuthorizationRequest` and asks the configured `AuthorizationProvider` for a decision:

```kotlin
interface AuthorizationProvider {
    fun authorize(request: AuthorizationRequest): AuthorizationDecision
}
```

The request includes subject, resource with `tenantId`, action, and environment. A missing provider or a `DENY` decision fails the command immediately. There is no implicit allow.

## FAQ

**Q: Can I disable security checks for local development?**

FileWeft provides a fixed-tenant fallback and local storage fallback for single-tenant development only. They are not a production multi-tenant solution. Production deployments must supply real `TenantProvider`, `UserRealmProvider`, and `AuthorizationProvider` implementations.

**Q: What happens if my custom repository forgets to filter by tenant?**

FileWeft's SPI contracts require tenant-scoped queries. If a custom persistence implementation cannot prove tenant isolation, guarded writes remain disabled and the application logs an assembly error at startup.

**Q: Are Doctor reports safe to expose to operators?**

Document-level Doctor reports are designed for operators, but they still omit raw object keys, connector credentials, and tenant identifiers. Always apply your own role-based access control on top of the API.

## Next steps

- [Consistency model](/architecture/consistency) — how FileWeft keeps local state atomic while converging remote systems.
- [Event-driven delivery](/architecture/event-driven) — how events propagate without breaking tenant boundaries.
- [Storage adapter guide](/guides/storage-adapter) — implement a storage backend that respects the same fail-closed boundaries.
