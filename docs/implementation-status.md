# FileWeft 实现对照与发布门槛

本文记录仓库相对 `.ai/` 实施手册的可验证范围，避免将“基础能力已验收”和“已具备完整开源发布治理”混为一谈。

## `.ai` 十阶段基础能力

| 阶段 | 已交付能力 | 验证方式 |
| --- | --- | --- |
| Core | 标识、上下文、结果、错误与 Outbox 模型，不依赖 Spring 或厂商 SDK | Core 单元测试、分层依赖与 Java 互操作语法门禁，以及 Java 8/11/21/25 运行时矩阵 |
| SPI | 身份、授权、租户、存储、连接器、交付、任务、诊断、Agent、审批路由、宿主目录与低基数 Gauge 契约 | SPI 模型与合约测试、Java 互操作语法门禁 |
| Domain | 文件、文档版本、生命周期、工作流、审计与操作日志领域规则；受控恢复草稿与发布代次 | Domain 单元测试 |
| Application | 上传、下载、审批、并行会签、发布、下线/归档撤回、受控新版本再发布、同步、Doctor、任务、Agent，以及受信任上下文下按可读目录范围约束的文档详情/cursor 读取端口 | Application 单元测试 |
| Persistence | PostgreSQL/Flyway、租户条件、Outbox 租约与全局积压快照、任务、审计、交付/撤回状态、发布代次及安全文档读取投影 | PostgreSQL 集成测试 |
| Starter | Boot 2 / Boot 3 自动装配、安全默认实现、Outbox Worker 积压采样、文档读取端口与客户替换点 | Starter 上下文测试，以及 Java 8/11/21/25（Boot 2）与 Java 17/21/25（Boot 3）运行时矩阵 |
| Adapter | 本地存储、S3 兼容存储、连接器弹性包装、Micrometer 计数器与受限 Outbox Gauge | Adapter 与 TestKit 合约测试 |
| Doctor | 权限、生命周期、工作流一致性、存储、连接器、交付档案与连接器映射、宿主目录绑定与 Agent 的诊断及持久化历史 | 单元与 Dev 验收测试 |
| Agent | 可恢复任务、建议确认、审计和操作记录 | 单元与 Dev 验收测试 |
| Hardening | 多租户隔离、Outbox、重试、固定状态的持久化 Outbox 积压/最老可执行年龄/读取失败 Gauge、零队列异步观测、连接器并发隔离与熔断、Trace、完整性校验、断点续传、下游撤回、交付/撤回指标、有界连接器诊断、依赖锁定/校验、JDK 运行时矩阵与 CycloneDX SBOM | 全仓检查、`compatibilityCheck`、`verifySbom` 与 Compose 验收 |

目前的关键验收命令：

```powershell
.\gradlew.bat check --no-daemon

.\gradlew.bat compatibilityCheck --no-daemon

.\gradlew.bat verifySbom --no-daemon

$env:FILEWEFT_RUN_POSTGRES_TESTS='true'
.\gradlew.bat :fileweft-persistence:test --no-daemon

$env:FILEWEFT_RUN_DEV_E2E='true'
.\gradlew.bat :fileweft-dev:test --tests 'com.fileweft.dev.e2e.DevAcceptanceIntegrationTest' --rerun-tasks --no-daemon
```

Dev API E2E 由环境变量选择性启用；该环境变量不是 Gradle 任务输入，因此重复验收必须保留 `--rerun-tasks`，避免已有测试输出被误判为本次真实执行。

根 `check` 还会运行 included `build-logic` 的 TestKit 测试：它验证 Core/SPI/Application 的分层导入白名单、基础模块禁用 Kotlin-only API 语法、Java 8/17 约定插件的回归，以及 Gradle 配置缓存下的反向拦截行为。日常 `check` 会继续执行所有 Java 8 基线模块的独立 `java8Test`；发版门禁 `compatibilityCheck` 通过 Gradle 工具链在 Java 8、11、21、25 上执行所有 Java 8 基线模块测试，并在 Java 17、21、25 上执行 Boot 3 Starter 与开发验收应用测试。该矩阵已在实际工具链运行通过，而不是只依赖字节码目标声明。

Dev 编排验证真实 PostgreSQL、RustFS、S3 预签名下载和独立下游平台；覆盖双租户、角色授权、上传、版本、单人审批、双人会签、多下游投递、失败重试、下线撤回、受控新版本再发布、Doctor、Agent 与审计。开发 API 还提供 `GET /api/documents/{id}/sync-status` 与 `GET /api/documents/{id}/logs?limit=`，用于分别查看脱敏的当前交付/同步/Outbox 状态和带来源标识的审计/操作时间线；两者都执行当前租户和 `document:read` 校验，且不会返回下游外部 ID、原始错误或 Outbox 负载。`fileweft-dev/web` 另有锁定依赖的 Playwright 浏览器验收：验证中英文切换、按角色隐藏操作控件、真实样例与普通表单上传、重命名、版本、授权下载、目录移动、单人与双人审批、驳回修订、Doctor、任务处理、下游镜像、断点续传与 Alpha/Beta 前端可见性隔离；设置 `FILEWEFT_RUN_DEV_UI_E2E=true` 后由 `:fileweft-dev:check` 调用。

## 本轮核对后仍未闭环的手册项

以下是明确的边界，而不是已交付能力：

- **正式公共 HTTP API**：`fileweft-web-api` 与 `fileweft-web-runtime` 已交付 JDK 8 的 v1 纯契约、安全响应分类、cursor 分页、不泄漏领域聚合的读取端口，以及创建草稿/新增版本/改名的最小命令门面；读取端口从可信租户/用户/授权上下文派生目录可见范围。可选 Boot 2/Boot 3 Web Starter 已对齐文档列表、详情、multipart 创建、multipart 新版本与 JSON 改名，安全映射 400/401/403/404/409/503/500、可选 Trace、脱敏响应和安全 `Location`，且 MVC/Spring 仅存在于外层模块。目录模式创建由应用层原子绑定；新增版本、改名和目录移动共用两阶段 mutation guard，上传后重验 ACL，并以 document → asset 的 PostgreSQL 行锁顺序复核源绑定；隐藏源目录不泄漏，竞态修改不会提交且上传对象会补偿删除。**完整 HTTP 面仍未闭环**：仍需持久化请求幂等、授权下载安全头、生命周期/多人审批、Doctor、同步状态与日志的正式映射，以及双租户 Dev v1 E2E；不能把 Dev Controller 当作正式协议。
- **数据库方言与运营策略**：当前唯一经过真实数据库迁移和并发测试的持久化目标是 PostgreSQL。MySQL 8 尚未实现：它需要独立迁移集、JSON/upsert/任务领取 SQL 方言、工作流部分唯一约束的等价实现和 MySQL 实库测试，不能只添加驱动。迁移目前遵循只前进的 Flyway 版本化策略；新增迁移必须附带 preflight 和回滚方案（或明确不可回滚）。审计与操作日志是 append-only，`fw_operation_log` 因而没有 `updated_time`；历史保留、分区和归档年限仍需产品与运维定义后才能实现。
- **配置与标识策略**：`fw_tenant_config`、Snowflake/ULID 是手册中的可选方向；当前分别由宿主 SPI 和可替换的 UUID `IdentifierGenerator` 承担。是否收回为 FileWeft 持久化责任，需先确定配置所有权、迁移路径和兼容性承诺。

## 当前明确不包含的厂商实现

手册列出 OSS、CenterFile、Dify、ESE、AppBuilder 等适配方向。这些不是安全的“空壳默认实现”：它们各自需要稳定的厂商 API 契约、凭据模型、超时/重试策略和真实环境合约测试。因此仓库当前提供相应的通用 SPI 与 S3/本地基线，宿主可以按需实现或以插件形式贡献适配器；不得把厂商 SDK 泄漏到 Core、Domain 或 SPI。

若要新增某一厂商适配器，应先确定：目标产品及版本、认证方式、租户映射、幂等键语义、删除/撤回语义、超时与重试上限，以及可运行的测试环境。随后以独立 `adapter-*` 或插件模块交付并运行对应 TestKit 合约。

## 开源发布仍需项目所有者决策

基础能力验收通过不等于可以自行声明“最终开源发布完成”。以下事项需要仓库所有者明确决定或提供外部信息：

- 开源许可证：许可证决定再分发、专利、商标和商业集成边界，不能由实现代理擅自选择。
- 安全披露渠道与维护承诺：需要一个可用的私密漏洞报告入口、响应时限和受支持版本范围。
- 发布目标：当前远端是 CNB；CI、制品签名、依赖漏洞扫描、SBOM、发布仓库和版本策略应按实际发布平台配置，而不是假设 GitHub Actions。
- 生产容量与恢复目标：需要给出 SLO、数据保留、RPO/RTO、单机/多 Worker 并发量和目标对象存储/下游平台，才能完成压力、故障注入和灾备演练验收。

在这些决策明确前，FileWeft 已具备可运行、可扩展、可验证的基础设施基线；但不会把未验证的厂商兼容性或未定义的发布治理误报为完成。

HTTP 入口限流目前不由 FileWeft 伪装实现：框架不拥有宿主的 HTTP 网关、身份会话或分布式计数存储，生产宿主应在网关或认证层按其用户、租户和风险模型配置限流。连接器侧已有有限并发、队列与熔断，但它们不是通用的 API 请求限流。
