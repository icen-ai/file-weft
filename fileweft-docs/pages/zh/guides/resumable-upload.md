---
route: "guides/resumable-upload"
group: "guides"
order: 6
locale: "zh"
nav: "断点续传"
title: "断点续传协议"
lead: "使用调用方稳定的幂等键和编号分片，在不稳定的网络里上传大文件。"
format: "markdown"
---

## 协议概览

1. 用调用方稳定的幂等键**启动**上传会话。
2. **上传**编号分片并持久化每次确认。
3. 重连后**检查**会话状态。
4. 幂等**完成**一次以创建对象、资产和事件。
5. 明确放弃时**终止**会话。

底层存储 upload ID 和对象路径不会返回给浏览器。

## 启动会话

```bash
curl -X POST http://localhost:8080/fileweft/v1/uploads \
  -H "Idempotency-Key: upload-report-001" \
  -H "Content-Type: application/json" \
  -d '{
    "documentNumber": "DOC-003",
    "title": "Large report",
    "fileName": "report.pdf",
    "contentLength": 104857600,
    "totalParts": 10
  }'
```

响应：

```json
{
  "uploadId": "fw-upload-7a8b9c",
  "uploadedParts": []
}
```

## 上传分片

```bash
curl -X POST "http://localhost:8080/fileweft/v1/uploads/fw-upload-7a8b9c/parts?partNumber=1" \
  -H "Idempotency-Key: upload-report-001-part-1" \
  -F "file=@part1.bin"
```

## 完成上传

```bash
curl -X POST http://localhost:8080/fileweft/v1/uploads/fw-upload-7a8b9c/complete \
  -H "Idempotency-Key: upload-report-001-complete"
```

> [!NOTE]
> 用相同幂等键重放完成调用会返回同一文档和版本，避免重复资产。
