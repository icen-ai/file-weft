---
route: "extensions/connectors"
group: "extensions"
order: 2
locale: "zh"
nav: "连接器工程"
title: "构建高可用连接器"
lead: "学习如何实现 FileConnector，通过有界超时、重试、幂等、撤回和健康检查，安全地将文档发布到下游系统。"
format: "markdown"
---

连接器将 FileWeft 稳定的交付契约转换为一个厂商集成。下游系统按设计就是不可靠的，因此生产级连接器必须把超时、重试、幂等、失败分类、撤回和健康检查当作一等公民，而不是事后补丁。

## 1. FileConnector 契约

SPI 小而明确。连接器收到同步或撤回请求，并返回已分类的结果。

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

| 状态 | 含义 | FileWeft 行为 |
| --- | --- | --- |
| `SUCCESS` | 交付或撤回成功 | 标记目标完成；交付成功时 `externalId` 必须非空 |
| `RETRYABLE_FAILURE` | 临时问题 | Worker 按退避策略重试 |
| `PERMANENT_FAILURE` | 请求非法或被拒绝 | 记录失败；必达目标会阻塞发布 |

健康状态有三档：

| 健康状态 | 用途 |
| --- | --- |
| `HEALTHY` | 连接器可以访问下游系统 |
| `DEGRADED` | 可达，但延迟或错误率升高 |
| `UNHEALTHY` | 下游系统不可达或配置错误 |

## 2. 交付流程与配置

FileWeft 从不在数据库事务中调用连接器。流程如下：

1. 应用服务提交业务事务。
2. 写入 Outbox 事件。
3. 异步 Worker 取出事件。
4. Worker 通过弹性层调用连接器（超时、熔断、并发限制）。
5. 连接器结果更新交付状态。

> **WARNING**  
> 绝不要在 FileWeft 数据库事务中调用外部系统。应使用 Outbox 模式，避免下游故障回滚自身数据。

在 `application.yml` 中将连接器映射到交付目标：

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
        display-name: 受监管发布
        targets:
          - id: compliance
            display-name: 合规归档
            connector-id: acmeArchive
            required: true
            owner-ref: compliance-ops
          - id: search
            display-name: 检索索引
            connector-id: searchConnector
            required: false
            owner-ref: search-ops
```

`connector-id` 的值必须与插件 `connectors()` 映射中的键，或 Spring `FileConnector` Bean 的 ID 一致。

## 3. 完整连接器示例

添加 SPI 依赖：

```kotlin
// build.gradle.kts
dependencies {
    compileOnly("ai.icen:fileweft-spi:0.0.1")
}
```

下面的示例将文档元数据提交到一个虚构的 Acme 归档系统。真实实现中，你可以从 `source.downloadUri` 读取字节流并上传到下游；连接器的结构保持不变。

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
> 生产环境建议使用 Jackson 等真正的 JSON 库；上例使用内联字符串仅为可读性。请求签名、内容流式传输和指标埋点同理。

该示例展示了：

- 每次调用都设置 `connectTimeout` 和 `readTimeout`。
- 从 FileWeft `businessId` 派生稳定的 `Idempotency-Key`。
- HTTP 状态码的清晰分类。
- 错误信息中绝不包含 API 密钥。
- 适合 Doctor 的只读健康检查。

## 4. 契约测试

集成测试应证明连接器在重复和失败场景下的行为：

1. 对同一 `businessId` 重复调用 `sync` 返回相同的 `externalId`。
2. 对同一 `externalId` 重复调用 `remove` 是安全的。
3. 超时返回 `RETRYABLE_FAILURE`。
4. `400 Bad Request` 返回 `PERMANENT_FAILURE`。
5. 错误信息不包含凭据。
6. 下游不可达时 `health()` 返回 `UNHEALTHY`。
7. 下游恢复后再次调用返回 `SUCCESS`。

## 5. 运维检查清单

部署连接器前，请确认：

- [ ] 每次网络调用都有超时。
- [ ] 幂等键稳定且每个业务对象唯一。
- [ ] 可重试失败与永久失败分类正确。
- [ ] 成功交付返回的 `externalId` 非空。
- [ ] 错误信息已脱敏。
- [ ] `health()` 只读且快速。
- [ ] 已实现幂等的撤回逻辑。

可通过公共 API 查看交付状态：

```bash
curl -s http://localhost:8080/fileweft/v1/documents/{documentId}/sync-status
```

## 常见问题

**如果下游系统不支持幂等怎么办？**  
使用稳定的 `businessId` 作为幂等键，必要时将返回的外部 ID 存入自己的状态。连接器仍需正确分类结果，以便 FileWeft 决定是否重试。

**我可以在应用服务中同步调用连接器吗？**  
不可以。FileWeft 通过 Outbox 和异步 Worker 交付，避免下游故障阻塞或回滚本地事务。

**必达目标失败会怎样？**  
文档进入 `SYNC_ERROR`，Worker 持续重试。可选目标失败会被记录，但不会阻塞发布。

## 下一步

- [插件开发](/extensions/plugins) —— 将你的连接器打包成可复用插件。
- [生命周期](/lifecycle) —— 了解 sync 和 remove 事件何时触发。
- [Doctor](/doctor) —— 向运维人员暴露连接器健康状态。
