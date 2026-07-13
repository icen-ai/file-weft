---
route: "getting-started/quickstart"
group: "getting-started"
order: 4
locale: "zh"
nav: "快速开始"
title: "快速开始"
lead: "用最小的 SPI 实现，在 Spring Boot 宿主里跑通一个 FileWeft 端点。"
format: "markdown"
---

## 添加运行时依赖

FileWeft 发布在 CNB Maven 仓库。给宿主添加 BOM 和 Spring Boot 3 Web Starter：

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
> Boot 2 宿主请使用 `fileweft-web-spring-boot2-starter`，Web API 契约完全一致。

## 提供可信宿主上下文

FileWeft 不会从 HTTP 参数读取租户、用户或权限。你必须提供三个 bean：

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
        // 在这里接入真实 ACL 校验。
        return AuthorizationDecision.GRANTED
    }
}
```

## 配置 FileWeft

添加少量属性，让 FileWeft 能找到 Flyway 迁移和存储：

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

## 上传第一个文件

启动应用后用 multipart 请求创建文档：

```bash
curl -F "documentNumber=DOC-001" \
     -F "title=First document" \
     -F "file=@report.pdf" \
     http://localhost:8080/fileweft/v1/documents
```

响应会返回已提交的 `documentId` 和 `versionId`。这里不会执行第二次查询，因此即使调用方没有读权限，命令也会成功。
