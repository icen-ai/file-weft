---
route: "project/release-0-0-2-development"
group: "project"
order: 4
locale: "zh"
nav: "0.0.2 发布说明"
title: "0.0.2 正式版"
lead: "本页记录 0.0.2 的精确发布合同，以及 Maven 坐标可消费前必须具备的受保护标签与远端匿名解析证据。"
format: "markdown"
---

> [!IMPORTANT] 当前 FlowWeft 决策已取代下方旧延期结论
> 本页的 Agent 段落原样保留，因为它精确记录 `0.0.2` 的产品边界；它不再约束 `0.0.3` 之后的开发。根 `AGENTS.md`、ADR 0001 与[当前路线图](./roadmap.md)已经批准 FlowWeft 1.0 的重新设计 Agent 和通用工作流底座，不能把历史表述继续解释为当前禁令。

## 0.0.2 交付什么

> [!IMPORTANT] 先验证远端可用性
> 本页对应 `v0.0.2`，但不会提前声称远端发布已经成功。只有受保护标签流水线成功，且匿名冷缓存能够解析精确远端制品后，才应消费 `ai.icen:*:0.0.2`。

0.0.2 主要收口首个公开版本后出现的问题：

1. **工作流决策证据** —— 审批和驳回记录不可变操作者 ID、可选安全显示名快照与 `decidedTime`。
2. **身份契约收紧** —— 宿主用户 ID 必须是区分大小写的不透明字符串，最多 256 个 UTF-16 code unit，并遵守固定安全字符契约。
3. **正式 HTTP 资源** —— 五操作断点续传资源从内部/开发形态进入正式 v1 表面。
4. **数据库实证** —— 受支持的 MySQL 8 与 KingbaseES 分别通过真实迁移和 JDBC repository 实库套件，并拥有独立按需门禁。
5. **发布加固** —— 基于运行时闭包的 SBOM、SNAPSHOT 发布校验器与可复现构建元数据。

> [!CAUTION] 0.0.2 不提供 Agent 产品能力
> `fileweft-agent`、Agent SPI/公共 ABI，以及 V012/V026 中 Agent 相关结构只为源码、二进制和数据库兼容而保留。默认 Runtime、Starter、Doctor/插件清单、公共 HTTP API 与 `fileweft-dev` 都不注册、宣传或暴露 Agent；显式遗留兼容开关也不是 0.0.2 功能。Agent 无限期延期，最早只能在 1.0.0 已发布后重新评估，这不承诺 1.x、下一版本或任何其他版本。

## MySQL 8 与 KingbaseES 实库证据

0.0.2 为两种数据库提供彼此独立、失败关闭的验证入口：

- `mysqlIntegrationCheck` 在原生 MySQL 8.x 的 8.0.17 及以上版本执行全新 Flyway 迁移链和 JDBC repository 套件；本发布线使用的实证版本是 MySQL 8.0.46；
- `kingbaseIntegrationCheck` 在官方 KingbaseES V008R006C009B0014、PostgreSQL 兼容模式上执行同等范围的迁移和 repository 套件；
- 本地开发只在改动触及相应迁移、方言或 persistence 边界时运行对应任务；CNB 对普通变更也通过路径规则按需调度，夜间全量验收和发布事件则运行两者。

缺少显式实库环境时，专属任务必须失败而不是跳过。PostgreSQL、H2、Mock 或另一种数据库的绿测不能替代目标数据库证据。MySQL 支持边界仅为原生 MySQL 8.x 中的 8.0.17+，不包含 MariaDB 或 MySQL 9，也不等于每个 8.x 小版本、排序规则或部署拓扑都已有实证；KingbaseES 证据同样只覆盖明确命名的测试版本与范围。

MySQL 迁移历史的兼容边界不能泛称为“重写了全部旧迁移”。MySQL `V001` 与既有 pre-0.0.2 工作树资源逐字节一致，为已经试用 0.0.2-SNAPSHOT 的团队保持 Flyway checksum 不变；0.0.1 正式标签尚未包含 MySQL 迁移。MySQL 专属修复从 V016 开始，修正了旧脚本中会让真实 MySQL 8 无法跑完整条迁移链的语法和重复列定义；所以 0.0.2 才是首个完成真实 MySQL 迁移与 repository 验证闭环的版本线。

若既有数据库报告 checksum 不匹配，或保存了旧 MySQL 尝试的部分历史，禁止无条件执行 `flyway repair`。应先停止写入、备份数据库，逐项对比精确迁移资源与 `fileweft_schema_history`，再由 DBA 评审处置方案；不能用 repair 把无法解释的历史强行变绿。

## 迁移库存、Flyway 宿主、V027 与 V028

`v0.0.2` 对应 PostgreSQL、MySQL 与 KingbaseES 各自完整的 28 个迁移，即 V001–V028。受保护标签成功发布后，三套资源都成为不可改写的发布迁移。作为历史边界，`v0.0.1` 只包含 PostgreSQL V001–V025；MySQL、KingbaseES 与 V026–V028 首次进入 `v0.0.2` 发布合同。V026 仍是下文单独说明的工作流决策证据迁移；V027 为 `fw_outbox_event` 和 `fw_task` 创建稳定的 `(created_time, id)` Worker 领取顺序索引；V028 把全部 18 张 FileWeft 自有 MySQL 业务表转成 NO PAD 的 `utf8mb4_0900_bin`，PostgreSQL 与 KingbaseES 以空操作脚本保持版本对齐。

`FlywayMigrationRunner` 已分别验证 Spring Boot 2 管理的 Flyway 8.5.13、FileWeft 自身依赖的 Flyway 9.22.3，以及 Spring Boot 3 管理的 Flyway 11.7.2。Boot 3 下的 `flyway-core`、`flyway-mysql`、`flyway-database-postgresql` 必须统一为 11.7.2，禁止混用版本。

V027 使用普通建索引语句，V028 则可能重建每张 MySQL 表与文本索引，两者都必须按维护窗口迁移。停止 Worker 和应用写流量，禁止新旧节点重叠，并为原表、重建副本、索引、临时工作数据、redo/binlog 与复制积压预留磁盘；持续监控锁等待、复制与磁盘。V028 后 tenant、用户与所有其他 opaque ID 按 Unicode 标量/文本值精确比较，大小写、重音和尾空格都不折叠；这不承诺任意原始字节身份。应用回滚保留 V027 索引与 NO PAD `utf8mb4_0900_bin`，绝不能退回 `utf8mb4_bin`、`*_ci` 或其他 PAD SPACE/折叠排序规则。前进重试与回滚步骤见[迁移与发布](operations/migrations-release)。

## V026 工作流决策证据迁移

V026 改变了工作流决策的存储与投影方式。必须严格按顺序迁移；跳过任何步骤都可能让数据库处于旧二进制读不懂、新二进制信不过的状态。

1. 运行 `docs/sql/postgresql-v026-workflow-decision-evidence-preflight.sql`。
2. 不截断、不填充、不猜测地修复不安全的宿主用户 ID 映射。
3. 关闭审批命令，停止全部旧 API 节点，等待在途决策结束。
4. 重跑预检脚本，应用 Flyway 迁移，然后核验列和约束。
5. 仅启动理解 V026 的新节点。

回滚时也要保留 V026 列、约束和证据。不能缩窄身份列，也不能让旧二进制重新开放审批写入。

> [!CAUTION] 遗留证据保持 UNKNOWN
> V026 不从受理人、当前目录条目或可选审计行推断操作者。已完成的遗留任务保持 `UNKNOWN`。

V026 还会更新由 V012 创建的 Agent 兼容表。即使 0.0.2 默认不暴露 Agent，也不能删除、跳过或改写这部分迁移；它们只用于保持已有数据库可升级，并不重新启用 Agent 产品能力。

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

## 发布证据与排除范围

实现范围已经收口。稳定消费仍依赖本文之外的证据：精确提交的干净构建、全部匹配 CNB lane、受保护标签发布，以及远端匿名冷缓存消费者验收。不能从源码树本身推断这些步骤已经成功。

> [!NOTE] 正式目录 HTTP 已移出 0.0.2
> 宿主拥有的 `DocumentCatalogProvider` SPI 和目录感知授权 guard 继续可用，但独立的正式目录树 HTTP 资源已明确移出 0.0.2。它没有承诺目标版本，也不是隐藏的发布阻断项。

> [!NOTE] 厂商连接器边界
> 0.0.2 不宣称提供 OSS、Dify、ESE 或 AppBuilder 官方厂商适配器。宿主可以实现通用 `StorageAdapter` / `FileConnector` SPI；只有未来经过可重复真实厂商服务验收的适配器，才能作为官方支持能力发布。

## 如何安全接入 0.0.2

远端发布完成验证后，先在与生产数据和流量隔离的环境接入 0.0.2：

1. 使用独立数据库 schema 和对象存储 bucket。
2. 先启用 `fileweft.persistence.migration-mode=validate` 确认 schema 预期。
3. 在跑真实工作流前，先调用 Doctor 端点并跑集成测试。
4. 提升到生产环境前，确认精确 `v0.0.2` 标签事件和匿名冷缓存解析结果。

## 常见问题

**如何确认 0.0.2 可以消费？**
必须同时具备 [路线图](project/roadmap) 中的全部验收证据、受保护标签发布和从空缓存匿名解析精确远端制品的结果。

**可以跳过 V026 预检吗？**
不能。预检用于发现迁移后会违反收紧身份契约的不安全用户 ID 映射。

**0.0.2 会破坏我的 SPI 实现吗？**
公共 SPI 契约预期保持兼容，但你必须在发布制品上重新编译并运行契约测试。

**兼容制品里还有 Agent 类型，是否说明 0.0.2 支持 Agent？**
不是。这些类型和迁移只为兼容保留，默认产品面不会暴露 Agent。不要基于它们开发新的 0.0.2 集成。

## 下一步

- 阅读 [路线图](project/roadmap)，了解 0.0.2 完整验收标准。
- 查看 [HTTP API v1 参考](reference/http-api)，确认哪些端点已是正式接口。
- 遵循 [迁移与发布](operations/migrations-release) 的安全升级实践。
