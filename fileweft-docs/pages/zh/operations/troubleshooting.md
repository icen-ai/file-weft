---
route: "operations/troubleshooting"
group: "operations"
order: 4
locale: "zh"
nav: "故障排查"
title: "诊断常见症状"
lead: "当 FileWeft 行为异常时，按结构化清单操作——先检查 Doctor、指标、Outbox 状态与 Trace ID，再改动代码或配置。"
format: "markdown"
---

## 这页解决什么问题

文件系统的错误会横跨上传、存储、审批、交付和后台 Worker。本页把常见症状映射到运维人员应首先执行的排查动作。

## 症状矩阵

| 症状 | 首先检查 | 可能原因 |
|------|----------|----------|
| 上传返回 400 或 409 | 幂等键与分片号 | 复用幂等键但载荷不同，或分片顺序错误 |
| 上传分片流中断 | Worker / 存储健康 | 存储适配器超时或连接重置 |
| 文档卡在 `PENDING_REVIEW` | 工作流任务收件箱 | 缺少审批或任务被驳回 |
| 发布返回 `SYNC_ERROR` | 交付同步状态 | 必达连接器失败；可选失败不会阻塞发布 |
| 已发布文档未出现在下游 | 连接器健康与日志 | 连接器返回可重试失败 |
| Worker CPU 低但堆积增长 | Outbox 指标与租约 | 租约过期但无 Worker 认领 |
| `/fileweft/v1/health` 报错 | 迁移模式与 schema | `validate` 模式下 schema 版本不匹配 |
| 每次调用都 403 | 租户 Provider 与授权 SPI | 缺失或不可信租户上下文 |

## 上传失败

### 验证会话

```bash
curl -sf http://api:8080/fileweft/v1/uploads/${UPLOAD_ID} \
  -H "Authorization: Bearer ${HOST_TOKEN}"
```

关注：

- `uploadId` 存在且属于调用方租户。
- `partSize`、`totalParts` 与已上传分片号匹配。
- 会话未超过 `resumable-session-ttl-millis`。

### 常见错误

1. **复用幂等键但文件不同。** 服务端会存储载荷的 SHA-256 摘要；不匹配的重放返回 `CONFLICT`。
2. **分片乱序上传。** 分片可以重试，但 `complete` 要求 1 到 total 的每个分片号都存在。
3. **发送错误的 `Content-Length`。** 适配器会按声明长度处理多分片边界。

## 同步卡住或失败

### 检查交付状态

```bash
curl -sf http://api:8080/fileweft/v1/documents/doc_123/sync-status \
  -H "Authorization: Bearer ${HOST_TOKEN}"
```

重点字段：

- `targets[].state` — `SUCCESS`、`PENDING`、`FAILED`、`REMOVAL_PENDING`
- `targets[].lastError` — 连接器提供的安全错误摘要
- `document.state` — `PUBLISHED`、`SYNC_ERROR`、`OFFLINE`

### 重试失败目标

```bash
curl -sf -X POST \
  http://api:8080/fileweft/v1/documents/doc_123/deliveries/dlv_456/retry \
  -H "Authorization: Bearer ${HOST_TOKEN}" \
  -H "X-Idempotency-Key: $(uuidgen)"
```

> [!NOTE]
> FileWeft 不会回滚已成功目标。必达目标失败会让文档进入 `SYNC_ERROR`；可选目标失败仍会让文档保持 `PUBLISHED` 并记录错误。

## Outbox 堆积

通过指标而非扫表来观察堆积：

```promql
fileweft.outbox_backlog{state="ready"}
fileweft.outbox_backlog{state="running"}
fileweft.outbox_oldest_ready_age_seconds
```

如果 `running` 行卡住：

1. 检查 Worker 进程是否存活。
2. 确认 Worker 配置了 `process-outbox: true`。
3. 查看是否超过 `lease-duration`；崩溃的 Worker 会把行留在 `running` 直到 `legacy-running-grace-millis` 过期。

## Doctor 失败

先运行文档级 Doctor，不要凭空猜测：

```bash
curl -sf http://api:8080/fileweft/v1/documents/doc_123/doctor \
  -H "Authorization: Bearer ${HOST_TOKEN}" \
  -H "X-Idempotency-Key: $(uuidgen)"
```

如果某个检查器返回 `UNHEALTHY`：

1. 阅读其 `detail` 字段——必须是可操作的。
2. 检查对应指标：`fileweft.doctor_failure`。
3. 在日志中搜索检查器 `name` 与响应中的 `traceId`。

## 认证与 403

FileWeft 失败关闭。403 通常意味着以下之一：

- `TenantProvider.currentTenant()` 返回了缺失或不可信的上下文。
- `AuthorizationProvider.authorize()` 拒绝对资源的操作。
- 用户对文档所在目录缺少可见性。

在宿主中验证：

```kotlin
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.authorization.AuthorizationAction
import ai.icen.fw.spi.authorization.AuthorizationRequest
import ai.icen.fw.spi.authorization.AuthorizationResource
import ai.icen.fw.spi.authorization.AuthorizationSubject
import ai.icen.fw.spi.authorization.AuthorizationEnvironment

val tenant = tenantProvider.currentTenant()
val user = userRealmProvider.currentUser()
    ?: throw IllegalStateException("没有当前用户")
val decision = authorizationProvider.authorize(
    AuthorizationRequest(
        subject = AuthorizationSubject(id = user.id, type = "user"),
        resource = AuthorizationResource(
            id = Identifier("doc_123"),
            type = "document",
            tenantId = tenant.id
        ),
        action = AuthorizationAction("document:read"),
        environment = AuthorizationEnvironment()
    )
)
```

> [!WARNING]
> 永远不要直接信任请求参数中的 tenantId。租户上下文必须来自宿主的可信身份系统。

## 常见问题

**为什么 Worker 不消费任务？**
确认该角色配置了 `process-tasks: true`、租约时长大于 handler 执行时间，并且每个租户级队列只有一个活跃的任务 Worker 集群。

**我可以删除失败的 Outbox 行来清除告警吗？**
不可以。失败行是证据。应重试根因；使用正式重试端点，或等待 Worker 的退避与耗尽处理。

## 下一步

- 理解运行角色：[生产部署](deployment)
- 阅读全部指标：[Doctor 与可观测性](doctor-observability)
- 查看 HTTP API 错误码：[HTTP API v1](../reference/http-api)
