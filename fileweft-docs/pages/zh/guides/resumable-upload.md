---
route: "guides/resumable-upload"
group: "guides"
order: 6
locale: "zh"
nav: "断点续传"
title: "断点续传协议"
lead: "通过正式 v1 上传资源，在不稳定网络中恢复服务端已确认的分片。"
format: "markdown"
---

## 协议边界

正式资源位于 `/fileweft/v1/uploads`，由 Spring Boot 2 和 Spring Boot 3 Web Starter 提供相同协议。它只负责把字节持久化为 `FileObject + FileAsset`，不会直接创建文档、版本、目录或审批流程。业务系统应在完成后使用回执中的资产标识执行后续业务命令。

浏览器不能提交租户、所有者、资产类型、对象键、存储 upload ID、ETag 或任意存储 metadata。租户和所有者始终来自宿主绑定的可信上下文；跨租户、跨所有者和不存在的上传统一表现为 `404 NOT_FOUND`。

协议只有五个业务操作。Servlet 容器或宿主可按标准 HTTP 语义自动回答 `HEAD`/`OPTIONS`，它们不是额外的 FileWeft 状态变更操作：

| 操作 | 路径 | 成功状态 |
| --- | --- | --- |
| 创建或重放会话 | `POST /fileweft/v1/uploads` | `201` |
| 检查服务端确认点 | `GET /fileweft/v1/uploads/{uploadId}` | `200` |
| 上传或替换一个分片 | `PUT /fileweft/v1/uploads/{uploadId}/parts/{partNumber}` | `200` |
| 完成上传 | `POST /fileweft/v1/uploads/{uploadId}/complete` | `200` |
| 放弃上传 | `DELETE /fileweft/v1/uploads/{uploadId}` | `200` |

所有 JSON 成功和失败都使用 FileWeft v1 统一响应外层。单独的 `/status` 没有必要：检查资源已经同时返回上传状态、服务端确认的分片，以及完成后的稳定回执。

## 创建会话

创建请求必须恰好提供一个 `Idempotency-Key`。允许字符为 ASCII 字母、数字、`.`、`_`、`~`、`:`、`-`，长度为 1～128。FileWeft 会把它与可信租户做版本化 SHA-256 摘要，数据库、响应和错误均不保存或回显原始值。

```bash
curl -i -X POST http://localhost:8080/fileweft/v1/uploads \
  -H "Idempotency-Key: upload-report-001" \
  -H "Content-Type: application/json" \
  -d '{
    "fileName": "report.pdf",
    "contentLength": 104857600,
    "contentType": "application/pdf"
  }'
```

`contentType` 和 `contentHash` 可省略；提供哈希时必须严格使用小写 `sha256:<64 位小写十六进制>`，且完成结果必须与它一致。`documentNumber`、`title`、`totalParts`、`assetType` 和 `metadata` 不属于这个资源。

默认根路径部署时响应带有 `Location: /fileweft/v1/uploads/{uploadId}`；配置 context path 或 servlet path 时，`Location` 会保留这些宿主前缀：

```json
{
  "code": "OK",
  "message": "OK",
  "data": {
    "uploadId": "fw-upload-7a8b9c",
    "fileName": "report.pdf",
    "contentLength": 104857600,
    "contentType": "application/pdf",
    "contentHash": null,
    "status": "UPLOADING",
    "expiresAt": 1784000000000,
    "createdTime": 1783900000000,
    "updatedTime": 1783900000000,
    "uploadedParts": [],
    "completion": null
  },
  "error": null,
  "traceId": null
}
```

同一租户、同一所有者以相同 key 和相同请求重放时，返回原会话及其最新确认点，不会新建远端 multipart；改变请求内容或由其他所有者复用同一 key 会固定返回 `409 CONFLICT`，且不泄漏冲突方。

## 上传分片

分片请求是原始字节流，不使用表单 multipart，也不需要额外幂等键。`partNumber` 必须在 1～10000 之间，`X-FileWeft-Part-Length` 必须恰好出现一次并等于实际请求体字节数。

```bash
curl -X PUT "http://localhost:8080/fileweft/v1/uploads/fw-upload-7a8b9c/parts/1" \
  -H "Content-Type: application/octet-stream" \
  -H "X-FileWeft-Part-Length: 5242880" \
  --data-binary @part-0001.bin
```

成功响应只包含上传 ID、分片号、长度和确认时间，不公开存储 ETag。客户端可在完成前用同一个分片号重新 PUT；服务端只在存储确认且实际读取长度精确匹配后更新持久化确认点。

## 断线恢复

重连后读取上传资源，以 `uploadedParts` 为唯一服务端权威检查点：

```bash
curl http://localhost:8080/fileweft/v1/uploads/fw-upload-7a8b9c
```

状态可能是 `UPLOADING`、`FINALIZING`、`COMPLETED`、`FAILED`、`ABORTED` 或 `EXPIRED`。内部 staging、abort 围栏和 `QUARANTINED` 不会公开。完成前，已确认的分片号必须从 1 开始连续，且总长度必须等于创建时声明的完整文件长度。

## 完成与结果未知

```bash
curl -X POST http://localhost:8080/fileweft/v1/uploads/fw-upload-7a8b9c/complete
```

完成成功返回不含存储细节的回执；三个资源 ID 稳定，`completedAt` 是可空的提交后观测值：

```json
{
  "code": "OK",
  "message": "OK",
  "data": {
    "uploadId": "fw-upload-7a8b9c",
    "fileObjectId": "fw-object-123",
    "fileAssetId": "fw-asset-123",
    "completedAt": 1783900100000
  },
  "error": null,
  "traceId": null
}
```

完成是同步且可重放的命令。若完成已经提交但提交后的检查点读取失败，本次 200 的 `completedAt` 可以是 `null`；后续 GET/重放会补回持久化时间，客户端不得用该时间字段判断幂等身份。响应丢失后，先 `GET` 同一上传资源：若为 `COMPLETED`，`completion` 中会返回相同资源 ID；若为 `FINALIZING`，等待后以同一 `uploadId` 重试完成。无法安全判定数据库或对象存储结果时返回 `503 OUTCOME_UNKNOWN`，客户端不得创建新 key 或盲目删除对象。FileWeft 会对陈旧的完成状态进行非破坏性对账。若对象存储明确拒绝且确定没有发布对象，服务会原子清空旧确认点、恢复 `UPLOADING`、刷新一个完整会话 TTL 的重试窗口并返回 `409 CONFLICT`；客户端应再次 GET，并重新 PUT 返回结果中缺失的分片。对象存储最小分片限制属于宿主策略，反复收到这种 409 时应改用符合该策略的分片大小或放弃会话。

## 放弃上传

```bash
curl -X DELETE http://localhost:8080/fileweft/v1/uploads/fw-upload-7a8b9c
```

放弃只会终止仍可安全取消的远端 multipart：活动会话转为 `ABORTED`；已经处于终态的会话则原样返回该终态（例如 `COMPLETED` 仍为 `COMPLETED`）。处于结果未知围栏中的对象不会被清理命令破坏。

## 生产网关

对 `/fileweft/v1/uploads/*/parts/*` 禁用请求体缓冲，并把单请求上限设置为允许的分片大小加少量协议开销。请求体大小、超时、速率限制和对象存储最小分片限制由宿主按风险模型配置；不要使用全局 multipart 表单上限代替分片路由策略。
