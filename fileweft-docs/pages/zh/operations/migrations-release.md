---
route: "operations/migrations-release"
group: "operations"
order: 3
locale: "zh"
nav: "迁移与发布"
title: "审慎迁移与发布"
lead: "FileWeft 拥有独立的 Flyway 资源路径和历史表。安全发布需要校验 schema 兼容性、真实基础设施链路、SBOM 完整性和可复现依赖状态。"
format: "markdown"
---

## 这页解决什么问题

升级 FileWeft 不只是替换 jar。本页说明从 schema 迁移到生产发布的安全路径，包括如何处理已撤回的 `com.fileweft` 试推制品。

## 迁移命名空间

FileWeft 迁移位于独立的命名空间：

| 资产 | 位置 | 重要性 |
|------|------|--------|
| 迁移资源 | `classpath:ai/icen/fw/db/migration` | 框架自有、版本化脚本 |
| 历史表 | `fileweft_schema_history` | 与宿主 schema 历史分离 |

> [!WARNING]
> 不要把 FileWeft 资源追加到宿主 Flyway 的 `locations`，也不要把 `fileweft_schema_history` 合并进 `flyway_schema_history`。这会破坏所有权与回滚能力。

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
- RustFS / 对象存储测试
- Dev API 验收测试
- 浏览器验收测试

全部在同一套健康开发编排上执行。

## 早期试推数据库

坐标 `com.fileweft:*:0.0.1` 已撤回。如果数据库曾运行过这些试推制品：

1. 停止应用。
2. 备份数据库。
3. 由 DBA 检查 schema 所有权与历史行。
4. 不能通过 baseline、repair、复制或删除 history 行绕过分析。

> [!WARNING]
> FileWeft 不会自动收养试推数据库。把迁移所有权当作数据所有权决策来处理。

## 可复现构建

项目使用 Gradle 依赖锁定和经过验证的 SBOM。每次发布保留以下产物：

- 包含签名制品的 `build/repository/`
- `build/reports/sbom/` 或等效 SBOM 输出
- 每个发布模块的 `gradle.lockfile`

## 回滚策略

- Schema 迁移只向前。先在生产数据副本上测试。
- 如果 schema 未变，应用 jar 可回滚到上一个 `ai.icen:*:0.0.1` patch。
- 如果发布后连接器开始失败，使用交付重试与撤回重试端点，而不是重启 Worker。

```bash
# 重试失败交付
curl -sf -X POST \
  http://api:8080/fileweft/v1/documents/doc_123/deliveries/dlv_456/retry \
  -H "Authorization: Bearer ${HOST_TOKEN}" \
  -H "X-Idempotency-Key: $(uuidgen)"
```

## 常见问题

**可以让 API 节点在启动时自动迁移，而不用迁移 Job 吗？**
不可以。长期运行的 API 节点必须以 `validate` 模式运行。schema 变更必须通过受控的一次性迁移 Job 执行。

**稳定版是哪个？**
`ai.icen:*:0.0.1`。`0.0.2-SNAPSHOT` 尚未发布，不能当作稳定版对外承诺。

## 下一步

- 按角色部署：[生产部署](deployment)
- 发布后监控：[Doctor 与可观测性](doctor-observability)
- 阅读发布说明：[0.0.1 正式版](../project/release-0-0-1)
