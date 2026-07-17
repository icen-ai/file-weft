---
route: "reference/spi"
group: "reference"
order: 1
locale: "zh"
nav: "SPI 索引"
title: "SPI 总览"
lead: "FlowWeft 围绕契约而非具体厂商构建。SPI 让你在不修改框架内部的前提下，接入身份、存储、目录、工作流、连接器、诊断和后台任务。"
format: "markdown"
---

## SPI 解决什么问题

企业部署很少共享同一个身份提供商、对象存储或审批系统。如果把这些硬编码进来，FlowWeft 就会从基础设施退化成产品。SPI 保证依赖方向始终单向：

```
starter → application → domain → core
                         ↑
                    adapter → spi
```

宿主应用实现 SPI，FlowWeft 调用你的 Bean，而厂商 SDK 只留在你的适配器里。

## 扩展族一览

| 领域 | 契约 | 你提供什么 |
|------|------|-----------|
| 身份与租户 | `TenantProvider`、`UserRealmProvider`、`AuthorizationProvider` | 当前可信租户、当前用户和访问决策 |
| 存储 | `StorageAdapter` | 任意后端的上传、下载、分片、预签名 URL 和删除 |
| 目录 | `DocumentCatalogProvider` | 目录拓扑和动作级 ACL |
| 工作流 | `DocumentReviewRouteProvider` | 审批路由与任务定义 |
| 连接器 | `FileConnector` | 幂等的下游同步、撤回与健康检查 |
| Doctor | `DoctorChecker` | 有界、无副作用的诊断 |
| 任务 | `FileWeftTaskHandler` | 通用持久任务 Handler |
| 遗留 Agent ABI | `FileWeftAgent`、`AgentTaskTrigger` | 仅兼容保留；0.0.2 与 0.0.3 默认都不注册或暴露 |

> [!CAUTION]
> `fileweft-agent` 和 Agent SPI 类型的存在不代表 0.0.2 或 0.0.3 提供 Agent 产品能力。它们只用于源码/二进制兼容；新集成应使用当前通用 SPI（例如 `FileWeftTaskHandler`、`FileConnector`），而不是遗留 Agent ABI。

## 身份与租户

FlowWeft 不会信任请求参数里的 `tenantId`，它会向宿主索取当前上下文。

```kotlin
@Component
class HeaderTenantProvider : TenantProvider {

    override fun currentTenant(): TenantContext {
        val request = RequestContextHolder.currentRequestAttributes() as ServletRequestAttributes
        val tenantId = request.request.getHeader("X-Tenant-Id")
            ?: throw IllegalStateException("缺少租户头")
        return TenantContext(Identifier(tenantId))
    }
}
```

授权是独立契约，便于复用现有策略引擎：

```kotlin
@Component
class AclAuthorizationProvider(private val aclService: AclService) : AuthorizationProvider {

    override fun authorize(request: AuthorizationRequest): AuthorizationDecision {
        val permitted = aclService.may(
            subject = request.subject.id,
            resource = request.resource,
            action = request.action,
        )
        return AuthorizationDecision(allowed = permitted)
    }
}
```

> [!WARNING]
> 用户 ID 是最长 256 个 UTF-16 code units 的不透明字符串。不要在 Provider 里 trim、大小写折叠或归一化。

## 存储适配器

存储适配器只负责落地字节。对象名、元数据和租户前缀由 FlowWeft 负责。

```kotlin
@Component
class MinioStorageAdapter(private val minioClient: MinioClient) : StorageAdapter {

    override fun upload(request: StorageUploadRequest, content: InputStream): StoredObject {
        val bucket = resolveBucket(request.tenantId)
        minioClient.putObject(
            PutObjectArgs.builder()
                .bucket(bucket)
                .`object`(request.objectName)
                .stream(content, request.contentLength, -1)
                .contentType(request.contentType)
                .build()
        )
        return StoredObject(
            location = StorageObjectLocation("minio", "${bucket}/${request.objectName}"),
            contentLength = request.contentLength,
            contentType = request.contentType,
        )
    }

    override fun download(location: StorageObjectLocation): StorageDownload {
        val (bucket, objectName) = parseLocation(location)
        val response = minioClient.getObject(bucket, objectName)
        return StorageDownload(
            content = response,
            contentLength = response.headers()["Content-Length"]?.toLong(),
            contentType = response.headers()["Content-Type"],
        )
    }

    override fun accessUrl(location: StorageObjectLocation, expiresIn: Duration): URI {
        val (bucket, objectName) = parseLocation(location)
        return minioClient.getPresignedObjectUrl(
            GetPresignedObjectUrlArgs.builder()
                .method(Method.GET)
                .bucket(bucket)
                .`object`(objectName)
                .expiry(expiresIn.toMillis().toInt())
                .build()
        ).let { URI.create(it) }
    }

    // 删除、存在性、分片等方法此处省略
}
```

> [!NOTE]
> `StorageObjectLocation` 对调用方应保持不透明，不要在 REST 响应里暴露后端细节。

## 连接器

连接器把文档交付到下游系统，必须具备幂等、重试和健康上报能力。

```kotlin
@Component("complianceConnector")
class ComplianceConnector : FileConnector {

    override fun sync(request: ConnectorSyncRequest): ConnectorSyncResult {
        val externalId = archiveClient.submit(request.source.downloadUri, request.attributes)
        return ConnectorSyncResult(
            status = ConnectorSyncStatus.SUCCESS,
            externalId = externalId,
        )
    }

    override fun remove(request: ConnectorRemoveRequest): ConnectorSyncResult {
        archiveClient.delete(request.externalId)
        return ConnectorSyncResult(
            status = ConnectorSyncStatus.SUCCESS,
            externalId = request.externalId,
        )
    }

    override fun health(): ConnectorHealth {
        return when (archiveClient.ping()) {
            is PingResult.Ok -> ConnectorHealth(ConnectorHealthStatus.HEALTHY)
            is PingResult.Timeout -> ConnectorHealth(ConnectorHealthStatus.DEGRADED)
            else -> ConnectorHealth(ConnectorHealthStatus.UNHEALTHY)
        }
    }
}
```

> [!TIP]
> 把连接器当作不可靠的外部系统。务必实现超时、重试和幂等；FlowWeft 会把失败路由到 Outbox，让运维人员可以针对单个目标重试。

## 公共 API 纪律

SPI 契约属于公共 API，必须保持 Java 友好。禁止在公共方法中使用：

- `suspend` 函数
- Kotlin `Flow`
- `value class`
- `sealed interface`
- `data object`

标识符保持不透明字符串，厂商 SDK 模型只留在 adapter 模块。

## 注册实现

FlowWeft 通过 Spring 自动装配发现 Bean，优先级为：

1. 宿主应用里定义的 Bean
2. `FileWeftPlugin` 贡献的 Bean
3. 框架默认（仅开发 fallback）

```kotlin
@Configuration
class FileWeftHostConfig {

    @Bean
    fun tenantProvider(): TenantProvider = HeaderTenantProvider()

    @Bean
    fun storageAdapter(minioClient: MinioClient): StorageAdapter =
        MinioStorageAdapter(minioClient)
}
```

## 常见问题

**一个适配器可以实现多个 SPI 吗？**
可以。一个插件或配置类可以同时提供存储、连接器、Doctor 检查器和任务处理器。

**必须实现所有 SPI 吗？**
不需要。只实现部署需要的 SPI。缺少租户等强制上下文时，操作会安全失败，而不是退回到共享默认值。

**厂商 SDK 应该放在哪里？**
放在依赖 SPI 的 adapter 模块里，绝不要放进 `core`、`domain` 或 `spi`。

## 下一步

- [实现存储适配器](../guides/storage-adapter.md)
- [构建连接器](../extensions/connectors.md)
- [使用 Doctor 添加诊断](../operations/doctor-observability.md)
