---
route: "operations/doctor-observability"
group: "operations"
order: 2
locale: "zh"
nav: "Doctor 与可观测性"
title: "从证据出发运维"
lead: "无需猜测即可诊断 FileWeft：Doctor 执行安全、已授权的检查；指标展示有界趋势；审计日志与 Trace ID 承载资源证据，同时避免高基数标签泄漏。"
format: "markdown"
---

## 这页解决什么问题

当上传卡住、交付失败或 Worker 漂移时，你需要三类独立信号：聚焦的诊断、聚合指标和资源级证据。本页说明 FileWeft 如何让这些信号保持分离、有界且可操作。

## 三条 Doctor 路径

| 路径 | 端点 | 适用场景 | 授权要求 |
|------|------|----------|----------|
| 即时文档 | `GET /documents/{id}/doctor` | 单个文档看起来异常，想要交互式答案 | 文档读权限 + 目录可见性 |
| 异步文档 | `POST /documents/{id}/doctor/tasks` | 检查较贵、跨 Worker 或需要持久化 | 文档读权限 + 目录可见性 |
| 系统 | `GET /doctor` | 查看租户级运行时健康 | `system:doctor:read` |

`DoctorChecker` 必须无副作用，并返回可操作结果而非抛出异常：

```kotlin
import ai.icen.fw.core.result.DoctorCheckResult
import ai.icen.fw.core.result.DoctorStatus
import ai.icen.fw.spi.doctor.DoctorChecker

@Component
class StorageDoctorChecker(private val storageAdapter: StorageAdapter) : DoctorChecker {

    override fun name(): String = "storage"

    override fun check(context: DoctorCheckContext): DoctorCheckResult {
        val location = StorageObjectLocation("s3", "probe/${context.tenantId.value}/doctor-${UUID.randomUUID()}")
        return try {
            storageAdapter.exists(location)
            DoctorCheckResult(name(), DoctorStatus.HEALTHY, "存储位置可达。")
        } catch (e: Exception) {
            DoctorCheckResult(
                name(),
                DoctorStatus.ERROR,
                "存储检查失败：${e.message}",
                repairSuggestion = "检查存储凭据、网络路径和存储桶策略。"
            )
        }
    }
}
```

## 调用 Doctor

### 即时文档检查

```bash
curl -sf http://api:8080/fileweft/v1/documents/doc_123/doctor \
  -H "Authorization: Bearer ${HOST_TOKEN}" \
  -H "X-Idempotency-Key: $(uuidgen)"
```

响应外层：

```json
{
  "code": "OK",
  "message": "OK",
  "data": {
    "documentId": "doc_123",
    "checks": [
      { "name": "storage", "status": "HEALTHY", "detail": "存储位置可达。" },
      { "name": "lifecycle", "status": "HEALTHY", "detail": "生命周期状态一致。" }
    ]
  },
  "error": null,
  "traceId": "abc-123"
}
```

### 异步文档检查

```bash
# 提交
curl -sf -X POST http://api:8080/fileweft/v1/documents/doc_123/doctor/tasks \
  -H "Authorization: Bearer ${HOST_TOKEN}" \
  -H "X-Idempotency-Key: $(uuidgen)"

# 轮询
TASK_ID="task_456"
curl -sf http://api:8080/fileweft/v1/documents/doc_123/doctor/tasks/${TASK_ID} \
  -H "Authorization: Bearer ${HOST_TOKEN}"
```

### 系统检查

```bash
curl -sf http://api:8080/fileweft/v1/doctor \
  -H "Authorization: Bearer ${HOST_TOKEN}"
```

## 核心指标

所有计数器都以 `fileweft.` 为前缀。不要把租户、文档或用户 ID 作为指标标签。

| 指标 | 类型 | 含义 |
|------|------|------|
| `fileweft.upload_count` | Counter | 成功上传 |
| `fileweft.upload_failure` | Counter | 失败上传 |
| `fileweft.sync_success` | Counter | 连接器交付成功 |
| `fileweft.sync_failure` | Counter | 连接器交付失败 |
| `fileweft.delivery_removal_success` | Counter | 成功收到撤回确认 |
| `fileweft.delivery_removal_failure` | Counter | 撤回确认失败 |
| `fileweft.doctor_failure` | Counter | 返回失败的 Doctor 检查 |
| `fileweft.task_success` | Counter | 任务执行成功 |
| `fileweft.task_failure` | Counter | 任务执行失败 |
| `fileweft.outbox_backlog` | Gauge | Outbox 中 ready/delayed/running/expired/failed 行数 |
| `fileweft.outbox_oldest_ready_age_seconds` | Gauge | 最老 ready Outbox 行年龄 |
| `fileweft.outbox_backlog_observation_failure` | Gauge | 指标观测失败次数 |

> [!TIP]
> `fileweft.outbox_backlog` 只使用 `state` 标签。资源级排查应使用审计日志和 Trace，而不是提高指标基数。

## PromQL 告警示例

```promql
# 交付持续失败
rate(fileweft.sync_failure[5m]) > 0.1

# Outbox 堆积
fileweft.outbox_backlog{state="ready"} > 1000

# 最老 ready 行老化
fileweft.outbox_oldest_ready_age_seconds > 300
```

## 审计与 Trace

每个正式 API 响应都包含 `traceId`，可用于关联：

- API 与 Worker 角色的应用日志。
- `fileweft.*_log` 表中的数据库审计行。
- 连接器调用记录。

> [!NOTE]
> 下载端点返回二进制流，响应头为 `private, no-store`。不支持 Range、HEAD、ETag 或预签名存储 URL。

## 常见问题

**我应该把 `/fileweft/v1/doctor` 开放给所有用户吗？**
不应该。系统 Doctor 需要 `system:doctor:read`；文档 Doctor 受文档级授权约束。

**为什么指标里看不到租户 ID？**
租户、文档和用户标识属于高基数或敏感信息。指标展示有界趋势；资源级证据应通过审计日志和 Trace ID 获取。

## 下一步

- 阅读完整 HTTP API 接口：[HTTP API v1](../reference/http-api)
- 编写自己的检查器：[实现持久任务 Handler](../guides/agent-handler)
- 规划安全发布：[迁移与发布](migrations-release)
