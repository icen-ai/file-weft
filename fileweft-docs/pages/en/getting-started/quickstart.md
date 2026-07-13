---
route: "getting-started/quickstart"
group: "getting-started"
order: 4
locale: "en"
nav: "Quick start"
title: "Quick start"
lead: "Get a FileWeft endpoint running in a Spring Boot host with a minimal SPI implementation."
format: "markdown"
---

## Add the runtime dependency

FileWeft is published to the CNB Maven repository. Add the BOM and the Spring Boot 3 Web starter to your host:

```kotlin
// build.gradle.kts
plugins {
    kotlin("jvm") version "2.1.21"
    id("org.springframework.boot") version "3.4.0"
    kotlin("plugin.spring") version "2.1.21"
}

dependencyManagement {
    imports {
        mavenBom("ai.icen:fileweft-bom:0.0.2-SNAPSHOT")
    }
}

dependencies {
    implementation("ai.icen:fileweft-web-spring-boot3-starter")
    implementation("ai.icen:fileweft-persistence")
    runtimeOnly("org.postgresql:postgresql")
}
```

> [!NOTE]
> Boot 2 hosts use `fileweft-web-spring-boot2-starter`. The Web API contract is identical.

## Provide trusted host context

FileWeft never reads tenant, user or permissions from HTTP parameters. You must supply three beans:

```kotlin
@Component
class HostTenantProvider : TenantProvider {
    override fun currentTenant(): TenantContext =
        TenantContext.current() ?: TenantContext.of(Identifier.from("default"))
}

@Component
class HostUserRealmProvider : UserRealmProvider {
    override fun currentUser(): UserIdentity? =
        SecurityContextHolder.getContext().authentication?.name
            ?.let { UserIdentity(Identifier.from(it), displayName = it) }

    override fun findUser(userId: Identifier): UserIdentity? =
        currentUser()?.takeIf { it.id == userId }
}

@Component
class HostAuthorizationProvider : AuthorizationProvider {
    override fun decide(request: AuthorizationRequest): AuthorizationDecision {
        // Your real ACL check goes here.
        return AuthorizationDecision.GRANTED
    }
}
```

## Configure FileWeft

Add a small set of properties so FileWeft can locate its Flyway migrations and storage:

```yaml
fileweft:
  storage:
    adapter: local
    local:
      root-path: ${user.home}/fileweft-store
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/fileweft
    username: fileweft
    password: fileweft
```

## Upload your first file

Start the application and create a document with a multipart request:

```bash
curl -F "documentNumber=DOC-001" \
     -F "title=First document" \
     -F "file=@report.pdf" \
     http://localhost:8080/fileweft/v1/documents
```

The response contains the committed `documentId` and `versionId`. No second read is performed, so the command succeeds even when the caller does not hold the read permission.
