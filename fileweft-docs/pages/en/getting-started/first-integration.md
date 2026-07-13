---
route: "getting-started/first-integration"
group: "getting-started"
order: 3
locale: "en"
nav: "First integration"
title: "Wire a trustworthy host"
lead: "Move from a dependency declaration to a production host by providing trusted identity context, shared storage, PostgreSQL schema ownership, and separate runtime roles."
format: "markdown"
---

## What this page covers

Adding FileWeft to your classpath is not enough. A production host must answer three questions on every request:

1. Which tenant is this?
2. Who is the user?
3. Are they allowed to do this?

This page shows how to wire the three SPI beans FileWeft expects, how to align PostgreSQL and storage ownership, and how to split API and worker nodes so background leases do not stall HTTP requests.

## Step 1: Supply trusted identity context

FileWeft never reads tenant, user, role, or permission results from HTTP parameters. You provide three beans from data already authenticated by your host.

```kotlin
import ai.icen.fw.core.context.TenantContext
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.tenant.TenantProvider
import ai.icen.fw.spi.identity.UserIdentity
import ai.icen.fw.spi.identity.UserRealmProvider
import ai.icen.fw.spi.authorization.AuthorizationDecision
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.authorization.AuthorizationRequest
import org.springframework.stereotype.Component

@Component
class HostTenantProvider : TenantProvider {
    override fun currentTenant(): TenantContext {
        // Resolve from your host's authenticated context: JWT claim, header, path, etc.
        val tenantId = resolveTenantIdFromHost()
        return TenantContext(Identifier(tenantId))
    }
}

@Component
class HostUserRealmProvider : UserRealmProvider {
    override fun currentUser(): UserIdentity? {
        val userId = resolveCurrentUserIdFromHost()
            ?: return null
        return UserIdentity(id = Identifier(userId), displayName = userId)
    }

    override fun findUser(userId: Identifier): UserIdentity? {
        // Look up the user in your host directory, or return currentUser() if it matches.
        return currentUser()?.takeIf { it.id == userId }
    }
}

@Component
class HostAuthorizationProvider : AuthorizationProvider {
    override fun authorize(request: AuthorizationRequest): AuthorizationDecision {
        // Delegate to your ACL / policy engine.
        val allowed = checkHostPolicy(request)
        return AuthorizationDecision(allowed = allowed)
    }
}
```

> [!WARNING]
> Controllers must not accept tenant IDs, user IDs, roles, or permission results as business parameters. Trust only what your host has already authenticated and authorized.

## Step 2: Respect user ID safety rules

User IDs cross FileWeft boundaries as opaque strings. Convert Long, Int, UUID, or external directory identifiers to one permanently stable string format in your host.

A valid FileWeft user ID:

- is case-sensitive,
- contains at most 256 UTF-16 code units,
- has no leading or trailing Unicode whitespace,
- contains no ISO control or FileWeft-rejected format characters.

> [!NOTE]
> Do not trim, convert to lowercase, or normalize IDs inside FileWeft. Do it once, consistently, in the host layer before converting to `Identifier`.

## Step 3: Choose storage and database ownership

Multi-node deployments need a shared, persistent `StorageAdapter`. Do not rely on the local filesystem fallback across pods or machines.

FileWeft does not choose the host's connection pool. This is the minimal recommended dependency set for a Boot 3 host with one DataSource. If your host already owns another pool, replace the first dependency with an equivalent JDBC stack that creates one unambiguous `DataSource` bean.

```kotlin
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("ai.icen:fileweft-spring-boot3-starter:0.0.2")
    implementation("ai.icen:fileweft-web-spring-boot3-starter:0.0.2")
    runtimeOnly("org.postgresql:postgresql")
}
```

Boot 2 hosts keep `spring-boot-starter-jdbc` and switch the two FileWeft coordinates to their matching `boot2` variants. Boot 2.7's BOM manages Kotlin at 1.6.21, below FileWeft's 2.1.21, so even Java-only hosts must align it: set `extra["kotlin.version"] = "2.1.21"` with Spring Dependency Management, set `<kotlin.version>2.1.21</kotlin.version>` with Maven, or add `org.jetbrains.kotlin:kotlin-bom:2.1.21` (or an equivalent explicit resolution rule) with native Gradle platforms. An ordinary Kotlin BOM cannot override a Boot 2 `enforcedPlatform`; use `dependencyInsight` to confirm `kotlin-stdlib` is 2.1.21.

For PostgreSQL, set the DataSource current schema and the FileWeft schema assertion to the same value:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://db:5432/app?currentSchema=fileweft

fileweft:
  persistence:
    migration-mode: validate # migrate | validate | disabled
    schema: fileweft
    create-schema: false
```

| Mode | When to use |
| --- | --- |
| `migrate` | Development or when FileWeft owns the schema lifecycle. |
| `validate` | Production: verify that the schema matches Flyway migrations on startup. |
| `disabled` | You manage schema changes externally; FileWeft skips migration checks. |

> [!TIP]
> In production, create the schema with your own DDL pipeline or a dedicated migration user, then run FileWeft with `validate` and `create-schema: false`.

## Step 4: Separate runtime roles

FileWeft has two runtime roles. Run them as separate process groups in production:

| Role | Handles | Typical config |
| --- | --- | --- |
| **API node** | HTTP requests | Web starter enabled; workers disabled or read-only. |
| **Worker node** | Outbox events, task queues, upload cleanup | Runtime starter; `process-outbox: true`; no HTTP surface. |

Example worker-only node:

```yaml
fileweft:
  worker:
    enabled: true
    process-outbox: true
    process-tasks: true
    process-upload-cleanup: true
```

This keeps HTTP latency independent of background lease contention and retry storms.

## FAQ

**Q: Can I run a single node with both API and workers enabled?**

Yes, for local development or very small deployments. In production, split them so you can scale, deploy, and restart each role independently.

**Q: Does FileWeft provide a default tenant provider?**

There is a development-only fallback (`fileweft.default-tenant-enabled=true`), but it is not a production multi-tenant solution. Always implement `TenantProvider` from your host's authentication context.

**Q: Where do catalog folders live?**

Folder topology is owned by your host through the `DocumentCatalogProvider` SPI. FileWeft writes a reserved `catalog.folder-id` metadata key on the asset, but storage paths never contain folder IDs.

## Next steps

- [Run the 5-minute quickstart on your laptop](quickstart.md)
- [Implement a storage adapter](../guides/storage-adapter.md)
- [Read the configuration reference](../reference/configuration.md)
