---
route: "project/faq"
group: "project"
order: 7
locale: "en"
nav: "FAQ"
title: "Frequently asked questions"
lead: "This page answers the most common questions about FileWeft's purpose, architecture, tenancy, storage and release policy, with pointers to deeper documentation."
format: "markdown"
---

## General

### What is FileWeft?

FileWeft is a Kotlin/JVM infrastructure framework for enterprise file intelligence. It handles document lifecycles, approvals, multi-target delivery, storage abstraction, diagnostics and audit — but it is not a simple upload module, a business document system, a Dify/ESE wrapper or a cloud storage SDK.

### Is FileWeft a drop-in file upload widget?

No. FileWeft provides the foundation; your host supplies identity, tenant, authorization, folder tree and business rules through SPI implementations.

## Architecture and SPI

### Do I have to use Spring Boot?

No, but the supported starters are for Spring Boot 2 and Spring Boot 3. You can assemble the runtime and web modules manually if you provide equivalent lifecycle and configuration management.

### Can I add my own storage backend?

Yes. Implement the `StorageAdapter` SPI and register it as a host bean or through a plugin. See the [storage adapter guide](guides/storage-adapter).

```kotlin
class MyStorageAdapter : StorageAdapter {
    override fun upload(request: StorageUploadRequest, content: InputStream): StoredObject { ... }
    override fun download(location: StorageObjectLocation): StorageDownload { ... }
    override fun delete(location: StorageObjectLocation) { ... }
    override fun exists(location: StorageObjectLocation): Boolean { ... }
    override fun accessUrl(location: StorageObjectLocation, expiresIn: Duration): URI { ... }
    override fun beginMultipartUpload(request: StorageUploadRequest): MultipartUpload { ... }
    override fun uploadPart(upload: MultipartUpload, partNumber: Int, content: InputStream, contentLength: Long): MultipartPart { ... }
    override fun completeMultipartUpload(upload: MultipartUpload, parts: List<MultipartPart>): StoredObject { ... }
    override fun abortMultipartUpload(upload: MultipartUpload) { ... }
}
```

### Can I call a vendor SDK from domain code?

No. `core`, `spi` and `domain` must not depend on vendor SDKs. Vendor code belongs in an `adapter` module that depends only on the SPI.

## Multi-tenancy

### Does FileWeft create tenants?

No. FileWeft consumes tenant context from your `TenantProvider`. The framework applies that context to queries, storage paths, events, tasks, logs and caches.

### Can I use `fileweft.default-tenant-enabled=true` in production?

No. That property is a development fallback for a fixed single tenant. It is not a production multi-tenant solution.

```properties
# development only
fileweft.default-tenant-enabled=true
fileweft.default-tenant-id=tenant-a
```

> [!WARNING] Tenant IDs from request parameters are not trusted
> FileWeft never trusts a `tenantId` sent by the caller. It always resolves the current tenant through the configured `TenantProvider`.

## Storage

### Does FileWeft replace S3 or MinIO?

No. FileWeft abstracts storage through `StorageAdapter` so you can keep using S3, MinIO, OSS or a custom backend without leaking vendor details into your domain.

### Can I serve files directly from object storage?

FileWeft does not expose presigned storage URLs through the public HTTP API. Authorized downloads are binary streams returned by the application with `attachment`, `nosniff` and `private, no-store` headers.

## Releases

### Which stable release is current?

The current stable release is `ai.icen:*:0.0.3`, fixed at commit `dbf2a50fbca41e2ac5b5cf18bb44f9287c153637`. CNB `tag_push` build `cnb-cl8-1jtgih45j` completed 12/12 pipelines, and independent anonymous readback verified the POM, main JAR, and checksum for all 19 coordinates.

### Can I still use `com.fileweft:*` artifacts?

No. Those trial artifacts have been withdrawn. Use an `ai.icen:*` version only after its complete remote evidence is available.

### How do I know 0.0.3 is consumable?

Verify the immutable release identity above: tag `v0.0.3`, commit `dbf2a50fbca41e2ac5b5cf18bb44f9287c153637`, build `cnb-cl8-1jtgih45j`, 12/12 successful pipelines, and 19/19 anonymous artifact readbacks. Later default-branch commits do not change that release identity.

## HTTP API

### Are `/api/**` endpoints part of the public protocol?

No. The formal HTTP surface lives under `/fileweft/v1`. Dev-only `/api/**` routes are not part of the public protocol and may change without notice.

### Does the API support Range requests?

No. Downloads are full binary streams with fixed headers. Range, HEAD, ETag and Content-Range are not supported.

## Next steps

- Read the [introduction](getting-started/introduction) for the project identity.
- Follow [first integration](getting-started/first-integration) to wire a production host.
- See the [roadmap](project/roadmap) for what is planned and what is already proven.
