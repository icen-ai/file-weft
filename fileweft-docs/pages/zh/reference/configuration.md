---
route: "reference/configuration"
group: "reference"
order: 3
locale: "zh"
nav: "配置"
title: "配置参考"
lead: "FileWeft 的生产默认值是保守的：校验 schema、不隐式选择租户、不使用本地存储、不自动迁移。每个 fallback 和运行角色都必须显式开启。"
format: "markdown"
---

## 如何阅读本页

配置按子系统分组。下文使用 YAML 形式，同样的键也可以写成 `.properties` 中的 `fileweft.<group>.<key>`。

> [!TIP]
> 先从本页底部的最小生产 YAML 开始，再根据环境添加适配器和连接器 profile。

## 持久化

控制 Flyway 迁移和数据库 schema。

```yaml
fileweft:
  persistence:
    migration-mode: validate # migrate | validate | disabled
    schema: fileweft
    create-schema: false
    kingbase-flyway-compatibility-enabled: true
```

| 值 | 使用场景 |
|----|---------|
| `validate` | 生产与 CI。FileWeft 校验 schema 是否与预期版本一致，但不会修改它。 |
| `migrate` | 全新安装或本地开发，由进程负责创建 schema。 |
| `disabled` | 外部 schema 管理或蓝绿部署，迁移在进程外执行。 |

Runner 已验证 Spring Boot 2 管理的 Flyway 8.5.13、FileWeft 自身的 Flyway 9.22.3，以及 Spring Boot 3 管理的 Flyway 11.7.2。Boot 3 下 `flyway-core`、`flyway-mysql`、`flyway-database-postgresql` 必须全部解析为 11.7.2。

`fileweft.persistence.kingbase-flyway-compatibility-enabled` 默认 `true`（上方 YAML 使用嵌套形式），只适配 Spring Boot 已选择给 Flyway 的 DataSource；应用主 DataSource 仍是真实 Kingbase DataSource。只有宿主提供并验证了等价 Kingbase/Flyway 集成时才可关闭。Spring Boot 2 的 Kingbase 宿主必须为 `spring.flyway.locations` 配置明确路径，不要使用会在 FileWeft customizer 前按原始 JDBC URL 解析的 `{vendor}` 占位符。

## KingbaseES 0.0.2 快速接入

FileWeft `0.0.2` 可从 CNB 公共 Maven 仓库匿名解析。Kingbase JDBC 驱动使用 Maven Central（或企业受控镜像）中的锁定版本；Spring Boot 2 与 3 只能选择其中一条：

> [!IMPORTANT]
> Spring Boot 2 宿主还必须按[安装文档](../getting-started/installation.md)把 Kotlin 对齐到 `2.1.21`；Boot 2 BOM 默认的 `1.6.21` 不是 FileWeft 运行时合同。

```kotlin
repositories {
    maven("https://maven.cnb.cool/china.ai/maven/-/packages/")
    mavenCentral()
}

// Spring Boot 2 宿主
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("ai.icen:fileweft-spring-boot2-starter:0.0.2")
    // implementation("ai.icen:fileweft-web-spring-boot2-starter:0.0.2") // 需要正式 HTTP API 时启用
    runtimeOnly("cn.com.kingbase:kingbase8:8.6.1")
}
```

```kotlin
repositories {
    maven("https://maven.cnb.cool/china.ai/maven/-/packages/")
    mavenCentral()
}

// Spring Boot 3 宿主
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("ai.icen:fileweft-spring-boot3-starter:0.0.2")
    // implementation("ai.icen:fileweft-web-spring-boot3-starter:0.0.2") // 需要正式 HTTP API 时启用
    runtimeOnly("cn.com.kingbase:kingbase8:8.6.1")
}
```

DBA 必须在任何进程启动前预建 `fileweft` schema。Kingbase URL 的 `currentSchema` 属性负责设置连接 search path；`fileweft.persistence.schema` 是严格安全断言，必须与 `SELECT current_schema()` 的结果完全相同。

Spring Boot 2 最小配置：

```yaml
spring:
  datasource:
    url: jdbc:kingbase8://${KINGBASE_HOST}:${KINGBASE_PORT:54321}/${KINGBASE_DATABASE}?currentSchema=fileweft
    username: ${KINGBASE_USERNAME}
    password: ${KINGBASE_PASSWORD}
    driver-class-name: com.kingbase8.Driver
  flyway:
    enabled: false # 仅当宿主没有自己的迁移时使用

fileweft:
  persistence:
    migration-mode: ${FILEWEFT_MIGRATION_MODE:validate}
    schema: fileweft
    create-schema: false
    kingbase-flyway-compatibility-enabled: true
```

Spring Boot 3 最小配置（DataSource 合同有意保持一致）：

```yaml
spring:
  datasource:
    url: jdbc:kingbase8://${KINGBASE_HOST}:${KINGBASE_PORT:54321}/${KINGBASE_DATABASE}?currentSchema=fileweft
    username: ${KINGBASE_USERNAME}
    password: ${KINGBASE_PASSWORD}
    driver-class-name: com.kingbase8.Driver
  flyway:
    enabled: false # 仅当宿主没有自己的迁移时使用

fileweft:
  persistence:
    migration-mode: ${FILEWEFT_MIGRATION_MODE:validate}
    schema: fileweft
    create-schema: false
    kingbase-flyway-compatibility-enabled: true
```

部署时先让一次性进程以受限 DDL 账号和 `FILEWEFT_MIGRATION_MODE=migrate` 启动；迁移成功后由宿主明确退出该进程，再让全部 API/Worker 使用独立运行账号和 `FILEWEFT_MIGRATION_MODE=validate` 启动。不要让滚动应用节点各自执行迁移。若宿主有自己的 Flyway 迁移，应开启宿主 Flyway 并配置只属于宿主的明确路径；不得追加 FileWeft 迁移路径，Boot 2 也不得使用 `{vendor}`。

开放流量前逐项确认：

- JDBC 驱动是 `cn.com.kingbase:kingbase8:8.6.1`，驱动类是 `com.kingbase8.Driver`；
- schema 已预建，迁移账号与运行账号执行 `SELECT current_schema()` 都精确返回 `fileweft`；
- `fileweft_schema_history` 已记录成功的 V001–V028 链，随后运行节点能以 `validate` 启动；
- 迁移与运行权限分离，运行账号只获得业务 DML 和校验读取所需权限；
- `KINGBASE_USERNAME`、`KINGBASE_PASSWORD` 来自部署密钥系统。禁止把数据库密码写入 YAML、Gradle 文件或版本库。

## Worker

Worker 处理 Outbox 事件、定时任务和上传清理。

```yaml
fileweft:
  worker:
    enabled: true
    fixed-delay-millis: 1000
    outbox-batch-size: 50
    task-batch-size: 50
    process-outbox: true
    process-tasks: true
    process-upload-cleanup: true
```

> [!NOTE]
> `fixed-delay-millis` 是两次轮询之间的静默间隔，不是单个 handler 的截止期限。

## Outbox

Outbox 租约防止多个 worker 同时处理同一事件。

```yaml
fileweft:
  outbox:
    lease-duration-millis: 300000
    legacy-running-grace-millis: 300000
    backlog-metrics-enabled: true
    backlog-metrics-interval-millis: 30000
    backlog-metrics-query-timeout-seconds: 5
```

## Task

后台任务使用独立租约，崩溃的 worker 不会永久持有事件。

```yaml
fileweft:
  task:
    lease-duration-millis: 60000
    legacy-running-grace-millis: 300000
```

## 同步与交付 Profile

Profile 把下游目标分组。必达目标成功前文档不会进入 `PUBLISHED`；可选目标失败不会阻塞发布。

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
        display-name: 受监管发布
        targets:
          - id: compliance
            display-name: 合规归档
            connector-id: complianceConnector
            required: true
            owner-ref: compliance-ops
          - id: search
            display-name: 检索索引
            connector-id: searchConnector
            required: false
            owner-ref: search-ops
```

| 字段 | 含义 |
|------|------|
| `connector-name` | `profiles` 为空时，合成的 `default` profile 中必达目标所使用的 connector ID |
| `default-profile-id` | 发布选择的 profile；显式非 sentinel 值必须与已配置 profile 匹配 |
| `connector-id` | `FileConnector` 实现的 Spring Bean 名称 |
| `required` | 为 `true` 时失败会使文档停留在 `SYNC_ERROR`，阻塞 `PUBLISHED` |
| `owner-ref` | 自由格式的运维责任人，显示在同步状态和 Doctor 输出中 |

Profile 选择遵循失败安全规则：

1. 未配置自定义 `profiles` 时，Starter 会合成 `default` profile，其中只有一个**必达**目标。该目标的 connector ID 来自 `fileweft.sync.connector-name`（未配置时也为 `default`），因此发布依赖同名 `FileConnector`，并不是“只在本地成功”。
2. 配置自定义 profiles 后，非 sentinel 的 `default-profile-id` 必须与某个 profile ID 精确匹配，否则启动失败。
3. 为保持兼容，当 `default-profile-id` 仍是默认 sentinel `default`，且自定义 profiles 中没有名为 `default` 的项时，FileWeft 仍选择第一项。新配置应写出精确匹配的 ID，不要依赖顺序。
4. 每个目标的 `connector-id` 必须与 Spring `FileConnector` Bean 名称或插件 `connectors()` 映射键精确一致。

## 上传

断点续传会话是持久的，但会在配置的超时后过期。

```yaml
fileweft:
  upload:
    resumable-session-ttl-millis: 86400000
    resumable-cleanup-batch-size: 100
```

## 开发 fallback

这些属性适用于固定单租户或单节点开发，不是生产多租户方案。

```properties
fileweft.default-tenant-enabled=true
fileweft.default-tenant-id=tenant-a
fileweft.storage.local-enabled=true
fileweft.storage.local-root=/var/lib/fileweft
```

> [!WARNING]
> `default-tenant-enabled` 和 `local-enabled` 是经过评审的开发快捷方式，Doctor 会将其报告为警告。不要在多节点生产环境中使用。

## 最小生产 YAML

```yaml
fileweft:
  persistence:
    migration-mode: validate
    schema: fileweft
    create-schema: false
    kingbase-flyway-compatibility-enabled: true
  worker:
    enabled: true
    process-outbox: true
    process-tasks: true
    process-upload-cleanup: true
  outbox:
    lease-duration-millis: 300000
    backlog-metrics-enabled: true
  task:
    lease-duration-millis: 60000
  sync:
    connector-timeout-millis: 30000
    source-access-url-ttl-millis: 900000
    default-profile-id: regulated
    profiles:
      - id: regulated
        display-name: 受监管发布
        targets:
          - id: compliance
            display-name: 合规归档
            connector-id: complianceConnector
            required: true
            owner-ref: compliance-ops
  upload:
    resumable-session-ttl-millis: 86400000
```

## 常见问题

**生产环境应该用 `migrate` 吗？**
通常不用。部署时执行迁移，运行时开启 `validate`，让应用在 schema 不一致时快速失败。

**可以关闭 worker 吗？**
可以，但 Outbox 事件和定时任务不会推进。只有外部 worker 进程共享同一数据库时才关闭。

**没有配置同步 profile 会怎样？**
Starter 会合成 `default` profile，并创建一个 connector ID 来自 `fileweft.sync.connector-name` 的必达目标。必须注册同名 `FileConnector`（或配置自定义 profiles）；否则必达交付无法完成，发布不会退化为只在本地成功。

**`default-profile-id` 与已配置 profile 不匹配会怎样？**
任何显式的非 sentinel 值都会让应用启动失败。唯一兼容例外是未改动的 sentinel `default`：若自定义 profiles 中没有同名项，FileWeft 会选择第一项。新配置应使用精确匹配的 ID。

## 下一步

- [实现存储适配器](../guides/storage-adapter.md)
- [构建连接器](../extensions/connectors.md)
- [阅读 SPI 总览](./spi.md)
