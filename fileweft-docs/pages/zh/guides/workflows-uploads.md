---
route: "guides/workflows-uploads"
group: "guides"
order: 2
locale: "zh"
nav: "工作流与上传"
title: "审批、断点续传与持久化 Agent"
lead: "理解 FileWeft 如何处理长周期工作——文档审批、分片上传和后台 AI 任务——同时不削弱事务边界，也不向外部泄漏存储内部细节。"
format: "markdown"
---

企业文件很少在一次请求内完成流转。一份文档可能需要审批，一段 2 GB 的视频需要断点续传，AI 模型可能需要异步处理内容。FileWeft 把这些关注点都保持为显式、持久且受控的。

## 1. 审批路由

`DocumentReviewRouteProvider` SPI 让宿主决定文档发布前需要谁审批。解析在 FileWeft 数据库事务外执行，因此可以安全查询 HR 或 BPM 系统。

```kotlin
@Component
class ComplianceRouteProvider : DocumentReviewRouteProvider {

    override fun id(): String = "compliance"

    override fun resolve(request: DocumentReviewRouteRequest): DocumentReviewRoute {
        // 宿主根据文档类型、租户或请求中的其他属性决定审批人。
        val approvers = listOf("compliance-lead", "legal-lead")
        return DocumentReviewRoute(
            tasks = approvers.map { userId ->
                DocumentReviewTask(
                    assignee = Identifier(userId),
                    operation = "APPROVE",
                )
            },
        )
    }
}
```

行为要点：

1. 调用 `submit` 时，FileWeft 会请求所有已注册 Provider 解析任务。
2. 并行任务同时会签，必须全部通过才发布。
3. 任一驳回即结束流程。
4. 最终事务在提交前会重新检查文档状态。

## 2. 断点续传

大文件通过正式资源 `/fileweft/v1/uploads` 上传。该协议是有状态、幂等的，且不会把存储 upload ID 或对象路径暴露给浏览器。

| 步骤 | HTTP | 作用 |
| --- | --- | --- |
| 1. 创建会话 | `POST /uploads` | 用幂等键预留上传 ID。 |
| 2. 上传分片 | `PUT /uploads/{id}/parts/{n}` | 发送某个编号分片的原始字节。 |
| 3. 检查检查点 | `GET /uploads/{id}` | 从服务端确认点恢复。 |
| 4. 完成上传 | `POST /uploads/{id}/complete` | 把分片组装成 `FileObject + FileAsset`。 |
| 5. 放弃上传 | `DELETE /uploads/{id}` | 安全取消进行中的会话。 |

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

完成后返回的 `fileAssetId` 可以传给业务命令，例如 `POST /documents` 或 `POST /documents/{id}/versions`。

> [!TIP]
> 始终把检查接口返回的 `uploadedParts` 当作唯一权威检查点。客户端状态可能丢失，服务端状态不会。

## 3. Agent 与后台任务

AI 抽取、OCR、病毒扫描和自定义诊断都应放入持久化 `fw_task` handler。它们在请求线程外执行，并通过租约重试。

```kotlin
@Component
class DocumentOcrHandler : FileWeftTaskHandler {

    override fun supports(task: TaskExecution): Boolean =
        task.type == "document.ocr"

    override fun handle(task: TaskExecution): TaskHandlingResult {
        val documentId = task.payload["documentId"]
            ?: return TaskHandlingResult(TaskHandlingStatus.PERMANENT_FAILURE, "missing documentId")

        return try {
            val text = ocrClient.extractText(documentId)
            suggestionStore.save(task.tenantId, documentId, text)
            TaskHandlingResult(TaskHandlingStatus.SUCCEEDED)
        } catch (failure: IOException) {
            TaskHandlingResult(TaskHandlingStatus.RETRYABLE_FAILURE, failure.message)
        }
    }
}
```

任务由应用层创建，或通过 Outbox 事件发出。Worker 消费后保证至少执行一次，并以 task id 幂等。

## 4. 整体流程

一个典型流程如下：

1. 用户创建断点续传会话并发送所有分片。
2. 完成接口返回 `fileAssetId`。
3. 宿主用该资产调用 `POST /documents` 创建草稿文档。
4. 宿主调用 `POST /documents/{id}/submit` 启动审批。
5. 审批路由 Provider 返回审批人。
6. 全部审批通过后，FileWeft 发布文档并触发交付连接器。
7. 可选：Outbox 事件为已发布文档调度 OCR 任务。

任何数据库事务都不会直接调用外部系统，所有外部调用都通过 Outbox 和异步 Worker 流转。

> [!WARNING]
> 不要在文档生命周期事务中调用连接器、存储或 AI 服务。请使用 Outbox 事件和任务 Handler。

## 常见问题

**Q：内部文档可以跳过审批吗？**
可以。注册一个对特定文档类型返回空任务列表的路由 Provider，即可视为预审批。

**Q：Worker 执行任务时崩溃怎么办？**
任务租约到期后，其他 Worker 会接管。Handler 必须对 task id 幂等。

**Q：客户端能选择存储 upload ID 吗？**
不能。存储 upload ID、ETag 和对象键都属于存储适配器内部。

## 下一步

- [断点续传协议](resumable-upload.md) 了解完整字节级语义。
- [实现持久任务 Handler](agent-handler.md) 添加 OCR、扫描或 AI Agent。
- [生命周期与交付概念](../concepts/lifecycle-delivery.md) 了解发布/下线/归档行为。
