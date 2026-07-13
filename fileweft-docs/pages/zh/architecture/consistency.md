---
route: "architecture/consistency"
group: "architecture"
order: 1
locale: "zh"
nav: "一致性模型"
title: "本地事务原子，远程状态显式收敛"
lead: "FileWeft 不承诺跨 PostgreSQL、对象存储和多个下游系统的分布式事务。它保证每一次本地状态变更的原子性，并让远端收敛过程可观测、可重试且易于推理。"
format: "markdown"
---

## 问题背景

一次文档发布操作至少会涉及三个相互独立的系统：

1. PostgreSQL：保存文档、版本、工作流和交付记录。
2. 对象存储：保存不可变的资产字节。
3. 一个或多个下游连接器：把文档同步到检索索引、合规归档或 AI 流水线。

如果其中任何一步执行到一半失败，朴素的分布式事务要么锁住所有系统，要么留下不一致状态。FileWeft 通过让本地数据库成为唯一真相源，并把每一次远程调用都建模为最终一致的投影，来同时避免这两种结果。

## 核心思想：本地原子性优先

FileWeft 只信任本地 PostgreSQL 事务，其他一切都被建模为框架后续收敛的持久事件。

| 你看到的内容 | 提供的保证 |
| --- | --- |
| PostgreSQL 中的文档状态 | 本地事务内强一致 |
| 存储中的对象字节 | 通过补偿或引用管理，不是真相源 |
| 连接器交付 | 至少一次、幂等、通过 Outbox 可观测 |

> **NOTE**
> FileWeft 绝不在数据库事务中调用下游连接器。事务只写入 Outbox 记录，异步 Worker 随后执行远程调用。

## 事务 Outbox 模式

发布命令遵循四个显式阶段：

1. **校验与准备**：检查权限、生命周期规则和连接器配置。
2. **写入本地事实**：在单个 PostgreSQL 事务中提交文档版本、交付记录和一条或多条 Outbox 事件。
3. **Worker 带租约领取**：异步 Worker 使用时间受限的租约领取 Outbox 记录，避免多节点竞争。
4. **连接器收敛**：Worker 以稳定的幂等身份调用连接器，并记录执行结果。

```yaml
fileweft:
  worker:
    enabled: true
    fixed-delay-millis: 1000
    outbox-batch-size: 50
    process-outbox: true
  outbox:
    lease-duration-millis: 300000
    legacy-running-grace-millis: 300000
```

```kotlin
// Worker 在业务事务外调用连接器
interface FileConnector {
    fun sync(request: ConnectorSyncRequest): ConnectorSyncResult
    fun remove(request: ConnectorRemoveRequest): ConnectorSyncResult
    fun health(): ConnectorHealth
}
```

> **TIP**
> 连接器实现应把 `ConnectorSyncRequest` 中的稳定幂等标识当作幂等键。即使上一次 HTTP 响应丢失，重放同一请求也必须产生相同的逻辑结果。

## 存储补偿

上传会先于业务记录产生字节。FileWeft 在能证明这些字节未被引用时进行补偿：

| 场景 | 动作 |
| --- | --- |
| 字节未通过校验（病毒、哈希不匹配、不支持的格式） | 立即删除对象 |
| 本地事务回滚且未写入持久引用 | 安全删除对象 |
| 提交结果未知（网络分区、JVM 崩溃） | 先对账，保留证据，绝不猜测 |

> **WARNING**
> 不要仅按对象年龄编写清理任务来删除对象。必须先与 PostgreSQL 引用对账，否则会误删提交记录延迟的真实数据。

## 命令内部的加锁顺序

受保护的命令按稳定顺序获取锁，避免并发发布、审批和生命周期转换时产生死锁：

```
幂等 claim → document → asset → workflow
```

对目录提供者、审批路由提供者和交付策略的外部调用位于最终短事务之外。它们可以影响命令，但不参与加锁顺序。

## 交付收敛状态

文档发布时，FileWeft 会根据当前同步配置向所有目标扇出：

| 必达目标 | 可选目标 | 最终文档状态 |
| --- | --- | --- |
| 全部成功 | 任意结果 | `PUBLISHED` |
| 一个失败且可重试 | 不决定结果 | `SYNC_ERROR`，Worker 继续重试 |
| 一个永久失败 | 不决定结果 | 转换失败，文档保持原状态 |
| 任意结果 | 一个失败 | 仍为 `PUBLISHED`；失败会被记录供运维排查 |

> **NOTE**
> FileWeft 不会回滚已经成功的连接器。交付是追加写入：成功的目标保持发布，失败的目标被重试或上报。

## 观测收敛过程

通过 Doctor 端点或 Outbox 指标来观测系统是否已收敛：

```bash
# 查看文档级 Doctor 报告
curl http://localhost:8080/fileweft/v1/documents/{documentId}/doctor

# 查看 Outbox 积压指标（标签中不会出现 tenantId 或 documentId）
curl http://localhost:8080/actuator/metrics/fileweft.outbox_backlog
```

`fileweft.outbox_backlog` 指标暴露 `ready`、`delayed`、`running`、`expired`、`failed` 等状态。`expired` 或 `failed` 持续非零，说明连接器或 Worker 需要干预。

## 常见问题

**Q: FileWeft 是否支持对象存储与 PostgreSQL 的两阶段提交？**

不支持。FileWeft 先提交 PostgreSQL，再收敛对象存储和连接器。这避免了在远程系统上持有锁，也让分区场景下架构仍可运行。

**Q: Worker 在连接器已提交但尚未标记 Outbox 记录完成时崩溃会怎样？**

下一个获得租约的 Worker 会重放该连接器调用。由于连接器必须是幂等的，第二次调用安全且产生相同逻辑结果。

**Q: 我可以在应用服务里同步调用连接器吗？**

不应该。应用服务只写 Outbox 事件，Worker 负责调用连接器。这样既保持了本地原子性保证，也防止外部失败回滚业务事务。

## 下一步

- [安全架构](/architecture/security) — 边界不完整时 FileWeft 如何失败关闭。
- [事件驱动交付](/architecture/event-driven) — 深入了解 Outbox、Worker 与任务处理机制。
- [断点续传](/guides/resumable-upload) — 一个依赖相同本地原子性模型的具体协议。
