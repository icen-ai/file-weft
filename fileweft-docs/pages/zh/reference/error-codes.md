---
route: "reference/error-codes"
group: "reference"
order: 4
locale: "zh"
nav: "错误码"
title: "稳定错误码"
lead: "当请求失败时，FileWeft 会在 JSON 外层里返回一个稳定的机器可读错误码。本页说明每个错误码的含义以及恢复操作。"
format: "markdown"
---

## 错误如何返回

所有 v1 接口返回统一外层。失败时 `code` 不是 `OK`，且 `error` 会被填充：

```json
{
  "code": "INVALID_REQUEST",
  "message": "缺少必填字段",
  "data": {},
  "error": {
    "code": "INVALID_REQUEST",
    "message": "缺少必填字段",
    "details": {
      "...": "额外上下文"
    }
  },
  "traceId": "trace-abc-123"
}
```

> [!TIP]
> 联系运维前，请记录 `traceId` 和完整错误体。错误码跨版本稳定；自由文本消息可能变化。

## 稳定错误码

| 错误码 | 含义 | 处理方式 |
|--------|------|---------|
| `INVALID_REQUEST` | 请求体、查询参数或路径变量格式错误或未通过校验。 | 修正请求后重试。 |
| `UNAUTHENTICATED` | 未提供有效凭证。 | 刷新或获取令牌后重试。 |
| `FORBIDDEN` | 调用方已认证，但没有该动作或资源的权限。 | 检查角色、目录 ACL 和租户范围。 |
| `NOT_FOUND` | 请求的文档、版本、上传会话、工作流任务或交付不存在。 | 核对标识符和租户上下文。 |
| `METHOD_NOT_ALLOWED` | 该路径不支持此 HTTP 方法。 | 使用 API 文档列出的方法。 |
| `NOT_ACCEPTABLE` | 无法满足 `Accept` 头。 | 使用 `application/json` 或省略该头。 |
| `UNSUPPORTED_MEDIA_TYPE` | 不支持的 `Content-Type`。 | 使用 `application/json` 或要求的二进制类型。 |
| `RANGE_NOT_SUPPORTED` | FileWeft 下载不支持 Range 请求。 | 请求完整资源。 |
| `CONFLICT` | 操作与当前状态冲突，例如重复发布已发布文档。 | 读取当前状态并协调。 |
| `FEATURE_UNAVAILABLE` | 功能未启用或未配置，例如缺少连接器 profile。 | 启用功能或配置所需的 SPI/profile。 |
| `CONTENT_UNAVAILABLE` | 内容存在但当前状态不可访问，例如文档已下线。 | 改变生命周期状态或等待交付完成。 |
| `OUTCOME_UNKNOWN` | 服务端已接受命令但无法确认最终结果，常见于超时。 | 使用幂等键并查询资源状态。 |
| `INTERNAL_ERROR` | FileWeft 内部发生意外失败。 | 退避重试，持续出现则联系运维。 |

> [!NOTE]
> HTTP 状态码是传输层语义，可能因端点而异。程序处理请以 `error.code` 为准。

## 幂等冲突

用同一个 `Idempotency-Key` 重放命令时，如果指纹匹配，FileWeft 会返回原始结果。如果 key 相同但命令不同，则返回 `CONFLICT`：

```json
{
  "code": "CONFLICT",
  "message": "Idempotency key already used with different parameters",
  "data": {},
  "error": {
    "code": "CONFLICT",
    "message": "Idempotency key already used with different parameters",
    "details": {
      "...": "额外上下文"
    }
  },
  "traceId": "trace-def-456"
}
```

规避方法：在每次幂等键中包含唯一作用域，例如资源 ID、动作和日期或 UUID。

## 与状态相关的错误

部分错误码与文档生命周期有关：

- `CONFLICT` — 文档不在 `PENDING_REVIEW` 或 `DRAFT` 状态时执行发布。
- `FEATURE_UNAVAILABLE` — 未注册 `DocumentReviewRouteProvider` 时提交审批。
- `CONTENT_UNAVAILABLE` — 下载 `OFFLINE` 或 `ARCHIVED` 文档的内容。
- `OUTCOME_UNKNOWN` — 连接器超时，未返回明确结果。

重试前可先查询 `GET /documents/{documentId}` 或 `GET /documents/{documentId}/sync-status` 了解当前状态。

## 常见问题

**0.0.2 到未来版本之间错误码会变吗？**
列出的错误码是稳定的。未来可能新增错误码，但已有错误码含义不变。

**`INTERNAL_ERROR` 应该立即重试吗？**
应使用指数退避重试。如果持续出现，请检查 Doctor 端点和服务端日志。

**为什么我刚创建的资源会返回 `FORBIDDEN`？**
授权基于 `TenantProvider` 返回的可信租户，而不是请求参数。请检查租户头或令牌是否映射到预期作用域。

## 下一步

- [HTTP API v1 参考](./http-api.md)
- [生命周期与交付概念](../concepts/lifecycle-delivery.md)
- [Doctor 可观测性](../operations/doctor-observability.md)
