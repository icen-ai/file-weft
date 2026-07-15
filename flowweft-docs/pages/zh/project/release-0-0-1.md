---
route: "project/release-0-0-1"
group: "project"
order: 3
locale: "zh"
nav: "0.0.1 发布说明"
title: "0.0.1 正式版"
lead: "本页记录稳定版 0.0.1 实际交付的内容，包括坐标、模块边界、已交付能力和集成前应了解的限制。"
format: "markdown"
---

## 0.0.1 交付了什么

FileWeft `0.0.1` 是首个稳定公开版本。它确立了模块链路、Maven 坐标、独立 Flyway 迁移命名空间，以及面向生产的文档生命周期、审批、交付、Doctor 和 Web 地基。

1. **分层模块链路** —— `core` → `spi` → `domain` → `application` → `persistence` → `starter` → `adapter`。
2. **Spring Boot Starter** —— Boot 2 与 Boot 3 的运行时及 Web Starter，行为镜像一致。
3. **持久化** —— PostgreSQL，Flyway 迁移限定在独立 schema 与历史表。
4. **存储路径** —— 共享持久化 `StorageAdapter` 契约，含本地文件系统与 S3 兼容适配器。
5. **弹性工作** —— 持久 Outbox、带租约的后台任务、并行审批路由与多目标交付。
6. **正式 HTTP 接口** —— `/fileweft/v1` 的上传、文档、工作流、交付、审计日志与 Doctor 端点。
7. **安全边界** —— 租户感知 ACL、授权决策、审计与 Trace 透传。

## Maven 坐标

使用 `ai.icen` group。旧版 `com.fileweft:*` 试推制品已撤回，不会自动采用。

```kotlin
// build.gradle.kts
implementation("ai.icen:fileweft-spring-boot3-starter:0.0.1")
```

```xml
<!-- pom.xml -->
<dependency>
  <groupId>ai.icen</groupId>
  <artifactId>fileweft-spring-boot3-starter</artifactId>
  <version>0.0.1</version>
</dependency>
```

JVM 包根为 `ai.icen.fw`。公共 API 保持 Java 友好：SPI 表面不使用 `suspend`、`Flow`、`value class`、`sealed interface` 或 `data object`。

## 模块地图

| 模块 | 0.0.1 中的职责 |
| --- | --- |
| `fileweft-core` | 标识、结果、错误、事件、上下文 |
| `fileweft-spi` | 租户、身份、授权、存储、连接器、工作流、任务、诊断契约 |
| `fileweft-domain` | Document、FileAsset、生命周期、版本、审计规则 |
| `fileweft-application` | 上传、发布、下线、审批、同步编排 |
| `fileweft-persistence` | PostgreSQL 映射、仓储、Flyway 迁移 |
| `fileweft-runtime` | 运行时组装与 Worker 机制 |
| `fileweft-web-api` / `fileweft-web-runtime` | 正式 HTTP v1 接口与 Boot 适配器 |
| `fileweft-spring-boot2-starter` / `fileweft-spring-boot3-starter` | 各 Boot 代际的自动装配 |
| `fileweft-adapter-*` | 外部实现：S3、MinIO、Micrometer、OpenTelemetry |

## 已包含能力

### 文档生命周期

```text
DRAFT → PENDING_REVIEW → PUBLISHED → OFFLINE → ARCHIVED
```

使用 `restore` 从 `OFFLINE` 回到 `DRAFT`。

### 多目标交付

文档可交付到多个下游系统。必达目标成功前文档不会进入 `PUBLISHED`；可选目标失败不会阻塞发布。已成功目标不会被回滚。

### 断点续传

大文件通过调用方稳定的幂等键和编号分片上传。上传会话端点包括：

- `POST /fileweft/v1/uploads`
- `GET /fileweft/v1/uploads/{uploadId}`
- `PUT /fileweft/v1/uploads/{uploadId}/parts/{partNumber}`
- `POST /fileweft/v1/uploads/{uploadId}/complete`
- `DELETE /fileweft/v1/uploads/{uploadId}`

### Doctor

每个主要组件都通过 `DoctorChecker` SPI 暴露诊断。可在 `GET /fileweft/v1/doctor` 查询组件健康，或在 `GET /fileweft/v1/documents/{id}/doctor` 查询单文档健康。

## 已知限制

- 在这份历史发布说明对应的时间点，`0.0.1` 是稳定线，`0.0.2-SNAPSHOT` 尚未发布。当前消费规则请查看 0.0.3 发布说明。
- 本地文件存储 fallback 与默认租户 fallback 仅用于开发，不是生产多租户方案。
- OSS、Dify、ESE、AppBuilder 官方厂商适配器在路线图中；0.0.1 中由 S3 兼容适配器覆盖类 S3 存储。
- 仅开发使用的 `/api/**` 端点不属于正式公共 HTTP 协议。

> [!WARNING] 不要在生产环境使用开发 fallback
> `fileweft.default-tenant-enabled=true` 和 `fileweft.storage.local-enabled=true` 在单节点笔记本上很方便，但不提供租户隔离，也不是持久共享存储。

## 许可证

FileWeft 使用 Apache License 2.0 开源，版权主体为 icen.ai。权威条款以仓库 `LICENSE` 与 `NOTICE` 文件为准。

## 常见问题

**还能使用 `com.fileweft:*` 制品吗？**
不能。这些试推制品已撤回。现有 `ai.icen:*:0.0.1` 安装继续保留历史升级边界；新接入只能在 0.0.3 远端发布证据完整后使用 `ai.icen:*:0.0.3`。

**0.0.1 可用于生产吗？**
它是已发布的稳定线，但你必须提供生产级身份、租户、授权、存储和数据库基础设施。FileWeft 不会把开发 fallback 变成生产多租户能力。

**0.0.1 支持 MySQL 吗？**
暂不支持。0.0.1 支持 PostgreSQL，MySQL 8 计划在后续版本中支持。

## 下一步

- [安装当前已验证版本](getting-started/installation)，或继续使用本页核对 0.0.1 历史合同。
- [装配可信宿主](getting-started/first-integration)，接入真实租户与存储 Provider。
- 阅读 [HTTP API v1 参考](reference/http-api)。
