---
route: "concepts/module-boundaries"
group: "concepts"
order: 1
locale: "en"
nav: "Module boundaries"
title: "Module boundaries"
lead: "Why does FlowWeft split its code into separate modules? Because a storage SDK that leaks into your domain makes the framework impossible to upgrade or replace. This page shows the dependency direction, what each module owns, and how to add a new adapter without crossing the line."
format: "markdown"
---

## 01. The layer stack

FlowWeft has two dependency flows that never mix:

```
starter → application → domain → core

adapter → spi
```

- `core` is the innermost ring. It contains identifiers, result models, errors, events and contexts, and it knows nothing about Spring, PostgreSQL or MinIO.
- `spi` defines contracts: tenant, identity, authorization, storage, connectors, workflow, AI and tasks. It has no implementation.
- `domain` contains the business rules: `Document`, `FileAsset`, lifecycle and versioning. It depends on `core` and `spi` only.
- `application` orchestrates use cases such as upload, publish, offline and Doctor. It calls domain objects and persists through repositories, but it never talks to MinIO or Dify directly.
- `adapter` is where host or plugin external implementations belong. Named OSS, Dify, ESE and AppBuilder official adapters remain future roadmap work; adapters depend on `spi`.
- `persistence` implements repositories and Flyway migrations.
- `runtime` and `starter` package the web layer and Spring Boot auto-configuration.

> [!NOTE]
> The arrow direction is a compile-time rule, not a runtime suggestion. If a class in `core` imports Spring or a vendor SDK, the architecture has been broken.

## 02. What each module owns

| Module | Owns | Must never contain |
|---|---|---|
| `fileweft-core` | identifiers, results, errors, events, contexts | Spring, ORM, vendor SDK |
| `fileweft-spi` | contracts for storage, identity, tenant, authorization, connectors, tasks, doctor | implementations, vendor types |
| `fileweft-domain` | `Document`, `FileAsset`, lifecycle, version, audit rules | database queries, HTTP, SDK calls |
| `fileweft-application` | upload, publish, offline, Doctor, synchronization use cases | direct storage/connector access |
| `fileweft-adapter-*` | External-system adapter boundary; current support claims are limited to implementations backed by repository evidence | Business rules |
| `fileweft-persistence` | repository implementations, Flyway migrations, tenant-scoped SQL | business logic |
| `fileweft-web-runtime` / `fileweft-spring-boot3-starter` | HTTP controllers, DTO conversion, auto-configuration | repository/storage/connector calls |

## 03. Adding a storage adapter the right way

If your organization stores files on a proprietary object store, create a new adapter module instead of patching `domain` or `application`.

### Step 1 — Add the SPI dependency

```kotlin
// fileweft-adapter-acme/build.gradle.kts
dependencies {
    implementation("ai.icen:fileweft-spi:0.0.3")
}
```

### Step 2 — Implement `StorageAdapter` inside the adapter

Vendor types stay private. The example below uses the local filesystem so the code is self-contained, but the boundary rule is the same for S3, MinIO or any proprietary store.

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

### Step 3 — Register the adapter through a plugin or bean

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
> Do not put the vendor client in `fileweft-domain` or `fileweft-application`. If business code imports a vendor class, the boundary has been crossed.

## 04. Forbidden shortcuts

- **Core must not depend on Spring or a database.** If `core` needed a transaction manager, every SPI user would need Spring.
- **Domain must not call MinIO, Dify or any vendor SDK.** Domain decides *what* to store; adapters decide *where*.
- **SPI must not expose vendor types.** An interface that returns an S3 `PutObjectResult` forces all callers to depend on AWS.
- **Controllers validate and convert; they do not access storage or repositories.** Controllers call application services.

## 05. A quick smell test

Ask these questions before you commit a change:

1. Does the new class import a vendor SDK? If yes, it belongs in an adapter.
2. Does it issue SQL? If yes, it belongs in `persistence`.
3. Does it know about HTTP headers? If yes, it belongs in `runtime`/`starter`.
4. Does it express a business invariant? If yes, it belongs in `domain`.
5. Does it coordinate several domain objects to fulfil a user goal? If yes, it belongs in `application`.

If a single class answers “yes” to more than one of these, split it.

## FAQ

**Q: Can `application` use a repository interface?**
Yes, but the interface should live in `domain` or `spi`, and the implementation must live in `persistence`. The application layer orchestrates; it does not execute SQL.

**Q: My connector needs domain data. Is that allowed?**
A connector (adapter) receives everything it needs through `ConnectorSyncRequest`. It must not reach back into `domain` services. Adapter depends on SPI; SPI does not depend on adapter.

**Q: Where do I add a new lifecycle rule?**
In `fileweft-domain`. The rule is a pure business invariant with no infrastructure calls.

## Next steps

- [Storage adapter guide](../guides/storage-adapter.md) — implement `StorageAdapter` end to end.
- [Plugins](../extensions/plugins.md) — package adapters, doctor checkers and task handlers as a plugin.
- [Security architecture](../architecture/security.md) — how fail-closed design protects every boundary.
