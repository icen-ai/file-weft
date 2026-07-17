---
route: "guides/spring-boot"
group: "guides"
order: 1
locale: "zh"
nav: "Spring Boot 宿主"
title: "将 FlowWeft 装配到 Spring Boot"
lead: "学习如何把 FlowWeft 作为基础设施库嵌入到 Spring Boot 应用中，同时保留对认证、DataSource 所有权和网关策略的完全控制。"
format: "markdown"
---

FlowWeft 不是独立应用，而是一组可装配到你宿主里的模块化组件。Starter 只负责自动装配 Bean，不会替代你的安全层、数据库连接池或运维决策。本页介绍最小且安全的装配方式。

## 1. 选择正确的 Starter 代际

| 宿主要求 | 运行时 Starter | HTTP Starter |
| --- | --- | --- |
| Spring Boot 3 / Java 17+ | `fileweft-spring-boot3-starter` | `fileweft-web-spring-boot3-starter` |
| Spring Boot 2.7 / Java 8+ | `fileweft-spring-boot2-starter` | `fileweft-web-spring-boot2-starter` |

只安装一个代际。Web Starter 暴露 `/fileweft/v1/**` 控制器；运行时 Starter 提供用例、Worker 和持久化，但不暴露 HTTP。

```kotlin
// build.gradle.kts（Spring Boot 3 示例）
dependencies {
    // 宿主持有 DataSource 与连接池；也可以替换为宿主选择的等价 JDBC 方案。
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("ai.icen:fileweft-spring-boot3-starter:0.0.3")
    implementation("ai.icen:fileweft-web-spring-boot3-starter:0.0.3")
}
```

> [!WARNING]
> 不要同时安装 Boot 2 和 Boot 3 的 Starter。它们的自动配置类针对不同 Spring Framework 版本，会互相冲突。

> [!IMPORTANT]
> Boot 2.7 BOM 默认的 Kotlin 1.6.21 低于 FlowWeft 的 2.1.21，纯 Java 宿主也必须对齐：Spring Dependency Management 设置 `extra["kotlin.version"] = "2.1.21"`，Maven 设置 `<kotlin.version>2.1.21</kotlin.version>`，原生 Gradle platform 同时导入 `org.jetbrains.kotlin:kotlin-bom:2.1.21` 或采用等价显式解析规则。普通 Kotlin BOM 不能覆盖 Boot 2 `enforcedPlatform`；请用 `dependencyInsight` 确认 `kotlin-stdlib` 为 2.1.21。

## 2. 由宿主持有 DataSource

FlowWeft 默认装配需要 Spring 能明确解析一个 `javax.sql.DataSource` 候选。只有一个 DataSource 时，Bean 名称可以任意；由你创建、调优连接池并负责凭据轮换。FlowWeft Starter 有意不传递引入 `spring-boot-starter-jdbc` 或 HikariCP，避免覆盖已有宿主的连接池选择；上面的依赖是使用 Boot 默认 JDBC 自动配置的推荐组合，已有连接池的宿主可提供等价的单一 `DataSource` Bean。

```kotlin
@Configuration
class FileWeftDataSourceConfig {

    @Bean
    @ConfigurationProperties("app.fileweft.datasource")
    fun dataSource(): DataSource {
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

> [!WARNING]
> 多 DataSource 宿主不能只把某个 Bean 命名为 `fileweftDataSource`；当前 Starter 不按名称选择它。请显式提供绑定到目标连接池的 `ApplicationTransaction`。当 `migration-mode` 为 `validate` 或 `migrate` 时，还必须为同一个连接池显式提供唯一的 `FlywayMigrationRunner`；Starter 只会在上下文中恰好有一个 DataSource 时自动创建迁移 Runner。

## 3. 提供身份与租户 SPI

FlowWeft 不提供任何弱默认认证。你必须暴露三个 Bean，让框架能够解析调用方身份并判断其是否有权操作。

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
        // 通过你的身份服务查询。
        return userService.findById(userId.value)
    }
}

@Component
class FileWeftAuthorizationProvider : AuthorizationProvider {
    override fun authorize(request: AuthorizationRequest): AuthorizationDecision {
        // 委托给你的 ABAC/RBAC 引擎。
        return policyEngine.evaluate(request)
    }
}
```

> [!NOTE]
> 用户 ID 被视为不透明字符串。不要在 Provider 内部 trim、大小写折叠或归一化。

## 4. 配置持久化与 Worker

最小生产配置只校验现有 schema 迁移，不会自动执行迁移。

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

本地单节点沙箱可以使用 `migration-mode: migrate`，但生产环境必须有经过评审的迁移流水线，不能自动执行 migrate。

## 5. 保护入口

以下配置应在宿主或网关层完成，而不是在 FlowWeft 内部：

- CORS 允许来源
- CSRF 令牌策略或无状态会话策略
- OAuth2/OIDC 登录与令牌校验
- 服务间 mTLS
- 最大请求体大小和分片路由流式限制
- 超时与限流

FlowWeft 控制器只负责请求校验和 DTO 转换，不会绕过你的 SPI 决策。

## 6. 验证启动

应用启动后，检查提供的健康端点：

```bash
curl http://localhost:8080/fileweft/v1/health
```

列出插件，确认存储适配器、目录 Provider 或连接器 Bean 已注册：

```bash
curl http://localhost:8080/fileweft/v1/plugins
```

## 常见问题

**Q：FlowWeft 可以脱离 Spring Boot 运行吗？**
可以。运行时 Starter 只是把它装配进 Spring，核心模块是 Kotlin/JVM 通用代码。你可以在 Micronaut、Quarkus 或普通 `main` 函数中手动组装。

**Q：Web Starter 会提供认证吗？**
不会。它依赖你提供的 `UserRealmProvider`、`TenantProvider` 和 `AuthorizationProvider` Bean。

**Q：Flyway 迁移脚本在哪里？**
迁移脚本位于 `fileweft-persistence` 并已版本化。生产环境使用 `migration-mode: validate`，变更应通过你自己的部署工具执行。

## 下一步

- [实现目录 Provider](catalog-provider.md)，把文档绑定到宿主目录树。
- [实现存储适配器](storage-adapter.md)，把字节落到 MinIO、OSS、S3 或本地文件系统。
- [配置断点续传](resumable-upload.md)，在网络不稳定时传输大文件。
