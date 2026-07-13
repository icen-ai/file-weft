# FileWeft CI/CD

本目录承载 FileWeft 的 CNB 云原生构建配置。根 `.cnb.yml` 只负责拆分加载；共享运行环境、PR、主干、夜间和标签发布分别位于独立文件中，避免把发布安全逻辑塞进一个难以审阅的大文件。

## 本地任务分层

| 目标 | 命令 | 用途 |
| --- | --- | --- |
| 快速反馈 | `.\gradlew.bat fastCheck` | 当前构建 JVM 上的单元、契约、Context 测试，以及架构、Build Logic、文档、迁移和凭据静态门禁；不访问外部系统，也不展开跨 JDK 矩阵 |
| 单条兼容线 | `.\gradlew.bat compatibilityJava8Check` 等 | 独立运行 Java 8、11、17、21 或 25 运行时线，适合定位和并行调度 |
| 完整兼容矩阵 | `.\gradlew.bat compatibilityCheck` | 汇总五条兼容线，供本地发版前复核 |
| PostgreSQL | `.\gradlew.bat postgresIntegrationCheck` | 要求 `FILEWEFT_RUN_POSTGRES_TESTS=true` 和专用测试数据库 |
| RustFS | `.\gradlew.bat rustFsIntegrationCheck` | 要求 `FILEWEFT_RUN_RUSTFS_TESTS=true` 和可用 RustFS |
| Dev 验收 | `.\gradlew.bat devAcceptanceCheck` | 要求完整 `fw-dev`、API 与 UI 两个开关；先跑 API，再跑 Playwright |
| 制品验证 | `.\gradlew.bat releaseArtifactCheck --no-configuration-cache` | 构建并核验本地 Maven 仓库、POM/metadata、SBOM、独立消费者和发布 ZIP，不重复跑外部验收与 JVM 矩阵 |
| 完整发版 | `.\gradlew.bat releaseCheck --no-configuration-cache` | 汇总质量、五条 JDK、外部验收和制品验证，是本地完整发布入口 |

兼容入口 `releaseBundle` 仍然依赖完整 `releaseCheck`，不会产生一个未经测试却外观相同的发布 ZIP。仅制品路径是内部 `assembleReleaseBundle` 与公开验证入口 `releaseArtifactCheck`；它们只能由已经取得同一提交独立门禁证据的 CNB 聚合发布流程复用。

普通 `test` 和所有运行时矩阵任务都排除 `**/*IntegrationTest.class`。外部测试只能放入明确命名、会检查环境开关且禁用结果复用的专用任务，避免一次本地 `check` 意外连接数据库、对象存储或开发环境。`verifyExternalTestPartition` 会阻止新的外部集成测试绕过这条边界。

所有 JVM 外部套件固定使用 Java 21 LTS toolchain；启动 Gradle 的 JDK 不会改变 PostgreSQL、RustFS 或 Dev API 验收证据。Playwright 使用 CI 镜像固定的 Node 与 Chromium。

仓库启用 Gradle parallel，同时把 Gradle worker 上限固定为 4，并为 Gradle/Kotlin daemon 明确保留受控堆空间，避免高核开发机一次启动过多 Kotlin/KAPT 编译而 OOM。所有 `Test` 任务再通过共享 Build Service 限制并发，默认最多同时运行 2 个；本地可用 `-Pfileweft.test.maxParallelTasks=N`，CI 可用 `FILEWEFT_TEST_MAX_PARALLEL_TASKS=N` 调整。它只限制测试任务数，不把整张任务图串行化。

## CNB 调度

- PR：始终运行 `fastCheck`；代码路径变化时并行运行最低 Java 8/17 线；PostgreSQL、RustFS 和完整 Dev 验收按相关路径触发。相同 PR 的旧流水线会被新提交取消。
- `main` push：快速门禁与五条 JDK 线并行，外部套件按相关路径触发。
- 夜间：北京时间以仓库所配置时区解释的 `30 2 * * *` 定时任务运行完整质量、制品、五条 JDK 和全部外部验收，用于发现工具链、镜像和依赖环境漂移。
- `vX.Y.Z` 标签：九条验证流水线并行；发布流水线必须等待全部 resolve 信号后才获得发布阶段。发布任务复核标签、版本、40 位提交 SHA、当前 `HEAD` 与已验证提交完全一致，并用全局锁防止两个版本同时写 Maven 仓库。

PR 缓存从 `main` 以 copy-on-write read-only 方式读取，不能污染主干缓存；主干、夜间和标签任务可写自己的 copy-on-write 层。CI 镜像固定基础镜像 digest，JVM 镜像包含 JDK 25、Docker CLI 与 Compose；E2E 镜像额外固定 Playwright/Chromium 版本。

标签发布使用 CNB 仅在受信事件提供的 `CNB_TOKEN` 写入 `https://maven.cnb.cool/china.ai/maven/-/packages/`。写入后立即销毁流水线 token，再用全新、隔离且先清空的 Gradle User Home 从公开仓库回读精确 17 个坐标并编译 Boot 2、Boot 3 和纯 SPI 消费者。远端仓库短暂最终一致时最多重试三次；验证失败不会被转成成功。

CI 中的 `FILEWEFT_DEV_PLATFORM_SHARED_SECRET` 只是隔离 Compose 网络内模拟平台的公开测试夹具值，不授予 CNB、Maven 或任何生产系统权限。真实发布凭据只来自 CNB 事件 token，禁止写入仓库、Gradle properties、缓存或测试报告。

## 启用与维护

1. 提交 `.cnb.yml`、本目录和对应 Gradle 改动后，让首个 PR 验证所有分支和路径条件。
2. 在 CNB 仓库保护规则中把 PR fast feedback、最低 Java 8/17，以及命中相应路径时的外部流水线设为合并所需检查。
3. 仅创建与稳定版本完全一致的标签，例如 `v0.0.2`；不得用标签流水线发布 `-SNAPSHOT`。
4. 修改任何 `.cnb.yml` 或 `.ci/*.yml` 后运行 CNB Pipeline skill 的 YAML、语义和 Schema 三层校验；修改 Dockerfile 后至少构建对应镜像。
5. 不要在验证流水线外直接调用 `publishVerifiedCnbArtifacts`。该入口有提交身份保护，但它的设计前提仍是同一标签事件中的九条 CNB await 已全部成功。
