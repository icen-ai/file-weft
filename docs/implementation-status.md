# FileWeft 实现对照与发布门槛

本文记录仓库相对 `.ai/` 实施手册的可验证范围，避免将“基础能力已验收”和“已具备完整开源发布治理”混为一谈。

项目已撤回历史试推坐标 `com.fileweft:*:0.0.1`，不得用于新接入或新的生产部署。当前实现状态描述 `v0.0.2` 发布合同；它与旧试推坐标不属于同一发布物，正式 `ai.icen:*:0.0.1` 只作为历史升级边界保留。

下文的 V026 决策证据、V027 Worker 领取索引、V028 MySQL NO PAD 精确文本比较安全迁移与正式断点续传 HTTP 资源均属于 `v0.0.2` 发布合同。`ai.icen:*:0.0.2` 是否已经可消费，必须以受保护标签流水线全部成功且远端公开仓库完成匿名冷缓存解析为准；源码、预期标签或本地制品本身不能证明远端发布完成。

> **最高优先级 Agent 决策：** `0.0.2` 不提供 Agent 产品能力。Agent
> 重新设计无限期延后，最早只能在 `1.0.0` 正式发布后重新评估，且不承诺
> 在 `1.x`、下一版本或任何版本交付。现有 `fileweft-agent`、Agent SPI/公共
> ABI 以及 V012/V026 中相关表和列仅为源码、二进制和数据库兼容保留；默认
> 运行时、Doctor/插件清单、正式 HTTP 与 Dev 均不暴露。下表中的历史 Agent
> 阶段不再是交付状态或 Roadmap 承诺。

## `.ai` 十阶段基础能力

| 阶段 | 已交付能力 | 验证方式 |
| --- | --- | --- |
| Core | 标识、上下文、结果、错误与 Outbox 模型，不依赖 Spring 或厂商 SDK | Core 单元测试、分层依赖与 Java 互操作语法门禁，以及 Java 8/11/21/25 运行时矩阵 |
| SPI | 身份、授权、租户、存储、连接器、交付、任务、诊断、审批路由、宿主目录与低基数 Gauge 契约；Agent 契约仅作兼容保留 | SPI 模型与合约测试、Java 互操作语法门禁 |
| Domain | 文件、文档版本、生命周期、工作流、审计与操作日志领域规则；受控恢复草稿与发布代次；新决策不可变操作者 ID/名称/时间快照 | Domain 单元测试与旧 Java/Kotlin 调用兼容测试 |
| Application | 普通与正式续传上传、实际分片长度/连续性校验、陈旧完成状态非破坏性对账、下载、审批、并行会签、发布、下线/归档撤回、受控新版本再发布、同步、Doctor、任务、受权审计分页、受权工作流决策证据与插件清单；目录模式的读取、下载及 action-aware 生命周期两阶段 guard | Application 单元测试 |
| Persistence | PostgreSQL、原生 MySQL 8.0.17+（仅 8.x，不含 MariaDB/MySQL 9）与 KingbaseES V8 的专属 Flyway location/history 和 migrate/validate/disabled 模式、租户条件、持久化请求幂等、Outbox 租约与全局积压快照、任务、审计、交付/撤回状态、发布代次、安全文档读取投影、不可变审批决策证据及 Audit/Trace 去重 keyset 查询 | 三种数据库各自独立、失败关闭的真实迁移/JDBC repository 集成门禁；MySQL 实证版本为 8.0.46；另有发布 JAR 迁移资源门禁 |
| Starter | Boot 2 / Boot 3 自动装配、安全默认实现、Outbox Worker 积压采样、文档读取端口与客户替换点；两代 Web Starter 镜像提供五条正式续传路由、统一错误外层及条件覆盖点 | Starter 上下文/MVC 测试，以及 Java 8/11/21/25（Boot 2）与 Java 17/21/25（Boot 3）运行时矩阵 |
| Adapter | 本地存储、S3 兼容存储、连接器弹性包装、Micrometer 计数器与受限 Outbox Gauge | Adapter 与 TestKit 合约测试 |
| Doctor | 权限、生命周期、工作流一致性、存储、连接器、交付档案与连接器映射、宿主目录绑定诊断；即时/异步/系统正式 v1、持久化历史与租约围栏投影 | 单元、Boot 2/3 MVC、PostgreSQL 与 Dev 验收测试 |
| Agent（历史阶段） | 旧模块、SPI/公共 ABI 与 V012/V026 数据库形状仅作兼容保留；`0.0.2` 无 Agent 产品面 | 只验证保留表面的编译、ABI 与迁移兼容；不计入 Dev 产品验收 |
| Hardening | 多租户与续传会话所有者隔离、宿主用户 ID 256 UTF-16 code unit 与固定安全字符契约、单调 `QUARANTINED` 围栏、Storage 写入口顶层事务边界、Outbox、重试、事务提交未知结果对账、固定状态的持久化 Outbox 积压/最老可执行年龄/读取失败 Gauge、零队列异步观测、连接器并发隔离与熔断、Trace、完整性校验、断点续传、下游撤回、交付/撤回指标、有界连接器诊断、Flyway 宿主命名空间隔离、旧 Kotlin ABI 运行夹具、依赖锁定/校验、JDK 运行时矩阵与 CycloneDX SBOM | `fastCheck`、五条独立 JDK 线、显式外部验收、制品/SBOM/迁移门禁与 CNB 远端消费者回读 |

目前的关键验收命令：

```powershell
.\gradlew.bat fastCheck --no-daemon

.\gradlew.bat compatibilityCheck --no-daemon

.\gradlew.bat verifySbom --no-daemon

.\gradlew.bat verifyFileWeftMigrationResources --no-daemon

$env:FILEWEFT_RUN_POSTGRES_TESTS='true'
.\gradlew.bat postgresIntegrationCheck --no-daemon --no-configuration-cache

$env:FILEWEFT_RUN_MYSQL_TESTS='true'
.\gradlew.bat mysqlIntegrationCheck --no-configuration-cache

$env:FILEWEFT_RUN_KINGBASE_TESTS='true'
.\gradlew.bat kingbaseIntegrationCheck --no-configuration-cache

$env:FILEWEFT_RUN_DEV_E2E='true'
.\gradlew.bat :fileweft-dev:devApiAcceptanceTest --no-daemon --no-configuration-cache
```

`verifyFileWeftMigrationResources` 会打开实际生成的 `fileweft-persistence` JAR，要求 28 个迁移 V001–V028 只完整存在于 `ai/icen/fw/db/migration`，拒绝重复 ZIP entry 与任何遗留 `db/migration/**`，并把每个 JAR entry 与声明为任务输入的源 SQL 逐字节比较。它已纳入 `releaseBundle` 与 `releaseCheck`。外部测试现在使用显式任务并失败关闭：环境开关不正确时直接报错，任务禁用结果复用，普通 `test` 和 JVM 矩阵都不会再重复执行这些套件。

`fastCheck` 会运行 included `build-logic` 的 TestKit 测试：它验证 Core/SPI/Application 的分层导入白名单、基础模块禁用 Kotlin-only API 语法、Java 8/17 约定插件的回归，以及 Gradle 配置缓存下的反向拦截行为。根 `check` 作为兼容入口继续执行 Java 8 基线模块的 `java8Test`；日常优先使用不展开跨运行时的 `fastCheck`。发版门禁 `compatibilityCheck` 汇总五条可独立运行的根任务，在 Java 8、11、21、25 上执行 Java 8 基线模块测试，并在 Java 17、21、25 上执行 Boot 3 Starter 与开发验收应用测试。CNB 将五条线放到独立节点并行，而单个 Gradle 构建通过共享并发上限避免测试 JVM 争抢资源。

Dev 编排验证真实 PostgreSQL、RustFS、S3 预签名下载和独立下游平台；覆盖双租户、同租户跨用户续传隔离、角色授权、上传、版本、单人审批、双人会签、多下游投递、失败重试、下线撤回、受控新版本再发布、Doctor 与审计。开发主应用关闭 Spring Boot 默认 Flyway，显式以 FileWeft `migrate` 模式和专属 history 管理 `fileweft_dev`；平台 profile 显式清空 FileWeft schema、关闭 schema 创建并覆盖为 `migration-mode=disabled`，仍由自己的 runner 管理 `fileweft_dev_platform`。`fileweft-dev` 已真实装配正式 Boot 3 Web Starter，控制台 Nginx 已代理 `/fileweft/`；UI 的断点创建/检查/分片/完成/放弃、文档读写/下载、审批待办与身份脱敏历史、仅向具备审计权限的角色展示的决策者证据、生命周期/审批动作、当前代次同步状态、失败目标重排、审计日志、插件清单、进程 liveness，以及即时/异步/系统 Doctor 均走正式 v1。续传卡滞检查仍明确留在 `/api/resumable-uploads/maintenance` Dev/运维验收边界，不冒充公共资源。Doctor 与运维面板只展示允许列表内的安全卡片；通用 Dev 文档详情不再返回原始 Audit/Operation details，旧 Dev 日志与 Doctor 路由固定不可用。Dev 不注册、不展示也不验收 Agent 产品能力。

浏览器回归包含独立的 formal-v1 与 Doctor 验收，并继续覆盖中英文切换、按角色隐藏操作控件、真实样例与普通表单上传、重命名、版本、授权下载、目录移动、单人与双人审批、双决策者身份快照、无审计权限用户不请求受权证据、遗留任务未知状态、驳回修订、任务处理、下游镜像、断点续传与 Alpha/Beta 前端可见性隔离。续传场景断言浏览器只对 `/fileweft/v1/uploads` 发起创建、两个流式 PUT 和完成请求，创建 key 只在 Header，body 不含 owner/tenant/assetType/idempotencyKey；本地检查点按可信租户和稳定用户隔离，跨用户不会检查或操作另一用户的 upload ID。Doctor 场景额外验证即时、异步、系统三条正式路由、管理员权限、跨租户 404、响应全树与 DOM 脱敏，以及浏览器不调用 `/api/**` Doctor。设置 `FILEWEFT_RUN_DEV_UI_E2E=true` 后由 `:fileweft-dev:devUiE2e` 调用；同时设置 API 开关时用根 `devAcceptanceCheck` 保证 API 先于浏览器执行。每次发布必须以本次命令生成的 PostgreSQL/Dev 测试 XML、Playwright 报告和 `releaseCheck` 最终输出为证据；任务和测试总数会随门禁演进，本文不再固化旧运行的数量，避免把历史成功输出误认为当前提交已通过。

## 本轮核对后仍未闭环的手册项

以下是明确的边界，而不是已交付能力：

- **正式公共 HTTP 扩展面**：JDK 8 纯契约、五条断点续传、文档读写/下载、八条生命周期与审批命令、审批待办/身份脱敏历史、同时要求 `document:audit` 与 `document:read` 的受权决策证据、当前代次同步状态、交付/撤回失败幂等重排、受权审计分页、安全插件清单、公开最小 liveness、即时/异步/系统 Doctor、flat/catalog-aware 安全解析以及 Boot 2/3 MVC 已交付。正式续传固定会话/文档解耦，创建 key 只持久化租户域摘要，GET 同时作为断线检查点和完成结果查询，公开 DTO 不含 owner/storage/ETag/错误详情。生命周期、重排与 Doctor 排队每次重放都会重新授权和检查目录 ACL；各自最终事务提交审计、事件/任务及稳定幂等回执。独立正式目录树 HTTP 已明确移出 `0.0.2`，没有承诺目标版本，不再是本次发布阻断项；这不影响现有宿主目录 SPI 与 catalog-aware 授权边界。旧 Roadmap 中的 Agent HTTP 家族同样不属于 `0.0.2` 缺口，且没有承诺版本。
- **数据库方言与运营策略**：`v0.0.2` 对应 PostgreSQL、MySQL 与 KingbaseES 各自完整的 28 个迁移 V001–V028，并已在 MySQL 8.0.46 与官方 KingbaseES V008R006C009B0014 上通过全新迁移和 JDBC repository 实库套件。MySQL 支持边界仅为原生 8.x 的 8.0.17+，不包含 MariaDB 或 MySQL 9，也不把单一 8.0.46 证据扩大为所有小版本、排序规则或拓扑。V017 在 PostgreSQL/Kingbase 上使用部分唯一索引、在 MySQL 上使用可空生成列加唯一索引，均在数据库层保证每个租户/文档至多一个 `PENDING` 工作流。V027 为 Outbox/Task 增加稳定 Worker 领取顺序索引。V028 把全部 MySQL FileWeft 业务表统一为 NO PAD 的 `utf8mb4_0900_bin`，使 tenant、用户和其他 opaque ID 按 Unicode 标量/文本值精确比较，大小写、重音和尾空格均不折叠；它会重建全表并需要停写、磁盘与锁维护窗口，升级后不得回滚到 `utf8mb4_bin`、`*_ci` 或其他 PAD SPACE/折叠比较。MySQL `V001` 保持既有 pre-0.0.2 工作树 checksum；作为历史边界，`v0.0.1` 只发布了 PostgreSQL V001–V025。受保护 `v0.0.2` 标签成功发布后，三种方言的 V001–V028 都成为不可改写的稳定发布资源。
- **Flyway 宿主兼容**：`FlywayMigrationRunner` 已验证 Spring Boot 2 管理的 Flyway 8.5.13、FileWeft 自身的 9.22.3 与 Spring Boot 3 管理的 11.7.2；Boot 3 的 `flyway-core`、`flyway-mysql`、`flyway-database-postgresql` 必须同为 11.7.2。Kingbase Starter 默认只包装 Spring Boot 已选择给 Flyway 的 DataSource，使其 Flyway metadata 兼容 PostgreSQL；应用主 DataSource 仍是真实 Kingbase DataSource。只有宿主已提供并验证等价集成时，才可设置 `fileweft.persistence.kingbase-flyway-compatibility-enabled=false`。
- **配置与标识策略**：`fw_tenant_config`、Snowflake/ULID 是手册中的可选方向；当前分别由宿主 SPI 和可替换的 UUID `IdentifierGenerator` 承担。是否收回为 FileWeft 持久化责任，需先确定配置所有权、迁移路径和兼容性承诺。

## 当前明确不包含的厂商实现

手册列出 OSS、CenterFile、Dify、ESE、AppBuilder 等适配方向。这些不是安全的“空壳默认实现”：它们各自需要稳定的厂商 API 契约、凭据模型、超时/重试策略和真实环境合约测试。因此仓库当前提供相应的通用 SPI 与 S3/本地基线，宿主可以按需实现或以插件形式贡献适配器；不得把厂商 SDK 泄漏到 Core、Domain 或 SPI。

若要新增某一厂商适配器，应先确定：目标产品及版本、认证方式、租户映射、幂等键语义、删除/撤回语义、超时与重试上限，以及可运行的测试环境。随后以独立 `adapter-*` 或插件模块交付并运行对应 TestKit 合约。

## 开源发布治理

基础能力验收通过不等于任意远端制品已经完成发布。当前已确定 Apache-2.0 许可证、`icen.ai` 版权主体和 `support@icen.ai` 私密安全入口；以下事项仍需按实际发布环境持续治理：

- 开源许可证与安全披露：根 LICENSE、NOTICE、SECURITY、Maven POM 和发布包必须保持一致；安全响应目标及支持版本见 `SECURITY.md`。
- 发布目标：当前远端是 CNB；CI、制品签名、依赖漏洞扫描、SBOM、发布仓库和版本策略应按实际发布平台配置，而不是假设 GitHub Actions。
- 生产容量与恢复目标：需要给出 SLO、数据保留、RPO/RTO、单机/多 Worker 并发量和目标对象存储/下游平台，才能完成压力、故障注入和灾备演练验收。

在这些决策明确前，FileWeft 已具备可运行、可扩展、可验证的基础设施基线；但不会把未验证的厂商兼容性或未定义的发布治理误报为完成。

HTTP 入口限流目前不由 FileWeft 伪装实现：框架不拥有宿主的 HTTP 网关、身份会话或分布式计数存储，生产宿主应在网关或认证层按其用户、租户和风险模型配置限流。连接器侧已有有限并发、队列与熔断，但它们不是通用的 API 请求限流。
