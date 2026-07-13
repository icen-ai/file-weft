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

当 `code` 不是 `OK` 时，`error` 会包含 `code`、`message` 和可选 `details`。稳定错误码清单见 [错误码](./error-codes.md)。

> [!NOTE]
> FileWeft 不会通过公共协议暴露存储 URL。下载返回二进制流，并携带 `attachment`、`nosniff` 和 `private, no-store` 头。不支持 Range、HEAD、ETag 和 Content-Range。

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

## 幂等命令

会改变状态的命令必须携带且仅携带一个 `Idempotency-Key` 头。服务端保存租户作用域的 SHA-256 摘要，并绑定可信操作者、动作、资源和命令指纹。

```bash
curl -X POST "https://fileweft.example.com/fileweft/v1/documents/doc_123/publish" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Idempotency-Key: publish-doc_123-20250713" \
  -H "Content-Type: application/json" \
  -d '{"comment": "Approved for release"}'
```

> [!WARNING]
> 重放时仍会重新执行认证、动作权限和目录可见性检查。幂等记录不是权限缓存。

## 完整示例：上传并发布

下面的 JSON 请求体使用代表性字段名展示命令结构，精确请求模式以 v1 契约为准。

### 1. 创建上传会话

```bash
curl -X POST "https://fileweft.example.com/fileweft/v1/uploads" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{
    "...": "文件元数据与分片计划"
  }'
```

响应：

```json
{
  "code": "OK",
  "message": "OK",
  "data": {
    "uploadId": "upl_7a8b9c",
    "...": "会话详情"
  },
  "error": null,
  "traceId": "trace-abc-123"
}
```

### 2. 上传每个分片

```bash
for part in {1..5}; do
  curl -X PUT "https://fileweft.example.com/fileweft/v1/uploads/upl_7a8b9c/parts/${part}" \
    -H "Authorization: Bearer ${TOKEN}" \
    -H "Content-Type: application/octet-stream" \
    --data-binary @part-${part}.bin
done
```

### 3. 完成上传并创建文档

```bash
curl -X POST "https://fileweft.example.com/fileweft/v1/uploads/upl_7a8b9c/complete" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Content-Type: application/json" \
  -d '{
    "...": "完成参数与文档元数据"
  }'
```

### 4. 提交审批

```bash
curl -X POST "https://fileweft.example.com/fileweft/v1/documents/doc_456/submit" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Idempotency-Key: submit-doc_456-20250713" \
  -H "Content-Type: application/json" \
  -d '{"...": "审批路由选择"}'
```

### 5. 审批并发布

```bash
curl -X POST "https://fileweft.example.com/fileweft/v1/workflows/wf_789/tasks/task_111/approve" \
  -H "Authorization: Bearer ${TOKEN}" \
  -H "Idempotency-Key: approve-task_111-20250713" \
  -H "Content-Type: application/json" \
  -d '{"...": "审批载荷"}'
```

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

**GET 请求需要幂等键吗？**
不需要。只有发布、审批、重试、Doctor 排队等会改变状态的命令才需要。

**不同动作可以复用同一个幂等键吗？**
不可以。幂等键与操作者、动作、资源和命令指纹绑定。用于不同动作会生成新记录。

## 下一步

- [错误码](./error-codes.md)
- [配置参考](./configuration.md)
- [生命周期与交付概念](../concepts/lifecycle-delivery.md)
