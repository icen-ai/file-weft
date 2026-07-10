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

启动开发 PostgreSQL：

```powershell
docker compose -f .docker\docker-compose.dev.yaml up -d postgres
```

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
