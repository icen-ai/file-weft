---
route: "extensions/plugins"
group: "extensions"
order: 1
locale: "en"
nav: "Plugin development"
title: "Build production-ready plugins"
lead: "Learn how to package FileWeft extensions into a single plugin artifact so operators can discover connectors, storage adapters, Doctor checkers, and task handlers without changing core code."
format: "markdown"
---

A FileWeft plugin is a bundle of existing SPI contributions. It does not create a new architectural layer, and it is **not** a security sandbox. When you need to add a new downstream connector, a custom storage backend, or a diagnostic checker, ask first whether it can be contributed through the SPI as a plugin instead of modifying core code.

## 1. Plugin vs. direct Spring bean

You can contribute many FileWeft capabilities directly as Spring beans, but plugins are better when the extension is reusable, bundles a vendor SDK, or needs to appear in the public inventory.

| Approach | Best for | Trade-off |
| --- | --- | --- |
| Direct Spring bean | One-off customization inside your application | Mixed with app code; harder to share |
| `FileWeftPlugin` | Reusable extension, SDK isolation, public inventory | Must follow SPI contract; trusted in-process code |

## 2. What a plugin can contribute

The `FileWeftPlugin` interface exposes every extension point. FileWeft calls each getter exactly once while building the registry, then stores an immutable snapshot.

| Method | Capability | Typical use |
| --- | --- | --- |
| `storageAdapters()` | Custom object storage | MinIO, OSS, S3, or proprietary storage |
| `connectors()` | Downstream delivery | Compliance archive, search index, AI platform |
| `doctorCheckers()` | Runtime diagnostics | Verify credentials, endpoints, and permissions |
| `outboxEventHandlers()` | Async reactions | React to lifecycle events outside the request thread |
| `taskHandlers()` | Background jobs | Bulk migration, cleanup, or import tasks |
| `reviewRouteProviders()` | Approval routing | Tenant-specific review workflows |
| `agents()` / `agentTaskTriggers()` | Compatibility only | Not registered or included in the default 0.0.2 or 0.0.3 plugin inventory |

> [!CAUTION]
> The Agent getters remain only to preserve `FileWeftPlugin` source and binary compatibility. Neither 0.0.2 nor 0.0.3 provides Agent product capability, and new plugins must not build on these getters. A future design may be reassessed only after 1.0.0 has been released, with no promised delivery version.

> **NOTE**  
> Contribution getters are called during registry construction. Do not perform network calls, database writes, or other business side effects inside them. Network calls belong in the connector, handler, or Doctor method that FileWeft invokes later.

## 3. Minimal plugin implementation

Add the SPI dependency:

```kotlin
// build.gradle.kts
dependencies {
    compileOnly("ai.icen:fileweft-spi:0.0.3")
}
```

Implement the plugin and expose a connector and a Doctor checker:

```kotlin
package com.example.fileweft.ext

import ai.icen.fw.spi.connector.FileConnector
import ai.icen.fw.spi.doctor.DoctorChecker
import ai.icen.fw.spi.plugin.FileWeftPlugin
import org.springframework.stereotype.Component

@Component
class AcmeArchivePlugin(
    private val archiveConnector: AcmeArchiveConnector,
    private val archiveDoctorChecker: AcmeArchiveDoctorChecker,
) : FileWeftPlugin {

    override fun id(): String = "acme-archive-plugin"

    override fun connectors(): Map<String, FileConnector> =
        mapOf("acmeArchive" to archiveConnector)

    override fun doctorCheckers(): List<DoctorChecker> =
        listOf(archiveDoctorChecker)
}
```

The connector itself must implement the `FileConnector` contract (see [Connector engineering](/extensions/connectors)):

```kotlin
package com.example.fileweft.ext

import ai.icen.fw.spi.connector.*
import java.util.concurrent.ConcurrentHashMap

class AcmeArchiveConnector : FileConnector {
    private val externalIds = ConcurrentHashMap<String, String>()

    override fun sync(request: ConnectorSyncRequest): ConnectorSyncResult {
        val externalId = externalIds.computeIfAbsent(request.businessId.toString()) {
            "acme-${System.currentTimeMillis()}"
        }
        return ConnectorSyncResult(
            status = ConnectorSyncStatus.SUCCESS,
            externalId = externalId,
            message = "Archived to Acme storage.",
        )
    }

    override fun remove(request: ConnectorRemoveRequest): ConnectorSyncResult {
        externalIds.remove(request.businessId.toString())
        return ConnectorSyncResult(
            status = ConnectorSyncStatus.SUCCESS,
            message = "Removed from Acme storage.",
        )
    }

    override fun health(): ConnectorHealth =
        ConnectorHealth(status = ConnectorHealthStatus.HEALTHY)
}
```

The Doctor checker validates configuration without side effects:

```kotlin
package com.example.fileweft.ext

import ai.icen.fw.core.context.DoctorCheckContext
import ai.icen.fw.core.result.DoctorCheckResult
import ai.icen.fw.core.result.DoctorStatus
import ai.icen.fw.spi.doctor.DoctorChecker

class AcmeArchiveDoctorChecker(
    private val baseUrl: String,
) : DoctorChecker {

    override fun name(): String = "acme-archive"

    override fun check(context: DoctorCheckContext): DoctorCheckResult {
        if (baseUrl.isBlank()) {
            return DoctorCheckResult(
                checkerName = name(),
                status = DoctorStatus.ERROR,
                reason = "Acme archive base URL is not configured.",
                repairSuggestion = "Set acme.archive.base-url in application.yml.",
            )
        }
        return DoctorCheckResult(
            checkerName = name(),
            status = DoctorStatus.HEALTHY,
            reason = "Acme archive base URL is configured.",
        )
    }
}
```

## 4. Discovery rules and precedence

FileWeft discovers plugins from two sources:

1. **Spring beans** — any `FileWeftPlugin` bean is collected automatically by the FileWeft starter.
2. **Java `ServiceLoader`** — place a provider file at `META-INF/services/ai.icen.fw.spi.plugin.FileWeftPlugin`:

    ```text
    # META-INF/services/ai.icen.fw.spi.plugin.FileWeftPlugin
    com.example.fileweft.ext.AcmeArchivePlugin
    ```

When both sources expose the same plugin class, the Spring bean wins. Conflicting plugin IDs or connector IDs fail fast at startup.

> **WARNING**  
> A plugin is trusted in-process code. Only load plugins that you have reviewed, because they share the same classpath and memory space as the host application.

Precedence for overlapping contributions:

| Source | Wins against |
| --- | --- |
| Host Spring bean | Plugin bean |
| Plugin bean | Framework default |
| Earlier plugin | Later plugin with same ID (startup error) |

## 5. Verify the plugin

Before releasing a plugin, complete this checklist:

1. **Unit test** plugin decisions and helper logic.
2. **Run SPI contract tests** for every storage adapter and connector.
3. **Start the matching starter context** (`fileweft-spring-boot2-starter` or `fileweft-spring-boot3-starter`) and confirm the application starts.
4. **Inspect the inventory** with the public endpoint:

    ```bash
    curl -s http://localhost:8080/fileweft/v1/plugins | jq .
    ```

    A healthy response looks like this:

    ```json
    {
      "code": "OK",
      "message": "OK",
      "data": [
        {
          "id": "acme-archive-plugin",
          "capabilities": [
            { "type": "CONNECTOR", "count": 1 },
            { "type": "DOCTOR_CHECKER", "count": 1 }
          ]
        }
      ],
      "error": null
    }
    ```

5. **Run Doctor checks** and confirm that missing configuration or unreachable dependencies produce actionable diagnostics instead of stack traces.

## FAQ

**Can a plugin override a framework default?**  
Yes. Host beans and plugin beans with the same connector ID take precedence over framework defaults. Conflicting IDs fail fast so the operator notices immediately.

**Should I perform network calls inside `connectors()` or `storageAdapters()`?**
No. Those getters are invoked once at startup. Keep them cheap and deterministic. Network work belongs in `FileConnector.sync()`, `DoctorChecker.check()`, or event handlers.

**Can a plugin contain its own configuration properties?**  
Yes. A plugin is a normal Spring bean and can inject `@Value` or `@ConfigurationProperties`. Keep vendor SDKs and credentials inside the plugin, never leak them into SPI or core.

## Next steps

- [Connector engineering](/extensions/connectors) — write a `FileConnector` that survives unreliable downstream systems.
- [Doctor](/doctor) — add diagnostics that operators can act on.
- [Lifecycle](/lifecycle) — understand when sync, remove, and outbox events are triggered.
