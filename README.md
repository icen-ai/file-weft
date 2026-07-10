# FileWeft

FileWeft 是面向企业的 Kotlin/JVM 文件智能基础设施。

当前实现已完成任务书定义的基础链路：`core → spi → domain → application → persistence → starter → adapter → doctor → agent`，并提供本地存储、诊断、确认式 Agent 任务与可重试 Outbox Worker 基线。

## 构建要求

- 构建运行时：JDK 17+（当前验证环境为 JDK 21）
- 核心及除 Spring Boot 3 Starter 外的模块：产物字节码兼容 Java 8
- Spring Boot 3 Starter：产物字节码兼容 Java 17

## 验证

```powershell
.\gradlew.bat check
```

依赖版本通过 `gradle/libs.versions.toml` 管理，所有配置启用依赖锁定。

## 本地开发

启动仅包含 PostgreSQL 与 RustFS 的基础服务：

```powershell
docker compose -f .docker\docker-compose.dev.yaml up -d postgres
```

## 开发验收台

仓库内的 `fileweft-dev` 是开发专用的可运行验收应用，不会被核心、领域或 SPI 依赖。它通过真实 PostgreSQL、RustFS（S3 兼容）、Outbox Worker 和独立 HTTP 下游平台覆盖上传、版本、审批、发布、同步、审计与 Doctor。

启动完整编排：

```powershell
docker compose -f .docker\docker-compose.dev.yaml up -d --build --wait
```

| 服务 | 地址 | 用途 |
| --- | --- | --- |
| 验收控制台 | http://127.0.0.1:8088 | 登录、文档流转、审批、Doctor、下游镜像 |
| FileWeft 开发 API | http://127.0.0.1:8080 | 验收 API |
| 模拟下游平台 | http://127.0.0.1:8081 | 接收发布同步并验证预签名 S3 下载 |
| RustFS 控制台 | http://127.0.0.1:9001 | S3 开发对象存储 |

预置开发用户：

| 用户名 | 密码 | 角色 |
| --- | --- | --- |
| `admin@alpha` | `dev-admin` | 管理员 |
| `editor@alpha` | `dev-editor` | 编辑者 |
| `reviewer@alpha` | `dev-reviewer` | 审批者 |
| `viewer@alpha` | `dev-viewer` | 只读者 |

开发应用使用独立的 `fileweft_dev` 和 `fileweft_dev_platform` schema；不会读取或覆写 `public` schema 的测试数据。预置账号和密码只适用于本地开发容器，禁止用于任何生产环境。

审计将用户 ID 视为不透明字符串，并同时保存操作发生时的显示名快照。接入方可在 `UserRealmProvider` 中将 Long、Int、UUID 或其他身份系统 ID 转为字符串；FileWeft 不维护用户表，也不会在查询历史审计时反查并改写原有操作者名称。

验收控制台默认英文，可切换完整中文。其“角色验收实验室”内置 TXT、Markdown、CSV、JSON 文件样例：拥有创建权限的用户可将它们上传为真实 RustFS 草稿；审批、Outbox 与只读路线则只展示当前用户经服务端授权的操作控件。

运行完整 Compose 验收回归：

```powershell
$env:FILEWEFT_RUN_DEV_E2E='true'
.\gradlew.bat :fileweft-dev:test
```

该测试会创建唯一编号文档，验证编辑者上传和提交、审批者通过、管理员处理 Outbox、下游平台下载 RustFS 对象并记录同步结果。

Spring Boot Starter 默认使用本地存储；可通过以下配置修改根目录：

```properties
fileweft.storage.local-root=./fileweft-data
```

运行 PostgreSQL 集成测试：

```powershell
$env:FILEWEFT_RUN_POSTGRES_TESTS='true'
.\gradlew.bat :fileweft-persistence:test
```

> 集成测试会重置开发库的 `public` schema，只能连接专用开发/测试数据库，不能指向任何生产数据库。
