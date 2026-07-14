---
route: "reference/spi"
group: "reference"
order: 1
locale: "en"
nav: "SPI index"
title: "SPI surface"
lead: "FileWeft is built around contracts, not concrete vendors. The SPI lets you plug in identity, storage, catalogs, workflows, connectors, diagnostics, and background tasks without changing the framework internals."
format: "markdown"
---

## What the SPI solves

Enterprise deployments rarely share the same identity provider, object store, or review system. Hard-coding any of them would turn FileWeft from infrastructure into a product. The SPI keeps the dependency arrow pointing one way:

```
starter → application → domain → core
                         ↑
                    adapter → spi
```

Your host application implements the SPI, FileWeft calls your beans, and your vendor SDKs stay inside your adapters.

## Extension families at a glance

| Area | Contract | What you provide |
|------|----------|------------------|
| Identity & tenant | `TenantProvider`, `UserRealmProvider`, `AuthorizationProvider` | The current trusted tenant, the current user, and access decisions |
| Storage | `StorageAdapter` | Upload, download, multipart, presigned URLs, and deletes against any backend |
| Catalog | `DocumentCatalogProvider` | Folder topology and action-aware ACL |
| Workflow | `DocumentReviewRouteProvider` | Approval routes and task definitions |
| Connector | `FileConnector` | Idempotent downstream sync, removal, and health |
| Doctor | `DoctorChecker` | Bounded, side-effect-free diagnostics |
| Task | `FileWeftTaskHandler` | Generic durable task handlers |
| Legacy Agent ABI | `FileWeftAgent`, `AgentTaskTrigger` | Compatibility only; not registered or exposed by default in 0.0.2 or 0.0.3 |

> [!CAUTION]
> The presence of `fileweft-agent` and Agent SPI types does not mean that 0.0.2 or 0.0.3 provides Agent product capability. They exist only for source/binary compatibility. New integrations should use current generic SPIs such as `FileWeftTaskHandler` and `FileConnector`, not the legacy Agent ABI.

## Identity and tenant

FileWeft never trusts a `tenantId` from request parameters. It asks the host for the current context.

```kotlin
@Component
class HeaderTenantProvider : TenantProvider {

    override fun currentTenant(): TenantContext {
        val request = RequestContextHolder.currentRequestAttributes() as ServletRequestAttributes
        val tenantId = request.request.getHeader("X-Tenant-Id")
            ?: throw IllegalStateException("Missing tenant header")
        return TenantContext(Identifier(tenantId))
    }
}
```

Authorization is a separate contract so you can reuse your existing policy engine:

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
> User IDs are opaque strings up to 256 UTF-16 code units. Do not trim, case-fold, or normalize them inside your provider.

## Storage adapter

A storage adapter only materializes bytes. FileWeft owns object names, metadata, and tenant-scoped prefixes.

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

    // delete, exists, multipart methods omitted for brevity
}
```

> [!NOTE]
> The `StorageObjectLocation` must stay opaque to callers. Do not expose backend-specific details in REST responses.

## Connector

Connectors deliver documents to downstream systems. They must be idempotent, retry-aware, and report health.

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
> Treat connectors as unreliable. Always implement timeout, retry, and idempotency; FileWeft routes failures through the Outbox so operators can retry a single target.

## Public API discipline

SPI contracts are public APIs and must remain Java friendly. Do not use:

- `suspend` functions
- Kotlin `Flow`
- `value class`
- `sealed interface`
- `data object`

Keep identifiers as opaque strings. Keep vendor SDK models inside adapter modules.

## Registering implementations

FileWeft discovers beans through Spring auto-wiring. Bean priority is:

1. Beans defined in the host application
2. Beans contributed by a `FileWeftPlugin`
3. Framework defaults (development fallbacks only)

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

## Frequently asked questions

**Can one adapter implement multiple SPIs?**
Yes. A plugin or configuration class can provide storage, connectors, doctor checkers, and task handlers together.

**Do I have to implement every SPI?**
No. Only the SPIs your deployment needs. Missing mandatory context such as a tenant will make operations fail-safe rather than fall back to shared defaults.

**Where do vendor SDKs belong?**
Inside adapter modules that depend on the SPI, never inside `core`, `domain`, or `spi`.

## Next steps

- [Implement a storage adapter](../guides/storage-adapter.md)
- [Build a connector](../extensions/connectors.md)
- [Add diagnostics with Doctor](../operations/doctor-observability.md)
