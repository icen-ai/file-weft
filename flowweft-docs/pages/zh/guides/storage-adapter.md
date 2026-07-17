---
route: "guides/storage-adapter"
group: "guides"
order: 4
locale: "zh"
nav: "存储适配器"
title: "实现存储适配器"
lead: "通过实现 StorageAdapter SPI 添加新的对象后端。FlowWeft 负责构造对象名和元数据，你的适配器只需把字节落地。"
format: "markdown"
---

FlowWeft 在核心层不绑定任何存储。无论你用 MinIO、阿里云 OSS、Amazon S3 还是本地文件系统，契约都相同。本页给出一个完整的 `StorageAdapter` 实现，并说明它的装配位置。

## 1. 存储契约

存储适配器必须处理：

| 操作 | 方法 | 典型用途 |
| --- | --- | --- |
| 单分片上传 | `upload` | 小文件或直接控制器上传。 |
| 多分片上传 | `beginMultipartUpload`、`uploadPart`、`completeMultipartUpload`、`abortMultipartUpload` | 断点续传协议。 |
| 下载 | `download` | 提供文档内容。 |
| 删除 | `delete` | 生命周期移除和清理。 |
| 存在性检查 | `exists` | Doctor 检查与幂等。 |
| 临时 URL | `accessUrl` | 连接器同步和源访问。 |

FlowWeft 构造对象名、提供租户级元数据，并期望返回 `StorageObjectLocation`。适配器只负责持久性、并发和清理。

## 2. 本地文件系统适配器

下面示例把对象存到用户主目录下，适用于开发和单节点测试，不适用于生产集群。

```kotlin
@Component
class LocalFileStorageAdapter : StorageAdapter {

    private val root = Paths.get(System.getProperty("user.home"), "fileweft-store")

    override fun upload(request: StorageUploadRequest, content: InputStream): StoredObject {
        val path = resolvePath(request.tenantId, request.objectName)
        Files.createDirectories(path.parent)
        Files.copy(content, path, StandardCopyOption.REPLACE_EXISTING)
        return StoredObject(
            location = StorageObjectLocation("local", path.toString()),
            contentLength = request.contentLength,
            contentType = request.contentType,
        )
    }

    override fun download(location: StorageObjectLocation): StorageDownload {
        val path = Paths.get(location.path)
        return StorageDownload(
            content = Files.newInputStream(path),
            contentLength = Files.size(path),
            contentType = Files.probeContentType(path),
        )
    }

    override fun delete(location: StorageObjectLocation) {
        Files.deleteIfExists(Paths.get(location.path))
    }

    override fun exists(location: StorageObjectLocation): Boolean =
        Files.exists(Paths.get(location.path))

    override fun accessUrl(location: StorageObjectLocation, expiresIn: Duration): URI {
        // 本地文件系统没有预签名 URL，返回 file URI。
        return Paths.get(location.path).toUri()
    }

    override fun beginMultipartUpload(request: StorageUploadRequest): MultipartUpload {
        val uploadId = UUID.randomUUID().toString()
        val dir = resolvePath(request.tenantId, ".uploads/$uploadId")
        Files.createDirectories(dir)
        return MultipartUpload(uploadId, location = StorageObjectLocation("local", dir.toString()))
    }

    override fun uploadPart(
        upload: MultipartUpload,
        partNumber: Int,
        content: InputStream,
        contentLength: Long,
    ): MultipartPart {
        val partPath = Paths.get(upload.location.path, "part-$partNumber")
        Files.copy(content, partPath, StandardCopyOption.REPLACE_EXISTING)
        return MultipartPart(partNumber.toString(), partNumber, contentLength)
    }

    override fun completeMultipartUpload(upload: MultipartUpload, parts: List<MultipartPart>): StoredObject {
        val target = Paths.get(upload.location.path).parent.resolve("object")
        Files.newOutputStream(target).use { out ->
            parts.sortedBy { it.partNumber }.forEach { part ->
                Files.newInputStream(Paths.get(upload.location.path, "part-${part.partNumber}")).use {
                    it.copyTo(out)
                }
            }
        }
        return StoredObject(
            location = StorageObjectLocation("local", target.toString()),
            contentLength = parts.sumOf { it.contentLength },
        )
    }

    override fun abortMultipartUpload(upload: MultipartUpload) {
        Paths.get(upload.location.path).toFile().deleteRecursively()
    }

    private fun resolvePath(tenantId: Identifier, objectName: String): Path =
        root.resolve(tenantId.value).resolve(objectName)
}
```

> [!NOTE]
> 断点续传资源使用多分片上传；普通单分片上传走 `upload()`。

## 3. 通过插件注册

如果你想让适配器可复用，可以把它打包进 `FileWeftPlugin`，而不是直接暴露 `@Component`。

```kotlin
class MinioStoragePlugin : FileWeftPlugin {

    override fun id(): String = "minio-storage"

    override fun storageAdapters(): List<StorageAdapter> =
        listOf(MinioStorageAdapter(minioClient()))
}
```

优先级为：宿主 Bean > 插件 Bean > 框架默认。这样运维可以在不重编插件的情况下覆盖厂商适配器。

## 4. 生产检查清单

在把适配器用于生产前，请确认：

1. 对象名包含租户 ID 以实现隔离。
2. 多分片上传强制后端要求的最小分片大小。
3. 临时 URL 在配置 TTL 内过期。
4. 删除操作幂等，对象不存在时不抛异常。
5. 提供 `DoctorChecker` 报告适配器健康状态。

> [!WARNING]
> 不要把本地文件系统 fallback 用于生产多租户部署。它只适合开发的单节点、非共享存储。

## 常见问题

**Q：一个适配器可以代理多个后端吗？**
可以。适配器可以按租户、文件大小或文档类型选择后端，只要对外暴露一个 `StorageAdapter` Bean。

**Q：FlowWeft 支持浏览器直接拿预签名 URL 上传吗？**
不支持。浏览器上传使用断点续传资源；存储预签名 URL 属于适配器内部。

**Q：存储 upload ID 如何保密？**
断点续传资源只返回 FlowWeft 的 `uploadId`。存储 upload ID 和 ETag 不会离开适配器。

## 下一步

- [断点续传协议](resumable-upload.md) 了解多分片方法如何被调用。
- [连接器](../extensions/connectors.md) 把文档交付到下游系统。
- [Doctor 与可观测性](../operations/doctor-observability.md) 添加健康检查。
