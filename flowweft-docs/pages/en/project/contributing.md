---
route: "project/contributing"
group: "project"
order: 1
locale: "en"
nav: "Contributing"
title: "Contribute without eroding the foundation"
lead: "This page shows how to add code, tests and documentation to FlowWeft while keeping the Core → SPI → Domain → Application → Persistence → Starter → Adapter chain intact, so the framework stays extensible and Java-friendly."
format: "markdown"
---

## Before you open an editor

Every contribution starts with the architecture, not with a new class file.

1. Read `.ai/README_FINAL.md` and the relevant extension manual under `.ai/` for your topic.
2. Decide which module owns the change. If no existing SPI fits, propose a new SPI before adding a dependency.
3. Check whether the same problem can be solved with a plugin, adapter or host-side provider instead of changing framework internals.
4. For any architectural change, explain compatibility impact, migration steps and how an operator will diagnose failure.
5. Open a focused issue or draft PR that describes the boundary first, then the implementation.

> [!TIP] Prefer extension over modification
> A new `StorageAdapter`, `FileConnector`, `DoctorChecker` or `FileWeftTaskHandler` lives in its own adapter module and does not force every host to accept your dependency.

## Where does your change belong?

| Layer | What lives here | What does NOT live here |
| --- | --- | --- |
| `core` | Identifiers, results, errors, events, contexts | Spring, ORM, vendor SDKs, business rules |
| `spi` | Contracts for tenant, identity, authorization, storage, connector, workflow, task, doctor | Implementation code |
| `domain` | Document, FileAsset, lifecycle, version, audit rules | Infrastructure calls, database queries |
| `application` | Upload, publish, offline, approve, sync orchestration use cases | Direct storage or connector calls inside transactions |
| `persistence` | PostgreSQL mappings, repositories, Flyway migrations | Business logic |
| `starter` | Boot 2/3 auto-configuration and conditional beans | Low-level adapter logic |
| `adapter` | Host/plugin external integrations; named official vendor adapters remain roadmap work | Core business rules |

## Test every layer you touch

FlowWeft refuses untested infrastructure code. Match the evidence to the layer.

1. **Core / Domain** — write focused unit tests that exercise invariants and error paths.
2. **SPI** — add contract tests that prove Java-friendly usage and surface-breaking changes.
3. **Adapter / Persistence** — run real integration tests against PostgreSQL, object storage or the actual downstream service; mocks are not enough.
4. **Starter / Web** — add Spring context tests and Boot 2/3 contract tests that fail if beans are missing or misordered.
5. **Release** — update the compatibility matrix, run Compose acceptance tests, browser E2E and verify the SBOM.

| Layer | Required evidence |
| --- | --- |
| Core / Domain | Unit tests and invariant checks |
| SPI | Contract tests and Java-friendly usage samples |
| Adapter / Persistence | Real integration tests |
| Starter / Web | Context and Boot 2/3 contract tests |
| Release | Compatibility matrix, Compose acceptance, browser E2E and SBOM |

## Submit a change reviewers can trust

- Use UTF-8 source files, small focused classes and explicit constructor dependencies.
- Preserve tenant filtering, lock order and audit fields in persistence changes.
- Do not rewrite unrelated user changes in the same commit.
- Keep commits coherent and use action-oriented messages: `add circuit-breaker config for connector invocations`, not `update`.
- Add or update documentation only for behavior that actually exists; never document wishful APIs.

A minimal SPI contribution looks like this:

```kotlin
package ai.icen.fw.adapter.example

import ai.icen.fw.spi.storage.*
import java.io.InputStream
import java.time.Duration

class ExampleStorageAdapter : StorageAdapter {
    override fun upload(request: StorageUploadRequest, content: InputStream): StoredObject {
        // real implementation
    }

    override fun download(location: StorageObjectLocation): StorageDownload {
        // real implementation
    }

    override fun delete(location: StorageObjectLocation) {}
    override fun exists(location: StorageObjectLocation): Boolean = false
    override fun accessUrl(location: StorageObjectLocation, expiresIn: Duration): URI = TODO()
    override fun beginMultipartUpload(request: StorageUploadRequest): MultipartUpload = TODO()
    override fun uploadPart(upload: MultipartUpload, partNumber: Int, content: InputStream, contentLength: Long): MultipartPart = TODO()
    override fun completeMultipartUpload(upload: MultipartUpload, parts: List<MultipartPart>): StoredObject = TODO()
    override fun abortMultipartUpload(upload: MultipartUpload) {}
}
```

Register it through a plugin or as a host bean:

```kotlin
class ExamplePlugin : FileWeftPlugin {
    override fun id(): String = "example-storage"
    override fun storageAdapters(): List<StorageAdapter> = listOf(ExampleStorageAdapter())
}
```

> [!WARNING] Do not bypass the SPI
> Calling a vendor SDK directly from `domain` or `core` breaks the dependency direction and will be rejected in review.

## FAQ

**Can I add a new database column directly in a domain class?**
No. Schema changes belong in `persistence` Flyway migrations; domain classes express business rules, not storage layout.

**Should I write tests for both Boot 2 and Boot 3 starters?**
Yes. Any starter change needs matching context or contract tests in both generations unless the change is generation-specific.

**Where do I report a security concern?**
See [Security](project/security). Do not open a public issue for suspected vulnerabilities.

## Next steps

- Read the [SPI reference](reference/spi) to see which contract matches your idea.
- Follow the [module boundaries](concepts/module-boundaries) page to confirm the dependency direction.
- Check the [roadmap](project/roadmap) to see whether your proposal overlaps with an in-progress line.
