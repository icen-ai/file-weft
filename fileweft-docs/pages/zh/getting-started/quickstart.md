---
route: "getting-started/quickstart"
group: "getting-started"
order: 4
locale: "zh"
nav: "快速开始"
title: "快速开始"
lead: "用最小的 Spring Boot 宿主和几个 curl 命令，在笔记本上跑通一个完整的 FileWeft 端点。"
format: "markdown"
---

## 这页解决什么问题？

这页带你完成一个自包含的本地环境：

1. 用 Docker 启动 PostgreSQL；
2. 在 Spring Boot 3 项目中引入 FileWeft 0.0.1；
3. 提供 FileWeft 要求的三个 SPI bean；
4. 启用开发 fallback；
5. 通过正式的 `/fileweft/v1/documents` 端点上传第一个文件。

> [!WARNING]
> 本页配置使用固定租户和本地文件系统 fallback，**仅**用于本地开发；生产接入请阅读[首次接入](first-integration.md)。

## 准备工作

- JDK 17+
- Docker（用于运行 PostgreSQL）
- Spring Boot 3.2+ 应用

## 步骤一：启动 PostgreSQL

运行本地 PostgreSQL 容器，创建 FileWeft 使用的数据库和用户：

```bash
docker run -d \
  --name fw-postgres \
  -e POSTGRES_DB=fileweft \
  -e POSTGRES_USER=fileweft \
  -e POSTGRES_PASSWORD=fileweft \
  -p 5432:5432 \
  postgres:16-alpine
```

## 步骤二：添加 FileWeft 依赖

创建或编辑 `build.gradle.kts`：

```kotlin
plugins {
    kotlin("jvm") version "2.1.21"
    id("org.springframework.boot") version "3.4.0"
    kotlin("plugin.spring") version "2.1.21"
}

dependencies {
    implementation("ai.icen:fileweft-web-spring-boot3-starter:0.0.1")
    implementation("ai.icen:fileweft-persistence:0.0.1")
    runtimeOnly("org.postgresql:postgresql")
}
```

> [!NOTE]
> Boot 2 宿主请使用 `fileweft-web-spring-boot2-starter`，Web API 契约完全一致。

## 步骤三：提供可信宿主上下文

创建一个配置类，提供 FileWeft 要求的三个 bean：

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

## 步骤四：配置开发 fallback

创建 `src/main/resources/application-dev.yaml`：

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
> `default-tenant-enabled` 与 `local-enabled` 是开发快捷方式，不能提供生产多租户能力，也无法跨节点共享存储。

## 步骤五：启动应用

```bash
./gradlew bootRun --args='--spring.profiles.active=dev'
```

等待 Flyway 迁移完成。应用将在 `8080` 端口监听。

## 步骤六：上传第一个文件

正式 v1 端点通过一次 multipart 请求创建文档及其首个版本。任选一份 PDF 或文本文件：

```bash
curl -i -X POST \
  -F "documentNumber=DOC-001" \
  -F "title=My first document" \
  -F "file=@report.pdf" \
  http://localhost:8080/fileweft/v1/documents
```

成功响应如下：

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

`documentId` 与 `versionId` 来自已提交的领域聚合。FileWeft 故意不在命令后执行第二次查询，因此即使调用方没有读权限，响应也会成功。

## 步骤七：查看结果

```bash
# 列文档
curl http://localhost:8080/fileweft/v1/documents

# 检查系统健康
curl http://localhost:8080/fileweft/v1/health
```

## 常见问题

**Q: 为什么用 multipart，而不是断点续传 API？**

小文件走 multipart `POST /fileweft/v1/documents` 最快。大文件、并行分片或不稳定网络请使用断点续传 API（`POST /fileweft/v1/uploads`）。

**Q: 可以暴露 `/api/**` 端点吗？**

不可以。正式公共协议是 `/fileweft/v1/**`。内部 `/api/**` 路由仅用于开发，可能随时变更。

**Q: 本地文件系统 fallback 在笔记本上能用，生产环境也能用吗？**

不能。它仅适用于单节点开发。生产环境需要共享的 `StorageAdapter`，如 S3、MinIO 或 OSS。

## 下一步

- [阅读《首次接入》，接入生产 SPI](first-integration.md)
- [浏览 HTTP API 参考](../reference/http-api.md)
- [实现自定义存储适配器](../guides/storage-adapter.md)
