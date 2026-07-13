---
route: "guides/storage-adapter"
group: "guides"
order: 4
locale: "en"
nav: "Storage adapter"
title: "Implement a storage adapter"
lead: "Add a new backend by implementing the StorageAdapter SPI. The example below stores objects on the local filesystem."
format: "markdown"
---

## The storage contract

A storage adapter is responsible for single-part and multipart uploads, downloads, deletes, existence checks and temporary access URLs. FileWeft builds the object name and metadata; the adapter only needs to materialize bytes.

## Local filesystem adapter

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
> Multipart uploads are used by the resumable upload resource. Single-part uploads go through `upload()`.
