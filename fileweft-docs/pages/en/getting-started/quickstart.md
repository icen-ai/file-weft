---
route: "getting-started/quickstart"
group: "getting-started"
order: 4
locale: "en"
nav: "Quick start"
title: "Quick start"
lead: "Run a complete FileWeft endpoint on your laptop with a minimal Spring Boot host and a few curl commands."
format: "markdown"
---

## What this page covers

This page walks you through a self-contained local setup:

1. Start PostgreSQL in Docker.
2. Add the verified FileWeft 0.0.2 coordinates to a Spring Boot 3 project.
3. Provide the three required SPI beans.
4. Enable the development fallbacks.
5. Upload your first file through the formal `/fileweft/v1/documents` endpoint.

> [!WARNING]
> The configuration in this page uses fixed tenant and local filesystem fallbacks. They are **only** for local development; see [First integration](first-integration.md) for production wiring.

## Prerequisites

- JDK 17 or newer
- Docker (to run PostgreSQL)
- A Spring Boot 3.2+ application

## Step 1: Start PostgreSQL

Run a local PostgreSQL container with a database and user for FileWeft:

```bash
docker run -d \
  --name fw-postgres \
  -e POSTGRES_DB=fileweft \
  -e POSTGRES_USER=fileweft \
  -e POSTGRES_PASSWORD=fileweft \
  -p 5432:5432 \
  postgres:16-alpine
```

## Step 2: Add FileWeft dependencies

Create or edit `build.gradle.kts`:

```kotlin
plugins {
    kotlin("jvm") version "2.1.21"
    id("org.springframework.boot") version "3.4.0"
    kotlin("plugin.spring") version "2.1.21"
}

dependencies {
    // The host owns its DataSource and pool; this lets Boot create them from spring.datasource.*.
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("ai.icen:fileweft-spring-boot3-starter:0.0.2")
    implementation("ai.icen:fileweft-web-spring-boot3-starter:0.0.2")
    runtimeOnly("org.postgresql:postgresql")
}
```

> [!NOTE]
> FileWeft starters do not transitively choose a JDBC pool for the host. Boot 2 hosts keep `spring-boot-starter-jdbc` and use both `fileweft-spring-boot2-starter` and `fileweft-web-spring-boot2-starter`. The Web API contract is identical. Boot 2.7's BOM manages Kotlin at 1.6.21, below FileWeft's 2.1.21, so even Java-only hosts must align it: set `extra["kotlin.version"] = "2.1.21"` with Spring Dependency Management, set `<kotlin.version>2.1.21</kotlin.version>` with Maven, or add `org.jetbrains.kotlin:kotlin-bom:2.1.21` (or an equivalent explicit resolution rule) with native Gradle platforms. Do not expect an ordinary Kotlin BOM to override a Boot 2 `enforcedPlatform`; use `dependencyInsight` to confirm `kotlin-stdlib` is 2.1.21.

## Step 3: Provide trusted host context

Create a small config class with the three beans FileWeft requires:

```kotlin
import ai.icen.fw.core.context.TenantContext
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.tenant.TenantProvider
import ai.icen.fw.spi.identity.UserIdentity
import ai.icen.fw.spi.identity.UserRealmProvider
import ai.icen.fw.spi.authorization.AuthorizationDecision
import ai.icen.fw.spi.authorization.AuthorizationProvider
import ai.icen.fw.spi.authorization.AuthorizationRequest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class HostContextConfiguration {

    @Bean
    fun tenantProvider(): TenantProvider = object : TenantProvider {
        override fun currentTenant(): TenantContext =
            TenantContext(Identifier("dev-tenant"))
    }

    @Bean
    fun userRealmProvider(): UserRealmProvider = object : UserRealmProvider {
        override fun currentUser(): UserIdentity =
            UserIdentity(Identifier("dev-user"), displayName = "Developer")

        override fun findUser(userId: Identifier): UserIdentity? =
            currentUser().takeIf { it.id == userId }
    }

    @Bean
    fun authorizationProvider(): AuthorizationProvider = object : AuthorizationProvider {
        override fun authorize(request: AuthorizationRequest): AuthorizationDecision =
            AuthorizationDecision(allowed = true)
    }
}
```

## Step 4: Configure development fallbacks

Create `src/main/resources/application-dev.yaml`:

```yaml
fileweft:
  default-tenant-enabled: true
  default-tenant-id: dev-tenant
  storage:
    local-enabled: true
    local-root: ${user.home}/fileweft-store
  persistence:
    migration-mode: migrate
    schema: fileweft
    create-schema: true

spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/fileweft?currentSchema=fileweft
    username: fileweft
    password: fileweft
```

> [!WARNING]
> `default-tenant-enabled` and `local-enabled` are development shortcuts. They do not provide production multi-tenancy or shared storage across nodes.

## Step 5: Start the application

```bash
./gradlew bootRun --args='--spring.profiles.active=dev'
```

Wait for the Flyway migrations to finish. You should see the application listening on port `8080`.

## Step 6: Upload your first file

The formal v1 endpoint creates a document and its first version in a single multipart request. Pick any PDF or text file:

```bash
curl -i -X POST \
  -F "documentNumber=DOC-001" \
  -F "title=My first document" \
  -F "file=@report.pdf" \
  http://localhost:8080/fileweft/v1/documents
```

A successful response looks like this:

```json
{
  "code": "OK",
  "message": "OK",
  "data": {
    "documentId": "doc_...",
    "versionId": "ver_..."
  },
  "error": null,
  "traceId": "..."
}
```

The `documentId` and `versionId` come from the committed aggregate. FileWeft deliberately does not perform a second read after the command, so the response succeeds even if the caller does not hold read permission.

## Step 7: Inspect the result

```bash
# List documents
curl http://localhost:8080/fileweft/v1/documents

# Check system health
curl http://localhost:8080/fileweft/v1/health
```

## FAQ

**Q: Why use multipart instead of the resumable upload API?**

Multipart `POST /fileweft/v1/documents` is the fastest way to create a document with a small file. For large files, parallel parts, or unreliable networks, use the resumable upload API (`POST /fileweft/v1/uploads`).

**Q: Can I expose `/api/**` endpoints instead?**

No. The formal public protocol is `/fileweft/v1/**`. Internal `/api/**` routes are development-only and may change without notice.

**Q: The local filesystem fallback works on my laptop; can I use it in production?**

No. It is only for single-node development. Production deployments need a shared `StorageAdapter` such as S3, MinIO, or OSS.

## Next steps

- [Read First integration to wire production SPIs](first-integration.md)
- [Explore the HTTP API reference](../reference/http-api.md)
- [Implement a custom storage adapter](../guides/storage-adapter.md)
