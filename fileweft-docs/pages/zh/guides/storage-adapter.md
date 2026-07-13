---
route: "guides/storage-adapter"
group: "guides"
order: 4
locale: "zh"
nav: "存储适配器"
title: "实现存储适配器"
lead: "通过实现 StorageAdapter SPI 添加新后端。下面的示例把对象存到本地文件系统。"
format: "markdown"
---

## 存储契约

存储适配器负责单分片与多分片上传、下载、删除、存在性检查和临时访问 URL。FileWeft 负责构造对象名和元数据，适配器只需把字节落地。

## 本地文件系统适配器

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

    override fun uploadPart(upload: MultipartUpload, partNumber: Int, content: InputStream, contentLength: Long): MultipartPart {
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
