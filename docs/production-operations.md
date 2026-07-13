# 生产部署与恢复

> **Agent 兼容边界：** `0.0.2` 不提供 Agent 产品能力。默认运行时、
> Doctor/插件清单、正式 HTTP 与 Dev 均不注册或暴露 Agent。现有
> `fileweft-agent`、Agent SPI/公共 ABI 及 V012/V026 数据库形状仅为兼容
> 保留。Agent 最早只能在 `1.0.0` 发布后重新评估，且没有承诺版本。本文
> 后续 Agent 运行说明只适用于显式旧版兼容模式，不是默认部署建议。

仅在受控维护既有 `0.0.1` 接入时，宿主才可显式设置
`fileweft.compatibility.legacy-agent-autoconfiguration-enabled=true` 恢复旧仓储、
任务、确认与 Doctor 自动装配。该开关默认 `false`，不得用于新部署或作为
`0.0.2` 产品支持合同。

## FileWeft 迁移命名空间与旧库升级

FileWeft 迁移资源只发布在 `classpath:ai/icen/fw/db/migration`，并使用专属 `fileweft_schema_history`。它不会向宿主常用的 `classpath:db/migration` 放置资源，也不能与宿主 `flyway_schema_history` 合并；这样宿主和 FileWeft 都可以拥有自己的 `V001`，而不会共享版本、checksum 或 repair 操作。

迁移模式默认是 `disabled`。生产部署必须明确迁移所有权：

```yaml
spring:
  datasource:
    url: jdbc:postgresql://db.example.internal:5432/app?currentSchema=fileweft

fileweft:
  persistence:
    migration-mode: validate # migrate | validate | disabled
    schema: fileweft
    create-schema: false
```

- `migrate` 会使用专属 location/history 校验并应用 pending migrations。`create-schema=true` 只对该模式有效，适合受控初始化；生产环境通常应由 DBA 预建 schema 并保持为 `false`。
- `validate` 是只读运行模式：目标 schema 和专属 history 必须已经存在，所有已执行迁移必须通过校验，且不能有 pending migration。缺失、旧 FileWeft 共享 history 记录或不一致都会让节点启动失败，不会自动创建、baseline、repair 或迁移。
- `disabled` 表示 FileWeft 完全不管理或校验数据库。只有外部发布系统已经以相同专属 location/history 完成迁移，或宿主有等价且经过评审的数据库发布流程时才应使用。

`fileweft.persistence.schema` 是对 DataSource 的安全断言：它必须与同一连接执行 `SELECT current_schema()` 的结果完全一致，不能依赖 Flyway 在运行中替宿主切换 schema。Migration Job、Web 和所有 Worker 的 JDBC search path 与该配置必须相同。独立 schema 推荐使用 PostgreSQL JDBC `currentSchema=fileweft`；共享 `public` 时应使用 `currentSchema=public`（或其他能确定返回 `public` 的等价 search path）并配置 `schema: public`。仅当模式为 `migrate` 且 `create-schema=true` 时允许目标尚未创建；即便如此，JDBC search path 也必须预先指向该名称，使创建前 `current_schema()` 返回 `null`，而不能返回另一个可用 schema。宿主有多个 DataSource 时，Starter 即使发现 `@Primary` 也不会猜测，必须显式注册绑定正确 DataSource 的 `FlywayMigrationRunner`。

MySQL 的 schema 就是 database，且无 database 的 JDBC URL 在 `CREATE DATABASE` 后建立的新连接仍不会自动选择该 database。为避免出现“DDL 已执行但应用仍失败”的半完成状态，FileWeft 不从这种 DataSource 自动建 MySQL database：先由 DBA 预建 database，在 JDBC URL 中明确选择它，并保持 `create-schema=false`。若当前 `SELECT DATABASE()` 为 `null`，即使配置 `create-schema=true`，runner 也会在任何 DDL 前失败关闭。

### MySQL 8 与人大金仓（Kingbase ES）

当前主工作树已为 PostgreSQL、MySQL 和 KingbaseES 提供各自完整的 28 个 V001–V028 迁移脚本与 `FlywayMigrationRunner` 方言路由：runner 根据 `DatabaseMetaData.getDatabaseProductName()` 自动选择 `classpath:ai/icen/fw/db/migration/postgres|mysql|kingbase`。MySQL 支持边界仅是原生 MySQL 8.x 中的 8.0.17+，本轮真实证据固定为 MySQL 8.0.46；MariaDB 与 MySQL 9 均不在支持范围内。官方 KingbaseES V008R006C009B0014 也已通过全新迁移和 JDBC repository 实库套件。MySQL 路径覆盖对应 DML 方言、JSON 访问、`FOR UPDATE SKIP LOCKED` 任务领取、V017 单 PENDING 数据库约束、V028 NO PAD 精确文本比较和重复文档号错误转换；KingbaseES 以 PostgreSQL 兼容模式验证迁移与 repository 行为。MySQL 连接示例：

```yaml
spring:
  datasource:
    url: jdbc:mysql://db.example.internal:3306/fileweft?useUnicode=true&characterEncoding=utf8

fileweft:
  persistence:
    migration-mode: validate
    schema: fileweft
    create-schema: false
```

MySQL `V001` 保持既有 pre-0.0.2 工作树中的资源字节与 Flyway checksum 不变，避免破坏已经试用 0.0.2-SNAPSHOT 的 schema history；0.0.1 正式标签尚未包含 MySQL 迁移，不能把这项兼容边界误写成 0.0.1 已发布契约，也不能把当前工作泛称为重写所有历史迁移。MySQL 专属修复从 V016 开始，修正旧链路中会让真实 MySQL 8 无法完整执行的语法和重复列定义。因此 0.0.2 是首个具备真实 MySQL 迁移与 JDBC repository 闭环证据的版本线。若旧库出现 checksum 不匹配或部分执行历史，必须先停写、备份并由 DBA 对比精确资源和 `fileweft_schema_history`；禁止无条件执行 `flyway repair`，也不能用 repair 掩盖无法解释的历史。

`fileweft-persistence` 已通过 `runtimeOnly` 引入 KingbaseES JDBC 坐标 `cn.com.kingbase:kingbase8:8.6.1`，测试类路径也锁定同一驱动；宿主仍应显式锁定同一版本，从其受控制品仓库解析依赖并遵守厂商许可。

#### KingbaseES 0.0.2 生产接入操作手册

FileWeft `0.0.2` 可从 CNB 公共 Maven 仓库匿名解析，不需要把 CNB token 写入构建。Kingbase 驱动来自 Maven Central 或企业批准的受控镜像。Spring Boot 2 与 3 只能选择对应的一条 Starter 线：

Spring Boot 2 宿主还必须按[安装文档](../fileweft-docs/pages/zh/getting-started/installation.md)把 Kotlin 对齐到 `2.1.21`；其 BOM 默认的 `1.6.21` 不是 FileWeft 运行时合同。

```kotlin
repositories {
    maven("https://maven.cnb.cool/china.ai/maven/-/packages/")
    mavenCentral()
}

// Spring Boot 2
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

// Spring Boot 3
dependencies {
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("ai.icen:fileweft-spring-boot3-starter:0.0.2")
    // implementation("ai.icen:fileweft-web-spring-boot3-starter:0.0.2") // 需要正式 HTTP API 时启用
    runtimeOnly("cn.com.kingbase:kingbase8:8.6.1")
}
```

DBA 必须先预建目标 schema；生产示例固定为 `fileweft`，并保持 `create-schema=false`。官方锁定驱动的 `currentSchema` URL 属性会设置连接 search path，因此 URL、账号与 FileWeft schema 的最小合同如下。Spring Boot 2：

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

Spring Boot 3 使用相同 DataSource 合同：

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

这里的 `fileweft.persistence.schema` 是严格断言，不是切换开关：迁移账号和运行账号经该 URL 取出的每条连接执行 `SELECT current_schema()` 都必须精确返回 `fileweft`。如果企业连接池不用 URL 属性，必须提供在**每条连接**上执行 `SET search_path TO fileweft` 的等价初始化，并再次验证 `current_schema()`；不能只在部署脚本的单个会话中执行一次。多 DataSource 宿主仍须显式把 FileWeft runner 绑定到这一 DataSource。

上线顺序固定为：

1. DBA 预建 `fileweft` schema，准备短期迁移账号与长期运行账号；两者都验证 URL、`currentSchema` 与 `fileweft.persistence.schema` 完全一致。
2. 一次性 Migration Job 使用短期 DDL 账号和 `FILEWEFT_MIGRATION_MODE=migrate` 启动。成功后由宿主明确退出该进程；Starter 不会自动退出。
3. 核对 `fileweft_schema_history` 的 V001–V028 全部成功且无 pending/checksum 差异，再回收 Migration Job 的 DDL 凭据。
4. API 与 Worker 使用独立运行账号和 `FILEWEFT_MIGRATION_MODE=validate` 启动；只有全部节点校验通过后才开放流量。不要让滚动节点各自执行 `migrate`。
5. 若宿主自己使用 Spring Flyway，保留独立的宿主 location/history，不得追加 FileWeft location；Boot 2 的 `spring.flyway.locations` 不得使用 `{vendor}`。宿主不使用 Flyway 时才设置 `spring.flyway.enabled=false`。

上线检查清单：

- [ ] 只引入与宿主匹配的 Boot 2 或 Boot 3 Starter，且 `cn.com.kingbase:kingbase8:8.6.1` 与 `com.kingbase8.Driver` 可解析。
- [ ] schema 已由 DBA 预建；迁移账号和运行账号执行 `SELECT current_schema()` 都精确返回 `fileweft`。
- [ ] `fileweft_schema_history` 的 V001–V028 已成功，运行节点随后以 `validate` 模式启动成功。
- [ ] 迁移账号只在发布窗口下发；运行账号只拥有业务 DML、schema `USAGE`、FileWeft 业务读取及 migration history 校验所需权限。
- [ ] `KINGBASE_USERNAME` 与 `KINGBASE_PASSWORD` 由部署 Secret/密钥系统注入；禁止把密码写入 `application*.yml`、Gradle 文件、Git、镜像或日志。
- [ ] 宿主 Flyway 与 FileWeft location/history 分离，多个 DataSource 的 runner 绑定明确。
- [ ] 迁移前完成可恢复备份，迁移失败时保留 history/日志证据并前进修复，禁止无条件 `repair` 或伪造成功记录。

KingbaseES 数据库镜像不得由 FileWeft 仓库、CNB 制品库或其他镜像仓库再次分发。开发机与 CI 必须使用 `.ci/scripts/prepare-kingbase-image.ps1` / `.sh` 从金仓官网公开地址下载 V8R6C9B14 tar，并依次核对官网 MD5、仓库锁定 SHA-256 和导入后的 Docker image ID；任何校验失败都必须终止。不得用来历不明的社区镜像替代。

MySQL 与 KingbaseES 使用独立 Compose profile，不会随普通 Dev 全栈启动。只有改动触及相应迁移、方言或 persistence 边界时，本地才运行根任务 `mysqlIntegrationCheck` 或 `kingbaseIntegrationCheck`；CNB 使用同样的路径规则按需调度，夜间和标签发布则运行两者。两个任务都要求显式 `FILEWEFT_RUN_*_TESTS=true` 和专属真实数据库，缺少环境时失败关闭而不是跳过。完整准备命令、环境变量和镜像许可边界见 `.ci/README.md`。

这些实库结果证明当前锁定测试版本与已覆盖 repository 路径，不自动扩大为所有 MySQL/KingbaseES 小版本、字符排序规则、高可用拓扑或厂商支持合同。MySQL 的公开下限是 8.0.17，且只覆盖原生 MySQL 8.x；8.0.46 是本轮实证版本，不是“仅支持 8.0.46”，也不能外推到 MariaDB 或 MySQL 9。本节后续关于 PostgreSQL 并发预建脚本、部分唯一索引、`pg_stat_progress_create_index` 等说明仍仅适用于 PostgreSQL。

Flyway 兼容矩阵必须按宿主实际解析结果理解：FileWeft persistence 自身声明并验证 Flyway 9.22.3；Spring Boot 2 宿主由其依赖管理把 runner 解析到 Flyway 8.5.13；Spring Boot 3 宿主解析到 Flyway 11.7.2。Boot 3 已拆分数据库模块，因此 `flyway-core`、`flyway-mysql`、`flyway-database-postgresql` 必须统一为 11.7.2；混用版本不属于受支持组合。升级 Spring Boot BOM 或覆盖 Flyway 版本后，必须重新运行对应宿主运行时和三种数据库迁移门禁，不能只验证编译。

Kingbase Starter 默认注册的兼容 customizer 只包装 **Spring Boot 已选择给 Flyway 的 DataSource**：它仅在 Flyway 获取 Kingbase 连接时把数据库产品名投影为 PostgreSQL，真实 URL、驱动版本、SQL、连接生命周期和诊断保持不变。应用主 DataSource Bean 仍是 Kingbase DataSource，不会被替换或重新分类。`fileweft.persistence.kingbase-flyway-compatibility-enabled=false` 只允许宿主已提供并通过实库迁移验证的等价 Flyway/Kingbase 集成时关闭；它不是绕过不兼容的故障开关，也不改变 FileWeft 自有 runner 对 Kingbase 的适配。Spring Boot 2 宿主不要在 `spring.flyway.locations` 中使用 `{vendor}`：Boot 会在 FileWeft customizer 运行前按原始 `jdbc:kingbase8:` URL 解析该占位符，无法可靠映射为 PostgreSQL；请直接配置明确的宿主迁移路径。

当目标 schema 已包含宿主对象但尚无 FileWeft 对象时，Flyway 默认会把“非空 schema”视为需要人工 baseline。FileWeft runner 不会启用 `baselineOnMigrate`：它先确认专属 history 不存在、默认 history 中没有任一已知 FileWeft 脚本、目标 schema 中没有任一已知 FileWeft 业务表，才通过 Flyway 公共 API 写入版本 `0` 的命名空间初始化标记，并继续执行全部 `V001` 及后续脚本。该标记只隔离两套 history，不代表收养既有 FileWeft 数据；任何旧、失败或无 history 的 FileWeft 痕迹都会在写入标记前失败关闭。

推荐由宿主提供的一次性 Migration Job 或受控迁移进程使用 `migrate`，成功后由宿主明确退出该进程，再让全部 Web/Worker 节点以 `validate` 启动；FileWeft Starter 只负责启动时迁移，不提供迁移后自动退出的通用 Job 入口。所有角色必须指向相同 FileWeft schema；不要让每个滚动节点自行改变 migration mode，也不要把 FileWeft 资源路径追加到宿主 `spring.flyway.locations`。Spring Boot 自己的 Flyway 可以继续管理宿主 schema 和默认 history，它与 `fileweft.persistence.*` 是两套独立边界。

权限应按角色拆分：`validate` 账号至少需要目标 schema 的 `USAGE`、FileWeft 业务读取权限，以及 `fileweft_schema_history` 的只读权限；若目标 schema 也存在宿主 `flyway_schema_history`，旧记录检测还需要读取其中的 `script` 等迁移记录。Migration Job 的账号除上述读取权限外，还需要创建/修改 FileWeft 表、索引和约束的 DDL 权限；`create-schema=true` 时才额外授予建 schema 权限。不要把 Migration Job 的 DDL 凭据长期下发给 Web/Worker。

已运行旧 `com.fileweft:*:0.0.1` 试推的数据库属于特殊升级，不支持自动 adoption。该试推可能把 FileWeft 迁移写入默认 `flyway_schema_history`，而同一张表也可能含宿主迁移；框架无法仅凭版本号安全判断每一行的所有权。升级必须：

1. 关闭写入口，排空并停止全部旧 API、Outbox Worker 和任务 Worker，禁止新旧版本混跑。
2. 对数据库、默认 history、FileWeft 业务 schema 和关联对象存储完成可恢复备份，并实际验证恢复路径。
3. 保存默认 history 全量快照，逐条核对实际成功版本、脚本名、checksum、安装顺序和目标 schema；同时检查宿主是否使用相同版本号或修改过脚本。
4. 由 DBA 基于核验结果制定人工转换或新 schema 数据迁移方案，并在隔离副本演练。仓库不会虚构一条通用 SQL 去复制、重命名或删除旧 history 行。
5. 只有专属 `fileweft_schema_history` 与业务 schema 能被确定性校验后，才可先以 `validate` 验证，再开放新版本流量。

任何缺失、失败、checksum 不符、同版本多来源或 schema 所有权不清的情况都必须失败关闭。禁止用 `baselineOnMigrate`、Flyway `repair`、手工伪造成功行或删除默认 history 来跳过调查；这些操作可能让未执行的安全迁移被误认为已经完成。

FileWeft 的 Web 节点默认不消费 Outbox 或后台任务。部署时推荐使用相同的应用工件启动两个角色：

```yaml
# Web 节点：默认值，无需配置 fileweft.worker.enabled
fileweft:
  worker:
    enabled: false

# 异步 Worker 节点
fileweft:
  worker:
    enabled: true
    fixed-delay-millis: 1000
    outbox-batch-size: 50
    task-batch-size: 50
    process-outbox: true
    process-tasks: true
    process-upload-cleanup: true

  upload:
    resumable-session-ttl-millis: 86400000
    resumable-cleanup-batch-size: 100
```

若需要拆分资源池，可让下游同步节点只开启 `process-outbox`，让 Doctor 节点只开启 `process-tasks`，让存储维护节点只开启 `process-upload-cleanup`。所有节点可以水平扩展：Outbox 与后台任务均通过数据库租约/锁领取，重复投递由事件或任务的幂等键约束。不要为 `0.0.2` 默认部署规划 Agent 节点。

Worker 每轮失败只记录日志，不会丢弃待处理记录；下一轮会继续领取符合重试时间或租约已过期的工作。生产报警应至少覆盖同步失败、任务失败、任务失租（`fileweft.task_lease_lost`）、Doctor 失败和持久化 Outbox 积压。

## 持久化 Outbox 积压指标与运行角色

Outbox 积压不是 API 节点上的内存计数，而是对 `fw_outbox_event` 的全局数据库聚合快照。启用了 Micrometer `MeterRegistry` 的 Starter 会导出以下指标：

| 指标 | 固定 `state` / 标签 | 运营含义 |
| --- | --- | --- |
| `fileweft.outbox_backlog` | `ready` | 已到执行时间的 `PENDING`/`RETRY`；持续增长通常表示 Worker 不足、下游受阻或领取失败。 |
| `fileweft.outbox_backlog` | `delayed` | 尚在退避窗口的 `PENDING`/`RETRY`；短暂存在是正常现象。 |
| `fileweft.outbox_backlog` | `running` | 仍在有效租约内、暂不可回收的 `RUNNING`。 |
| `fileweft.outbox_backlog` | `expired` | 已可回收的 `RUNNING`，包括 token 租约到期或无 token 历史记录超过 legacy grace。持续非零应检查处理时长、旧 Worker 排空和下游超时。 |
| `fileweft.outbox_backlog` | `failed` | 已进入终态的 `FAILED`，需要按交付/事件的既有运维流程排查和人工重排。 |
| `fileweft.outbox_oldest_ready_age_seconds` | 无 | 最早 `ready` 事件的等待秒数；没有 `ready` 事件时为 `0`。 |
| `fileweft.outbox_backlog_observation_failure` | 无 | 最近一次实际执行的积压读取是否失败；`0` 为成功，`1` 为失败。 |

五个 `state` 彼此互斥，且 `state` 是唯一允许的 gauge 标签。指标不包含 `tenantId`、文档 ID、用户 ID、连接器 ID 或下游错误文本；租户级排查必须使用审计、操作日志、Trace 与受权限保护的交付状态查询。多个 Outbox Worker 观察的是同一份全局数据库状态，因此查询或报警规则不能把各实例序列相加；应按 `state` 取最近值或最大值。

默认启用后，每个 Outbox Worker 进程每 30 秒最多尝试一次采样；可通过下列配置调整（间隔和查询超时必须大于零）：

```yaml
fileweft:
  worker:
    enabled: true
    process-outbox: true
    fixed-delay-millis: 1000
  outbox:
    backlog-metrics-enabled: true
    backlog-metrics-interval-millis: 30000
    backlog-metrics-query-timeout-seconds: 5
```

采样只挂在 Outbox 轮询角色上：Web 节点维持默认的 `fileweft.worker.enabled=false`，不会产生积压 gauge；仅处理 `process-tasks` 或 `process-upload-cleanup` 的 Worker 也不会产生。若集群需要这组指标，至少保留一个 `enabled=true` 且 `process-outbox=true` 的 Worker。设置 `backlog-metrics-enabled=false` 会使默认读取器、发布器和观察执行通道都不装配。采样在每次 Outbox 轮询结束后尝试，故实际频率还受轮询周期和进程存活影响，不是独立于 Worker 的定时探针。

每次采样先提交到独立的单线程、零队列观察通道，Outbox Worker 不会等待该数据库工作；若前一次慢查询仍在运行，新采样不会排队。实际运行时会在一个独立、短暂的数据库事务内执行只针对 `PENDING`、`RETRY`、`RUNNING` 和 `FAILED` 的聚合查询，事务外才更新指标后端；单条 JDBC 查询最长默认 5 秒。它不会在事务中调用连接器，也不会影响事件的确认、退避或租约恢复。`fileweft.outbox_backlog_observation_failure=1` 表示最近一个已实际执行的读取失败，下一次成功会写回 `0`；通道拒绝或进程退出前未执行的任务不会在 Worker 线程直接调用客户指标适配器。默认使用 `JdbcOutboxBacklogReader` 与 Micrometer 导出器；宿主可通过 `OutboxBacklogReader` 或 `FileWeftGaugeRecorder` Bean 替换查询或指标后端。自定义读取器必须维持五种状态的互斥分类与最早 `ready` 创建时间，自定义导出器必须把写入理解为当前值替换、快速且非阻塞，并隔离自身失败，不能改变 Outbox 处理语义。

默认读取已经排除 `SUCCESS` 历史，但它仍需统计保留的 `FAILED`、待处理和运行中记录。长期保留大量终态失败记录的部署，应按自身合规策略归档/分区，并在需要时提供分区感知的 `OutboxBacklogReader`；不要通过取消查询超时或让观察任务在 Worker 线程中排队来换取指标完整性。

## Outbox 租约与滚动升级

`V018` 为 `fw_outbox_event` 增加持久化租约字段。新 Worker 每次只领取一条事件，并在短事务内写入独立的 `lease_owner`、随机 `lease_token` 与到期时间；只有持有同一 token 的 Worker 才能确认成功、重试或失败，迟到的旧 Worker 不能覆盖新领取者的状态。默认租约为 5 分钟（`fileweft.outbox.lease-duration-millis=300000`）。该值应大于一次外部调用的最长预期耗时及必要余量；租约到期后其他 Worker 可以重新领取事件，因此不能把它当作“最多执行一次”保证。

`fileweft.outbox.worker-id` 应在同时运行的 Worker 间唯一，建议使用 Pod/主机实例 ID。未配置或空白时 Starter 会生成 `fileweft-outbox-<UUID>`，便于独立进程启动但不适合跨重启关联诊断。`fileweft.outbox.legacy-running-grace-millis` 默认也是 5 分钟，只用于回收升级前没有 token 的历史 `RUNNING` 记录；它不是旧、新 Worker 并行运行的安全屏障。

滚动升级到 `V018` 时，必须先暂停旧版本 Worker 的轮询并排空其正在处理的 Outbox，再执行迁移并启动租约感知的新 Worker。不要仅依赖 legacy grace：旧 Worker 可能仍在调用下游，而新 Worker 回收同一事件会产生重复交付。若故障场景无法排空，legacy grace 应覆盖旧 Worker 的最长外部调用时间，并持续观察 `RUNNING` 记录、下游幂等命中和失败告警。

Outbox 语义仍然是至少一次（at-least-once）：进程崩溃、超时或租约到期后，同一事件可能再次调用处理器。每个 `OutboxEventHandler` 必须以事件 ID 实现幂等；下游连接器还必须使用 `ConnectorInvocation.idempotencyKey` 去重。租约 token 只防止陈旧确认覆盖较新的所有权，不能替代外部系统幂等。

正式交付处理器只接受携带当前持久化 lease token 的 Worker 调用；对旧的无租约 `handle(event)` 入口安全失败。保留的两参数构造器仅用于显式兼容集成，并在新版 Worker 传入 lease 时继续走旧行为。生产 Starter 会要求自定义 `DocumentDeliveryTargetRepository` 同时实现 mutation 行锁能力，并要求唯一的 `OutboxEventMutationRepository`；缺失时启动失败而不是静默让事件进入无处理器失败。Outbox 标记 `FAILED` 与本地 target 终态投影之间仍可能遇到进程退出，但同步状态和显式幂等恢复会识别“当前事件精确失败 + target 仍 PENDING/RETRYING”的组合并原子推进新事件，无需手工改表。

旧版本使用单目标 `document.publish.requested` 事件。该兼容处理器现在默认关闭，正式部署只消费带目标快照的 `document.delivery.target.requested` 与 `document.delivery.target.removal.requested`。升级前必须先暂停旧 Worker，并确认旧事件已经处理或由运营人员明确处置；不要让旧处理器与新的多目标交付链路同时工作。仅在处理升级遗留事件的受控维护窗口中，才可临时设置 `fileweft.sync.legacy-publish-handler-enabled=true`，处理完成后立即恢复为 `false` 并重启节点。自动猜测“交付或撤回”的旧 `RetryDocumentDeliveryService` 也默认不装配；`fileweft.sync.legacy-delivery-retry-enabled=true` 只供 Dev/迁移兼容入口使用。正式系统必须使用两条带持久化幂等键的明确重排命令。两个兼容开关都不能作为长期运行模式。

```yaml
fileweft:
  sync:
    legacy-publish-handler-enabled: false
    legacy-delivery-retry-enabled: false
  outbox:
    worker-id: ${HOSTNAME}
    lease-duration-millis: 300000
    legacy-running-grace-millis: 300000
    backlog-metrics-interval-millis: 30000
```

## 后台任务租约与滚动升级

`V019` 为 `fw_task` 的既有 `lease_owner` 和 `lease_expire_time` 增加持久化 `lease_token`，并为 token 化与历史 `RUNNING` 任务分别建立恢复索引。新 Worker 每次只领取一条任务：在短事务内写入 Worker 标识、随机 token 和到期时间，随后在事务外执行任务处理器；确认成功、安排重试或标记失败时必须同时匹配该 token。这样，租约到期后被新 Worker 重新领取的任务，不会被旧 Worker 的迟到确认覆盖。

默认任务租约为 60 秒（`fileweft.task.lease-duration-millis=60000`）。它必须大于一次任务处理的最长预期耗时和必要余量；任务耗时超过租约、进程崩溃或确认前网络/数据库失败时，任务可能被重新执行。`fileweft.task.worker-id` 应在同时运行的 Worker 间唯一，建议使用 Pod 或主机实例 ID。未配置或空白时 Starter 保持生成 `fileweft-<UUID>` 的行为，适合单独启动但不适合跨重启关联诊断。

`fileweft.task.legacy-running-grace-millis` 默认 5 分钟（300000 毫秒），仅用于回收 `V019` 前没有 token 的历史 `RUNNING` 任务；它不是旧、新 Worker 并行运行的安全屏障。升级时必须先停止旧版本的任务轮询并等待正在执行的处理器排空，再执行 `V019` 并启动租约感知的新 Worker。不能只依赖 legacy grace：旧 Worker 可能仍在执行外部调用，而新 Worker 已回收同一任务。无法排空时，grace 至少应覆盖旧 Worker 的最长任务耗时，并持续观察 `RUNNING` 任务、幂等命中和失败告警。

后台任务语义是至少一次（at-least-once），不是恰好一次：`FileWeftTaskHandler` 必须把 `TaskExecution.id` 作为幂等依据；任务创建端的租户级 `idempotencyKey` 只负责折叠重复入队，不能替代处理器或外部系统的去重。租约 token 只保证陈旧 Worker 不能覆盖较新的持有者，不能阻止已发出的外部副作用重复执行。

任务状态确认的 token 围栏并不自动保护处理器写入的其他业务表。默认 Doctor 处理器因此还实现 `LeasedTaskHandler`：外部检查仍在数据库事务外，写入 `fw_doctor_record` 前才开启短事务，通过 `TaskMutationRepository.findForMutation` 锁定任务，并精确复核 tenant、task ID、type、business ID、`RUNNING`、owner 与 token。失租结果不会覆盖新领取者；无 token 的 legacy lease 在调用检查器前即安全失败。报告提交与随后任务确认之间仍是两个短事务，所以读取端必须以 `fw_task` 为权威：只有 `SUCCESS` Doctor 才展示报告，`FAILED` 也不复用可能来自旧失租尝试的暂存报告。显式旧版 Agent 兼容模式沿用同一 `LeasedTaskHandler` 围栏和 `fw_agent_result` 约束，但该路径不是 `0.0.2` 默认产品能力。

JDBC 默认仓储已经同时实现 token 围栏与任务 mutation 行锁。自定义 `TaskProcessingRepository` 为兼容旧插件仍可继续运行，但只获得原有的 `lease_owner` 语义；要在多 Worker、重启或租约到期场景获得 token 围栏，必须同时实现可选的 `LeasedTaskProcessingRepository`，在领取和全部确认路径持久化并校验 `TaskLeaseClaim.leaseToken`。若使用 Starter 默认的 Doctor 业务投影，自定义任务仓储还必须实现唯一的 `TaskMutationRepository`，否则启动失败；不能把仅实现旧端口的仓储误认为已经具备 fencing。显式旧版 Agent 兼容模式也要求该仓储，但默认部署不得据此声明 Agent 能力。升级自定义仓储前应按至少一次语义验证任务 ID 和外部副作用幂等。

```yaml
fileweft:
  task:
    worker-id: ${HOSTNAME}
    lease-duration-millis: 60000
    legacy-running-grace-millis: 300000
```

## 并行审批与直接发布

同一工作流的最终审批或驳回会通过 `WorkflowInstanceRepository.findForDecision` 串行化。默认 PostgreSQL 实现对工作流父行使用 `SELECT … FOR UPDATE`，因此双人会签的第二位审批者会在第一位提交后读取最新任务状态，而不会用旧聚合快照覆盖已批准的任务。所有文档读改写用例则必须通过 `DocumentRepository.findForMutation` 获取同一文档的串行化读取；默认 PostgreSQL 实现对文档行使用 `SELECT … FOR UPDATE`。审批决策统一按“文档、工作流”顺序加锁，避免与提交、直接发布或版本更新形成反向锁顺序。

`V017` 在数据库层保证同一 `(tenant_id, document_id)` 最多一条 `state='PENDING'` 的本地审批流。PostgreSQL 与 KingbaseES 使用部分唯一索引；MySQL 没有同等 partial unique 语法，因此使用两个仅在 `PENDING` 时非空的 stored generated columns，再对二者建立唯一索引。既有重复会让预检或唯一索引创建明确失败，迁移绝不静默删除、合并或关闭审批记录；运营人员必须核实并处理历史工作流后重试。自定义文档/工作流仓储仍须实现等价的行锁、CAS 或互斥语义，框架不会退化为普通读取。

`PublishDocumentService` 始终检查同租户、同文档是否存在本地 `PENDING` 工作流。有活动工作流时，直接发布会被拒绝，保留原审批任务和文档状态；没有本地工作流的 `PENDING_REVIEW` 文档仍可用于“外部审批已完成”的集成场景。若业务需要显式绕过本地审批，必须另行实现带专门权限、不可空原因、取消状态和审计记录的用例，不能复用普通 `document:publish`。

## 数据库迁移与查询索引

所有数据库变更只能新增版本化 Flyway 脚本。这里的“已发布迁移不可改写”边界必须准确：作为历史边界，`v0.0.1` 只发布了 PostgreSQL V001–V025；受保护 `v0.0.2` 标签成功发布后，PostgreSQL、MySQL 与 KingbaseES 三种方言的 V001–V028 全部成为不可改写的发布资源。任何后续开发线都必须只前进并保持 checksum 可审计，不得用 `repair` 掩盖差异。`V016` 新增同步/任务索引；`V019` 新增 token 化任务租约和历史任务回收索引；`V020` 新建持久化请求幂等表；`V021`/`V022` 增加工作流 keyset 查询索引；`V023` 建立交付事件围栏；`V026` 统一宿主用户标识宽度并新增决策证据；`V027` 稳定 Worker 领取顺序；`V028` 收紧 MySQL 文本比较安全边界。普通升级保持单一前进版本。

对于已有大量同步、任务或工作流数据的生产库，DBA 应在升级前以自动提交会话逐条运行 [V016 并发预建脚本](sql/postgresql-v016-concurrent-indexes.sql)、[V019 并发预建脚本](sql/postgresql-v019-concurrent-indexes.sql)、[V021 审批查询并发预建脚本](sql/postgresql-v021-concurrent-workflow-query-indexes.sql) 和 [V022 受理人待办并发预建脚本](sql/postgresql-v022-concurrent-workflow-assignee-inbox-index.sql)，并监控 `pg_stat_progress_create_index` 与磁盘余量。完成后 Flyway 会发现同名索引并跳过创建。旧的同步索引不会由应用自动删除；只有在 DBA 已核对查询计划、回滚窗口和磁盘预算后，才可使用脚本中注明的并发删除语句。

`V020` 只新增 `fw_idempotency_record`，不回填或重写现有业务表，因此升级前检查重点是新表、索引所需磁盘以及应用账号的建表权限。回滚到不认识 V020 的旧应用在尚未开放正式幂等写入口时可以保留该表；只有确认没有新版本写流量和待重放客户端后才可手工删除。幂等记录当前没有自动 TTL：不得以通用历史清理作业删除该表数据，否则迟到重试可能重复推进业务。正确事务不会提交 `IN_PROGRESS`；诊断索引一旦发现可见行，应按应用缺陷或自定义仓储不满足原子性处理，禁止自动改为完成、删除或接管。

`V021` 与 `V022` 只创建索引，不回填或重写工作流数据。高数据量数据库应先使用并发脚本预建同名索引，再由 Flyway 记录迁移；回滚旧应用可以保留这些兼容索引。V021 保留全局 `created_time + id` 有序路径，V022 为当前受理人和未分配池提供可检索路径；只有在目标环境比较真实查询计划、确认没有新版本查询流量且磁盘预算允许时，才可由 DBA 并发删除任一索引。

`V023` 是需要维护窗口的数据回填，不是可与旧 Worker 混跑的滚动迁移。执行前先停止发布、最终审批、离线和人工重排入口，停止旧版本交付 Worker，并运行 [V023 交付围栏预检脚本](sql/postgresql-v023-delivery-dispatch-fence-preflight.sql)；所有 `issue_count` 必须为零。预检与迁移使用相同的最新事件排序和交付/撤回状态匹配规则。迁移会拒绝相关 `RUNNING` 事件、同一目标的多个活动事件、缺少历史事件的目标、非法交付/撤回状态及最新事件终态不一致，绝不会生成虚构事件或静默选择冲突记录。修复历史数据必须保留原始审计证据，并在修复后重新执行预检。

`current_event_id` 故意不外键关联 Outbox：生产环境可能按保留策略归档终态事件；但任何 Outbox 清理作业都必须排除仍被 `fw_document_delivery_target.current_event_id` 引用的记录。当前事件缺失表示一致性损坏，正式重排必须 fail closed 并交给 Doctor/运营处理。V023 落库后不能回滚到不了解事件围栏的旧 Worker；旧二进制会忽略事件身份，数据库也无法从旧的整行更新推断它正在处理哪个事件。

`V025` 只为正式文档审计 keyset 查询增加 `(tenant_id, resource_type, resource_id, created_time DESC, id DESC)` 复合索引，不修改或回填审计数据。审计表较大的生产库应在升级前以自动提交会话运行 [V025 审计查询并发预建脚本](sql/postgresql-v025-concurrent-audit-log-index.sql)，建议使用 `psql -v ON_ERROR_STOP=1 -f docs/sql/postgresql-v025-concurrent-audit-log-index.sql`，并监控 `pg_stat_progress_create_index`、磁盘和复制延迟。脚本会在创建前后严格核验索引状态和精确定义；若早先失败的并发创建留下无效同名索引，必须按提示先 `DROP INDEX CONCURRENTLY`，不能让 `IF NOT EXISTS` 静默跳过。Flyway V025 也执行同等后置校验，只有索引有效、ready 且五列顺序与排序方向完全一致时才会记录迁移成功。回滚旧应用可以保留该兼容索引；只有确认所有新日志查询节点已下线并核对查询计划后，才可按脚本注释并发删除。无论是否保留索引，审计与操作记录本身都是合规证据，不属于迁移回滚可删除的数据。

`V026` 将 `fw_audit_record.operator_id`、`fw_operation_log.operator_id`、`fw_agent_suggestion_confirmation.confirmed_by` 和 `fw_workflow_task.assignee_id` 扩宽为 `varchar(256)`，并在工作流任务上新增可空的 `decision_operator_id`、`decision_operator_name` 与 `decided_time`。这些列记录新审批或驳回提交时的不可变身份快照和决策时间；显示名仅供证据展示，后续授权仍使用宿主当前可信身份。迁移不会从受理人、当前用户目录或可选审计记录推断既有已完成任务的决策者，因此遗留任务保持空证据；受权 JSON 以 `decisionEvidenceRecorded=false` 和空操作者/时间字段表达 `UNKNOWN`/未记录。

升级前先以只读账号运行 [V026 工作流决策证据预检脚本](sql/postgresql-v026-workflow-decision-evidence-preflight.sql)，建议使用 `psql -v ON_ERROR_STOP=1 -f docs/sql/postgresql-v026-workflow-decision-evidence-preflight.sql`。预检会检查四类既有宿主用户 ID 是否满足固定契约，并报告各任务状态及待审批工作流数量；任何不安全 ID 都必须由拥有该身份映射的宿主先核实并修复，不能截断、trim、归一化或猜测替换。宿主 ID 是区分大小写的不透明字符串，最多 256 个 UTF-16 code unit，首尾不得有 Unicode whitespace，也不得包含 ISO control 或 FileWeft 固定拒绝的 Unicode format 码点；`Long`、`Int`、UUID 或组合主键应在 `UserRealmProvider` 中使用永久稳定格式转换为字符串。

V026 不能与旧审批入口节点滚动混跑：旧节点不了解决策证据列，即使迁移成功也能继续提交“已完成但没有决策者”的新任务。维护窗口中必须先关闭 submit/approve/reject 入口并停止全部旧 API 节点，等待在途决策事务完成，再重新运行预检，执行 Flyway V026，验证约束和列宽，最后一次性启动新节点并做一笔新审批读取 `/fileweft/v1/documents/{id}/workflow-decisions` 验收。`ALTER COLUMN TYPE`、加列以及 `VALIDATE CONSTRAINT` 会取得表锁或扫描历史数据；大表环境应事先检查长事务、锁等待、复制延迟和维护窗口，不能把它当作无锁在线变更。

紧急回滚应用时必须保留 V026 的列、约束以及已经写入的决策证据，不得删除操作者快照、把遗留空证据回填为当前受理人，也不得把 256 宽身份列缩回 64；新版本可能已经写入旧列宽无法容纳的合法用户 ID。若只能启动不认识 V026 的旧二进制，应继续关闭所有审批提交入口，因为旧节点会制造新的证据缺口；恢复到支持 V026 的节点并完成对账后才能重新开放。决策者 ID/名称属于审计和个人信息治理范围，应按宿主已批准的合规保留、访问和归档策略处理，不能进入指标标签、普通错误文案或不受控日志；删除或匿名化策略必须同时满足不可抵赖审计要求，不能由通用 TTL 作业直接清理。

`V027` 分别在 `fw_outbox_event` 与 `fw_task` 上创建 `(created_time, id)` 非唯一索引，使稳定领取顺序能直接使用索引；MySQL repository 还以对应 `FORCE INDEX` 避免 OR eligibility 谓词先 filesort 并在 `SKIP LOCKED` 前锁住过多候选行。三个方言都使用普通 `CREATE INDEX`，没有承诺 concurrent/online DDL：PostgreSQL/KingbaseES 建索引期间会阻塞相关表写入，MySQL 的实际 online 算法也受版本、表结构和运行条件影响，不能当作无锁合同。升级前停止 Outbox/Task Worker，并关闭所有会向两表写入的 API/调度入口；排空长事务，按表与索引规模为最终索引、临时排序/重建、WAL/binlog 和复制积压预留磁盘，持续监控锁等待、复制延迟和磁盘余量。

V027 是只前进迁移。失败且 Flyway 尚未记录成功时，应先检查同名索引是否完整有效；任何不完整或定义不同的同名对象都由 DBA 在维护窗口清理后再重跑迁移，禁止伪造 history 或无条件 `repair`。应用紧急回滚可以保留两条兼容非唯一索引，不需要也不应同步降级 schema；只有全部依赖新领取计划的 Worker 已下线、旧版本查询计划和锁范围已实测，并确认磁盘回收必要时，才可另行评审删除。

`V028` 是 MySQL 安全迁移：MySQL 常见 `utf8mb4` 默认排序规则会折叠大小写、重音或尾空格，不符合 Core Identifier 不归一化并允许首尾空格的契约。迁移对全部 18 张 FileWeft 业务表执行 `CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_bin`；该 MySQL 8 排序规则为 NO PAD，因此 tenant ID、用户 ID、业务/存储/任务/幂等 ID、V017 generated uniqueness keys 及其索引均按 Unicode 标量/文本值精确比较，大小写、重音和尾空格都不折叠。这里不承诺保存任意原始字节身份。标题、评论等显示文本也随表采用相同排序语义，这是防止未来 key 列静默继承不安全默认值的安全优先取舍。PostgreSQL 与 KingbaseES 的 V028 只用于版本对齐，不重写 schema。

MySQL `ALTER TABLE ... CONVERT` 可能重建整张表和全部文本索引，必须独占维护窗口：停止所有新旧 API/Worker 写流量，禁止新旧节点混跑，完成可恢复备份，并按最大表同时预算原表、重建副本、索引、临时空间、redo/binlog 与复制积压；在演练环境测量真实锁时长，生产持续监控 metadata lock、磁盘和副本延迟。迁移后只能前进：应用回滚必须保留 NO PAD 的 `utf8mb4_0900_bin`，不得把表转回 PAD SPACE 的 `utf8mb4_bin`、`*_ci` 或其他会折叠大小写、重音或尾空格的排序规则。回退比较语义会重新混淆只在大小写、重音或尾空格上不同的 tenant/opaque ID，可能造成跨租户命中、错误唯一冲突或幂等身份折叠，不能作为故障恢复手段。

## 断点续传与对象完整性

`ResumableUploadService` 把 multipart 状态持久化到 `fw_upload_session` 与 `fw_upload_session_part`，并以可信上下文中的租户和用户 ID 绑定用户操作。所有者 ID 只从 `UserRealmProvider.currentUser()` 的单次身份快照取得，是区分大小写且不做 trim、大小写折叠或 Unicode 归一化的不透明字符串；禁止从请求 DTO、Header、metadata、查询参数或浏览器检查点接收。接入值必须非空、最多 256 个 UTF-16 code unit、首尾无 Unicode whitespace，且不含 ISO control 或 FileWeft 固定拒绝表中的 Unicode format 字符；应用校验与 V024 数据库约束使用同一固定码点表，不受 JDK 8～25 内置 Unicode 版本差异影响。宿主若使用 `Long`、`Int`、UUID 或组合主键，应先在身份 SPI 中稳定转换为字符串，后续不得改变格式。正式 `/fileweft/v1/uploads` 只把会话 ID、公开状态、已确认分片号/长度/时间、过期时间和完成回执返回给浏览器；`tenantId`、`ownerId`、`storageUploadId`、对象路径、存储 ETag、`lastError` 和对象存储凭据始终只能留在服务端。所有该资源的 JSON 成功/失败固定 `private, no-store`、`Pragma: no-cache` 与 `nosniff`，宿主网关不得覆盖为公开缓存。

`inspect`、`uploadPart`、`complete` 与用户 `abort` 会先执行所有者边界，再执行普通上传授权。同租户其他用户无论权限高低都收到与不存在会话相同的 404，且不会打开对象存储、写入分片或改变会话状态；所有者后来失去上传权限时才返回 403。租户内幂等键继续保持全局唯一：同一所有者与同一请求可重放，不同请求或不同所有者复用该键固定返回 409，不返回已有会话信息。系统 Worker 的过期清理和平台级卡滞检查不依赖用户所有者，因此仍能处理遗留会话。

推荐的服务端调用顺序是：`start` 创建或恢复幂等会话，`uploadPart` 逐片确认，`inspect` 在刷新或网络恢复后读取服务端确认点，`complete` 幂等完成，用户放弃时调用 `abort`。正式 HTTP 对应五条镜像路由：`POST /fileweft/v1/uploads`、`GET /fileweft/v1/uploads/{uploadId}`、`PUT /fileweft/v1/uploads/{uploadId}/parts/{partNumber}`、`POST /fileweft/v1/uploads/{uploadId}/complete` 和 `DELETE /fileweft/v1/uploads/{uploadId}`。创建要求恰好一个 `Idempotency-Key`；Runtime 负责公共格式验证，Application 从同一次可信身份快照取得租户，把版本域、可信租户和原始 key 做长度分帧 SHA-256，并在任何 Storage/数据库操作前替换为 `v1:sha256:<64hex>`。分片与完成分别以 PUT 资源替换和会话状态重放，不要求也不使用额外幂等键。创建体只接受文件名、正数完整长度和可选类型/预期哈希；assetType 固定为 `DOCUMENT`，不接受 owner、tenant、存储字段或 metadata。

新建会话不会先提交未经验证的 `ACTIVE` 行：初始行使用带固定 creation-staging 标记的不可见 `ABORTING` 状态，在同一事务内完成 global/owner 四路精确核验后，只有 tenant、ID、预期 owner、staging 标记和 `expires_at > activationTime` 均匹配时才条件激活并清除标记。即使自定义 transaction 缺乏真实回滚，异常最多遗留用户不可见的 staging 行，不能被另一用户接管。Worker 只会自动清理仍可安全取消且经固定时间再次核验已过期的会话；仓储错误返回未来行、`COMPLETING`、`QUARANTINED` 或终态时固定零状态变更、零 Storage 调用。`COMPLETING` 状态意味着对象存储可能已经接受完成请求，清理任务不会删除其对象，以免把刚完成的文件变成悬空记录；公开 GET 映射为 `FINALIZING`。客户端短暂等待后以同一 upload ID 重试完成时，服务会对陈旧 claim 执行非破坏性对账：最终对象存在则下载并重新计算实际长度/SHA-256 后补齐本地引用；最终对象尚不可见时继续保留完成围栏并返回 `503 OUTCOME_UNKNOWN`，因为另一个慢速 Storage 调用仍可能在运行，后来的请求不能仅凭一次 `exists=false` 把会话恢复为 `ACTIVE`。适配器只有在能保证本次请求已停止且没有发布对象时才可抛 `MultipartCompletionRejectedException`；官方 JDBC 仓储会在一个事务中清空旧分片确认、恢复 `ACTIVE`，并把过期时间刷新为从拒绝时起的一个完整会话 TTL，HTTP 返回 `409`，让客户端在真实可用的新窗口内从空检查点重新 PUT。S3 仅把 `EntityTooSmall`、`InvalidPart` 与 `InvalidPartOrder` 归为这种确定拒绝；`NoSuchUpload` 可能来自早先完成成功，仍按未知结果围栏。自定义仓储若不提供原子 reset 能力则失败关闭并继续 `FINALIZING`。`QUARANTINED` 是 owner/不可变身份映射异常后的单调安全状态：用户路径永久按 404 隐藏，普通失败和 TTL 清理都不能把它改回可见状态；远端 multipart 可在已确认围栏后安全终止，但数据库行和固定诊断会一直保留，需由运营审计而不是通用 TTL 作业删除。

创建会话的数据库提交若丢失确认，`ApplicationTransactionOutcomeUnknownException` 表示“可能已提交”，不能按普通失败立即删除 multipart。服务会先按本次 session ID、所有者、幂等键和不可变存储身份重新对账：确认是同一次提交时返回已持久化会话；确认是另一竞争请求时才清理本次远端上传；数据库仍不可读或结果无法安全区分时保留远端状态并失败关闭。生产对象存储必须同时配置“未完成 multipart 生命周期”兜底，运维应结合应用 Trace、会话表和存储 upload ID 对账；客户端只重试原幂等键，不能收到 5xx 后换键盲目重传。

普通上传、文档初版、新版本以及续传的 `start/uploadPart/complete/abort/cleanupExpired` 都会组合数据库状态与非事务型 Storage 副作用，必须作为顶层 Application 边界调用。不要从外层 Spring `@Transactional`、手写 JDBC transaction 或另一个尚未完成的 FileWeft transaction 调用这些入口。官方 `JdbcApplicationTransaction` 实现 `ApplicationTransactionState`，能在生成 ID、访问仓储或调用 Storage 前拒绝同一 DataSource 的环境事务；不同 DataSource 按引用身份使用独立连接上下文。自定义 transaction 若不实现这个 additive capability 仍保持二进制兼容，但宿主必须自行保证顶层调用；测试辅助方法 `JdbcConnectionContext.withConnection(Connection)` 是匿名绑定，不代表生产宿主事务，Spring `@Transactional` 也不会被它自动感知。

同一规则覆盖普通上传、文档初版、新增版本和 multipart 完成：事务返回提交结果未知后，服务会按本次生成的 `FileObject`、`FileAsset`、文档和版本绑定重新读取。完整匹配时即使文档随后已改名、推进生命周期或新增版本，也返回当前已提交结果；读取失败、无记录、部分引用或冲突引用都不会触发删除。只有事务明确失败且权威回读确认本次生成的持久化引用全部不存在时，才删除远端对象。告警和故障处理必须保留原异常的 Trace，并以数据库引用与对象存储位置双向对账，不能仅凭一次 5xx 手工删除对象。

自定义 `ResumableUploadSessionRepository` 必须原样持久化 `ownerId`，并让按 ID、幂等键的租户查询返回相同的不可变会话；实现 `OwnerScopedResumableUploadSessionRepository` 时还必须在查询内同时约束租户和 owner。`savePart` 必须在同一事务中确认会话仍为未过期 `ACTIVE`、写入确认并把会话 `updatedTime` 推进到分片时间，而且必须与 `claimForCompletion` 原子串行，禁止晚到确认进入 `COMPLETING/COMPLETED` 或逃过稳定快照检查。服务不会信任 owner capability 的单次结果，而会在同一事务中与 tenant-global 权威快照逐字段核对。新建会话还要求 repository 同时实现 additive 的 `StagedResumableUploadSessionRepository` 与 `QuarantinableResumableUploadSessionRepository`，在任何 multipart 创建前确认支持带 owner/标记/过期条件的 staging 激活，以及 `ABORTING → QUARANTINED` 单调围栏；正式 HTTP 创建还会在任何 Storage/数据库副作用前要求 `CompletionRejectionResettableResumableUploadSessionRepository`，保证确定拒绝后能原子清空旧分片、刷新过期时间并恢复上传。缺少任一正式能力都会明确返回 `503`，而旧 Application API 仍保持二进制兼容。保存后服务会对全局 ID/key 与 owner ID/key 四路回读并再次校验。旧映射器丢弃 owner、错误改写 owner、owner capability 返回克隆对象或 `save` 静默 no-op 时，新会话不会发布给客户端；已提交 `ACTIVE` 的异常行会先在独立事务中 claim 并持久隐藏，随后才尝试隔离，因而隔离事务失败只会回到隐藏的 `ABORTING`，绝不会恢复 `ACTIVE`。只有围栏事务内的不可变身份和两条权威回读全部一致，才允许终止远端 multipart，任何提交未知、读取失败或矛盾状态都会保留远端状态并报告结果未知。升级自定义仓储应先完成 owner 字段迁移、分片/完成原子串行、staging/quarantine/reset 能力和合约测试，再开放续传入口。

`V024` 为既有会话增加可空的 `owner_id`，安装固定 owner 校验约束，并把 `QUARANTINED` 加入状态约束。迁移无法可靠推导历史会话的创建者，因此不会自动认领旧行；`owner_id IS NULL` 的历史会话对所有用户路径一律不可见，只能由系统清理或运维检查处理。升级前应暂停新建续传、让可完成的活动会话完成或明确放弃，然后停止全部旧版 HTTP 入口节点，再执行迁移并一次性启动新节点。新旧入口节点不得滚动混跑：旧节点不了解所有者边界，即使数据库已有新列仍可能绕过隔离。被遗留行占用的租户级幂等键在清理前会固定冲突，客户端应等待清理或使用新的幂等键重新开始，不能把旧会话转交给新用户。

紧急回滚不能直接重新开放旧版续传入口。必须先在网关关闭全部续传 HTTP/RPC 路由，停止新版本入口节点，逐条对账并终止或完成非终态 multipart，再按审计和保留策略清除 `fw_upload_session_part` 与 `fw_upload_session` 中所有仍可被旧代码读取的会话记录。只有确认会话表为空后，才可让旧节点重新承接续传流量；若不能清空，就应保持续传入口关闭，直到恢复具备所有者校验的新版本。回滚时保留 V024 列和约束，不以降级表结构代替安全处置。

生产宿主可将 `inspectStalledCompletionsAsSystem(limit)` 封装为只授予平台运维角色的只读接口；`inspectStalledCompletions(limit)` 则会从可信的当前租户和 `file:upload:maintenance` 授权上下文中查询，适合租户管理员。开发验收 API 对应 `GET /api/resumable-uploads/maintenance`，它只读取当前认证租户的会话并返回会话 ID、文件名、长度、过期时间、更新时间和最后错误，不会返回 `storageUploadId`、存储路径或对象凭据。

普通上传、文档初版与新增版本都会在落库前检查对象存储返回的长度；调用方提供 `contentHash` 时还会校验 SHA-256。正式续传 PUT 还要求恰好一个正数 `X-FileWeft-Part-Length`，以 `application/octet-stream` 流式传输；Application 对 Storage 实际消费字节计数并检查尾随内容，只有实际长度与声明完全相同才保存分片确认。完成前还要求分片号形成从 1 开始的连续序列、总长度等于会话完整长度。任一明确失配都不会落库相应文件、资产、确认点或 Outbox。该“明确校验失败”补偿不得扩展到数据库提交结果未知的场景；后者必须遵循上述持久化对账与保留策略。

`LocalStorageAdapter` 的 multipart 互斥只覆盖同一 JVM，包括共享 root 的多个 adapter 实例；同一目录不得由多个 JVM 或滚动双进程同时写入。多实例生产应使用 S3-compatible adapter，或由外部机制保证该目录始终只有一个写入者。

网关必须显式允许单个续传分片加少量协议开销，不能沿用 Nginx 的默认 1 MiB 请求体限制。开发编排的页面上传分片上限为 512 MiB，因此 `.docker/nginx.dev.conf` 将 `client_max_body_size` 设为 513 MiB，并对 `/fileweft/` 与 `/api/` 禁用代理请求体缓冲；生产至少应对 `/fileweft/v1/uploads/*/parts/*` 禁用请求缓冲，并根据实际分片上限、磁盘预算、对象存储约束和超时策略作出同等或更严格的配置。仅增大网关限制不会绕过 FileWeft 的续传会话授权、实际 body 长度校验和对象存储完整性校验。

## 下游连接器韧性

Starter 会为默认的交付解析器、兼容的单连接器同步路径和连接器 Doctor 共享同一个保护实例。它在进程内提供硬超时、有限并发/排队和每个连接器独立的熔断状态；Outbox 仍是唯一的重试调度者，不会在一次投递中隐藏式重放请求。

`fileweft.sync.connector-timeout-millis` 只限制一次 FileWeft 到下游的 RPC 调用；`fileweft.sync.source-access-url-ttl-millis` 则限制交给下游的对象存储访问 URL。两者不能复用：异步下游可能先确认接收、稍后才拉取文件。源 URL 有效期必须为正且不短于 RPC 超时，默认是 15 分钟（900000 毫秒）。异步平台应按其实际取文件队列的最长等待时间加上网络余量配置该值，同时保持预签名 URL 的最小必要权限；不要为了匹配一次 RPC 超时而把 URL 缩短到几十秒。

`delivery-profile` Doctor 会在发布前验证当前租户可见的每个交付档案及目标 `connectorId` 都能被 `DeliveryConnectorResolver` 解析。它只做解析，不调用下游连接器，也不写入交付记录；找不到连接器、解析器异常或租户没有可用档案都会作为 `ERROR` 给出明确的档案/目标证据。`connector` Doctor 则继续负责已解析连接器的实际健康检查。自定义解析器应让此查找保持快速、无副作用，远程策略读取需要自行设置超时和故障隔离。

发布或最终审批会先在数据库事务外调用 `DocumentDeliveryPlanner.prepare`，把档案和目标复制为不可变的租户快照并验证连接器 ID；随后短事务只推进生命周期、冻结目标和写入 Outbox。这样远程策略中心、配置服务或连接器注册表不会被放进数据库事务或审批行锁中。准备完成后发生的配置变化只影响后续发布，已冻结的目标继续以当次快照和各自的 Outbox 幂等键执行。

```yaml
fileweft:
  sync:
    connector-timeout-millis: 30000
    source-access-url-ttl-millis: 900000
    circuit-breaker-failure-threshold: 3
    circuit-breaker-open-duration-millis: 30000
    connector-max-concurrent-invocations: 16
    connector-invocation-queue-capacity: 256
```

连接器抛出异常、超过超时或返回 `RETRYABLE_FAILURE` 都会交给 Outbox 的退避重试，并计入该下游的连续失败阈值；达到阈值后，熔断器直接返回可重试结果而不触达下游。执行池饱和和线程中断同样会交给 Outbox 重试，但不会误开某个下游的熔断器，因为它们是本地容量信号。冷却窗口结束后只允许一个真实调用作为恢复探针。`PERMANENT_FAILURE` 不会打开熔断器，因为它通常表示下游已收到请求但拒绝了业务内容。已成功的其他目标不会被回滚。

若业务方替换了默认 `DeliveryConnectorResolver` 或 `DocumentSyncService`，应通过 `ConnectorResilienceRegistry.protect(connectorId, connector)` 获取连接器；否则该自定义入口会自行承担超时与熔断责任。

Doctor 的历史 `agent` 检查器仅为旧版兼容保留；`0.0.2` 默认运行时不注册它，Doctor 清单也不展示 Agent。显式启用旧版兼容模式时，该检查器只核对旧 Agent 登记状态，不调用 AI、产生费用或修改文档；这不构成当前产品支持。第三方 AI 连通性检查不得借普通 `DoctorChecker` 绕过 Agent 延后决策并冒充正式 Agent 能力。

恢复步骤：

1. 存储异常时，先运行 Doctor，核对对象引用和存储健康，再恢复连接并重试。
2. 下游异常时，检查交付记录的目标状态、错误和外部 ID；仅重排失败目标，避免重新推送已成功目标。
3. 怀疑租户越权时，立即停止该租户入口，按审计和操作日志中的 `tenantId`、资源 ID、`traceId` 排查，并确认仓储查询保持租户条件。
4. 仅在专用测试库上运行 `FILEWEFT_RUN_POSTGRES_TESTS=true`；该测试会重置 `public` schema，绝不能指向生产数据库。
