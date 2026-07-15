---
route: "getting-started/first-integration"
group: "getting-started"
order: 3
locale: "zh"
nav: "首次接入"
title: "接入可信宿主"
lead: "从依赖声明走向生产宿主：提供可信身份上下文、共享存储、PostgreSQL schema 归属，并拆分运行时角色。"
format: "markdown"
---

## 这页讲什么

把 FlowWeft 加入 classpath 只是第一步。生产宿主必须在每次请求时回答三个问题：

1. 这是哪个租户？
2. 用户是谁？
3. 他是否有权做这件事？

这页展示如何接入 FlowWeft 期望的三个 SPI bean、如何对齐 PostgreSQL 与存储归属，以及如何拆分 API 节点与 Worker 节点，避免后台租约拖慢 HTTP 请求。

## 步骤一：提供可信身份上下文

FlowWeft 不会从 HTTP 参数读取租户、用户、角色或权限结果。你必须从宿主已经认证的数据中提供三个 bean。

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
        // 从宿主已认证上下文解析：JWT claim、Header、路径等
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
        // 在宿主目录中查询，或在匹配时返回 currentUser()
        return currentUser()?.takeIf { it.id == userId }
    }
}

@Component
class HostAuthorizationProvider : AuthorizationProvider {
    override fun authorize(request: AuthorizationRequest): AuthorizationDecision {
        // 委托给宿主的 ACL / 策略引擎
        val allowed = checkHostPolicy(request)
        return AuthorizationDecision(allowed = allowed)
    }
}
```

> [!WARNING]
> Controller 不能把租户 ID、用户 ID、角色或权限结果作为业务参数接收。只信任宿主已经完成认证和授权的数据。

## 步骤二：遵守用户 ID 安全规则

跨越 FlowWeft 边界时，用户 ID 应视为不透明字符串。把 Long、Int、UUID 或外部目录 ID 在宿主层转为永久稳定的字符串格式。

合法的 FlowWeft 用户 ID：

- 区分大小写；
- 最多 256 个 UTF-16 码元；
- 首尾无 Unicode 空白；
- 不含 ISO 控制字符或 FlowWeft 拒绝的格式控制字符。

> [!NOTE]
> 不要在 FlowWeft 内部裁剪、转小写或归一化 ID。在宿主层一次性、一致地处理后再转成 `Identifier`。

## 步骤三：选择存储和数据库归属

多节点部署需要共享、持久的 `StorageAdapter`。不要跨 Pod 或机器依赖本地文件系统 fallback。

FlowWeft 不选择宿主连接池。下面是 Boot 3 单 DataSource 宿主的最小推荐依赖；如果已有自定义连接池，可以用能创建唯一 `DataSource` Bean 的等价 JDBC 方案替换第一项：

```kotlin
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("ai.icen:fileweft-spring-boot3-starter:0.0.3")
    implementation("ai.icen:fileweft-web-spring-boot3-starter:0.0.3")
    runtimeOnly("org.postgresql:postgresql")
}
```

Boot 2 宿主保留 `spring-boot-starter-jdbc`，并把两个 FlowWeft 坐标改为对应的 `boot2` 版本。Boot 2.7 BOM 默认的 Kotlin 1.6.21 低于 FlowWeft 的 2.1.21，纯 Java 宿主也必须对齐：Spring Dependency Management 设置 `extra["kotlin.version"] = "2.1.21"`，Maven 设置 `<kotlin.version>2.1.21</kotlin.version>`，原生 Gradle platform 同时导入 `org.jetbrains.kotlin:kotlin-bom:2.1.21` 或采用等价显式解析规则。普通 Kotlin BOM 不能覆盖 Boot 2 `enforcedPlatform`；请用 `dependencyInsight` 确认 `kotlin-stdlib` 为 2.1.21。

使用 PostgreSQL 时，DataSource 的当前 schema 必须与 FlowWeft 的 schema 断言一致：

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

| 模式 | 适用场景 |
| --- | --- |
| `migrate` | 开发环境，或由 FlowWeft 自行管理 schema 生命周期。 |
| `validate` | 生产环境：启动时校验 schema 与 Flyway 迁移一致。 |
| `disabled` | 你自行管理 schema 变更；FlowWeft 跳过迁移检查。 |

> [!TIP]
> 生产环境建议用自己的 DDL 流程或专用迁移用户创建 schema，然后以 `validate` 和 `create-schema: false` 运行 FlowWeft。

## 步骤四：拆分运行时角色

FlowWeft 有两种运行角色。生产环境建议拆分为不同进程组：

| 角色 | 职责 | 典型配置 |
| --- | --- | --- |
| **API 节点** | 处理 HTTP 请求 | 启用 Web starter；Worker 禁用或只读。 |
| **Worker 节点** | 消费 Outbox 事件、任务队列、上传清理 | Runtime starter；`process-outbox: true`；不暴露 HTTP 接口。 |

仅作为 Worker 的节点示例：

```yaml
fileweft:
  worker:
    enabled: true
    process-outbox: true
    process-tasks: true
    process-upload-cleanup: true
```

这样 HTTP 延迟与后台租约竞争、重试风暴互不干扰。

## 常见问题

**Q: 能否在一个节点同时启用 API 和 Worker？**

可以，适用于本地开发或极小规模部署。生产环境建议拆分，以便独立扩缩容、部署和重启。

**Q: FlowWeft 是否提供默认租户 Provider？**

有一个仅用于开发的 fallback（`fileweft.default-tenant-enabled=true`），但它不是生产多租户方案。务必从宿主认证上下文中实现 `TenantProvider`。

**Q: 目录文件夹存在哪里？**

目录拓扑由宿主通过 `DocumentCatalogProvider` SPI 维护。FlowWeft 只在资产 metadata 中写入保留键 `catalog.folder-id`，对象存储路径本身不包含目录 ID。

## 下一步

- [在笔记本上跑通 5 分钟快速开始](quickstart.md)
- [实现存储适配器](../guides/storage-adapter.md)
- [阅读配置参考](../reference/configuration.md)
