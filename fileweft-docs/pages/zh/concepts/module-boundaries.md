---
route: "concepts/module-boundaries"
group: "concepts"
order: 1
locale: "zh"
nav: "模块边界"
title: "模块边界"
lead: "为什么 FileWeft 要把代码拆到多个模块？因为一旦存储 SDK 泄漏到领域层，框架就变得难以升级和替换。本页说明依赖方向、每个模块的职责，以及如何在不过线的前提下新增适配器。"
format: "markdown"
---

## 01. 分层结构

FileWeft 有两条不会交叉的依赖流：

```
starter → application → domain → core

adapter → spi
```

- `core` 是最内环，只包含标识、结果模型、错误、事件与上下文，不依赖 Spring、PostgreSQL 或 MinIO。
- `spi` 定义契约：租户、身份、授权、存储、连接器、工作流、AI 与任务。它不包含实现。
- `domain` 包含业务规则：`Document`、`FileAsset`、生命周期与版本。它只依赖 `core` 和 `spi`。
- `application` 编排用例，如上传、发布、下线、Doctor。它调用领域对象并通过仓库持久化，但绝不直接调用 MinIO 或 Dify。
- `adapter` 是宿主或插件外部实现的归属层。OSS、Dify、ESE、AppBuilder 官方适配器仍是未来路线图工作；适配器依赖 `spi`。
- `persistence` 实现仓库与 Flyway 迁移。
- `runtime` 和 `starter` 打包 Web 层与 Spring Boot 自动配置。

> [!NOTE]
> 箭头方向是编译期规则，不是运行期建议。如果 `core` 里出现了 Spring 或厂商 SDK 的导入，架构就已经被破坏。

## 02. 每个模块拥有什么

| 模块 | 负责 | 绝不能包含 |
|---|---|---|
| `fileweft-core` | 标识、结果、错误、事件、上下文 | Spring、ORM、厂商 SDK |
| `fileweft-spi` | 存储、身份、租户、授权、连接器、任务、诊断等契约 | 实现、厂商类型 |
| `fileweft-domain` | `Document`、`FileAsset`、生命周期、版本、审计规则 | 数据库查询、HTTP、SDK 调用 |
| `fileweft-application` | 上传、发布、下线、Doctor、同步编排等用例 | 直接访问存储/连接器 |
| `fileweft-adapter-*` | 外部系统适配边界；当前支持声明只覆盖仓库中已有真实证据的实现 | 业务规则 |
| `fileweft-persistence` | 仓库实现、Flyway 迁移、租户级 SQL | 业务逻辑 |
| `fileweft-web-runtime` / `fileweft-spring-boot3-starter` | HTTP 控制器、DTO 转换、自动配置 | 仓库/存储/连接器调用 |

## 03. 正确地新增存储适配器

如果你的组织使用专有对象存储，应该新建一个适配器模块，而不是去改 `domain` 或 `application`。

### 步骤 1 — 添加 SPI 依赖

```kotlin
// fileweft-adapter-acme/build.gradle.kts
dependencies {
    implementation("ai.icen:fileweft-spi:0.0.2")
}
```

### 步骤 2 — 在适配器内实现 `StorageAdapter`

厂商类型必须保持私有。下面的示例使用本地文件系统，让代码自成一体；但无论后端是 S3、MinIO 还是专有存储，边界规则都一样。

```kotlin
package ai.icen.fw.adapter.local.storage

import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.storage.MultipartPart
import ai.icen.fw.spi.storage.MultipartUpload
import ai.icen.fw.spi.storage.StorageAdapter
import ai.icen.fw.spi.storage.StorageDownload
import ai.icen.fw.spi.storage.StorageObjectLocation
import ai.icen.fw.spi.storage.StorageUploadRequest
import ai.icen.fw.spi.storage.StoredObject
import java.io.InputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.Duration
import java.util.UUID

class LocalStorageAdapter(private val root: Path) : StorageAdapter {

    override fun upload(request: StorageUploadRequest, content: InputStream): StoredObject {
        val target = root.resolve(request.tenantId.value).resolve(request.objectName)
        Files.createDirectories(target.parent)
        Files.copy(content, target, StandardCopyOption.REPLACE_EXISTING)
        return StoredObject(
            location = StorageObjectLocation("local", target.toString()),
            contentLength = request.contentLength,
            contentType = request.contentType,
            contentHash = request.contentHash
        )
    }

    override fun download(location: StorageObjectLocation): StorageDownload {
        val source = Path.of(location.path)
        return StorageDownload(
            content = Files.newInputStream(source),
            contentLength = Files.size(source),
            contentType = Files.probeContentType(source)
        )
    }

    override fun delete(location: StorageObjectLocation) {
        Files.deleteIfExists(Path.of(location.path))
    }

    override fun exists(location: StorageObjectLocation): Boolean =
        Files.exists(Path.of(location.path))

    override fun accessUrl(location: StorageObjectLocation, expiresIn: Duration): URI =
        throw UnsupportedOperationException("Local adapter does not expose presigned URLs")

    override fun beginMultipartUpload(request: StorageUploadRequest): MultipartUpload {
        val uploadId = Identifier(UUID.randomUUID().toString())
        val location = StorageObjectLocation("local", "${request.tenantId.value}/${request.objectName}")
        Files.createDirectories(root.resolve("multipart").resolve(uploadId.value))
        return MultipartUpload(uploadId, location)
    }

    override fun uploadPart(
        upload: MultipartUpload,
        partNumber: Int,
        content: InputStream,
        contentLength: Long
    ): MultipartPart {
        val partPath = root.resolve("multipart").resolve(upload.uploadId.value).resolve(partNumber.toString())
        Files.createDirectories(partPath.parent)
        Files.copy(content, partPath, StandardCopyOption.REPLACE_EXISTING)
        return MultipartPart(partNumber, "local-$partNumber")
    }

    override fun completeMultipartUpload(upload: MultipartUpload, parts: List<MultipartPart>): StoredObject {
        val target = root.resolve(upload.location.path)
        Files.createDirectories(target.parent)
        Files.newOutputStream(target, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING).use { out ->
            parts.sortedBy { it.partNumber }.forEach { part ->
                val partPath = root.resolve("multipart").resolve(upload.uploadId.value).resolve(part.partNumber.toString())
                Files.newInputStream(partPath).use { input ->
                    input.copyTo(out)
                }
            }
        }
        return StoredObject(upload.location, Files.size(target), null, null)
    }

    override fun abortMultipartUpload(upload: MultipartUpload) {
        val dir = root.resolve("multipart").resolve(upload.uploadId.value)
        Files.walk(dir).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }
}
```

### 步骤 3 — 通过插件或 Bean 注册适配器

```kotlin
package ai.icen.fw.adapter.local

import ai.icen.fw.spi.plugin.FileWeftPlugin
import ai.icen.fw.adapter.local.storage.LocalStorageAdapter
import org.springframework.stereotype.Component
import java.nio.file.Paths

@Component
class LocalStoragePlugin : FileWeftPlugin {
    override fun id() = "local-storage"
    override fun storageAdapters() = listOf(LocalStorageAdapter(Paths.get("/var/lib/fileweft")))
}
```

> [!WARNING]
> 不要把厂商客户端放到 `fileweft-domain` 或 `fileweft-application`。如果业务代码里出现了厂商类，边界就已经被突破。

## 04. 禁止的捷径

- **Core 不能依赖 Spring 或数据库。** 如果 `core` 需要事务管理器，每个 SPI 使用者都得引入 Spring。
- **Domain 不能调用 MinIO、Dify 等厂商 SDK。** Domain 决定“存什么”，适配器决定“存到哪”。
- **SPI 不能暴露厂商类型。** 返回 S3 `PutObjectResult` 的接口会迫使所有调用方依赖 AWS。
- **Controller 只负责校验与转换，不访问存储或仓库。** Controller 调用应用服务。

## 05. 快速自查

提交改动前问自己：

1. 新类是否导入了厂商 SDK？如果是，它属于适配器。
2. 它是否执行 SQL？如果是，它属于 `persistence`。
3. 它是否关心 HTTP 头？如果是，它属于 `runtime`/`starter`。
4. 它是否表达业务不变量？如果是，它属于 `domain`。
5. 它是否协调多个领域对象以完成用户目标？如果是，它属于 `application`。

如果一个类同时满足多条，请拆分它。

## 常见问题

**Q：`application` 可以使用仓库接口吗？**
可以，但接口应位于 `domain` 或 `spi`，实现必须位于 `persistence`。应用层负责编排，不执行 SQL。

**Q：我的连接器需要领域数据，这允许吗？**
连接器（适配器）通过 `ConnectorSyncRequest` 接收所需的一切数据。它不能回调 `domain` 服务。适配器依赖 SPI；SPI 不依赖适配器。

**Q：新增生命周期规则应该放在哪？**
放在 `fileweft-domain`。规则是纯业务不变量，不能包含基础设施调用。

## 下一步

- [存储适配器指南](../guides/storage-adapter.md)——从头到尾实现 `StorageAdapter`。
- [插件](../extensions/plugins.md)——把适配器、Doctor 检查器与任务处理器打包成插件。
- [安全架构](../architecture/security.md)——故障关闭设计如何保护每个边界。
