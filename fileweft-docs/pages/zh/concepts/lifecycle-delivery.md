---
route: "concepts/lifecycle-delivery"
group: "concepts"
order: 2
locale: "zh"
nav: "生命周期与交付"
title: "生命周期与交付"
lead: "“已发布”不是单个布尔值，而是一条必须经受审批、下游部分失败和后续撤回考验的证据链。本页说明 FileWeft 的状态机、如何按目标跟踪多下游交付，以及代次围栏为何重要。"
format: "markdown"
---

## 01. 文档状态机

文档通过显式状态流转。每次转换都是命令，而不是直接改字段。

| 状态 | 含义 | 典型转换 |
|---|---|---|
| `DRAFT` | 可编辑的工作副本 | 初始状态 |
| `PENDING_REVIEW` | 等待审批 | 从 `DRAFT` 执行 `submit` |
| `REJECTED` | 被驳回修改 | 从 `PENDING_REVIEW` 执行 `reject` |
| `PUBLISHING` | 已审批；正在交付下游目标 | 审批后的内部转换 |
| `PUBLISHED` | 所有必达目标都已成功 | `publish` 命令成功 |
| `SYNC_ERROR` | 一个或多个必达目标失败 | 从 `PUBLISHING` 收到 `sync_failed` |
| `OFFLINE` | 已公开下线，但仍可恢复 | 从 `PUBLISHED` 执行 `offline` |
| `HISTORY` | 已归档；不再建议变更 | 从 `PUBLISHED` 执行 `archive` |

主路径是：

```
DRAFT → PENDING_REVIEW → PUBLISHING → PUBLISHED
```

返工路径：`REJECTED → revise → DRAFT`。已发布文档可以先 `OFFLINE`，再执行 `restore` 回到 `DRAFT`。归档是显式操作，会进入 `HISTORY`。

## 02. 通过 HTTP 发送生命周期命令

控制器不会直接修改状态字段。调用方发送命令，FileWeft 应用领域规则。

提交草稿进入审批：

```bash
curl -X POST "https://api.example.com/fileweft/v1/documents/{documentId}/submit" \
     -H "Authorization: Bearer $TOKEN"
```

审批工作流任务：

```bash
curl -X POST "https://api.example.com/fileweft/v1/workflows/{workflowId}/tasks/{taskId}/approve" \
     -H "Authorization: Bearer $TOKEN"
```

发布、下线、恢复、归档遵循同样模式：

```bash
curl -X POST "https://api.example.com/fileweft/v1/documents/{documentId}/publish"  -H "Authorization: Bearer $TOKEN"
curl -X POST "https://api.example.com/fileweft/v1/documents/{documentId}/offline"  -H "Authorization: Bearer $TOKEN"
curl -X POST "https://api.example.com/fileweft/v1/documents/{documentId}/restore"  -H "Authorization: Bearer $TOKEN"
curl -X POST "https://api.example.com/fileweft/v1/documents/{documentId}/archive"  -H "Authorization: Bearer $TOKEN"
```

> [!NOTE]
> 状态变更、审计日志与 Outbox 事件在同一个业务事务中提交。如果事务回滚，不会发出任何事件。

## 03. 多目标交付

一份文档通常需要到达多个下游系统：合规归档、检索索引、内容分发网络。FileWeft 为每个目标单独跟踪。

| 目标类型 | 是否必达 | 失败影响 |
|---|---|---|
| 必达 | 是 | 阻止进入 `PUBLISHED`；文档进入 `SYNC_ERROR` 并继续重试 |
| 可选 | 否 | 文档仍可发布；失败被记录，可单独重试 |

保护下游可靠性的规则：

1. **所有必达目标成功**后，文档才会变成 `PUBLISHED`。
2. **可选目标失败不会阻塞发布。** 文档发布后，失败目标仍可重试。
3. **不会回滚已成功目标。** 如果后面的目标失败，FileWeft 不会撤销之前成功的目标。
4. **运维人员只重试失败目标。** 使用交付重试端点，而不是重新发布整份文档。

## 04. 代次围栏

每次审批都会产生一个新的**交付代次**。如果一个慢连接器在代次 3 已经发布后才返回代次 2 的结果，这个迟到结果会被丢弃，不能覆盖当前状态。

```
代次 1：draft-v1  →  published-v1
代次 2：draft-v2  →  published-v2（当前）
            ↑ 代次 1 的迟到响应被丢弃
```

代次围栏避免了密集连续发布时出现的竞态条件。

## 05. Outbox 让事务保持本地

FileWeft 从不在写入文档状态的数据库事务中调用下游连接器。它会把 Outbox 事件写入同一份 PostgreSQL 事务，再由 Worker 异步交付。

```
业务事务
    ↓
Outbox 事件
    ↓
异步 Worker
    ↓
连接器
```

这带来三点保证：

- 状态变更与事件在本地数据库是原子的。
- 连接器崩溃不会回滚文档状态。
- 每个目标独立重试。

## 06. 重试与撤回

查看当前同步状态：

```bash
curl "https://api.example.com/fileweft/v1/documents/{documentId}/sync-status" \
     -H "Authorization: Bearer $TOKEN"
```

重试失败的交付目标：

```bash
curl -X POST "https://api.example.com/fileweft/v1/documents/{documentId}/deliveries/{deliveryId}/retry" \
     -H "Authorization: Bearer $TOKEN"
```

当文档被下线或归档时，FileWeft 会为每个已交付目标写入 `document.delivery.target.removal.requested` 事件。失败的移除同样可以这样重试：

```bash
curl -X POST "https://api.example.com/fileweft/v1/documents/{documentId}/deliveries/{deliveryId}/removal/retry" \
     -H "Authorization: Bearer $TOKEN"
```

## 07. 配置交付目标

`application.yml` 的 `sync` 段声明哪些连接器是必达、哪些是可选。

```yaml
fileweft:
  sync:
    connector-timeout-millis: 30000
    source-access-url-ttl-millis: 900000
    circuit-breaker-failure-threshold: 3
    circuit-breaker-open-duration-millis: 30000
    connector-max-concurrent-invocations: 16
    connector-invocation-queue-capacity: 256
    default-profile-id: regulated
    profiles:
      - id: regulated
        display-name: "受监管发布"
        targets:
          - id: compliance
            display-name: "合规归档"
            connector-id: complianceConnector
            required: true
            owner-ref: compliance-ops
          - id: search
            display-name: "检索索引"
            connector-id: searchConnector
            required: false
            owner-ref: search-ops
```

> [!TIP]
> 为每个目标设置 `owner-ref`。当交付卡在 `SYNC_ERROR` 时，运维人员知道该找谁。

## 常见问题

**Q：没有配置目标也能发布文档吗？**
可以。如果当前 profile 没有必达目标且宿主允许，FileWeft 仍会记录发布事件与审计日志。

**Q：必达目标永久宕机怎么办？**
文档会停留在 `SYNC_ERROR`。FileWeft 会按任务与 Outbox 策略继续重试。连接器恢复健康后，运维人员也可以手动触发重试。

**Q：归档会撤销已交付吗？**
不会。归档会写入移除请求，但不保证立即移除。每个目标的连接器独立处理移除。

## 下一步

- [Outbox 模式](./outbox.md)——FileWeft 如何避免 PostgreSQL 与外部系统之间的双写。
- [连接器](../extensions/connectors.md)——实现带超时、重试与幂等的 `FileConnector`。
- [Doctor 与可观测性](../operations/doctor-observability.md)——诊断卡住交付与连接器健康。
