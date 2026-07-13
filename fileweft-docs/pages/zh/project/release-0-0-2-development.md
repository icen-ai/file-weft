---
route: "project/release-0-0-2-development"
group: "project"
order: 4
locale: "zh"
nav: "0.0.2 开发中"
title: "0.0.2 开发线"
lead: "本页跟踪 0.0.2-SNAPSHOT 开发线、它引入的迁移工作，以及你在把任何 0.0.2 制品视为稳定前必须验证的内容。"
format: "markdown"
---

## 0.0.2-SNAPSHOT 改动什么

> [!WARNING] 不是稳定版
> `0.0.2-SNAPSHOT` 是尚未发布的开发线。在发布门禁和远端制品验收完成前，稳定正式版仍是 `ai.icen:*:0.0.1`。

0.0.2 主要收口首个公开版本后出现的问题：

1. **工作流决策证据** —— 审批和驳回记录不可变操作者 ID、可选安全显示名快照与 `decidedTime`。
2. **身份契约收紧** —— 宿主用户 ID 必须是区分大小写的不透明字符串，最多 256 个 UTF-16 code unit，并遵守固定安全字符契约。
3. **正式 HTTP 资源** —— 断点续传、目录与 Agent 端点从内部/开发形态进入正式 v1 表面。
4. **发布加固** —— 基于运行时闭包的 SBOM、SNAPSHOT 发布校验器与可复现构建元数据。

## V026 工作流决策证据迁移

V026 改变了工作流决策的存储与投影方式。必须严格按顺序迁移；跳过任何步骤都可能让数据库处于旧二进制读不懂、新二进制信不过的状态。

1. 运行 `docs/sql/postgresql-v026-workflow-decision-evidence-preflight.sql`。
2. 不截断、不填充、不猜测地修复不安全的宿主用户 ID 映射。
3. 关闭审批命令，停止全部旧 API 节点，等待在途决策结束。
4. 重跑预检脚本，应用 Flyway 迁移，然后核验列和约束。
5. 仅启动理解 V026 的新节点。

回滚时也要保留 V026 列、约束和证据。不能缩窄身份列，也不能让旧二进制重新开放审批写入。

> [!CAUTION] 遗留证据保持 UNKNOWN
> V026 不从受理人、当前用户目录或可选审计行推断操作者。已完成的遗留任务保持 `UNKNOWN`。

## 身份契约收紧

宿主用户 ID 必须满足：

- 不透明字符串；
- 区分大小写；
- 最多 256 个 UTF-16 code unit；
- 去除首尾 Unicode whitespace；
- 不含 ISO control/format 字符。

如果宿主当前把用户 ID 存为 `Long`、`Int`、`UUID` 或任意目录标识，迁移到 0.0.2 前必须先转换成永久稳定的字符串表示。

```kotlin
// 不要这样做
val userId = rawUserId.toString().trim().lowercase()

// 应该这样做
val userId = encodeStableString(rawUserId) // 永远使用同一算法
```

> [!NOTE] 为什么不归一化？
> FileWeft 把用户 ID 视为不透明。在框架内部小写、trim 或重排会静默改变授权决策。

## 哪些尚未稳定

以下事项在 0.0.2-SNAPSHOT 中仍未完成。在发布门禁关闭前，不要把生产行为建立在这些能力上。

- 正式版/SNAPSHOT fixture 及损坏、重复、XXE、路径穿越、混合构建负例。
- 仓库精确库存、artifact 级 metadata/checksum 与危险 JAR entry 校验。
- 正式目录与 Agent HTTP 资源及其双 Boot/浏览器验收。
- 最终干净发布门禁。

## 如何安全地测试 SNAPSHOT

如果你想评估 0.2-SNAPSHOT，请将其与生产数据和流量隔离：

1. 使用独立数据库 schema 和对象存储 bucket。
2. 先启用 `fileweft.persistence.migration-mode=validate` 确认 schema 预期。
3. 在跑真实工作流前，先调用 Doctor 端点并跑集成测试。
4. 在维护者发布稳定 `0.0.2` 前，不要把 SNAPSHOT 提升到生产环境。

## 常见问题

**0.0.2 什么时候稳定？**
只有当 [路线图](project/roadmap) 中所有验收证据都能从干净环境复现，且远端制品已验收后，才会稳定。

**可以跳过 V026 预检吗？**
不能。预检用于发现迁移后会违反收紧身份契约的不安全用户 ID 映射。

**0.0.2 会破坏我的 SPI 实现吗？**
公共 SPI 契约预期保持兼容，但你必须在发布制品上重新编译并运行契约测试。

## 下一步

- 阅读 [路线图](project/roadmap)，了解 0.0.2 完整验收标准。
- 查看 [HTTP API v1 参考](reference/http-api)，确认哪些端点已是正式接口。
- 遵循 [迁移与发布](operations/migrations-release) 的安全升级实践。
