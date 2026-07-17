---
route: "reference/http-api"
group: "reference"
order: 2
locale: "zh"
nav: "HTTP API v1"
title: "HTTP API v1"
lead: "正式公共协议统一位于 /fileweft/v1。除授权二进制下载直接流式返回字节外，所有响应都使用稳定的 JSON 外层。"
format: "markdown"
---

## 基础地址与外层

所有接口共享前缀 `/fileweft/v1`，成功与失败都使用统一外层：

```json
{
  "code": "OK",
  "message": "OK",
  "data": {},
  "error": null,
  "traceId": "optional-host-trace-id"
}
```

当 `code` 不是 `OK` 时，`error` 只包含与外层一致的稳定 `code` 和安全 `message`，不接受任意详情属性。稳定错误码清单见 [错误码](./error-codes.md)。

> [!NOTE]
> FlowWeft 不会通过公共协议暴露存储 URL。下载返回二进制流，并携带 `attachment`、`nosniff` 和 `private, no-store` 头。不支持 Range、HEAD、ETag 和 Content-Range。

## 资源族

| 资源族 | 路由 | 用途 |
|--------|------|------|
| 上传会话 | `POST /uploads`<br>`GET /uploads/{uploadId}`<br>`PUT /uploads/{uploadId}/parts/{partNumber}`<br>`POST /uploads/{uploadId}/complete`<br>`DELETE /uploads/{uploadId}` | 断点续传分片上传 |
| 文档 | `GET /documents`<br>`POST /documents`<br>`GET /documents/{documentId}`<br>`PATCH /documents/{documentId}`<br>`POST /documents/{documentId}/versions`<br>`GET /documents/{documentId}/content`<br>`GET /documents/{documentId}/versions/{versionId}/content` | 文档生命周期与内容访问 |
| 生命周期与工作流 | `POST /documents/{documentId}/revise`<br>`POST /documents/{documentId}/publish`<br>`POST /documents/{documentId}/offline`<br>`POST /documents/{documentId}/restore`<br>`POST /documents/{documentId}/archive`<br>`POST /documents/{documentId}/submit`<br>`POST /workflows/{workflowId}/tasks/{taskId}/approve`<br>`POST /workflows/{workflowId}/tasks/{taskId}/reject`<br>`GET /workflows/tasks`<br>`GET /documents/{documentId}/workflows`<br>`GET /documents/{documentId}/workflow-decisions` | 状态流转与审批任务 |
| 交付 | `GET /documents/{documentId}/sync-status`<br>`POST /documents/{documentId}/deliveries/{deliveryId}/retry`<br>`POST /documents/{documentId}/deliveries/{deliveryId}/removal/retry` | 跟踪并恢复下游交付 |
| 审计 | `GET /documents/{documentId}/logs` | 文档审计轨迹 |
| Doctor | `GET /documents/{documentId}/doctor`<br>`POST /documents/{documentId}/doctor/tasks`<br>`GET /documents/{documentId}/doctor/tasks/{taskId}`<br>`GET /doctor` | 文档与系统诊断 |
| 系统 | `GET /plugins`<br>`GET /health` | 插件清单与存活检查 |

## 按命令定义幂等要求

幂等要求属于每个命令自己的契约，不能仅因 HTTP 方法会改变状态就推断必须携带幂等键。上传资源的要求如下：

| 命令 | 必须满足的请求契约 |
| --- | --- |
| `POST /uploads` | 必须且只能有一个 `Idempotency-Key`；JSON 体只能包含 `fileName`、`contentLength`、可选 `contentType` 与可选 `contentHash` |
| `GET /uploads/{uploadId}` | 不需要幂等键；返回权威断点，完成后还返回完成回执 |
| `PUT /uploads/{uploadId}/parts/{partNumber}` | `Content-Type: application/octet-stream`、必须且只能有一个 `X-FileWeft-Part-Length`，并发送非空原始字节；不需要幂等键 |
| `POST /uploads/{uploadId}/complete` | 无请求体，不需要幂等键 |
| `DELETE /uploads/{uploadId}` | 无请求体，不需要幂等键 |

其他命令各自声明要求。例如，发布文档需要 `Idempotency-Key`：

```bash
curl -X POST "https://fileweft.example.com/fileweft/v1/documents/doc_123/publish" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Idempotency-Key: publish-doc_123-20250713" \
  -H "Content-Type: application/json" \
  -d '{"deliveryProfileId": "regulated"}'
```

发布请求体可以省略；若提供，请求体唯一支持的字段是 `deliveryProfileId`，发布接口不接受审批 `comment`。

> [!WARNING]
> 重放时仍会重新执行认证、动作权限和目录可见性检查。幂等记录不是权限缓存。

## 完整断点续传示例

### 1. 创建上传会话

```bash
curl -X POST "https://fileweft.example.com/fileweft/v1/uploads" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Idempotency-Key: annual-report-upload-2025" \
  -H "Content-Type: application/json" \
  -d '{
    "fileName": "annual-report-2025.pdf",
    "contentLength": 104857600,
    "contentType": "application/pdf",
    "contentHash": "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
  }'
```

该幂等键应对本次逻辑上传创建保持唯一。JSON 对象不能指定租户、所有者、资产类型、存储键、存储 upload ID、ETag 或任意元数据。

响应：

```json
{
  "code": "OK",
  "message": "OK",
  "data": {
    "uploadId": "upl_7a8b9c",
    "fileName": "annual-report-2025.pdf",
    "contentLength": 104857600,
    "contentType": "application/pdf",
    "contentHash": "sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
    "status": "UPLOADING",
    "expiresAt": 1752393600000,
    "createdTime": 1752307200000,
    "updatedTime": 1752307200000,
    "uploadedParts": [],
    "completion": null
  },
  "error": null,
  "traceId": "trace-abc-123"
}
```

### 2. 上传每个分片

```bash
PART_FILE="part-1.bin"
PART_LENGTH="$(wc -c < "${PART_FILE}" | tr -d ' ')"

curl -X PUT "https://fileweft.example.com/fileweft/v1/uploads/upl_7a8b9c/parts/1" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/octet-stream" \
  -H "X-FileWeft-Part-Length: ${PART_LENGTH}" \
  --data-binary @"${PART_FILE}"
```

之后使用下一个正整数 `partNumber` 重复执行。`X-FileWeft-Part-Length` 必须且只能出现一次，并与该分片实际发送的字节数相同。

### 3. 从权威断点恢复

```bash
curl "https://fileweft.example.com/fileweft/v1/uploads/upl_7a8b9c" \
  -H "Authorization: Bearer ${TOKEN}"
```

断线恢复前读取 `data.uploadedParts`。完成后仍使用同一个 `uploadId` 查询，此时 `status` 为 `COMPLETED`，`data.completion` 包含稳定回执。

### 4. 完成上传

```bash
curl -X POST "https://fileweft.example.com/fileweft/v1/uploads/upl_7a8b9c/complete" \
  -H "Authorization: Bearer ${TOKEN}"
```

完成命令没有请求体。它同步完成上传并返回不透明回执：

```json
{
  "code": "OK",
  "message": "OK",
  "data": {
    "uploadId": "upl_7a8b9c",
    "fileObjectId": "file_123",
    "fileAssetId": "asset_456"
  },
  "error": null,
  "traceId": "trace-abc-123"
}
```

它不会创建文档。把完成后的资产创建为文档或版本，是独立的宿主应用命令。当前正式 `POST /fileweft/v1/documents` 与 `POST /fileweft/v1/documents/{documentId}/versions` 接收 multipart 内容，不接收 `fileAssetId`。这是有意划分的 API 边界，不是断点续传资源缺陷，也不是 0.0.2 的阻断项。

### 5. 再次查询已完成上传

```bash
curl "https://fileweft.example.com/fileweft/v1/uploads/upl_7a8b9c" \
  -H "Authorization: Bearer ${TOKEN}"
```

查询响应会在 `data.completion` 下返回相同三个 ID，因此即使完成响应丢失，也能恢复结果而无需再次完成上传。

## 二进制下载

内容端点返回原始字节，不要期望 JSON。

```bash
curl -O -J "https://fileweft.example.com/fileweft/v1/documents/doc_456/content" \
  -H "Authorization: Bearer ${TOKEN}"
```

响应头包含：

```
Content-Disposition: attachment; filename="annual-report-2025.pdf"
X-Content-Type-Options: nosniff
Cache-Control: private, no-store
```

## 常见问题

**内部开发路由是公共协议的一部分吗？**
不是。`/api/**` 下的路由是开发或内部端点，可能随时变更。集成请只使用 `/fileweft/v1`。

**所有写请求都需要幂等键吗？**
不需要，应遵循各端点契约。上传创建必须且只能携带一个；上传分片、完成和放弃都不需要；GET 也不需要。

**不同动作可以复用同一个幂等键吗？**
不要这样做。对于接受该头的命令，应使用对目标逻辑命令唯一的键；服务端会把摘要绑定到可信上下文与命令指纹。

## 下一步

- [错误码](./error-codes.md)
- [配置参考](./configuration.md)
- [生命周期与交付概念](../concepts/lifecycle-delivery.md)
