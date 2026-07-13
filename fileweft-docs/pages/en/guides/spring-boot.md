---
route: "guides/spring-boot"
group: "guides"
order: 1
locale: "en"
nav: "Spring Boot hosting"
title: "Assemble FileWeft into Spring Boot"
lead: "Learn how to embed FileWeft as an infrastructure library inside your Spring Boot application while keeping full control over authentication, DataSource ownership, and gateway policy."
format: "markdown"
---

FileWeft is not a standalone application. It is a set of opinionated modules that attach to your host. The starters only auto-wire beans; they do not replace your security layer, your database pool, or your operational decisions. This page shows the minimal, safe way to assemble everything.

## 1. Pick the right starter generation

| Host requirement | Runtime starter | HTTP starter |
| --- | --- | --- |
| Spring Boot 3 / Java 17+ | `fileweft-spring-boot3-starter` | `fileweft-web-spring-boot3-starter` |
| Spring Boot 2.7 / Java 8+ | `fileweft-spring-boot2-starter` | `fileweft-web-spring-boot2-starter` |

Install exactly one generation. The Web starter exposes `/fileweft/v1/**` controllers; the runtime starter supplies use cases, workers, and persistence without HTTP.

```kotlin
// build.gradle.kts (Spring Boot 3 example)
dependencies {
    implementation("ai.icen:fileweft-spring-boot3-starter:0.0.1")
    implementation("ai.icen:fileweft-web-spring-boot3-starter:0.0.1")
}
```

> [!WARNING]
> Do not install both Boot 2 and Boot 3 starters. Auto-configuration classes target specific Spring Framework versions and will conflict.

## 2. Own the DataSource

FileWeft expects a `javax.sql.DataSource` bean named `fileweftDataSource` (or a primary bean if only one exists). You create it, you tune the pool, and you own credentials rotation.

```kotlin
@Configuration
class FileWeftDataSourceConfig {

    @Bean
    @ConfigurationProperties("app.fileweft.datasource")
    fun fileweftDataSource(): DataSource {
        return DataSourceBuilder.create().type(HikariDataSource::class.java).build()
    }
}
```

```yaml
app:
  fileweft:
    datasource:
      jdbc-url: jdbc:postgresql://localhost:5432/fileweft
      username: ${FW_DB_USER}
      password: ${FW_DB_PASSWORD}
      maximum-pool-size: 10
```

## 3. Provide identity and tenant SPIs

FileWeft deliberately ships with no weak default authentication. You must expose three beans so the framework can resolve who is calling and whether they are allowed to act.

```kotlin
@Component
class SecurityTenantProvider : TenantProvider {
    override fun currentTenant(): TenantContext {
        val authentication = SecurityContextHolder.getContext().authentication
        val tenantId = authentication.details as? String
            ?: throw IllegalStateException("Tenant not resolved by host")
        return TenantContext(Identifier(tenantId))
    }
}

@Component
class SecurityUserRealmProvider : UserRealmProvider {
    override fun currentUser(): UserIdentity? {
        val authentication = SecurityContextHolder.getContext().authentication
        return UserIdentity(
            userId = Identifier(authentication.name),
            displayName = authentication.name,
        )
    }

    override fun findUser(userId: Identifier): UserIdentity? {
        // Lookup through your identity service.
        return userService.findById(userId.value)
    }
}

@Component
class FileWeftAuthorizationProvider : AuthorizationProvider {
    override fun authorize(request: AuthorizationRequest): AuthorizationDecision {
        // Delegate to your ABAC/RBAC engine.
        return policyEngine.evaluate(request)
    }
}
```

> [!NOTE]
> The user ID is treated as an opaque string. Do not trim, case-fold, or normalize it inside the provider.

## 4. Configure persistence and workers

A minimal production configuration validates existing schema migrations rather than running them automatically.

```yaml
fileweft:
  persistence:
    migration-mode: validate
    schema: fileweft
    create-schema: false
  worker:
    enabled: true
    fixed-delay-millis: 1000
    outbox-batch-size: 50
    task-batch-size: 50
```

For a local single-node sandbox you can use `migration-mode: migrate`, but never run `migrate` in production without a reviewed migration pipeline.

## 5. Secure the edge

Configure the following in your host or gateway, not inside FileWeft:

- CORS allowed origins
- CSRF token strategy or stateless session policy
- OAuth2/OIDC login and token validation
- mTLS between services
- Maximum request body size and per-route streaming limits
- Timeouts and rate limiting

FileWeft controllers perform request validation and DTO conversion only. They never bypass your SPI decisions.

## 6. Verify startup

After starting the application, check the delivered health endpoint:

```bash
curl http://localhost:8080/fileweft/v1/health
```

You should also list plugins to confirm that your storage adapter, catalog provider, or connector beans were registered:

```bash
curl http://localhost:8080/fileweft/v1/plugins
```

## FAQ

**Q: Can FileWeft run without Spring Boot?**
Yes. The runtime starter wires beans into Spring, but the core modules are plain Kotlin/JVM. You can assemble them manually in Micronaut, Quarkus, or a plain `main` function.

**Q: Does the Web starter provide authentication?**
No. It relies on the `UserRealmProvider`, `TenantProvider`, and `AuthorizationProvider` beans that you supply.

**Q: Where are Flyway migrations?**
Migrations live in `fileweft-persistence` and are versioned. Use `migration-mode: validate` in production and run changes through your own deployment tooling.

## Next steps

- [Implement a catalog provider](catalog-provider.md) to bind documents to your host folder tree.
- [Implement a storage adapter](storage-adapter.md) to store bytes on MinIO, OSS, S3, or the local filesystem.
- [Configure resumable uploads](resumable-upload.md) for large files over unstable networks.
