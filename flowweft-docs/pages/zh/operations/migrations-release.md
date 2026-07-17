---
route: "operations/migrations-release"
group: "operations"
order: 3
locale: "zh"
nav: "迁移与发布"
title: "审慎迁移与发布"
lead: "FlowWeft 拥有独立的 Flyway 资源路径和历史表。安全发布需要校验 schema 兼容性、真实基础设施链路、SBOM 完整性和可复现依赖状态。"
format: "markdown"
---

## 这页解决什么问题

升级 FlowWeft 不只是替换 jar。本页说明从 schema 迁移到生产发布的安全路径，包括如何处理已撤回的 `com.fileweft` 试推制品。

## 迁移命名空间

FlowWeft 迁移位于独立的命名空间：

| 资源 | 位置 | 重要性 |
|------|------|--------|
| 迁移资源 | `classpath:ai/icen/fw/db/migration` | 框架自有、版本化脚本 |
| 历史表 | `fileweft_schema_history` | 与宿主 schema 历史分离 |

> [!WARNING]
> 不要把 FlowWeft 资源追加到宿主 Flyway 的 `locations`，也不要把 `fileweft_schema_history` 合并进 `flyway_schema_history`。这会破坏所有权与回滚能力。

## 迁移模式

按运行角色选择模式：

| 模式 | 含义 | 使用场景 |
|------|------|----------|
| `migrate` | 应用待执行迁移 | 仅一次性迁移 Job |
| `validate` | schema 不匹配则失败 | API 和 Worker 角色 |
| `disabled` | 完全跳过 Flyway | 特殊维护窗口 |

```yaml
fileweft:
  persistence:
    migration-mode: validate
    schema: fileweft
    create-schema: false
```

## 当前迁移与 Flyway 矩阵

当前 0.0.3 版本线为 PostgreSQL、MySQL 与 KingbaseES 各自提供完整的 29 个迁移，即 V001–V029。V029 只追加可空的工作流提交者证据，不改写任何既有资源或 checksum；`v0.0.2` 的三套 V001–V028 继续作为不可改写的发布资源。作为历史边界，`v0.0.1` 只包含 PostgreSQL V001–V025；MySQL、KingbaseES 与 V026–V028 首次进入 `v0.0.2` 发布合同。

`FlywayMigrationRunner` 已验证三套由宿主最终解析的运行时：

| 运行时所有者 | Flyway 版本 | 必须一致的模块 |
|---|---:|---|
| Spring Boot 2 依赖管理 | 8.5.13 | `flyway-core`、`flyway-mysql` |
| FlowWeft persistence 自身 | 9.22.3 | FlowWeft 锁定的 core/database 组合 |
| Spring Boot 3 依赖管理 | 11.7.2 | `flyway-core`、`flyway-mysql`、`flyway-database-postgresql`，三者都必须为 11.7.2 |

Kingbase Starter 的兼容 customizer 只包装 Spring Boot 已经选择给 Flyway 的 DataSource，应用主 DataSource 仍是真实 Kingbase DataSource。只有宿主提供并实库验证了等价的 Kingbase/Flyway 集成时，才可设置 `fileweft.persistence.kingbase-flyway-compatibility-enabled=false`；它不是通用绕过开关。Spring Boot 2 宿主不要在 `spring.flyway.locations` 中使用 `{vendor}`：该占位符会在 FlowWeft customizer 运行前按原始 `jdbc:kingbase8:` URL 解析，无法可靠映射为 PostgreSQL，请改用明确的宿主迁移路径。

## 0.0.2 MySQL 迁移边界

MySQL 支持从 8.0.17 开始且仅限原生 MySQL 8.x。本轮发布证据运行于 MySQL 8.0.46；该结果不支持 MariaDB 或 MySQL 9，也不代表每个 8.x 小版本、排序规则和部署拓扑都已实证。

MySQL 的 schema 即 database。必须先由 DBA 创建 database，并让 JDBC URL 明确选择它；使用 `create-schema: false`。没有当前 database 的连接在建库后也不会自动切换，新连接的 `SELECT DATABASE()` 仍为 `null`，所以 FlowWeft 在这种情况下会在任何 DDL 前拒绝 `create-schema: true`，避免留下已变更但启动失败的半完成状态。

不能把本轮 MySQL 工作描述为重写全部历史迁移。MySQL `V001` 与既有 pre-0.0.2 工作树资源逐字节一致，为已经试用 0.0.2-SNAPSHOT 的团队保持 Flyway checksum 不变；0.0.1 正式标签尚未包含 MySQL 迁移。MySQL 专属修复从 V016 开始：它们修正旧链路中会让真实 MySQL 8 无法完整执行的语法和重复列定义。因此，0.0.2 是首个具备完整真实 MySQL 迁移与 JDBC repository 验证闭环的版本线。

若既有数据库出现 checksum 不匹配，或保留了早期 MySQL 尝试的部分历史，应先停写、备份，并将精确资源与 `fileweft_schema_history` 逐项比对。禁止无条件执行 `flyway repair`，也不能用 repair 给无法解释的 checksum 背书；任何处置都必须由 DBA 明确评审。

## V017、V027 与 V028 运维

V017 在数据库层保证同一租户/文档最多一个本地 `PENDING` 工作流。PostgreSQL 与 KingbaseES 使用部分唯一索引；MySQL 使用仅在 PENDING 时非空的 stored generated tenant/document 列加唯一索引，以不拼接 opaque ID 的方式提供同一约束。既有重复会使迁移失败，框架绝不会自动删除或裁决。

V027 为 `fw_outbox_event` 与 `fw_task` 增加非唯一 `(created_time, id)` 领取顺序索引。V028 将全部 18 张 FlowWeft 自有 MySQL 业务表转成 NO PAD 的 `utf8mb4_0900_bin`，让 tenant ID、用户 ID、幂等身份和所有其他 opaque ID 都按 Unicode 标量/文本值精确比较，大小写、重音和尾空格均不折叠；这不承诺任意原始字节身份。显示文本也继承同一排序语义；这是防止未来文本 key 静默继承会折叠标识值的默认规则所作的安全优先取舍。PostgreSQL 与 KingbaseES 的 V028 只保持迁移版本对齐。

两项变更都需要维护窗口。V027 使用普通建索引，不承诺 concurrent/online DDL；MySQL V028 可能重建每张表与文本索引。停止 Worker 与所有会写入相关表的 API/调度入口，禁止新旧节点重叠，完成并验证备份；同时为原表、重建副本、索引、临时排序/重建数据、redo/binlog 和复制积压预留磁盘，并持续监控 metadata/行锁、复制延迟与磁盘余量。

只能前进重试。V027 在 Flyway 记录成功前失败时，先由 DBA 检查同名索引并清理任何无效或定义不兼容的残留，再重跑迁移；不得伪造 history 或无条件 repair。应用回滚必须保留 V027 索引与 MySQL NO PAD `utf8mb4_0900_bin`，绝不能把 V028 表转回 PAD SPACE 的 `utf8mb4_bin`、`*_ci` 或其他会折叠大小写、重音或尾空格的排序规则；否则不同 tenant/opaque ID 可能重新折叠，造成跨租户命中、错误唯一冲突或幂等身份变化。

## 发布门禁

任何制品进入生产前，先对精确构建运行以下检查：

```powershell
# Windows
.\gradlew.bat check --no-daemon
.\gradlew.bat compatibilityCheck --no-daemon
.\gradlew.bat verifySbom --no-daemon
```

```bash
# Linux / macOS
./gradlew check --no-daemon
./gradlew compatibilityCheck --no-daemon
./gradlew verifySbom --no-daemon
```

正式流水线还会启用：

- PostgreSQL 集成测试
- MySQL 8 集成测试
- KingbaseES V8 集成测试
- RustFS / 对象存储测试
- Dev API 验收测试
- 浏览器验收测试

全部在同一套健康的开发栈上执行。

## 早期试推数据库

坐标 `com.fileweft:*:0.0.1` 已撤回。如果数据库曾运行过这些试推制品：

1. 停止应用。
2. 备份数据库。
3. 由 DBA 检查 schema 所有权与历史行。
4. 不能通过 baseline、repair、复制或删除 history 行绕过分析。

> [!WARNING]
> FlowWeft 不会自动采用试推数据库。把迁移所有权当作数据所有权决策来处理。

## 可复现构建

项目使用 Gradle 依赖锁定和经过验证的 SBOM。每次发布保留以下产物：

- 包含签名制品的 `build/repository/`
- `build/reports/sbom/` 或等效 SBOM 输出
- 每个发布模块的 `gradle.lockfile`

## 回滚策略

- Schema 迁移只向前。先在生产数据副本上测试；应用回滚保留 V027 索引和 V028 NO PAD 精确文本比较语义。
- 如果 schema 未变，应用 jar 可回滚到上一个 `ai.icen:*:0.0.1` patch。
- 如果发布后连接器开始失败，使用交付重试与撤回重试端点，而不是重启 Worker。

```bash
# 重试失败交付
curl -sf -X POST \
  http://api:8080/fileweft/v1/documents/doc_123/deliveries/dlv_456/retry \
  -H "Authorization: Bearer ${HOST_TOKEN}" \
  -H "Idempotency-Key: $(uuidgen)"
```

## 常见问题

**可以让 API 节点在启动时自动迁移，而不用迁移 Job 吗？**
不可以。长期运行的 API 节点必须以 `validate` 模式运行。schema 变更必须通过受控的一次性迁移 Job 执行。

**什么时候可以安全消费 0.0.3？**
稳定版 `ai.icen:*:0.0.3` 已在提交 `dbf2a50fbca41e2ac5b5cf18bb44f9287c153637` 发布；CNB 构建 `cnb-cl8-1jtgih45j` 完成 12/12 流水线，并匿名回读全部 19 个坐标。制品可用不放宽 V029 的停写、停止旧节点和迁移核验流程。

## 下一步

- 按角色部署：[生产部署](deployment)
- 发布后监控：[Doctor 与可观测性](doctor-observability)
- 阅读当前稳定版：[0.0.3 发布说明](../project/release-0-0-3)，并保留 [0.0.2 正式版](../project/release-0-0-2-development) 与 [0.0.1 正式版](../project/release-0-0-1) 作为历史升级边界。
