---
route: "guides/workflows-uploads"
group: "guides"
order: 2
locale: "zh"
nav: "工作流与上传"
title: "审批、断点续传与持久任务"
lead: "理解 FlowWeft 如何处理长周期工作——文档审批、分片上传和通用后台任务——同时不削弱事务边界，也不向外部泄漏存储内部细节。"
format: "markdown"
---

企业文件很少在一次请求内完成流转。一份文档可能需要审批，一段 2 GB 的视频需要断点续传，OCR 或扫描任务可能需要异步处理内容。FlowWeft 把这些关注点保持为显式、持久且隔离的。

## 1. 审批路由

`DocumentReviewRouteProvider` SPI 让宿主决定文档发布前需要谁审批。解析在 FlowWeft 数据库事务外执行，因此可以安全查询 HR 或 BPM 系统。

```kotlin
import ai.icen.fw.core.id.Identifier
import ai.icen.fw.spi.workflow.DocumentReviewRoute
import ai.icen.fw.spi.workflow.DocumentReviewRouteProvider
import ai.icen.fw.spi.workflow.DocumentReviewRouteRequest
import ai.icen.fw.spi.workflow.DocumentReviewRouteTask
import org.springframework.stereotype.Component

@Component
class ComplianceRouteProvider : DocumentReviewRouteProvider {

    override fun id(): String = "compliance"

    override fun resolve(request: DocumentReviewRouteRequest): DocumentReviewRoute {
        // 宿主根据文档类型、租户或请求中的其他属性决定审批人。
        val approvers = listOf("compliance-lead", "legal-lead")
        return DocumentReviewRoute(
            workflowType = "COMPLIANCE_REVIEW",
            tasks = approvers.map { userId ->
                DocumentReviewRouteTask(assigneeId = Identifier(userId))
            },
        )
    }
}
```

行为要点：

1. 调用 `submit` 时，FlowWeft 只选择一个 Provider：优先使用请求中的 `reviewRouteId`，否则使用 `fileweft.workflow.default-review-route-id` 配置的默认路由。
2. 并行任务同时会签，必须全部通过才发布。
3. 任一驳回即结束流程。
4. 最终事务在提交前会重新检查文档状态。

## 2. 断点续传

大文件通过正式资源 `/fileweft/v1/uploads` 上传。该协议是有状态、幂等的，且不会把存储 upload ID 或对象路径暴露给浏览器。

| 步骤 | HTTP | 作用 |
| --- | --- | --- |
| 1. 创建会话 | `POST /uploads` | 用幂等键预留上传 ID。 |
| 2. 上传分片 | `PUT /uploads/{id}/parts/{n}` | 发送某个编号分片的原始字节。 |
| 3. 检查确认点 | `GET /uploads/{id}` | 从服务端确认点恢复。 |
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

完成回执中的 `fileAssetId` 是已持久化资产的标识。当前正式 `POST /fileweft/v1/documents` 与 `POST /fileweft/v1/documents/{id}/versions` 仍接收 multipart 文件内容，并不接受这个 ID。若要复用该资产，宿主必须在自己的应用层集成中完成资产绑定；当前正式文档 HTTP 资源尚未提供这一步。

> [!TIP]
> 始终把检查接口返回的 `uploadedParts` 当作唯一权威检查点。客户端状态可能丢失，服务端状态不会。

## 3. 通用后台任务

OCR、病毒扫描、宿主自有抽取和自定义诊断都应放入持久化 `fw_task` handler。它们在请求线程外执行，并通过租约重试。

> [!CAUTION]
> `FileWeftTaskHandler` 是当前通用持久任务能力，不是 FlowWeft Agent。`fileweft-agent`、Agent SPI/ABI 和相关迁移仅为兼容保留；0.0.2 与 0.0.3 的默认产品面都不注册或暴露 Agent。

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

任务由应用层创建，或通过 Outbox 事件发出。Worker 消费后保证至少执行一次，且对 task id 幂等。

## 4. 整体流程

一个典型流程如下：

1. 用户创建断点续传会话并发送所有分片。
2. 完成接口返回 `fileAssetId`。
3. 宿主通过自有应用层集成把该资产绑定为文档或版本；这不是当前正式文档 HTTP 资源的一部分。
4. 宿主调用 `POST /documents/{id}/submit` 启动审批。
5. 审批路由 Provider 返回审批人。
6. 全部审批通过后，FlowWeft 发布文档并触发交付连接器。
7. 可选：Outbox 事件为已发布文档调度 OCR 任务。

任何数据库事务都不会直接调用外部系统，所有外部调用都通过 Outbox 和异步 Worker 流转。

> [!WARNING]
> 不要在文档生命周期事务中调用连接器、存储或 AI 服务。请使用 Outbox 事件和任务 Handler。

## 常见问题

**Q：内部文档可以跳过审批吗？**
不能通过空路由表达。`DocumentReviewRoute` 要求 `workflowType` 非空、至少包含一个任务且任务不能重复；当前正式 `submit` HTTP 路径也会创建本地审批任务。若业务允许不走本地审批，宿主需要设计另一条受授权的生命周期流程，不能让该 Provider 返回空列表。

**Q：Worker 执行任务时崩溃怎么办？**
任务租约到期后，其他 Worker 会接管。Handler 必须对 task id 幂等。

**Q：客户端能选择存储 upload ID 吗？**
不能。存储 upload ID、ETag 和对象键都属于存储适配器内部。

## 下一步

- [断点续传协议](resumable-upload.md) 了解完整字节级语义。
- [实现持久任务 Handler](agent-handler.md) 添加 OCR、扫描或其他宿主后台工作。
- [生命周期与交付概念](../concepts/lifecycle-delivery.md) 了解发布/下线/归档行为。
