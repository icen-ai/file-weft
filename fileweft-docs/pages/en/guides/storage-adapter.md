---
route: "guides/storage-adapter"
group: "guides"
order: 4
locale: "en"
nav: "Storage adapter"
title: "Implement a storage adapter"
lead: "Add a new object backend by implementing the StorageAdapter SPI. FileWeft builds object names and metadata; your adapter only materializes bytes."
format: "markdown"
---

FileWeft is storage-agnostic at the core. Whether you store objects on MinIO, Alibaba Cloud OSS, Amazon S3, or a local filesystem, the contract is the same. This page walks through a complete `StorageAdapter` implementation and explains where it plugs in.

## 1. The storage contract

A storage adapter must handle:

| Operation | Method | Typical use |
| --- | --- | --- |
| Single-part upload | `upload` | Small files or direct controller uploads. |
| Multipart upload | `beginMultipartUpload`, `uploadPart`, `completeMultipartUpload`, `abortMultipartUpload` | Resumable upload protocol. |
| Download | `download` | Serving document content. |
| Delete | `delete` | Lifecycle removal and cleanup. |
| Existence check | `exists` | Doctor checks and idempotency. |
| Temporary URL | `accessUrl` | Connector sync and source access. |

FileWeft constructs the object name, supplies tenant-scoped metadata, and expects a `StorageObjectLocation` back. The adapter is responsible only for durability, concurrency, and cleanup.

## 2. Local filesystem adapter

The example below stores objects under the user's home directory. It is suitable for development and single-node tests, not for production clusters.

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
        // Local filesystem has no presigned URL; return a file URI.
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
> Multipart uploads are used by the resumable upload resource. Single-part uploads go through `upload()`.

## 3. Register through a plugin

For reusable adapters, package them inside a `FileWeftPlugin` instead of exposing a raw `@Component`.

```kotlin
class MinioStoragePlugin : FileWeftPlugin {

    override fun id(): String = "minio-storage"

    override fun storageAdapters(): List<StorageAdapter> =
        listOf(MinioStorageAdapter(minioClient()))
}
```

Priority order is: host bean, plugin bean, framework default. This lets operators override a vendor adapter without rebuilding the plugin.

## 4. Production checklist

Before using an adapter in production, verify:

1. Object names include the tenant ID for isolation.
2. Multipart uploads enforce minimum part sizes required by the backend.
3. Temporary URLs expire within the configured TTL.
4. Delete operations are idempotent and do not throw on missing objects.
5. A `DoctorChecker` reports adapter health.

> [!WARNING]
> Do not use the local filesystem fallback for production multi-tenant deployments. It is single-node, unshared storage suitable only for development.

## FAQ

**Q: Can one adapter delegate to multiple backends?**
Yes. An adapter can choose a backend based on tenant, file size, or document type, as long as it exposes a single `StorageAdapter` bean.

**Q: Does FileWeft support presigned URL upload from the browser?**
No. Browser uploads use the resumable upload resource; storage presigned URLs are an internal adapter concern.

**Q: How are storage upload IDs kept secret?**
The resumable upload resource returns only a FileWeft `uploadId`. Storage upload IDs and ETags never leave the adapter.

## Next steps

- [Resumable upload protocol](resumable-upload.md) to see how multipart methods are invoked.
- [Connectors](../extensions/connectors.md) to deliver documents to downstream systems.
- [Doctor and observability](../operations/doctor-observability.md) to add health checks.
