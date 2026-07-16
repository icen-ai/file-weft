# FlowWeft

FlowWeft 是面向企业的 Kotlin/JVM 文件智能与通用工作流基础设施。FileWeft 是
`0.0.x` 的历史产品名和已发布兼容命名空间。

当前实现提供 `core → spi → domain → application → persistence → starter → adapter → doctor` 基础链路、本地存储、诊断与可重试 Outbox Worker 基线。

> **1.0 产品决策：** `0.0.2` 与 `0.0.3` 不提供 Agent 仍是不可改写的历史
> 事实；后续开发已改为直接收敛 FlowWeft `1.0.0`，并交付重新设计的 Agent、
> 权限过滤检索、可脱离文件独立安装的通用工作流和完整产品控制台。旧
> `fileweft-agent`、`ai.icen.fw.spi.ai`
> ABI 及 V012/V026 只保留兼容，不改义复用。具体边界见
> [ADR 0001](docs/decisions/0001-flowweft-1.0-product-scope.md)、
> [FlowWeft 更名 ADR](docs/decisions/0002-flowweft-product-rename.md)、
> [通用 Workflow ADR](docs/decisions/0003-generic-workflow-platform.md) 和
> [1.0 交付总账](docs/flowweft-1.0-delivery-ledger.md)。

`.ai` 手册的基础能力对照、验证命令以及开源发布前仍需由项目所有者决定的事项见[实现对照与发布门槛](docs/implementation-status.md)。

当前稳定版为 `0.0.3`；发布证据、升级约束和兼容边界见[0.0.3 发布说明](docs/releases/0.0.3.md)。

FlowWeft 采用 [Apache License 2.0](LICENSE) 开源。安全漏洞请按[安全策略](SECURITY.md)私密报告至 `support@icen.ai`。

仓库根目录内置独立前端项目 [flowweft-docs](flowweft-docs/)，默认英文并可完整切换中文；启动 `fw-dev` 后可访问 `http://127.0.0.1:8088/docs/`。它沿用 `fileweft-dev/web` 的视觉语言，但独立维护、启动和测试。面向 AI 的最小接入、SPI 实现和生产运行约束见根目录 [SKILL.md](SKILL.md)。

## 项目接入

FlowWeft 的正式 Maven group 为 `ai.icen`，JVM 包名继续为 `ai.icen.fw`。`0.0.3`
发布的 19 个 `fileweft-*` 坐标在 1.0 继续发布实体 JAR，不因品牌更名失效。当前
`1.0.0-SNAPSHOT` 当前新增 `flowweft-retrieval-api/spi/runtime`、`flowweft-agent-api/runtime`、
`flowweft-workflow-api/spi/domain/runtime/persistence-jdbc`、`flowweft-migration-cli`、
`flowweft-adapter-dify` 与 `flowweft-adapter-oss` 十三个尚未发布过的规范坐标；不会
凭空发布对应的 `fileweft-{retrieval,agent,workflow}-*`，也不会复制旧模块为
`flowweft-core` 等重复
JAR。未来只有旧实体确实迁移坐标时才需要 relocation/薄兼容 POM，规则以
[ADR 0002](docs/decisions/0002-flowweft-product-rename.md) 为准。
`0.0.3` 已完成受发布门禁约束的稳定标签、12/12 CNB 发布流水线和全部 19 个坐标的
匿名远端回读，下面的稳定坐标可以消费：

```xml
<dependency>
    <groupId>ai.icen</groupId>
    <artifactId>fileweft-spi</artifactId>
    <version>0.0.3</version>
</dependency>
```

Spring Boot 3 项目通常同时引入运行时 Starter 和正式 HTTP Starter：

```kotlin
dependencies {
    // DataSource 与连接池由宿主选择；Spring Boot 默认组合可使用 starter-jdbc。
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("ai.icen:fileweft-spring-boot3-starter:0.0.3")
    implementation("ai.icen:fileweft-web-spring-boot3-starter:0.0.3")
}
```

FlowWeft Starter 不会传递引入 `spring-boot-starter-jdbc`、HikariCP 或其他连接池；数据库连接、池实现、容量与凭据轮换都归宿主所有。上面的推荐组合让 Spring Boot 根据 `spring.datasource.*` 创建唯一的 `DataSource`；已有自定义池的宿主可改为提供自己的 `DataSource` Bean。Boot 2 项目将 `fileweft-*` 兼容坐标中的 `boot3` 改为 `boot2`，宿主 JDBC Starter 坐标不变。

Boot 2.7 BOM 默认把 Kotlin 管理为 1.6.21，低于 FlowWeft 使用的 2.1.21；即使宿主全部用 Java 编写，也必须对齐运行时。使用 Spring Dependency Management 时设置 `extra["kotlin.version"] = "2.1.21"`，Maven 设置 `<kotlin.version>2.1.21</kotlin.version>`；使用原生 Gradle platform 时同时导入 `org.jetbrains.kotlin:kotlin-bom:2.1.21` 或采用等价的显式解析规则。不要期待普通 Kotlin BOM 覆盖 Boot 2 的 `enforcedPlatform`，并用 `dependencyInsight` 确认 `kotlin-stdlib` 最终为 2.1.21。

本仓库的 `v0.0.3` 发布身份和 Maven 坐标 `ai.icen:*:0.0.3` 固定对应提交 `dbf2a50fbca41e2ac5b5cf18bb44f9287c153637`。CNB 构建 `cnb-cl8-1jtgih45j` 已完成 12/12 流水线、发布令牌销毁、冷缓存消费者及 19/19 个 POM/JAR/`.sha256` 的独立匿名回读；后续默认分支继续前进不改变该稳定发布身份。本机验证仍可先执行 `.\gradlew.bat installReleaseToMavenLocal`，并在消费项目中启用 `mavenLocal()`。

完整本地发版入口是 `.\gradlew.bat releaseCheck --no-configuration-cache`；兼容入口 `releaseBundle` 保持相同的完整门禁语义。它们要求 `FILEWEFT_RUN_POSTGRES_TESTS`、`FILEWEFT_RUN_MYSQL_TESTS`、`FILEWEFT_RUN_KINGBASE_TESTS`、`FILEWEFT_RUN_RUSTFS_TESTS`、`FILEWEFT_RUN_DEV_E2E`、`FILEWEFT_RUN_DEV_UI_E2E` 全部为 `true`，且 `fw-dev` 已使用同一 `FILEWEFT_DEV_PLATFORM_SHARED_SECRET` 启动；成功后完整 Maven 仓库与发布压缩包分别位于 `build/repository/` 和 `build/release/`。仅 `releaseArtifactCheck` 会在不重复 JVM 和外部验收的情况下重建、验证制品，它只供同一提交已取得独立验证证据的流水线使用。CNB 在稳定标签事件中并行完成重型门禁，再由唯一发布流水线等待所有结果、发布并从远端冷缓存回读。任务分层和流水线维护方式见 [`.ci/README.md`](.ci/README.md)。

早期试推曾使用 `com.fileweft:*:0.0.1`，这些坐标已撤回且不得接入；与该历史试推相对的正式 0.0.1 发布使用完全不同的 `ai.icen:*:0.0.1`。源码与二进制包名统一为 `ai.icen.fw`，不会兼容或自动收养旧试推坐标写入的共享 Flyway history。迁移边界见[0.0.1 历史发布说明](docs/releases/0.0.1.md)。

### 数据库迁移隔离

FlowWeft 的兼容 Flyway 脚本只存在于专属 `classpath:ai/icen/fw/db/migration`，迁移历史只写入 `fileweft_schema_history`；不会再把脚本放入宿主通常使用的 `classpath:db/migration`，也不会与宿主的 `flyway_schema_history` 共用版本号。Spring Boot 宿主自己的 `spring.flyway.*` 配置与 FlowWeft 迁移相互独立，不要把 FlowWeft 专属路径追加到宿主 Flyway locations。

当前 `0.0.3` 版本线为 PostgreSQL、MySQL 与 KingbaseES 各自提供完整的 29 个迁移 V001–V029；V029 只追加可空的工作流提交者证据，不改写任何既有迁移或 checksum。历史边界保持不变：`v0.0.2` 的三套 V001–V028 是不可改写的发布资源，`v0.0.1` 只发布了 PostgreSQL V001–V025；MySQL、KingbaseES 和 V026–V028 首次进入 `v0.0.2` 发布合同。MySQL 支持范围仅是原生 MySQL 8.x 的 8.0.17+，当前实库证据为 8.0.46，不包含 MariaDB 或 MySQL 9。V029 上线前必须停止 submit/approve/reject/withdraw 写入和旧节点，完成迁移后再启动 `0.0.3` 节点；应用回滚保留 V029 列与已有提交者证据。

`FlywayMigrationRunner` 已验证 Spring Boot 2 管理的 Flyway 8.5.13、FlowWeft 自身的 9.22.3，以及 Spring Boot 3 管理的 11.7.2。Boot 3 下 `flyway-core`、`flyway-mysql`、`flyway-database-postgresql` 必须同为 11.7.2。Kingbase Starter 默认只包装 Spring Boot 已选择给 Flyway 的 DataSource，应用主 DataSource 仍是真实 Kingbase；只有宿主具备并验证了等价集成时，才可关闭 `fileweft.persistence.kingbase-flyway-compatibility-enabled`。

V027 普通建索引与 MySQL V028 全表字符集/排序规则转换都需要受控维护窗口、停写和磁盘预算。V028 后 tenant、用户及所有 opaque ID 按 Unicode 标量/文本值精确比较，大小写、重音和尾空格都不折叠；应用回滚也必须保留 NO PAD 的 `utf8mb4_0900_bin`，不得退回 `utf8mb4_bin`、`*_ci` 或其他 PAD SPACE/折叠比较。锁、复制、前进重试与 schema 回滚边界见[生产部署与恢复](docs/production-operations.md#数据库迁移与查询索引)。

迁移模式默认是 `disabled`；宿主应同时显式选择 DataSource 当前 schema、迁移模式和沿用的兼容 `fileweft` schema：

```yaml
spring:
  datasource:
    url: jdbc:postgresql://127.0.0.1:5432/app?currentSchema=fileweft

fileweft:
  persistence:
    migration-mode: migrate # migrate | validate | disabled
    schema: fileweft
    create-schema: false
```

- `migrate`：由 FlowWeft 使用专属 history 校验并应用尚未执行的迁移；只有该模式可以按显式配置创建 schema。
- `validate`：只读校验 schema、专属 history 与迁移状态；缺少 history、存在 pending migration、校验不一致或发现旧 FileWeft 共享 history 记录时启动失败，不会创建或修改数据库对象。
- `disabled`：FlowWeft 不执行迁移或校验，数据库变更完全由宿主 DBA/发布系统负责。

`fileweft.persistence.schema` 是安全断言，不是隐式切换开关；它必须与该 DataSource 执行 `SELECT current_schema()` 的结果相同。所有 Migration Job、API 和 Worker 角色都必须保持一致。使用共享 `public` schema 时，应让 JDBC 当前 schema 为 `public` 并配置 `schema: public`；使用独立 schema 时，推荐像上例一样通过 PostgreSQL JDBC `currentSchema` 明确 search path。只有 `migrate + create-schema=true` 允许目标尚不存在，此时 JDBC search path 仍须先指向该名称，使创建前的 `current_schema()` 为 `null` 而不是其他 schema。宿主存在多个 DataSource 时，Starter 不会根据 `@Primary` 猜测迁移目标，必须显式提供绑定正确 DataSource 的 `FlywayMigrationRunner`。

首次建立专属 history 时，runner 只会在确认不存在旧 FileWeft history、`fileweft_schema_history` 和任何已知 FileWeft 业务表后，写入版本 `0` 的命名空间初始化标记；随后仍会逐个执行全部 `V001` 及之后的脚本，不会跳过任何业务迁移。发现旧记录、失败记录或无 history 的 FileWeft 表时会直接失败，绝不会把它们当作版本 `0` 收养。

生产环境建议预建 schema 并保持 `create-schema=false`。`validate` 运行账号至少需要目标 schema 的 `USAGE`、FlowWeft 业务读取权限及 `fileweft_schema_history` 的只读权限；若 FlowWeft 与宿主共享 schema 且存在默认 `flyway_schema_history`，还需要读取该表以执行旧记录拦截。`migrate` 应使用受控迁移账号，额外授予建表、索引、约束等 DDL 权限；只有显式创建 schema 时才授予建 schema 权限。推荐由宿主提供的一次性 Migration Job 或受控进程执行 `migrate` 并在成功后自行退出，再以 `validate` 启动 API/Worker；Starter 不提供迁移完成后自动退出的通用 Job 入口。旧 `com.fileweft:*:0.0.1` 试推可能曾把 FileWeft 记录写入宿主默认 history；历史正式坐标 `ai.icen:*:0.0.1` 不会猜测或自动收养这些记录。旧库升级必须先停止全部 API/Worker、完成可恢复备份，并由 DBA 严格核验实际成功版本、脚本名、checksum、schema 与宿主迁移是否冲突；在形成经评审的人工转换或数据迁移方案前不得滚动升级，也不得使用盲目 baseline、复制/删除 history 行来绕过校验。完整步骤见[生产部署与恢复](docs/production-operations.md#flowweft-迁移命名空间与旧库升级)。

## 构建要求

- 构建运行时：JDK 17+（当前验证环境为 JDK 21）
- 核心及除 Spring Boot 3 Starter 与开发验收应用外的模块：产物字节码兼容 Java 8，`check` 会额外在 Java 8 运行时执行其测试
- Spring Boot 3 Starter：产物字节码兼容 Java 17

## 验证

```powershell
.\gradlew.bat fastCheck
.\gradlew.bat compatibilityJava8Check
.\gradlew.bat compatibilityJava17Check
```

`fastCheck` 是默认开发门禁：运行当前构建 JVM 上的单元、契约和 Context 测试及静态质量检查，不访问外部系统，也不展开跨 JDK 矩阵。根 `check` 作为兼容入口继续包含 Java 8 基线回归；本地需要完整矩阵时运行 `compatibilityCheck`。五条 `compatibilityJava8Check`、`compatibilityJava11Check`、`compatibilityJava17Check`、`compatibilityJava21Check`、`compatibilityJava25Check` 可独立执行并由 CNB 并行调度。Gradle 工具链会自动取得缺失 JDK，首次执行可能需要下载。

普通测试不再按环境变量暗中启用外部集成测试。PostgreSQL、MySQL 8、KingbaseES、RustFS、Dev API 与 Playwright 各自有显式、失败关闭且不复用旧结果的任务；完整命令见 [`.ci/README.md`](.ci/README.md)。仓库默认最多并行运行 2 个 `Test` 任务，可通过 `-Pfileweft.test.maxParallelTasks=N` 调整，不再把全仓测试任务串成一条长链。

依赖版本通过 `gradle/libs.versions.toml` 管理，所有配置启用依赖锁定。`verifySbom` 生成并校验全仓 CycloneDX JSON/XML 物料清单，适合在发版流水线中归档。

## 本地开发

启动仅包含 PostgreSQL 与 RustFS 的基础服务：

```powershell
docker compose -f .docker\docker-compose.dev.yaml up -d postgres
```

## 开发验收台

仓库内的 `fileweft-dev` 是开发专用的可运行验收应用，不会被核心、领域或 SPI 依赖。它真实装配正式的 `fileweft-web-spring-boot3-starter`，控制台 Nginx 将 `/fileweft/` 代理到开发 API；同时通过真实 PostgreSQL、RustFS（S3 兼容）、独立 `fw-dev-worker`、Outbox Worker 和独立 HTTP 下游平台覆盖上传、版本、审批、发布、多下游交付、审计与 Doctor。Compose 中 API 节点不消费异步队列，后台执行由 Worker 容器承担，用于验收生产推荐的角色拆分。

依赖版本和 SHA-256 校验已提交到仓库；升级依赖时请遵循[可复现构建说明](docs/reproducible-builds.md)。

启动完整编排：

```powershell
$env:FILEWEFT_DEV_PLATFORM_SHARED_SECRET = ([guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N'))
docker compose -f .docker\docker-compose.dev.yaml up -d --build --wait
```

若本机保留的是迁移隔离前的旧 Dev volume，新版本会按设计拒绝自动收养旧 `flyway_schema_history`。确认本地 PostgreSQL 与 RustFS 测试数据均可删除后，可先执行 `docker compose -f .docker\docker-compose.dev.yaml down -v`；该命令会永久删除 `fw-dev` 的数据库和对象存储 volume。需要保留任何数据时不得执行，必须按生产旧库流程先停机、备份并制定人工迁移方案。

`FILEWEFT_DEV_PLATFORM_SHARED_SECRET` 是开发 API/Worker 与独立下游模拟器之间的系统凭据，至少 32 个字符且每次新建本地编排时应生成新的值。它不会下发给浏览器，也不能替代用户登录令牌。Compose 会拒绝未设置该变量的完整编排，避免意外以公开固定凭据启动模拟下游。

| 服务 | 地址 | 用途 |
| --- | --- | --- |
| 验收控制台 | http://127.0.0.1:8088 | 登录、文档流转、审批、Doctor、下游镜像 |
| FlowWeft 开发 API | http://127.0.0.1:8080 | 验收 API |
| 模拟下游平台 | http://127.0.0.1:8081 | 接收发布同步并验证预签名 S3 下载 |
| RustFS 控制台 | http://127.0.0.1:9001 | S3 开发对象存储 |

预置开发用户：

| 用户名 | 密码 | 角色 |
| --- | --- | --- |
| `admin@alpha` | `dev-admin` | 管理员 |
| `editor@alpha` | `dev-editor` | 编辑者 |
| `reviewer@alpha` | `dev-reviewer` | 审批者 |
| `viewer@alpha` | `dev-viewer` | 只读者 |

开发主应用关闭 Spring Boot 默认 Flyway 扫描，显式使用 FlowWeft `migrate` 模式和专属 history 管理 `fileweft_dev` schema；模拟下游平台 profile 显式覆盖为 `migration-mode=disabled`、空的历史 FileWeft schema 和 `create-schema=false`，继续由自己的专属 runner 管理 `fileweft_dev_platform`。两者不会读取或覆写 `public` schema 的测试数据。预置账号和密码只适用于本地开发容器，禁止用于任何生产环境。

模拟下游平台只绑定宿主机 `127.0.0.1`，并且不再经控制台 Nginx 代理暴露 `/platform/`。除健康检查外的所有平台接口都要求该系统凭据；控制台的“下游镜像”通过已登录的 FlowWeft API 服务端转发读取，因此浏览器无法获得平台密钥。平台只允许从 `rustfs` 容器拉取 HTTP(S) 文件 URL，拒绝 URI 用户信息、跳转和超过 512 MiB 的响应。需要演练其他受控存储主机时，可显式设置 `FILEWEFT_DEV_PLATFORM_ALLOWED_DOWNLOAD_HOSTS`（逗号分隔）和 `FILEWEFT_DEV_PLATFORM_MAX_DOWNLOAD_BYTES`，不要把任意公网或内网地址加入允许列表。

审计将用户 ID 视为不透明字符串，并同时保存操作发生时的显示名快照。接入方可在 `UserRealmProvider` 中将 Long、Int、UUID 或其他身份系统 ID 转为字符串；FlowWeft 不维护用户表，也不会在查询历史审计时反查并改写原有操作者名称。

验收控制台默认英文，可切换完整中文。其“角色验收实验室”内置 TXT、Markdown、CSV、JSON 文件样例：拥有创建权限的用户可将它们上传为真实 RustFS 草稿；审批、Outbox 与只读路线则只展示当前用户经服务端授权的操作控件。控制台的创建、改名、新增版本、授权下载、审批待办与文档审批历史、当前代次同步状态、明确的交付/撤回重排、受权审计日志、插件清单、进程 liveness、即时/异步/系统 Doctor，以及提交/审批/驳回/修订/下线/恢复/归档均走 `/fileweft/v1` 正式协议。旧 `/api/documents/{documentId}/doctor`、`/api/documents/{documentId}/doctor/tasks` 与 `/api/documents/{documentId}/logs` 已撤销并保持 `404`，防止绕过正式目录授权、双权限检查和脱敏门面；通用 Dev 文档详情保留兼容字段名，但审计与操作列表固定为空。

运行完整 Compose 验收回归：

请在启动 Compose 的同一终端会话中执行，或显式恢复启动时使用的同一个 `FILEWEFT_DEV_PLATFORM_SHARED_SECRET`；重新生成值会使验收客户端与已运行的平台凭据不一致。

```powershell
$env:FILEWEFT_RUN_DEV_E2E='true'
.\gradlew.bat :fileweft-dev:devApiAcceptanceTest --no-configuration-cache
```

该测试会创建唯一编号文档，验证编辑者上传和提交、单人审批与双人会签、管理员处理 Outbox、下游平台下载 RustFS 对象，并覆盖可选下游失败、必达下游人工恢复、正式 Doctor 的即时检查、幂等任务重放、Worker 报告、角色拒绝、双租户隔离和系统诊断脱敏。默认数据库连接为本机 `5432`，非默认映射可通过 `FILEWEFT_DEV_E2E_DB_URL`、`FILEWEFT_DEV_E2E_DB_USERNAME` 和 `FILEWEFT_DEV_E2E_DB_PASSWORD` 覆盖。

### 浏览器验收回归

`fileweft-dev/web` 内置锁定版本的 Playwright 测试。它只针对本地 Compose 验收台，不会访问生产地址；覆盖完整中文切换、角色控件过滤、真实内置样例上传/提交、单人与双人审批、驳回修订、直接创建、重命名、版本、授权下载、目录移动、任务处理、下游镜像、断点续传与 Alpha/Beta 租户文件可见性隔离。Doctor 另有正式协议浏览器场景，验证即时卡片、异步任务轮询、管理员系统诊断、双租户/角色拒绝、响应与 DOM 脱敏，并断言浏览器不会回退调用 `/api/**` Doctor。设置下述开关后会执行完整浏览器回归。

首次执行先安装锁定的 Node 依赖和 Chromium：

```powershell
Push-Location .\fileweft-dev\web
npm ci
npx playwright install chromium
Pop-Location
```

在完整 Compose 编排健康后执行：

```powershell
$env:FILEWEFT_RUN_DEV_UI_E2E='true'
.\gradlew.bat :fileweft-dev:devUiE2e --no-daemon --no-configuration-cache
```

也可在 `fileweft-dev/web` 中直接运行 `npm run test:e2e`。需要测试其他本地地址时，设置 `FILEWEFT_DEV_UI_BASE_URL`；测试报告会输出到被 Git 忽略的 `playwright-report/`。

## 宿主文件树与目录权限

FlowWeft 不拥有业务系统的目录，也不会把目录名称写入对象存储路径。宿主实现 `DocumentCatalogProvider`，以租户内不透明字符串 ID 返回文件夹；选中的 ID 仅以 `catalog.folder-id` 元数据绑定到文件资产。这样同一个 `inbox` ID 可以在不同租户中独立存在，目录改名或移动也无需迁移 FlowWeft 数据。

新版 SPI 可接收 `DocumentCatalogAccessRequest`，其中的租户、当前用户和操作意图由 FlowWeft 的可信上下文生成。目录 ACL 应由宿主在该方法中实施，不能信任前端传入的租户或用户。为兼容现有实现，旧的 `listFolders(tenantId)` 仍有效；需要按用户过滤时覆写请求版本。Starter 自动生成 `DocumentCatalogAccessService` 时要求恰好一个 `DocumentCatalogProvider`；即使候选之一标记了 `@Primary`，多个未聚合的目录安全边界也会让启动明确失败。确需组合多个目录源时，宿主必须显式提供一个负责聚合与 ACL 的 `DocumentCatalogAccessService`，且访问服务本身仍只能有一个。创建草稿前先校验 `document:create` 与目录权限，再将返回 ID 写入 `DocumentCatalogBinding.METADATA_KEY`；文件树移动则要求 `document:edit` 和目标目录 ACL，目标目录解析后还会再次验证源目录，最后只更新资产元数据并记录审计，不移动对象、不改变生命周期或重新推送下游。

逐请求 `folderId`、canonical ID 约束、按租户动态路由、多个 OA/ERP 目录聚合及远程 ACL 缓存要求见[目录动态路由实现规范](docs/plugin-development.md#动态目录与组合系统实现规范)。

目录模式下，改名、新版本以及提交、审批、修订、发布、下线、恢复和归档都会先在短事务中冻结文档到资产的原始目录绑定，在事务外执行当前动作授权和源目录 `BROWSE` ACL，外部审批路由或交付策略返回后再次验证，最后按 document → asset（审批再到 workflow）的锁序复核绑定。权限撤销、目录移动竞态或跨租户恶意仓储结果都会在业务写入和审计前失败。自定义资产仓储若没有 `FileAssetMutationRepository` 行锁能力，Starter 不会装配目录安全的 mutation/lifecycle 门面；目录模式的 Controller 或宿主入口必须把该能力缺失视为不可用，不能回退调用租户级底层服务。相同条件下的 `CatalogDoctorChecker` 会在异步 Doctor 中校验绑定目录仍属于该租户；它不模拟用户或检查用户 ACL，因此不会绕过宿主权限模型。

为保持已有嵌入代码兼容，`DocumentCommandService`、`DocumentReviewWorkflowService`、`PublishDocumentService`、`OfflineDocumentService`、`RestoreOfflineDocumentService` 和 `ArchiveDocumentService` 仍是无目录宿主可直接使用的租户级原语。启用目录后直接注入这些原语不会自动获得目录 ACL；官方 Controller 与宿主自定义入口必须改用目录 mutation/lifecycle 门面。正式生命周期 HTTP 通过统一解析器严格选择 flat 或 catalog-aware 能力；目录能力不完整时固定返回 `503`，不会降级到 flat。

正式写接口使用 `V020` 持久化请求幂等记录：原始 `Idempotency-Key` 只在内存中校验，数据库仅保存租户作用域摘要，并把当前用户、动作、资源/子资源和 typed-command 指纹绑定到第一次成功结果。`revise`、`publish`、`offline`、`restore`、`archive`、`submit` 以及工作流任务 `approve/reject` 均已提供 flat/catalog-aware Application 边界和 Boot 2/3 正式 HTTP；每次重放仍重新执行当前授权和目录 ACL，审批路由与交付策略只在未命中后于事务外解析。最终事务采用 idempotency → document → asset → workflow 的锁序，使领域状态、审计、Outbox 与稳定回执同时提交；Controller 不得另包外层事务。

正式审批读取提供 `GET /fileweft/v1/workflows/tasks` 与 `GET /fileweft/v1/documents/{documentId}/workflows`。待办只返回当前可信用户已分配或尚未分配、且文档与工作流仍处于待审批状态的任务，并要求 `document:audit + document:read`；文档审批历史属于文档详情的一部分，只要求 `document:read`。两条路由都会执行目录可见性校验。公共 DTO 不暴露受理人 ID、审批评论、操作者、租户或存储信息，分页游标只封装不可变的 `createdTime + id` 排序键。

## 多下游交付

发布不再把“所有下游”折叠成一个同步结果。接入方通过 `DocumentDeliveryProfileProvider` 为租户提供可选交付档案；每个档案由多个 `DocumentDeliveryTargetDefinition` 组成，目标使用不透明字符串 `id`、`connectorId` 和可选 `ownerRef`，并声明为 `REQUIRED` 或 `OPTIONAL`。`DeliveryConnectorResolver` 将 `connectorId` 解析为实际的 `FileConnector`，不把 Spring 或厂商 SDK 泄漏到 SPI。

审批或直接发布时，FlowWeft 在同一业务事务中冻结目标快照，并为每个目标写入独立 Outbox 事件。目标记录含状态、外部 ID、失败原因与重试次数，因此一个目标重试不会重复推送已成功的目标。

- 全部必达目标成功：文档成为 `PUBLISHED`。
- 必达目标重试中或失败：文档显示 `SYNC_ERROR`，Outbox 继续按策略重试；恢复成功后自动回到 `PUBLISHED`。
- 可选目标失败：文档仍可 `PUBLISHED`，但交付记录保留“待处理”、责任引用和错误原因。
- 不执行默认分布式回滚：成功下游不会因为另一个下游失败而被自动删除。删除/撤回必须由业务显式发起，避免误删已生效的外部记录。
- 当前 Outbox 已失败后，拥有 `document:delivery:retry` 权限的管理员可只重排该目标；这同时覆盖终态投影前进程退出而遗留的 `PENDING/RETRYING` 状态。原目标 ID 继续作为连接器幂等键。

文档下线或归档属于显式撤回：系统会在同一业务事务中为每个已经成功交付的目标冻结一条 `document.delivery.target.removal.requested` Outbox 事件。外部 `FileConnector.remove` 调用仍只在 Worker 中执行；撤回拥有独立于“交付成功”的 `PENDING`、`RETRYING`、`SUCCEEDED`、`FAILED` 状态和重试次数，因此不会把“曾经交付成功”误显示为“已撤回”。暂时性失败由 Worker 自动重试，永久失败可由拥有同一 `document:delivery:retry` 权限的管理员只重排该目标的撤回事件。连接器应返回外部 ID；若历史连接器未返回，框架会以稳定的文档 ID 作为撤回调用的回退外部键，连接器实现必须保证该键可幂等处理。

需要更新已发布内容时，使用受控路径：`PUBLISHED → OFFLINE → document:restore → DRAFT → 新版本 → 审批 → 发布`。恢复草稿前，当前交付代次不得有 `PENDING` 或 `RETRYING` 同步，且每个已成功交付目标都必须已撤回成功；这样不会在新版本已发布后让旧版本的迟到请求覆盖下游。每次审批进入发布都会递增交付代次，并为同一目标创建新的交付快照；旧代次的迟到 Outbox 事件会被安全忽略。若下线恰好发生在一次下游调用进行期间，随后返回的成功结果会立即补发撤回事件。

Starter 可直接使用单连接器兼容默认档案：当应用或插件只提供一个 `FileConnector` 且未声明 `profiles` 时，Starter 会创建 `default` 档案，并把这个唯一连接器兼容解析为 `fileweft.sync.connector-name`（默认也是 `default`）；连接器 Bean 不必命名为 `default`。存在多个连接器时，必须在档案目标中显式填写对应的 `connector-id`，或替换下列 SPI 实现以接入租户自己的策略中心：

```yaml
fileweft:
  sync:
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

Doctor 会额外运行 `delivery-profile` 检查：它按当前租户读取全部可用档案，逐个验证目标的 `connectorId` 能否解析，并把档案、目标、责任引用与未解析项写入诊断证据。该检查不调用 `FileConnector`、不写入任何业务数据；连接器实际连通性仍由 `connector` 检查负责。这样，漏注册连接器或租户档案为空会在发布前的诊断中被明确标出，而不是等到用户提交发布事务时才失败。

开发验收台预置 `regulated`（合规、协作必达；搜索可选）和 `internal`（协作必达）两个档案；文档检视器会展示每个目标的责任组、状态、错误和重试次数，并按服务端权限显示人工重试控件。

## 可插拔审批路由

`DocumentReviewRouteProvider` 是审批人和会签策略的 SPI。它接收当前租户、文档的不可变摘要、提交人和可选的请求审批人，返回工作流类型和一个或多个审批任务；任务中的用户 ID 与审计用户 ID 一样都是不透明字符串。路由解析发生在 FlowWeft 数据库事务之外，解析完成后系统会在最终事务中重新读取并确认文档没有变化，避免远程策略调用占用事务连接。

默认路由 ID 为 `default`，保持原来的兼容行为：创建一个指定审批人（或未指派）的 `DOCUMENT_REVIEW` 任务。路由返回多个任务时属于并行会签：所有任务批准前文档维持 `PENDING_REVIEW`，不会创建发布或下游交付事件；任一任务驳回则结束该工作流。已有接入无需迁移。宿主可将实现注册为 Spring Bean 或从 `FileWeftPlugin.reviewRouteProviders()` 贡献，并配置默认路由：

```yaml
fileweft:
  workflow:
    default-review-route-id: finance-dual-control
```

路由提供者若查询外部组织、角色或策略系统，必须自行实现超时、重试、幂等和 Doctor/监控记录；它不应在其调用路径中持有 FlowWeft 的数据库事务。开发验收台提供 `dual-control` 路由，Alpha 和 Beta 两个租户均会分配“审批者 + 管理员”两个任务；编辑者在草稿操作区可选择标准审批或双人会签，以验证第一人通过后仍未发布的行为。

## 断点续传上传与完整性

`ResumableUploadService` 面向大文件和不稳定网络提供持久化 multipart 会话。宿主服务可按下列顺序封装自己的 HTTP 或 RPC API：

1. 以稳定的调用方幂等键执行 `start(StartResumableUploadCommand)`。
2. 用 `uploadPart(sessionId, partNumber, contentLength, stream)` 逐片上传；每片确认后可安全刷新页面或重试。
3. 用 `inspect(sessionId)` 从服务端读取已确认分片，再继续缺失分片。
4. 用 `complete(sessionId)` 幂等创建 `FileObject`、`FileAsset` 与 `file.uploaded` Outbox 事件；放弃时用 `abort(sessionId)`。

会话和分片的用户操作同时绑定可信身份上下文中的租户与用户 ID；用户 ID 是区分大小写且不做 trim/大小写折叠/Unicode 归一化的不透明字符串，不能由 HTTP 参数、Header、metadata 或前端检查点指定。公共接入约束是：非空、最多 256 个 UTF-16 code unit、首尾无 Unicode whitespace，并且不含 ISO control 或受禁 Unicode format 字符；数字型宿主 ID 应先由宿主稳定转换成字符串。同租户的其他用户即使拥有相同上传权限，也不能查看、写分片、完成或终止该会话；不存在、跨租户、非所有者及迁移前无所有者的会话统一表现为资源不存在。跨租户、跨用户扫描只存在于受控 Worker 的过期清理，不接受任何前端租户或用户参数。底层对象存储 upload ID、存储路径、会话所有者及凭据不得交给前端；开发验收台只把安全的会话视图保存在浏览器本地。新会话首次持久化为不可见 staging，只有 owner、幂等键、存储身份和未过期条件全部核验后才原子激活；完成中的会话不被自动删除，因为对象存储可能已接受完成请求；持久化映射不可信时会话进入不可逆的 `QUARANTINED` 状态，即使远端 multipart 已安全终止也保留数据库证据且永不重新暴露给用户。

普通上传、文档初版、新增版本与续传完成都会验证对象长度；调用方提供 `contentHash` 时还会验证 SHA-256。校验失败会补偿删除远端对象，绝不会写入不完整的文件、文档或 Outbox 状态。数据库明确回滚且权威回读确认没有任何持久化引用时，服务才会执行同类补偿；提交结果未知时会先对账，完整匹配则返回当前已提交结果，回读失败、无记录、部分引用或冲突引用则保留对象并抛出 `ApplicationTransactionOutcomeUnknownException`，避免把已经提交但确认丢失的文件删掉。所有会写对象存储的 Application API 必须作为顶层应用边界调用，Controller、`@Transactional` 方法或宿主事务不得在外层包裹它们；官方 JDBC transaction 会在任何 ID、仓储或 Storage 副作用前拒绝已激活的同数据源事务。生产 Worker 的 TTL、批量清理和运行角色配置见 [生产部署与恢复](docs/production-operations.md)。

## 持久化后台任务与 Doctor

`fw_task` 是独立于 Outbox 的通用后台任务表，适用于 Doctor、AI、索引、转码等可恢复工作。它采用 PostgreSQL `SKIP LOCKED` 领取任务，并使用带所有者的过期租约：Worker 宕机后，超过租约的 `RUNNING` 任务会重新变为可领取状态。处理器通过 `FileWeftTaskHandler` SPI 注册，必须以任务 ID 实现幂等；框架统一处理退避重试、重试耗尽和本地失败投影。

Doctor 提供三条受控路径：即时文档检查用于交互式请求；异步检查以持久化幂等键排队，在请求时完成 `document:doctor`、文档读取与目录可见性授权，随后由无用户会话的后台 Worker 仅执行只读技术检查；系统诊断则要求独立的 `system:doctor:read`。异步结果写入 `fw_doctor_record`，因此运营者可保留诊断历史，而不会让后台线程绕过用户权限。开发验收台通过正式 v1 DTO 以卡片展示即时结果、轮询任务及系统状态，不读取 `report_json`、原始 evidence、租户标识或基础设施细节；仅管理员可查看系统诊断和手动处理任务队列。

## 操作日志与请求追踪

每一条由 `AuditTrail` 写入的业务审计记录，都会在同一个应用事务内镜像为 `fw_operation_log`。两者共享同一个不可变 ID，并保留租户、资源、动作、外部用户 ID、显示名快照、JSON 明细与发生时间；操作日志额外保存可选的 `trace_id`。因此审计语义保持兼容，而运维系统可以按资源或 Trace 聚合操作证据。

`TraceContextProvider` 是不绑定日志框架或链路追踪厂商的 SPI。Starter 默认提供安全的空实现；接入 OpenTelemetry、Micrometer Tracing、消息头或其他宿主追踪系统时，只需提供该 SPI Bean。开发验收应用会接受格式受限的 `X-Trace-Id`（否则生成新的 ID），在响应中回显，并在文档检视器的“操作追踪”区域展示。

Outbox 将 Trace 作为独立的 `trace_id` 持久化字段，而不是混入业务 payload。Worker 在处理时若宿主提供 `TraceContextScope`，会暂时恢复事件 Trace、运行连接器与审计投影，随后恢复 Worker 原上下文；因此重试和多下游交付仍可关联到最初的发布/审批操作。接入方使用 OpenTelemetry 等追踪系统时，应同时实现 `TraceContextScope`；未实现时仍安全运行，只是不建立跨异步上下文。

## 受控文件下载

`DocumentDownloadService` 是文档内容的唯一应用层入口：它在读取对象前按当前租户执行 `document:download` 授权，校验版本属于文档，再从对应的 tenant-scoped 文件对象流式读取。接入宿主目录后，默认 Starter 还会在数据库事务外冻结当前用户的可信目录范围，并在解析文件的同一个短事务内按租户、文档和目录再次复核；隐藏目录和空范围统一表现为文档不存在，且不会打开对象存储或写入下载审计。下载意图以 `document:download` 写入审计和操作日志；Storage URL 不会作为前端 API 返回值，因此前端权限不能绕过 FlowWeft 的服务端授权。

正式 Web Starter 提供 `/fileweft/v1/documents/{documentId}/content` 与 `/fileweft/v1/documents/{documentId}/versions/{versionId}/content`。成功响应使用 `attachment`、RFC 5987 文件名、安全 ASCII fallback、内容类型白名单、`nosniff` 与 `private, no-store`；只有对象存储已报告并与持久化值核对的长度才会成为 `Content-Length`。首版协议不承诺断点续传：任何 `Range` 都在打开下载前固定返回 416，显式 `HEAD` 固定返回 405 和 `Allow: GET`，且不返回 ETag、`Accept-Ranges`、内容哈希或存储地址。开始流式输出前的失败使用固定 JSON 外层；开始输出后的 I/O 中断只关闭流并终止响应，不会把 JSON 拼到二进制尾部。

开发验收 API 仍保留当前版本和指定历史版本的流式兼容端点。Dev 控制台只为服务端下发了 `document:download` 的角色显示下载控件；按钮已经使用带 Bearer Token 的正式 `/fileweft/v1` 授权下载请求而非裸 S3 地址，便于演示下载授权与跨租户拒绝。

## Starter 租户与存储边界

Spring Boot Starter 不会静默推断租户，也不会静默在当前工作目录创建本地文件。多租户生产宿主必须提供可信、逐请求解析的 `TenantProvider`；多节点 API/Worker 必须提供共享且持久的 `StorageAdapter` Bean，或安装唯一的存储插件。客户 Bean 优先于插件，插件优先于显式本地适配器。

只有经过评审的固定单租户、开发或单节点部署，才应同时显式选择 fallback 并填写不可为空的值：

```properties
fileweft.default-tenant-enabled=true
fileweft.default-tenant-id=tenant-a
fileweft.storage.local-enabled=true
fileweft.storage.local-root=/var/lib/fileweft
```

未提供正式 SPI 实现且未显式启用对应 fallback 时，应用会在启动期给出包含修复属性的错误，而不会带着 `default` 租户或相对本地目录继续运行。即使显式启用，系统 Doctor 仍会把固定租户或本地文件系统标记为 `WARNING`，提醒部署者在扩容或切换多租户前完成替换。

运行 PostgreSQL 集成测试：

```powershell
$env:FILEWEFT_RUN_POSTGRES_TESTS='true'
.\gradlew.bat postgresIntegrationCheck --no-configuration-cache
```

> 集成测试会重置开发库的 `public` schema，只能连接专用开发/测试数据库，不能指向任何生产数据库。
