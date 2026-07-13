---
route: "extensions/connectors"
group: "extensions"
order: 2
locale: "en"
nav: "Connector engineering"
title: "Engineer resilient connectors"
lead: "Learn how to implement a FileConnector that publishes documents to downstream systems safely using bounded timeouts, retries, idempotency, removal, and health checks."
format: "markdown"
---

A connector translates FileWeft's stable delivery contract into one vendor integration. Downstream systems are unreliable by design, so a production connector must treat timeouts, retries, idempotency, failure classification, removal, and health checks as first-class concerns—not as afterthoughts.

## 1. The FileConnector contract

The SPI is small and explicit. A connector receives a sync or remove request and returns a classified result.

```kotlin
package ai.icen.fw.spi.connector

import java.net.URI

data class ConnectorFileSource(
    val downloadUri: URI,
    val fileName: String,
    val contentType: String? = null,
    val contentHash: String? = null,
)

data class ConnectorSyncRequest(
    val tenantId: Identifier,
    val businessId: Identifier,
    val source: ConnectorFileSource,
    val invocation: ConnectorInvocation,
    val attributes: Map<String, String> = emptyMap(),
)

data class ConnectorRemoveRequest(
    val tenantId: Identifier,
    val businessId: Identifier,
    val externalId: String,
    val invocation: ConnectorInvocation,
)

enum class ConnectorSyncStatus {
    SUCCESS,
    RETRYABLE_FAILURE,
    PERMANENT_FAILURE,
}

interface FileConnector {
    fun sync(request: ConnectorSyncRequest): ConnectorSyncResult
    fun remove(request: ConnectorRemoveRequest): ConnectorSyncResult
    fun health(): ConnectorHealth
}
```

| Status | Meaning | FileWeft behavior |
| --- | --- | --- |
| `SUCCESS` | Delivery or removal succeeded | Mark target complete; `externalId` must be non-blank for deliveries |
| `RETRYABLE_FAILURE` | Transient problem | Worker retries with exponential backoff |
| `PERMANENT_FAILURE` | Bad request or rejected content | Record failure; required targets block publication |

Health has its own tri-state:

| Health status | Use |
| --- | --- |
| `HEALTHY` | Connector can reach the downstream system |
| `DEGRADED` | Reachable but latency or errors are elevated |
| `UNHEALTHY` | Downstream system is unreachable or misconfigured |

## 2. Delivery flow and configuration

FileWeft never calls a connector inside a database transaction. The flow is:

1. Application service commits the business transaction.
2. An outbox event is written.
3. The async worker picks up the event.
4. The worker invokes the connector through a resilience layer (timeout, circuit breaker, concurrency limit).
5. The connector result updates delivery state.

> **WARNING**  
> Never call external systems inside a FileWeft database transaction. Use the outbox pattern so a downstream failure cannot roll back your own data.

Map a connector to a delivery target in `application.yml`:

```yaml
fileweft:
  sync:
    connector-timeout-millis: 30000
    circuit-breaker-failure-threshold: 3
    circuit-breaker-open-duration-millis: 30000
    connector-max-concurrent-invocations: 16
    default-profile-id: regulated
    profiles:
      - id: regulated
        display-name: Regulated publishing
        targets:
          - id: compliance
            display-name: Compliance archive
            connector-id: acmeArchive
            required: true
            owner-ref: compliance-ops
          - id: search
            display-name: Search index
            connector-id: searchConnector
            required: false
            owner-ref: search-ops
```

The `connector-id` value must match a key returned from a plugin's `connectors()` map or from a Spring `FileConnector` bean.

## 3. Complete connector example

Add the SPI dependency:

```kotlin
// build.gradle.kts
dependencies {
    compileOnly("ai.icen:fileweft-spi:0.0.1")
}
```

The example below posts document metadata to a fictional Acme archive. In a real implementation you would stream bytes from `source.downloadUri` to the downstream system; the structure of the connector stays the same.

```kotlin
package com.example.fileweft.ext

import ai.icen.fw.spi.connector.*
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets

class AcmeArchiveConnector(
    private val baseUrl: String,
    private val apiKey: String,
) : FileConnector {

    override fun sync(request: ConnectorSyncRequest): ConnectorSyncResult {
        val idempotencyKey = request.businessId.toString()
        return withRetry(times = 3) {
            val url = URI("$baseUrl/archive/v1/documents").toURL()
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 30_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $apiKey")
            conn.setRequestProperty("Idempotency-Key", idempotencyKey)

            val body = """
                {
                  "fileName": "${request.source.fileName}",
                  "sourceUrl": "${request.source.downloadUri}",
                  "contentType": "${request.source.contentType ?: "application/octet-stream"}"
                }
            """.trimIndent()

            conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }

            when (val status = conn.responseCode) {
                in 200..299 -> {
                    val externalId = conn.inputStream.bufferedReader().use { it.readText() }.trim()
                    ConnectorSyncResult(
                        status = ConnectorSyncStatus.SUCCESS,
                        externalId = externalId,
                        message = "Archived to Acme.",
                    )
                }
                429, in 500..599 -> throw RetryableException("Acme returned $status")
                else -> ConnectorSyncResult(
                    status = ConnectorSyncStatus.PERMANENT_FAILURE,
                    message = "Acme rejected the request with status $status.",
                )
            }
        }
    }

    override fun remove(request: ConnectorRemoveRequest): ConnectorSyncResult {
        val externalId = request.externalId
        return withRetry(times = 3) {
            val url = URI("$baseUrl/archive/v1/documents/${externalId.encode()}").toURL()
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "DELETE"
            conn.connectTimeout = 10_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("Authorization", "Bearer $apiKey")

            when (val status = conn.responseCode) {
                in 200..299, 404 -> ConnectorSyncResult(
                    status = ConnectorSyncStatus.SUCCESS,
                    message = "Removed from Acme.",
                )
                429, in 500..599 -> throw RetryableException("Acme returned $status")
                else -> ConnectorSyncResult(
                    status = ConnectorSyncStatus.PERMANENT_FAILURE,
                    message = "Acme refused removal with status $status.",
                )
            }
        }
    }

    override fun health(): ConnectorHealth {
        return try {
            val url = URI("$baseUrl/health").toURL()
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.connectTimeout = 5_000
            conn.readTimeout = 5_000
            val status = conn.responseCode
            conn.disconnect()
            if (status == 200) {
                ConnectorHealth(status = ConnectorHealthStatus.HEALTHY, message = "Acme reachable.")
            } else {
                ConnectorHealth(status = ConnectorHealthStatus.DEGRADED, message = "Acme returned $status.")
            }
        } catch (e: IOException) {
            ConnectorHealth(status = ConnectorHealthStatus.UNHEALTHY, message = "Acme unreachable.")
        }
    }

    private class RetryableException(message: String) : RuntimeException(message)

    private inline fun withRetry(
        times: Int = 3,
        block: () -> ConnectorSyncResult,
    ): ConnectorSyncResult {
        var lastFailure: Exception? = null
        repeat(times) { attempt ->
            try {
                return block()
            } catch (e: RetryableException) {
                lastFailure = e
                if (attempt < times - 1) {
                    Thread.sleep((1000L * (attempt + 1)).coerceAtMost(5000L))
                }
            }
        }
        return ConnectorSyncResult(
            status = ConnectorSyncStatus.RETRYABLE_FAILURE,
            message = "Acme failed after $times attempts: ${lastFailure?.message}",
        )
    }

    private fun String.encode(): String =
        java.net.URLEncoder.encode(this, StandardCharsets.UTF_8.name())
}
```

> **TIP**  
> Use a real JSON library such as Jackson in production; the inline string above is kept simple for readability. The same applies to request signing, content streaming, and metrics.

What this connector demonstrates:

- Bounded `connectTimeout` and `readTimeout` on every call.
- Stable `Idempotency-Key` derived from the FileWeft `businessId`.
- Clear classification of HTTP status codes.
- Redacted messages that never include the API key.
- A read-only health check suitable for Doctor.

## 4. Test the contract

Integration tests should prove the connector behaves correctly under repetition and failure:

1. Repeated `sync` with the same `businessId` returns the same `externalId`.
2. Repeated `remove` with the same `externalId` is safe.
3. A timeout produces `RETRYABLE_FAILURE`.
4. A `400 Bad Request` produces `PERMANENT_FAILURE`.
5. Error messages do not contain credentials.
6. `health()` returns `UNHEALTHY` when the downstream is unreachable.
7. Recovery after the downstream comes back returns `SUCCESS`.

## 5. Operational checklist

Before deploying a connector, verify:

- [ ] Every network call has a timeout.
- [ ] The idempotency key is stable and unique per business object.
- [ ] Retryable and permanent failures are classified correctly.
- [ ] Returned `externalId` is non-blank for successful deliveries.
- [ ] Error messages are redacted.
- [ ] `health()` is read-only and fast.
- [ ] Removal is implemented and idempotent.

You can inspect delivery state through the public API:

```bash
curl -s http://localhost:8080/fileweft/v1/documents/{documentId}/sync-status
```

## FAQ

**What if my downstream system does not support idempotency?**  
Use the stable `businessId` as the idempotency key and store the returned external ID in your own state if necessary. The connector must still classify results so FileWeft knows whether to retry.

**Can I invoke the connector synchronously from an application service?**  
No. FileWeft delivers through the outbox and async worker so a downstream outage cannot block or roll back the local transaction.

**What happens when a required target fails?**  
The document enters `SYNC_ERROR` and the worker keeps retrying. Optional target failures are recorded but do not block publication.

## Next steps

- [Plugin development](/extensions/plugins) — package your connector as a reusable plugin.
- [Lifecycle](/lifecycle) — learn when sync and removal events are emitted.
- [Doctor](/doctor) — expose connector health to operators.
