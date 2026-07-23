# FileWeft CI/CD

本目录承载 FileWeft 的 CNB 云原生构建配置。根 `.cnb.yml` 只负责拆分加载；共享运行环境、PR、主干、夜间和标签发布分别位于独立文件中，避免把发布安全逻辑塞进一个难以审阅的大文件。

## 本地任务分层

| 目标 | 命令 | 用途 |
| --- | --- | --- |
| 快速反馈 | `.\gradlew.bat fastCheck` | 当前构建 JVM 上的单元、契约、Context 测试，以及架构、Build Logic、文档、迁移和凭据静态门禁；不访问外部系统，也不展开跨 JDK 矩阵 |
| 单条兼容线 | `.\gradlew.bat compatibilityJava8Check` 等 | 独立运行 Java 8、11、17、21 或 25 运行时线，适合定位和并行调度 |
| 完整兼容矩阵 | `.\gradlew.bat compatibilityCheck` | 汇总五条兼容线，供本地发版前复核 |
| PostgreSQL | `.\gradlew.bat postgresIntegrationCheck` | 要求 `FILEWEFT_RUN_POSTGRES_TESTS=true` 和专用测试数据库 |
| MySQL 8 | `.\gradlew.bat mysqlIntegrationCheck` | 要求 `FILEWEFT_RUN_MYSQL_TESTS=true` 和专用 MySQL 8 测试数据库；PostgreSQL 结果不能替代它 |
| 人大金仓 | `.\gradlew.bat kingbaseIntegrationCheck` | 要求 `FILEWEFT_RUN_KINGBASE_TESTS=true` 和专用 KingbaseES V8 测试数据库；JDBC 驱动由锁定依赖提供，测试不得因驱动缺失静默跳过 |
| RustFS | `.\gradlew.bat rustFsIntegrationCheck` | 要求 `FILEWEFT_RUN_RUSTFS_TESTS=true` 和可用 RustFS |
| Dev 验收 | `.\gradlew.bat devAcceptanceCheck` | 要求完整 `fw-dev`、API 与 UI 两个开关；先跑 API，再跑 Playwright |
| 制品验证 | `.\gradlew.bat releaseArtifactCheck --no-configuration-cache` | 构建并核验本地 Maven 仓库、POM/metadata、SBOM、独立消费者和发布 ZIP，不重复跑外部验收与 JVM 矩阵 |
| 完整发版 | `.\gradlew.bat releaseCheck --no-configuration-cache` | 汇总质量、五条 JDK、外部验收和制品验证，是本地完整发布入口 |

### 按变更范围选择证据

测试选择是追加式的：一次改动命中多行时取任务并集，但不因此扩大到完整发布门禁。

| 变更范围 | 迭代时 | 一批改动交付前的最低证据 |
| --- | --- | --- |
| 纯文档或文档站 | `node --test fileweft-docs/test/site.test.mjs` | 同一条文档契约；不运行 JVM 与外部系统套件 |
| 单个模块内部实现 | `:模块:test --tests "完整测试类名"`，随后 `:模块:test` | 一批代码改完后运行一次 `fastCheck` |
| Build Logic 测试夹具 | `verifyFileWeftBuildLogic` | `fastCheck`；不展开 JVM 或外部系统 lane |
| Build Logic 主代码、Core、SPI、Metadata API/runtime、公共 API | 聚焦测试 | `fastCheck`，再由 CNB 运行受影响的 Java 8/17 或完整 JVM lane |
| JDBC、仓储、Flyway、PostgreSQL 方言 | 聚焦 Persistence 测试 | `fastCheck` + `postgresIntegrationCheck` |
| MySQL 或 Kingbase 方言/迁移 | 聚焦 Persistence 测试 | `fastCheck` + 对应 `mysqlIntegrationCheck` 或 `kingbaseIntegrationCheck` |
| S3/RustFS | S3 Adapter 聚焦测试 | `fastCheck` + `rustFsIntegrationCheck` |
| Dev API、UI、Boot 3 Compose | 对应 API/UI 用例 | `fastCheck` + `devAcceptanceCheck` |
| POM、metadata、SBOM、锁文件、发布 ZIP | 聚焦 Build Logic 测试 | `fastCheck` + `releaseArtifactCheck` |
| 正式发布 | 先修复所有聚焦失败 | `releaseCheck`，随后是稳定标签、远端发布和冷缓存消费者回读 |

普通开发不得把无模块限定的 `test` 或 `check` 当作默认入口：前者缺少架构、迁移和凭据门禁，后者会因模块约定意外展开运行时测试。`compatibilityCheck`、`externalAcceptanceCheck`、`releaseVerification`、`releaseCheck` 与 `releaseBundle` 只用于 CNB、夜间或正式发布。日常不要运行 `clean`，也不要添加 `--rerun-tasks`、`--no-build-cache`、`--no-daemon`；这些选项会直接丢失本地增量、Build Cache 或 Gradle Daemon 的收益。

失败后先重跑失败测试类或最窄命名任务；确认修复后只补一次该变更所需的最终门禁。不得因为一条 lane 失败而重复运行已经取得同一提交绿灯的无关外部套件。

同一个 checkout 同一时间只允许一个 Gradle wrapper 进程。多个 AI/终端不得并发执行两条 Gradle 命令，因为它们会共同写模块 `build/`、Kotlin 增量缓存和测试结果目录，造成结果丢失、缓存损坏或假失败。需要覆盖多个任务时，把它们放在同一条 Gradle 命令中，让 Gradle 在单张任务图内按自身依赖和并发上限调度。CNB 的不同 runner 有独立文件系统，可以继续并行；真正隔离的独立 worktree 也不受此限制。

兼容入口 `releaseBundle` 仍然依赖完整 `releaseCheck`，不会产生一个未经测试却外观相同的发布 ZIP。仅制品路径是内部 `assembleReleaseBundle` 与公开验证入口 `releaseArtifactCheck`；它们只能由已经取得同一提交独立门禁证据的 CNB 聚合发布流程复用。

普通 `test` 和所有运行时矩阵任务都排除 `**/*IntegrationTest.class`。外部测试只能放入明确命名、会检查环境开关且禁用结果复用的专用任务，避免一次本地 `check` 意外连接数据库、对象存储或开发环境。`verifyExternalTestPartition` 会阻止新的外部集成测试绕过这条边界。

所有 JVM 外部套件固定使用 Java 21 LTS toolchain；启动 Gradle 的 JDK 不会改变 PostgreSQL、MySQL、KingbaseES、RustFS 或 Dev API 验收证据。Playwright 使用 CI 镜像固定的 Node 与 Chromium。

仓库启用 Gradle parallel，同时把 Gradle worker 上限固定为 4，并为 Gradle/Kotlin daemon 明确保留受控堆空间，避免高核开发机一次启动过多 Kotlin/KAPT 编译而 OOM。所有 `Test` 任务再通过共享 Build Service 限制并发，默认最多同时运行 2 个；本地可用 `-Pfileweft.test.maxParallelTasks=N`，CI 可用 `FILEWEFT_TEST_MAX_PARALLEL_TASKS=N` 调整。它只限制测试任务数，不把整张任务图串行化。

## CNB 调度

- PR：文档只运行文档契约；代码运行 `fastCheck`；受影响的 Java 8/17、PostgreSQL、MySQL、KingbaseES、RustFS、Dev 验收和制品契约 lane 按精确路径并行触发。制品契约只覆盖发布构建逻辑、依赖锁、独立消费者及 LICENSE/NOTICE 等制品边界；相同 PR 的旧流水线会被新提交取消。
- `main` push：文档契约、快速门禁、五条 JDK、五个外部套件和制品契约均按精确路径触发；Build Logic 测试夹具只进入快速门禁，Build Logic 主代码才扩大到运行时、外部套件和制品契约。
- 夜间：北京时间以仓库所配置时区解释的 `30 2 * * *` 定时任务运行完整质量、制品、五条 JDK 和全部外部验收，用于发现工具链、镜像和依赖环境漂移。
- `vX.Y.Z` 标签：十一条验证流水线并行；发布流水线必须等待全部 resolve 信号后才获得发布阶段。制品验证与上传保留在同一个发布 runner 上只执行一次，避免另开 lane 后重复构建或搬运未验证的 Maven 仓库。发布任务复核标签、版本、40 位提交 SHA、当前 `HEAD` 与全部已验证提交完全一致，并要求该提交是实时远端受保护发布分支的 HEAD：`main`，或该标签所属 `X.Y` 产品线的维护分支 `ver/X.Y`（补丁版本不必合并或回退 `main`），两者至少有一个匹配且失败关闭，并用全局锁防止两个版本同时写 Maven 仓库。正式发布前必须在 CNB 为实际发布来源分支（`main` 或对应 `ver/X.Y`）启用分支保护；CNB 当前没有可替代该约束的标签保护规则，因此文档只称“受发布门禁约束的标签”，不得把标签名称本身视为受保护证据。维护分支只追加、不回溯：标签只能指向维护分支当时 HEAD，同一产品线的新补丁从该分支向前推进。

`.ci/test/path-policy.test.mjs` 把代表性 changed path 映射到预期 lane，并由 `verifyCnbPathPolicy` 纳入 `fastCheck`。发布消费者、法律文件、Docker context 和影响全图的 Gradle 锁文件还带有“不得选择零 lane”的回归断言。修改路径组时必须先补或更新代表性用例，再修改 YAML。CNB `ifModify` 在 PR 与非新建分支 push 中按 glob 判断，最多统计 300 个变更文件；超过 300 个文件或新分支 push 时不得只依赖按需结果，必须人工选择完整相关门禁。参见 [CNB `ifModify` 语法](https://docs.cnb.cool/zh/build/grammar.html#ifmodify)。

PR 缓存从 `main` 以 copy-on-write read-only 方式读取，不能污染主干缓存；主干、夜间和标签任务可写自己的 copy-on-write 层。CI 镜像固定基础镜像 digest，JVM 镜像包含 JDK 25、Docker CLI 与 Compose；E2E 镜像额外固定 Playwright/Chromium 版本。

标签发布使用 CNB 仅在受信事件提供的 `CNB_TOKEN` 写入 `https://maven.cnb.cool/china.ai/maven/-/packages/`。写入后立即销毁流水线 token，再用全新、隔离且先清空的 Gradle User Home 从公开仓库回读精确 19 个坐标（含 `fileweft-metadata-api` 与 `fileweft-metadata-runtime`），并编译 Boot 2、Boot 3 和纯 SPI 消费者；发布流水线任何正常 stage 失败时也会进入失败清理并销毁 token。远端仓库短暂最终一致时最多重试三次；验证失败不会被转成成功。

CI 中的 `FILEWEFT_DEV_PLATFORM_SHARED_SECRET` 只是隔离 Compose 网络内模拟平台的公开测试夹具值，不授予 CNB、Maven 或任何生产系统权限。真实发布凭据只来自 CNB 事件 token，禁止写入仓库、Gradle properties、缓存或测试报告。

### KingbaseES 真实测试镜像

FileWeft 不分发 KingbaseES 数据库镜像，也不从 Docker Hub 拉取来历不明的社区镜像。`.ci/scripts/prepare-kingbase-image.ps1`（Windows）与同目录 `.sh`（CNB/Linux）从电科金仓官网公开的 V8R6C9B14 x86_64 下载地址取得 tar，先核对官网 MD5，再核对仓库锁定的 SHA-256 和导入后的 Docker image ID；任一身份不符都会失败。使用者仍需自行确认其场景符合金仓许可条款。

本地只在改动命中金仓边界时运行：

```powershell
.\.ci\scripts\prepare-kingbase-image.ps1
$env:FILEWEFT_DEV_PLATFORM_SHARED_SECRET = "fileweft-local-only-shared-secret-2026"
docker compose -f .docker/docker-compose.dev.yaml up --detach --wait kingbase

$env:FILEWEFT_RUN_KINGBASE_TESTS = "true"
$env:FILEWEFT_KINGBASE_URL = "jdbc:kingbase8://127.0.0.1:54321/test"
$env:FILEWEFT_KINGBASE_USER = "system"
$env:FILEWEFT_KINGBASE_PASSWORD = "kingbase"
.\gradlew.bat kingbaseIntegrationCheck --no-configuration-cache --stacktrace

docker compose -f .docker/docker-compose.dev.yaml stop kingbase
docker compose -f .docker/docker-compose.dev.yaml rm --force kingbase
```

这里仅清理本次测试创建的 `kingbase` 容器，不删除共享的 `fw-dev` 数据卷。需要清空金仓测试数据时，应在确认没有其他本地任务依赖后，单独删除其命名卷；不得用全项目 `down --volumes` 作为日常测试收尾。

MySQL 与 KingbaseES 服务带独立 Compose profile；显式 `up mysql` / `up kingbase` 会启用对应服务，普通 Dev/API/UI 全栈 `up` 不会额外启动这两个数据库。CNB 也只在路径规则命中、夜间或标签发布时下载并运行金仓，因此不会把日常十分钟开发变成全数据库串行验收。

### RustFS 与 Dev 全栈本地验收

S3/RustFS 边界命中时，只启动对应服务并运行专用任务：

```powershell
$compose = ".docker/docker-compose.dev.yaml"
$env:FILEWEFT_DEV_PLATFORM_SHARED_SECRET = "fileweft-local-only-shared-secret-2026"
docker compose -f $compose up --detach --wait rustfs

$env:FILEWEFT_RUN_RUSTFS_TESTS = "true"
.\gradlew.bat rustFsIntegrationCheck --no-configuration-cache --stacktrace
```

Dev API、UI 或 Compose 行为命中时，先安装锁定的 Playwright 依赖，再启动完整默认栈。Dev 镜像的 BuildKit Gradle 缓存会跨重建复用；不要通过 `clean` 或禁用缓存抵消它。

```powershell
$compose = ".docker/docker-compose.dev.yaml"
$env:FILEWEFT_DEV_PLATFORM_SHARED_SECRET = "fileweft-local-only-shared-secret-2026"
$env:FILEWEFT_RUN_DEV_E2E = "true"
$env:FILEWEFT_RUN_DEV_UI_E2E = "true"

npm.cmd ci --prefix fileweft-dev/web
docker compose -f $compose up --detach --build --wait

(Invoke-RestMethod "http://127.0.0.1:8080/api/health").status
(Invoke-RestMethod "http://127.0.0.1:8081/platform/v1/health").status
(Invoke-WebRequest -UseBasicParsing "http://127.0.0.1:8088/").StatusCode

.\gradlew.bat devAcceptanceCheck --no-configuration-cache --stacktrace
```

期望结果依次为 `UP`、`UP`、`200`。`devAcceptanceCheck` 串行运行 API 与 Playwright，不能用 Compose healthy 代替。`fileweft-dev:test` 和 `fastCheck` 还会自动执行零依赖的 Dev UI 静态合同；该合同只需 CNB JVM 镜像已经提供的 Node 22，不需要 `npm ci`。

普通收尾只停止本轮使用的服务；若 RustFS 马上用于 Dev 验收则直接复用。不得用 `docker compose down --volumes`，以免删除同一 `fw-dev` 项目下的数据库与共享卷。

```powershell
docker compose -f $compose stop fileweft-dev-web fileweft-dev-worker fileweft-dev-api fileweft-dev-platform rustfs
docker compose -f $compose rm --force fileweft-dev-web fileweft-dev-worker fileweft-dev-api fileweft-dev-platform rustfs
```

## 仓库知识库与“织澜”

`.ci/knowledge.yml` 在 `main` push 后按 `.fileweft-knowledge-paths` 增量更新 CNB 仓库知识库。它只索引 `README.md`、`AGENTS.md`、`SECURITY.md`、本文件、`docs/**/*.md` 和中文文档站，不把 `.ai/**` 历史蓝图、英文重复页面、测试夹具、构建产物或给 AI 执行的 `SKILL.md` 混入产品事实。普通源码改动不会触发知识库任务，PR 也不会写主干知识库；任务固定使用 1 CPU。

Issue 同步目前保持关闭。只有建立明确的 Issue 关闭状态和 `knowledge` 标签治理后，才可以考虑同步已关闭的结论型 Issue；不能让开放问题或过期讨论覆盖默认分支文档。知识源、排除规则或人格配置发生变化时，必须同时更新 `.ci/test/path-policy.test.mjs`。

`.cnb/settings.yml` 定义仓库助手“织澜”和仓库页的“问织澜”入口。她是仓库助手，不是 FileWeft Agent 产品能力。仓库首页问答只能使用检索到的文档和 CodeWiki；Issue/PR 中的 NPC 工作模式拥有真实 checkout 时，必须先核对 SHA，再用 `rg`、精确源码、直接测试和装配配置回答具体实现。CodeWiki 是 AI 生成摘要，不能覆盖 `AGENTS.md` 或源码事实。人格可以温暖、直接并带少量幽默，但技术事实、来源路径、安全边界和“不知道”声明始终优先。CNB 从默认分支读取 NPC 设置，因此 PR 中只能预览配置；合并且知识库流水线成功后仓库入口才使用新配置和新知识。

### 按需刷新源码知识

`.ci/codewiki.yml` 只响应 `main` 的 `web_trigger_codewiki`，不响应 PR、普通 push、定时任务或标签，因此不会增加日常开发和稳定版发布的等待时间。需要刷新时，在 CNB 的「代码 → 分支 → `main` 分支详情页」点击「刷新源码知识」；按钮由 `.cnb/web_trigger.yml` 定义，仅向仓库负责人和管理员展示，触发者仍必须具有仓库写权限。CNB 的 `permissions.roles` 只在页面检查，不是服务端授权边界，不能拿它保护密钥或高风险发布动作；本任务本身不接收输入、不发布制品。CodeWiki 首次拉取的固定镜像较大，仓库生成也可能超过一小时，这是低频维护任务而不是发布门禁。

流水线固定使用 CodeWiki `v1.12.0` 的 amd64 镜像 digest。插件没有 include/exclude 参数，因此生成前用 `.ci/codewiki-sparse-checkout` 在本次临时 checkout 中隐藏 `.ai/**` 历史蓝图、`fileweft-agent/**` 兼容实现、测试源码、英文重复文档和 Web 夹具；这不会删除或改写 Git 中的任何文件。正式文档知识更新和 CodeWiki 入库共用 `fileweft-knowledge-base` 锁并按顺序等待，不能互相取消或并发改写同一知识库。

CodeWiki 插件目前不会把 Wiki 入库子进程的退出码传给主进程，而且删除旧 CodeWiki 或单个文档最终上传失败都可能只写日志；它上传的 metadata 也没有 commit 字段。为此准备阶段先证明 checkout HEAD 精确等于 `CNB_COMMIT` 且不存在遗留 `codewiki/` 目录，流水线最后再运行 `.ci/scripts/verify-codewiki-knowledge.mjs`：本次生成状态必须成功，知识库的 `last_commit_sha` 必须等于本次 `CNB_COMMIT`，每个命中片段的 SHA-256 必须自洽且其去除插件合成标题后的完整 token 序列必须来自本次 `codewiki/` 生成物，并且 `.ci/code-knowledge-acceptance.json` 中每个具体类问题都必须包含约定的精确符号。只有这样才能排除旧 CodeWiki 片段误绿；仅有 HTTP 200、高相似度、全局 SHA 或普通 `metadata.type=code` 文档都不算源码知识验收成功。

没有 CNB Skill 时也可以只读检查。先按下一节取得 `web_trigger_codewiki` 的精确 SHA、事件和 SN，再查询知识库信息和三个黄金问题；不得为了查看状态而重新触发任务，也不要设置 `NODE_TLS_REJECT_UNAUTHORIZED=0`。`last_commit_sha` 必须与按钮所针对的 `main` SHA 完全一致，返回片段还要人工确认 `metadata.type` 为 `codewiki`。

```powershell
$repo = "china.ai/file-weft"
$sha = (git rev-parse origin/main).Trim()
Remove-Item Env:NODE_TLS_REJECT_UNAUTHORIZED -ErrorAction SilentlyContinue

cnb knowledge-base get-knowledge-base-info --repo $repo --verbose

$queries = @(
  "JdbcResumableUploadSessionRepository 具体实现了哪些续传仓储能力？",
  "S3StorageAdapter 的 multipart 完成和确定拒绝如何实现？",
  "ResumableUploadReconciler 如何处理完成结果未知？"
)
foreach ($query in $queries) {
  cnb knowledge-base query-knowledge-base-get `
    --repo $repo `
    --query $query `
    --top-k 20 `
    --score-threshold 0 `
    --verbose
}
```

源码更新后是否需要刷新由维护者按需判断：涉及模块边界、核心实现、公开 SPI、Starter 装配或跨模块调用链时刷新；只改拼写、测试数据或不影响理解的局部实现时不刷新。不要把全量源码复制为 `.txt`/Markdown 塞进文档知识库。若首页问答无法核实某个具体类，在 Issue/PR 中 `@china.ai/file-weft(织澜)` 请求她基于真实 checkout 核验。

首次合并后，在 CNB 构建中确认 `Main FileWeft knowledge base` 成功。随后可用只读 CLI 做一次最小检索验证；查询返回 404 通常表示默认分支还没有一条成功的知识库构建，不应为了查看状态而触发、停止或重跑构建。

```powershell
$repo = "china.ai/file-weft"
Remove-Item Env:NODE_TLS_REJECT_UNAUTHORIZED -ErrorAction SilentlyContinue
cnb knowledge-base query-knowledge-base-get `
  --repo $repo `
  --query "FileWeft 0.0.3 是否提供 Agent 产品能力？" `
  --top-k 5
```

## CNB 构建结果闭环

CNB 证据只对精确提交和精确事件有效。完成判据是：

1. `sha` 与 `git rev-parse HEAD` 的 40 位值完全一致。
2. `event`、`sourceRef` 和 `targetRef` 与本次 PR、main push、夜间或标签事件一致。
3. 总体状态为 `success` 且 `pipelineFailCount=0`。
4. 对照对应事件 YAML，所有按路径应触发的业务 pipeline 都存在并成功；旧 SHA、运行中、取消、部分绿灯或不相关事件都不能作为证据。
5. 新修复提交必须使用新的 SHA 和 SN 重新验收。`DebugDetection` 等平台 housekeeping stage 可以合法跳过，业务 stage 不可以用平台跳过冒充成功。

有 CNB Pipeline skill 时优先使用它。当前环境没有 skill 或需要原始证据时，使用已在 `cnb` CLI 1.10.9 验证的只读流程。先确认登录；若未登录，`cnb login` 会启动设备授权流程。读取历史和状态分别需要 `repo-cnb-history:r` 与 `repo-cnb-trigger:r`，不得输出或提交 token。

```powershell
$repo = "china.ai/file-weft"
$sha = (git rev-parse HEAD).Trim()

cnb status
cnb build get-build-logs `
  --repo $repo `
  --sha $sha `
  --page-size 100 `
  --verbose
```

`get-build-logs` 的名称容易误解：这个命令首先返回构建列表与总体结果，包括 `sn`、`sha`、事件、分支、总体状态、pipeline 数量、pipeline ID 和 `buildLogUrl`。必须从中选择精确 SHA 和事件对应的 SN，不能简单取分支“最新一条”。

取得 SN 后查看每条 pipeline 和 stage：

```powershell
cnb build get-build-status `
  --repo $repo `
  --sn <SN> `
  --verbose
```

定位首个失败的业务 stage 后读取状态、错误、时间和普通日志：

```powershell
cnb build get-build-stage `
  --repo $repo `
  --sn <SN> `
  --pipelineId <PIPELINE_ID> `
  --stageId <STAGE_ID> `
  --verbose
```

当前 CLI 不存在 `get-build-result` 命令：总体 result 来自 `get-build-logs`/`get-build-status`，stage result 与日志来自 `get-build-stage`。只有 Prepare、容器、网络或清理证据不足时，才下载完整 runner 日志：

```powershell
$response = cnb build build-runner-download-log `
  --repo $repo `
  --pipelineId <PIPELINE_ID> `
  --verbose | ConvertFrom-Json

Get-Content -LiteralPath $response.data -Tail 300
```

`response.data` 是 CLI 写入本机临时目录的日志文件，不是仓库工件。诊断时只摘录必要错误，脱敏 token、凭据、签名 URL、请求头和环境变量。修复 push 后不要高频轮询；平台会通知新的失败。需要最终交付证据时再针对新 SHA 查询一次终态。

只读诊断不授权 `start-build`、`stop-build`、创建标签或发布。不得通过 `NODE_TLS_REJECT_UNAUTHORIZED=0` 绕过 TLS 证书校验。

## 启用与维护

1. 提交 `.cnb.yml`、本目录和对应 Gradle 改动后，让首个 PR 验证所有分支和路径条件。
2. 在 CNB 仓库保护规则中配置按路径所需的文档、fast feedback、最低 Java 8/17 和外部检查；不得让文档-only PR 因缺少未触发的 JVM lane 永久等待，也不得让命中代码路径的失败 lane 被忽略。
3. 仅创建与稳定版本完全一致的标签，例如 `v0.0.3`；不得用标签流水线发布 `-SNAPSHOT`。
4. 修改任何 `.cnb.yml` 或 `.ci/*.yml` 后运行 CNB Pipeline skill 的 YAML、语义和 Schema 三层校验；修改 Dockerfile 后至少构建对应镜像。
5. 不要在验证流水线外直接调用 `publishVerifiedCnbArtifacts`。该入口有提交身份保护，但它的设计前提仍是同一标签事件中的十一条 CNB await 已全部成功。
