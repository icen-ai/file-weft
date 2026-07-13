const code = (language, source) => `<div class="code-block"><div class="code-label"><span>${language}</span></div><pre><code>${source.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")}</code></pre></div>`;
const note = (mark, title, body, warning = false) => `<aside class="callout${warning ? " warning" : ""}" data-mark="${mark}"><div><strong>${title}</strong><p>${body}</p></div></aside>`;
const table = (heads, rows) => `<table class="comparison-table"><thead><tr>${heads.map((item) => `<th>${item}</th>`).join("")}</tr></thead><tbody>${rows.map((row) => `<tr>${row.map((item) => `<td>${item}</td>`).join("")}</tr>`).join("")}</tbody></table>`;
const withdrawnGroup = ["com", "fileweft"].join(".");

export const groups = [
  { id: "getting-started", index: "01", en: "Getting started", zh: "开始使用" },
  { id: "concepts", index: "02", en: "Concepts", zh: "核心概念" },
  { id: "guides", index: "03", en: "Guides", zh: "使用指南" },
  { id: "architecture", index: "04", en: "Architecture", zh: "架构" },
  { id: "reference", index: "05", en: "Reference", zh: "参考" },
  { id: "operations", index: "06", en: "Operations", zh: "生产运维" },
  { id: "extensions", index: "07", en: "Extensions", zh: "扩展" },
  { id: "project", index: "08", en: "Project", zh: "项目" },
];

const page = (group, order, en, zh) => ({ group, order, en, zh });

export const pages = {
  "getting-started/introduction": page("getting-started", 1,
    {
      title: "Infrastructure for files that must endure",
      nav: "Introduction",
      lead: "FileWeft is an extensible Kotlin/JVM foundation for enterprise document lifecycles, storage, approvals, delivery and diagnostics — without taking ownership of your identity, folder tree or business rules.",
      sections: [
        { title: "What FileWeft is", html: `<p>FileWeft coordinates durable file operations behind stable application and SPI boundaries. It owns document versions, lifecycle transitions, audit evidence, Outbox delivery and diagnosable background work.</p>${table(["FileWeft owns", "Your host owns"], [["Document, version and delivery state", "Authentication and user directory"], ["Outbox, task leases and audit evidence", "Folder topology and folder ACL"], ["Stable storage and connector contracts", "Business-specific policy and presentation"]])}` },
        { title: "Design posture", html: `<p>External systems are assumed to fail. FileWeft commits local business state first, records durable work in the same transaction, and calls storage or downstream connectors outside long-running database transactions.</p>${note("SPI", "Extend before modifying", "Storage, identity, authorization, tenant, catalog, workflow, connector and AI behavior enter through contracts. Core and Domain do not depend on Spring, databases or vendor SDKs.")}` },
        { title: "Choose your entry point", html: `<ul><li><b>SPI only:</b> implement or consume contracts without a Spring runtime.</li><li><b>Runtime Starter:</b> assemble persistence, workers and application services for Spring Boot 2 or 3.</li><li><b>Web Starter:</b> add the stable <code>/fileweft/v1</code> HTTP surface for the same Boot generation.</li></ul>` },
      ],
    },
    {
      title: "为必须长久运行的文件系统打地基",
      nav: "介绍",
      lead: "FileWeft 是可扩展的 Kotlin/JVM 企业文件基础设施，覆盖文档生命周期、存储、审批、交付与诊断，但不接管宿主的身份、目录树和业务规则。",
      sections: [
        { title: "FileWeft 是什么", html: `<p>FileWeft 在稳定的 Application 与 SPI 边界后协调可靠文件操作，负责文档版本、生命周期、审计证据、Outbox 交付和可诊断后台任务。</p>${table(["FileWeft 负责", "宿主负责"], [["文档、版本和交付状态", "认证与用户目录"], ["Outbox、任务租约和审计证据", "目录拓扑与目录 ACL"], ["稳定的存储及连接器契约", "业务策略与界面"]])}` },
        { title: "设计立场", html: `<p>外部系统默认不可靠。FileWeft 先提交本地业务状态，在同一事务记录持久任务，再在数据库长事务之外调用存储或下游连接器。</p>${note("SPI", "优先扩展而不是修改", "存储、身份、授权、租户、目录、工作流、连接器和 AI 行为都通过契约进入。Core 与 Domain 不依赖 Spring、数据库或厂商 SDK。")}` },
        { title: "选择接入方式", html: `<ul><li><b>仅 SPI：</b>不引入 Spring 运行时，只实现或使用契约。</li><li><b>运行时 Starter：</b>为 Spring Boot 2 或 3 装配持久化、Worker 与应用服务。</li><li><b>Web Starter：</b>为同一 Boot 代际增加稳定的 <code>/fileweft/v1</code> HTTP 接口。</li></ul>` },
      ],
    }),

  "getting-started/installation": page("getting-started", 2,
    {
      title: "Install the 0.0.1 line",
      nav: "Installation",
      lead: "Published artifacts use Maven group ai.icen and JVM package ai.icen.fw. Choose matching Spring Boot generations; never combine Boot 2 and Boot 3 starters.",
      sections: [
        { title: "Maven coordinates", html: code("Kotlin / Gradle", `repositories {\n    mavenCentral()\n    maven { url = uri("https://maven.cnb.cool/china.ai/maven/-/packages/") }\n}\n\ndependencies {\n    implementation("ai.icen:fileweft-spring-boot3-starter:0.0.1")\n    implementation("ai.icen:fileweft-web-spring-boot3-starter:0.0.1")\n}`) + `<p>For contract-only integrations, use <code>ai.icen:fileweft-spi:0.0.1</code>. Boot 2 applications replace both <code>boot3</code> artifact names with <code>boot2</code>.</p>` },
        { title: "Runtime requirements", html: `<ul><li>Build FileWeft with JDK 17 or newer; the verified build environment is JDK 21.</li><li>Baseline modules publish Java 8-compatible bytecode.</li><li>Spring Boot 3 starters require Java 17; Boot 2 starters retain the Java 8 baseline.</li></ul>${note("!", "Do not use withdrawn coordinates", `The early ${withdrawnGroup}:*:0.0.1 trial coordinates are not a supported release line.`, true)}` },
        { title: "Verify the dependency", html: code("PowerShell", `.\\gradlew.bat dependencyInsight --dependency fileweft-spi --configuration runtimeClasspath`) },
      ],
    },
    {
      title: "安装 0.0.1 正式版",
      nav: "安装",
      lead: "正式制品使用 Maven group ai.icen，JVM 包名为 ai.icen.fw。Spring Boot 代际必须匹配，不能混用 Boot 2 与 Boot 3 Starter。",
      sections: [
        { title: "Maven 坐标", html: code("Kotlin / Gradle", `repositories {\n    mavenCentral()\n    maven { url = uri("https://maven.cnb.cool/china.ai/maven/-/packages/") }\n}\n\ndependencies {\n    implementation("ai.icen:fileweft-spring-boot3-starter:0.0.1")\n    implementation("ai.icen:fileweft-web-spring-boot3-starter:0.0.1")\n}`) + `<p>只接入契约时使用 <code>ai.icen:fileweft-spi:0.0.1</code>。Boot 2 应用将两个制品名中的 <code>boot3</code> 换成 <code>boot2</code>。</p>` },
        { title: "运行要求", html: `<ul><li>构建 FileWeft 使用 JDK 17+，当前验证环境为 JDK 21。</li><li>基础模块发布 Java 8 兼容字节码。</li><li>Spring Boot 3 Starter 需要 Java 17；Boot 2 Starter 保持 Java 8 基线。</li></ul>${note("!", "不要使用已撤回坐标", `早期 ${withdrawnGroup}:*:0.0.1 试推坐标不属于受支持发布线。`, true)}` },
        { title: "验证依赖", html: code("PowerShell", `.\\gradlew.bat dependencyInsight --dependency fileweft-spi --configuration runtimeClasspath`) },
      ],
    }),

  "getting-started/first-integration": page("getting-started", 3,
    {
      title: "Wire a trustworthy host",
      nav: "First integration",
      lead: "A production host must provide trusted tenant, identity and authorization context, a shared persistent StorageAdapter, and an explicit migration policy.",
      sections: [
        { title: "Supply trust context", html: `<p>Implement <code>TenantProvider</code>, <code>UserRealmProvider</code> and <code>AuthorizationProvider</code> from data already authenticated by your host. Controllers must never accept tenant IDs, user IDs, roles or permission results as business parameters.</p>${note("ID", "User IDs are opaque strings", "Long, Int, UUID and external directory identifiers should be converted to stable strings by the host. Audit records preserve both the ID and the display-name snapshot.")}` },
        { title: "Choose storage and database ownership", html: `<p>Multi-node deployments need a shared persistent <code>StorageAdapter</code>. For PostgreSQL, set the DataSource current schema and the FileWeft schema assertion to the same value.</p>${code("YAML", `spring:\n  datasource:\n    url: jdbc:postgresql://db:5432/app?currentSchema=fileweft\n\nfileweft:\n  persistence:\n    migration-mode: validate\n    schema: fileweft\n    create-schema: false`)}` },
        { title: "Separate runtime roles", html: `<p>Run API nodes without queue consumption. Run separate Worker nodes against the same database and storage with only the processors they need. This keeps HTTP latency and background leases independent.</p>` },
      ],
    },
    {
      title: "装配可信宿主",
      nav: "首次接入",
      lead: "生产宿主必须提供可信租户、身份与授权上下文、共享持久化 StorageAdapter，以及明确的迁移策略。",
      sections: [
        { title: "提供可信上下文", html: `<p>从宿主已经认证的数据实现 <code>TenantProvider</code>、<code>UserRealmProvider</code> 与 <code>AuthorizationProvider</code>。Controller 不能把租户 ID、用户 ID、角色或权限结果作为业务参数接收。</p>${note("ID", "用户 ID 是不透明字符串", "Long、Int、UUID 与外部目录 ID 都应由宿主稳定转换为字符串。审计同时保存 ID 与当时的显示名快照。")}` },
        { title: "选择存储和数据库所有权", html: `<p>多节点部署需要共享、持久的 <code>StorageAdapter</code>。使用 PostgreSQL 时，DataSource 当前 schema 与 FileWeft schema 安全断言必须一致。</p>${code("YAML", `spring:\n  datasource:\n    url: jdbc:postgresql://db:5432/app?currentSchema=fileweft\n\nfileweft:\n  persistence:\n    migration-mode: validate\n    schema: fileweft\n    create-schema: false`)}` },
        { title: "拆分运行角色", html: `<p>API 节点不消费队列，独立 Worker 使用相同数据库和存储，仅开启所需处理器。这样 HTTP 延迟与后台租约互不干扰。</p>` },
      ],
    }),

  "concepts/module-boundaries": page("concepts", 1,
    {
      title: "Boundaries before features",
      nav: "Module boundaries",
      lead: "FileWeft keeps policy, orchestration and vendor integration in separate modules so extension does not erode compatibility.",
      sections: [
        { title: "Dependency direction", html: `<div class="architecture-stack"><div>starter → application → domain → core</div><div>adapter → spi</div></div><p>Core contains identifiers, results, errors, events and context only. Domain contains business rules. Application owns use cases. Adapters own external implementations.</p>` },
        { title: "Forbidden shortcuts", html: `<ul><li>Core must not depend on Spring or a database.</li><li>Domain must not call MinIO, Dify or another vendor SDK.</li><li>SPI must not expose vendor types.</li><li>Controllers validate and convert; they do not access storage or repositories.</li></ul>` },
      ],
    },
    {
      title: "先守边界，再加功能",
      nav: "模块边界",
      lead: "FileWeft 将策略、编排与厂商集成拆分在不同模块，保证扩展不会侵蚀兼容性。",
      sections: [
        { title: "依赖方向", html: `<div class="architecture-stack"><div>starter → application → domain → core</div><div>adapter → spi</div></div><p>Core 只放标识、结果、错误、事件与上下文；Domain 放业务规则；Application 负责编排用例；Adapter 负责外部实现。</p>` },
        { title: "禁止走捷径", html: `<ul><li>Core 不依赖 Spring 或数据库。</li><li>Domain 不调用 MinIO、Dify 等厂商 SDK。</li><li>SPI 不暴露厂商类型。</li><li>Controller 只校验和转换，不访问存储或仓储。</li></ul>` },
      ],
    }),

  "concepts/lifecycle-delivery": page("concepts", 2,
    {
      title: "Lifecycle is evidence, not a flag",
      nav: "Lifecycle & delivery",
      lead: "Drafts become reviewable, published and removable through explicit transitions. Delivery to multiple downstream systems is tracked per target and per generation.",
      sections: [
        { title: "Document path", html: `<p>A common controlled path is <code>DRAFT → PENDING_REVIEW → PUBLISHING → PUBLISHED</code>. Rework uses <code>PUBLISHED → OFFLINE → restore → DRAFT</code>; archive is explicit. State changes, audit and Outbox work commit together.</p>` },
        { title: "Partial downstream failure", html: `<p>Required targets block the published projection when failing; optional targets may fail while the document remains published. Successful targets are not rolled back automatically. Operators retry only the failed delivery or removal target.</p>${note("N", "Generation fencing", "Each new publication creates a delivery generation. Late results from an older generation cannot overwrite current state.")}` },
      ],
    },
    {
      title: "生命周期是证据，不是一个布尔值",
      nav: "生命周期与交付",
      lead: "草稿通过显式状态转换进入审批、发布和撤回；多个下游按目标、按发布代次分别跟踪。",
      sections: [
        { title: "文档路径", html: `<p>常见受控路径是 <code>DRAFT → PENDING_REVIEW → PUBLISHING → PUBLISHED</code>。返工使用 <code>PUBLISHED → OFFLINE → restore → DRAFT</code>，归档必须显式触发。状态、审计与 Outbox 在同一事务提交。</p>` },
        { title: "下游部分失败", html: `<p>必达目标失败会阻止已发布投影；可选目标失败时文档仍可保持发布。已成功目标不会自动回滚，运维人员只重试失败的交付或撤回目标。</p>${note("N", "代次围栏", "每次重新发布都会形成新的交付代次，旧代次的迟到结果不能覆盖当前状态。")}` },
      ],
    }),

  "concepts/tenant-catalog": page("concepts", 3,
    {
      title: "Tenant and catalog isolation",
      nav: "Tenancy & file trees",
      lead: "Tenant scope is trusted context, while the host's file tree is an authorization surface supplied through DocumentCatalogProvider.",
      sections: [
        { title: "Tenant everywhere", html: `<p>Tenant context constrains database reads, storage paths, events, tasks, logs and caches. A request parameter is never a trusted tenant source. Repository implementations must filter by tenant even when an ID appears globally unique.</p>` },
        { title: "Folders remain host-owned", html: `<p>The browser submits only a bounded opaque <code>folderId</code>. FileWeft asks the host catalog with trusted tenant, user and operation context, then stores the returned canonical ID as <code>catalog.folder-id</code> metadata. Object keys do not contain folder IDs.</p>${note("ACL", "No silent fallback", "Once catalog mode is enabled, missing safe mutation capability returns feature unavailable. It never falls back to a tenant-wide write path.")}` },
        { title: "Stable IDs", html: `<p>Folder IDs may begin as numbers, UUIDs or composite external keys, but are converted to stable strings. Rename or re-parent a folder without changing its canonical ID. A real ID change requires an explicit catalog move.</p>` },
      ],
    },
    {
      title: "租户与目录隔离",
      nav: "租户与文件树",
      lead: "租户来自可信上下文，宿主文件树则由 DocumentCatalogProvider 提供，并构成真实授权边界。",
      sections: [
        { title: "租户贯穿所有层", html: `<p>租户上下文约束数据库查询、存储路径、事件、任务、日志和缓存。请求参数永远不是可信租户来源，即使 ID 看似全局唯一，仓储也必须按租户过滤。</p>` },
        { title: "目录归宿主所有", html: `<p>浏览器只提交有界的不透明 <code>folderId</code>。FileWeft 使用可信租户、用户和操作上下文调用宿主目录，再将返回的 canonical ID 作为 <code>catalog.folder-id</code> 元数据保存。对象键不包含目录 ID。</p>${note("ACL", "不会静默降级", "目录模式开启后，缺少安全修改能力会返回功能不可用，不会降级到租户级写入路径。")}` },
        { title: "稳定 ID", html: `<p>目录 ID 可以来自数字、UUID 或外部组合键，但应转换为稳定字符串。目录改名或换父级时保持 canonical ID；确需换 ID 时执行显式目录移动。</p>` },
      ],
    }),

  "guides/spring-boot": page("guides", 1,
    {
      title: "Assemble Spring Boot safely",
      nav: "Spring Boot hosting",
      lead: "The runtime and Web starters are additive adapters. Your host remains responsible for authentication, gateway policy, DataSource ownership and explicit capability selection.",
      sections: [
        { title: "Match the generation", html: `${table(["Host", "Runtime", "HTTP"], [["Spring Boot 3 / Java 17+", "fileweft-spring-boot3-starter", "fileweft-web-spring-boot3-starter"], ["Spring Boot 2.7 / Java 8+", "fileweft-spring-boot2-starter", "fileweft-web-spring-boot2-starter"]])}<p>Do not install both generations. Web starters do not implicitly add persistence or replace runtime starters.</p>` },
        { title: "Secure the edge", html: `<p>Bind your verified authentication to the tenant, user and authorization SPIs. Configure CORS, CSRF, OAuth/OIDC, mTLS, upload limits, timeout and rate limiting in the host or gateway. FileWeft deliberately supplies no weak default authentication.</p>` },
      ],
    },
    {
      title: "安全装配 Spring Boot",
      nav: "Spring Boot 宿主",
      lead: "运行时与 Web Starter 都是加法适配器。认证、网关策略、DataSource 所有权和能力选择仍由宿主负责。",
      sections: [
        { title: "匹配 Boot 代际", html: `${table(["宿主", "运行时", "HTTP"], [["Spring Boot 3 / Java 17+", "fileweft-spring-boot3-starter", "fileweft-web-spring-boot3-starter"], ["Spring Boot 2.7 / Java 8+", "fileweft-spring-boot2-starter", "fileweft-web-spring-boot2-starter"]])}<p>不能同时安装两个代际。Web Starter 不会隐式引入持久化，也不替代运行时 Starter。</p>` },
        { title: "保护入口", html: `<p>将已验证身份绑定到租户、用户和授权 SPI。CORS、CSRF、OAuth/OIDC、mTLS、上传限制、超时与限流由宿主或网关配置。FileWeft 不提供弱默认认证。</p>` },
      ],
    }),

  "guides/workflows-uploads": page("guides", 2,
    {
      title: "Reviews, resumable bytes and agents",
      nav: "Workflow & uploads",
      lead: "Long-running work is explicit, persistent and fenced. Approval routing, multipart upload and AI processing remain replaceable without weakening transaction boundaries.",
      sections: [
        { title: "Approval routing", html: `<p><code>DocumentReviewRouteProvider</code> returns one or more review tasks outside the database transaction. All tasks must approve for parallel sign-off; one rejection ends the workflow. The final transaction rechecks document state before committing.</p>` },
        { title: "Resumable upload", html: `<ol><li>Start with a caller-stable idempotency key.</li><li>Upload numbered parts and persist each acknowledgement.</li><li>Inspect the session after reconnecting.</li><li>Complete once to create the object, asset and event; abort when intentionally abandoned.</li></ol><p>Sessions bind to trusted tenant and user identity. Storage upload IDs and object paths never reach the browser.</p>` },
        { title: "Agent work", html: `<p>AI and diagnostic work belong in durable <code>fw_task</code> handlers. Workers use leases and idempotent task IDs. Agent output becomes visible only after the matching task reaches a fenced successful terminal state.</p>` },
      ],
    },
    {
      title: "审批、断点字节与 Agent",
      nav: "工作流与上传",
      lead: "长任务必须显式、持久且具备围栏。审批路由、分片上传和 AI 处理都可以替换，但不能削弱事务边界。",
      sections: [
        { title: "审批路由", html: `<p><code>DocumentReviewRouteProvider</code> 在数据库事务外返回一个或多个审批任务。并行会签要求全部通过，任一驳回结束流程；最终事务在提交前重新检查文档状态。</p>` },
        { title: "断点续传", html: `<ol><li>使用调用方稳定幂等键启动。</li><li>上传编号分片并持久化每次确认。</li><li>重连后从服务端检查会话。</li><li>幂等完成以创建对象、资产与事件；明确放弃时终止。</li></ol><p>会话绑定可信租户和用户，底层 upload ID 与对象路径不会交给浏览器。</p>` },
        { title: "Agent 任务", html: `<p>AI 与诊断任务应进入持久化 <code>fw_task</code> handler。Worker 使用租约和幂等任务 ID，只有匹配任务在围栏下成功终态后，Agent 结果才可见。</p>` },
      ],
    }),

  "architecture/consistency": page("architecture", 1,
    {
      title: "Local atomicity, explicit convergence",
      nav: "Consistency model",
      lead: "FileWeft does not promise a distributed transaction across PostgreSQL, object storage and downstream systems. It makes local state atomic and remote convergence observable.",
      sections: [
        { title: "Transactional Outbox", html: `<div class="architecture-stack"><div>business transaction + Outbox record</div><div>commit local truth</div><div>async Worker claims with lease</div><div>connector call + fenced projection</div></div><p>Never call a downstream connector inside the business transaction. Retried connector calls must accept stable idempotency identity.</p>` },
        { title: "Storage compensation", html: `<p>When uploaded bytes fail validation or the local transaction definitely rolls back without references, FileWeft compensates by deleting the object. When commit outcome is unknown, it reconciles first and preserves evidence rather than risking deletion of committed data.</p>` },
        { title: "Lock order", html: `<p>Idempotent catalog-aware review paths follow a stable order: idempotency → document → asset → workflow. External catalog, review-route and delivery-policy calls happen outside this final short transaction.</p>` },
      ],
    },
    {
      title: "本地原子，显式收敛",
      nav: "一致性模型",
      lead: "FileWeft 不承诺跨 PostgreSQL、对象存储和多个下游的分布式事务，而是保证本地状态原子，并让远端收敛过程可观测。",
      sections: [
        { title: "事务 Outbox", html: `<div class="architecture-stack"><div>业务事务 + Outbox 记录</div><div>提交本地事实</div><div>异步 Worker 带租约领取</div><div>连接器调用 + 围栏投影</div></div><p>不能在业务事务中调用下游连接器。连接器重试必须接受稳定幂等标识。</p>` },
        { title: "存储补偿", html: `<p>上传字节校验失败，或本地事务明确回滚且无持久引用时，FileWeft 删除对象进行补偿。提交结果未知时先对账并保留证据，避免误删已提交数据。</p>` },
        { title: "锁顺序", html: `<p>目录感知的幂等审批路径遵循 idempotency → document → asset → workflow。外部目录、审批路由和交付策略调用位于最终短事务之外。</p>` },
      ],
    }),

  "architecture/security": page("architecture", 2,
    {
      title: "Fail closed at every boundary",
      nav: "Security architecture",
      lead: "Capabilities are installed only when their complete security boundary exists. Missing context or ambiguous providers make the operation unavailable instead of silently broadening access.",
      sections: [
        { title: "Capability assembly", html: `<p>A single unambiguous provider is required at each boundary. Multiple catalog or lifecycle candidates are not resolved by guessing or <code>@Primary</code> when that could change security semantics. Custom persistence must implement real mutation locks and atomic idempotency claims before guarded writes are exposed.</p>` },
        { title: "Public projections", html: `<p>HTTP DTOs omit storage URLs, object keys, connector internals, raw Doctor evidence, tenant identifiers and unsafe diagnostic text. Audit views expose stable action and operator snapshots, not unrestricted details JSON.</p>` },
        { title: "Plugins are trusted code", html: note("!", "No in-process sandbox", "A plugin shares the host JVM, permissions and classpath. Install only reviewed artifacts. Run untrusted extensions in a separate process behind authenticated, limited and audited protocols.", true) },
      ],
    },
    {
      title: "所有边界都失败关闭",
      nav: "安全架构",
      lead: "只有完整安全边界存在时才装配能力。上下文缺失或 Provider 歧义会让操作不可用，而不是静默扩大访问范围。",
      sections: [
        { title: "能力装配", html: `<p>每个边界都要求唯一且无歧义的 Provider。多个目录或生命周期候选如果会改变安全语义，就不会通过猜测或 <code>@Primary</code> 选择。自定义持久化必须先提供真实修改锁与原子幂等 claim，才能开放受保护写入。</p>` },
        { title: "公共投影", html: `<p>HTTP DTO 不暴露存储 URL、对象键、连接器内部信息、Doctor 原始证据、租户标识或不安全诊断文本。审计视图提供稳定动作和操作者快照，不返回任意 details JSON。</p>` },
        { title: "插件是可信代码", html: note("!", "进程内没有沙箱", "插件与宿主共享 JVM、权限和类路径，只能安装经过评审的制品。不可信扩展应放在独立进程，通过鉴权、限流、审计的协议接入。", true) },
      ],
    }),

  "reference/spi": page("reference", 1,
    {
      title: "SPI surface",
      nav: "SPI index",
      lead: "Contracts keep infrastructure and host policy replaceable while preserving trustworthy context and Java-friendly APIs.",
      sections: [
        { title: "Primary extension families", html: table(["Area", "Contract responsibility"], [["Identity & tenant", "Trusted current tenant, current user and authorization decisions"], ["Storage", "Tenant-scoped object and multipart operations"], ["Catalog", "Host folder topology, canonical IDs and action-aware ACL"], ["Workflow", "Approval routes and task definitions"], ["Connector", "Idempotent downstream delivery, removal and health"], ["Doctor & metrics", "Bounded diagnostics, counters, gauges and trace scope"], ["Agent & task", "Durable handlers and AI contributions"]]) },
        { title: "Public API discipline", html: `<p>Public contracts target Java callers. Avoid suspend functions, Kotlin Flow, value classes, sealed interfaces and data objects. IDs remain opaque strings; vendor SDK models remain inside adapters.</p>` },
      ],
    },
    {
      title: "SPI 总览",
      nav: "SPI 索引",
      lead: "契约让基础设施和宿主策略可替换，同时保持可信上下文与 Java 友好的公共 API。",
      sections: [
        { title: "主要扩展族", html: table(["领域", "契约职责"], [["身份与租户", "可信当前租户、当前用户与授权决策"], ["存储", "租户作用域对象与分片操作"], ["目录", "宿主目录拓扑、canonical ID 与动作级 ACL"], ["工作流", "审批路由与任务定义"], ["连接器", "幂等下游交付、撤回与健康检查"], ["Doctor 与指标", "有界诊断、计数、Gauge 与 Trace scope"], ["Agent 与任务", "持久 Handler 与 AI 贡献"]]) },
        { title: "公共 API 纪律", html: `<p>公共契约面向 Java 调用方，不暴露 suspend、Kotlin Flow、value class、sealed interface 或 data object。ID 保持不透明字符串，厂商 SDK 模型只留在 Adapter。</p>` },
      ],
    }),

  "reference/http-api": page("reference", 2,
    {
      title: "HTTP API v1",
      nav: "HTTP API v1",
      lead: "The formal surface lives under /fileweft/v1 and returns a stable JSON envelope, except for authorized binary downloads.",
      sections: [
        { title: "Resource families", html: `<ul><li><code>/fileweft/v1/documents</code> — list, create, inspect, rename, version and lifecycle.</li><li><code>/fileweft/v1/workflows/tasks</code> — trusted-user review inbox and decisions.</li><li><code>/fileweft/v1/documents/{id}/sync-status</code> — safe delivery projection and explicit retry commands.</li><li><code>/fileweft/v1/documents/{id}/doctor</code> and <code>/fileweft/v1/doctor</code> — document and system diagnostics.</li><li><code>/fileweft/v1/plugins</code> and <code>/fileweft/v1/health</code> — safe inventory and process liveness.</li></ul>` },
        { title: "Stable envelope", html: code("JSON", `{
  "code": "OK",
  "message": "OK",
  "data": {},
  "error": null,
  "traceId": "optional-host-trace-id"
}`) },
        { title: "Idempotent commands", html: `<p>Lifecycle, review, delivery recovery and Doctor scheduling require exactly one <code>Idempotency-Key</code>. The server stores only a tenant-scoped SHA-256 digest and binds it to the trusted operator, action, resource and typed-command fingerprint.</p>${note("KEY", "Replay still authorizes", "Authentication, action permission and catalog visibility run again before every replay. An idempotency record is not an authorization cache.")}` },
      ],
    },
    {
      title: "HTTP API v1",
      nav: "HTTP API v1",
      lead: "正式接口统一位于 /fileweft/v1；除授权二进制下载外，响应使用稳定 JSON 外层。",
      sections: [
        { title: "资源族", html: `<ul><li><code>/fileweft/v1/documents</code> — 列表、创建、详情、改名、版本与生命周期。</li><li><code>/fileweft/v1/workflows/tasks</code> — 当前可信用户的审批待办与决策。</li><li><code>/fileweft/v1/documents/{id}/sync-status</code> — 安全交付投影与显式重试。</li><li><code>/fileweft/v1/documents/{id}/doctor</code> 和 <code>/fileweft/v1/doctor</code> — 文档与系统诊断。</li><li><code>/fileweft/v1/plugins</code> 与 <code>/fileweft/v1/health</code> — 安全清单与进程存活。</li></ul>` },
        { title: "稳定外层", html: code("JSON", `{
  "code": "OK",
  "message": "OK",
  "data": {},
  "error": null,
  "traceId": "optional-host-trace-id"
}`) },
        { title: "幂等命令", html: `<p>生命周期、审批、交付恢复与 Doctor 排队必须带且只带一个 <code>Idempotency-Key</code>。服务端只保存租户作用域 SHA-256 摘要，并绑定可信操作者、动作、资源和 typed-command 指纹。</p>${note("KEY", "重放仍需授权", "每次重放前都会重新执行认证、动作权限和目录可见性检查，幂等记录不是权限缓存。")}` },
      ],
    }),

  "reference/configuration": page("reference", 3,
    {
      title: "Configuration map",
      nav: "Configuration",
      lead: "Production-safe defaults disable implicit tenant, local storage and migration behavior. Enable each fallback or runtime role deliberately.",
      sections: [
        { title: "Persistence", html: code("YAML", `fileweft:\n  persistence:\n    migration-mode: validate # migrate | validate | disabled\n    schema: fileweft\n    create-schema: false`) },
        { title: "Workers and observation", html: code("YAML", `fileweft:\n  worker:\n    enabled: true\n    process-outbox: true\n  outbox:\n    backlog-metrics-enabled: true\n    backlog-metrics-interval-millis: 30000\n    backlog-metrics-query-timeout-seconds: 5`) },
        { title: "Explicit development fallbacks", html: code("Properties", `fileweft.default-tenant-enabled=true\nfileweft.default-tenant-id=tenant-a\nfileweft.storage.local-enabled=true\nfileweft.storage.local-root=/var/lib/fileweft`) + note("!", "Not a multi-node production setup", "Fixed tenant and local filesystem fallbacks are for reviewed single-tenant or development deployments. Doctor reports them as warnings.", true) },
      ],
    },
    {
      title: "配置地图",
      nav: "配置",
      lead: "安全生产默认值不会隐式选择租户、本地存储或迁移行为。每个 fallback 和运行角色都必须显式开启。",
      sections: [
        { title: "持久化", html: code("YAML", `fileweft:\n  persistence:\n    migration-mode: validate # migrate | validate | disabled\n    schema: fileweft\n    create-schema: false`) },
        { title: "Worker 与观测", html: code("YAML", `fileweft:\n  worker:\n    enabled: true\n    process-outbox: true\n  outbox:\n    backlog-metrics-enabled: true\n    backlog-metrics-interval-millis: 30000\n    backlog-metrics-query-timeout-seconds: 5`) },
        { title: "显式开发 fallback", html: code("Properties", `fileweft.default-tenant-enabled=true\nfileweft.default-tenant-id=tenant-a\nfileweft.storage.local-enabled=true\nfileweft.storage.local-root=/var/lib/fileweft`) + note("!", "不适用于多节点生产", "固定租户与本地文件系统只适合经过评审的单租户或开发部署，Doctor 会将其报告为警告。", true) },
      ],
    }),

  "operations/deployment": page("operations", 1,
    {
      title: "Deploy distinct runtime roles",
      nav: "Production deployment",
      lead: "Use one validated artifact with intentionally different API, Worker and migration-job configurations. Share database and object storage; do not share privileges unnecessarily.",
      sections: [
        { title: "Recommended topology", html: `<div class="architecture-stack"><div>Migration Job · DDL identity · migrate</div><div>API nodes · read/write identity · validate</div><div>Outbox Workers · queue + connector identity · validate</div><div>Task Workers · task-specific identity · validate</div></div>` },
        { title: "Rollout order", html: `<ol><li>Back up and verify recovery.</li><li>Run a controlled migration job with exclusive migration ownership.</li><li>Start API and Worker roles in <code>validate</code> mode.</li><li>Observe health, Doctor, Outbox ready age and lease recovery.</li><li>Enable traffic only after validation succeeds.</li></ol>` },
        { title: "Credential boundaries", html: `<p>Do not give long-lived API or Worker processes schema-creation credentials. Connector credentials belong only to Worker roles that invoke those connectors. Browser clients never receive object-storage credentials or downstream secrets.</p>` },
      ],
    },
    {
      title: "按运行角色部署",
      nav: "生产部署",
      lead: "同一份已验证制品通过不同配置承担 API、Worker 与迁移 Job。共享数据库和对象存储，但不应无差别共享权限。",
      sections: [
        { title: "推荐拓扑", html: `<div class="architecture-stack"><div>Migration Job · DDL 身份 · migrate</div><div>API 节点 · 业务读写身份 · validate</div><div>Outbox Worker · 队列与连接器身份 · validate</div><div>Task Worker · 任务专用身份 · validate</div></div>` },
        { title: "发布顺序", html: `<ol><li>备份并实际验证恢复。</li><li>由唯一迁移所有者运行受控迁移 Job。</li><li>以 <code>validate</code> 启动 API 和 Worker。</li><li>观察 health、Doctor、Outbox ready age 和租约恢复。</li><li>校验通过后再开放流量。</li></ol>` },
        { title: "凭据边界", html: `<p>长期运行的 API 或 Worker 不应持有建 schema 权限。连接器凭据只交给实际调用该连接器的 Worker。浏览器不会获得对象存储凭据或下游密钥。</p>` },
      ],
    }),

  "operations/doctor-observability": page("operations", 2,
    {
      title: "Operate from evidence",
      nav: "Doctor & observability",
      lead: "Doctor explains component health through safe projections; metrics show bounded trends; audit and Trace locate tenant and resource evidence without high-cardinality labels.",
      sections: [
        { title: "Three Doctor paths", html: table(["Path", "Purpose"], [["Immediate document", "Bounded interactive checks after document and catalog authorization"], ["Asynchronous document", "Durable, idempotent diagnostics with fenced Worker result"], ["System", "Tenant runtime checks requiring system:doctor:read"]]) },
        { title: "Core metrics", html: `<p>Count uploads, synchronization, delivery removal, task and Doctor outcomes under the <code>fileweft.</code> prefix. Outbox gauges expose fixed <code>ready</code>, <code>delayed</code>, <code>running</code>, <code>expired</code> and <code>failed</code> states plus the oldest ready age.</p>${note("#", "Keep labels bounded", "Default metrics drop tenant, document and user identifiers. Use audit, operation logs and Trace for resource-level investigation.")}` },
      ],
    },
    {
      title: "从证据出发运维",
      nav: "Doctor 与可观测性",
      lead: "Doctor 通过安全投影解释组件健康；指标展示有界趋势；审计与 Trace 定位租户和资源证据，同时避免高基数标签。",
      sections: [
        { title: "三条 Doctor 路径", html: table(["路径", "用途"], [["即时文档", "通过文档和目录授权后的有界交互式检查"], ["异步文档", "持久、幂等且具备 Worker 围栏的诊断"], ["系统", "要求 system:doctor:read 的租户运行时检查"]]) },
        { title: "核心指标", html: `<p>上传、同步、撤回、任务和 Doctor 结果以 <code>fileweft.</code> 前缀计数。Outbox Gauge 只包含固定的 <code>ready</code>、<code>delayed</code>、<code>running</code>、<code>expired</code>、<code>failed</code> 与最老 ready age。</p>${note("#", "保持标签有界", "默认指标会丢弃租户、文档和用户标识。资源级排障应使用审计、操作日志与 Trace。")}` },
      ],
    }),

  "operations/migrations-release": page("operations", 3,
    {
      title: "Migrate and release deliberately",
      nav: "Migrations & releases",
      lead: "FileWeft owns a namespaced Flyway location and history table. Release gates test compatibility, real infrastructure paths, SBOM and reproducible dependency state.",
      sections: [
        { title: "Migration namespace", html: `<p>Resources live only at <code>classpath:ai/icen/fw/db/migration</code> and history lives in <code>fileweft_schema_history</code>. Do not append these resources to the host's Flyway locations or merge them into <code>flyway_schema_history</code>.</p>` },
        { title: "Old trial databases", html: `${note("!", "No automatic adoption", `A database previously run with ${withdrawnGroup} trial artifacts must be stopped, backed up and inspected by a DBA. Do not baseline, repair, copy or delete history rows to bypass ownership analysis.`, true)}` },
        { title: "Release gates", html: code("PowerShell", `.\\gradlew.bat check --no-daemon\n.\\gradlew.bat compatibilityCheck --no-daemon\n.\\gradlew.bat verifySbom --no-daemon`) + `<p>The formal release pipeline also enables PostgreSQL, RustFS, Dev API and browser acceptance suites against the same healthy development stack.</p>` },
      ],
    },
    {
      title: "审慎迁移与发布",
      nav: "迁移与发布",
      lead: "FileWeft 使用独立 Flyway 资源路径和历史表。发布门禁覆盖兼容矩阵、真实基础设施链路、SBOM 和可复现依赖状态。",
      sections: [
        { title: "迁移命名空间", html: `<p>迁移资源只位于 <code>classpath:ai/icen/fw/db/migration</code>，历史只写入 <code>fileweft_schema_history</code>。不能把资源追加到宿主 Flyway locations，也不能合并进 <code>flyway_schema_history</code>。</p>` },
        { title: "早期试推数据库", html: `${note("!", "不会自动收养", `运行过 ${withdrawnGroup} 试推制品的数据库必须停机、备份并由 DBA 核验。不能通过 baseline、repair、复制或删除 history 行绕过所有权分析。`, true)}` },
        { title: "发布门禁", html: code("PowerShell", `.\\gradlew.bat check --no-daemon\n.\\gradlew.bat compatibilityCheck --no-daemon\n.\\gradlew.bat verifySbom --no-daemon`) + `<p>正式发布流水线还会在同一套健康开发编排上开启 PostgreSQL、RustFS、Dev API 与浏览器验收。</p>` },
      ],
    }),

  "extensions/plugins": page("extensions", 1,
    {
      title: "Build a disciplined plugin",
      nav: "Plugin development",
      lead: "Plugins aggregate existing SPI contributions. They do not create a new architectural layer or an in-process security sandbox.",
      sections: [
        { title: "Contribution model", html: `<p>A <code>FileWeftPlugin</code> may contribute connectors, storage, Doctor checkers, task handlers, review routes, metrics or agents through existing contracts. Contribution getters are called once during registry construction and captured as immutable snapshots.</p>` },
        { title: "Discovery", html: `<p>Register a reviewed Spring Bean or use Java <code>ServiceLoader</code> metadata when the host supports it. Plugin IDs must be stable, bounded and safe for public inventory. Never perform remote calls or business side effects in contribution getters.</p>` },
        { title: "Verification", html: `<ul><li>Unit-test plugin decisions.</li><li>Run SPI contract tests for each adapter.</li><li>Start the matching Starter context.</li><li>Use Doctor to cover missing configuration and remote failure.</li><li>Verify the public plugin inventory remains redacted.</li></ul>` },
      ],
    },
    {
      title: "编写克制的插件",
      nav: "插件开发",
      lead: "插件聚合已有 SPI 贡献，不会形成新的架构层，也不是进程内安全沙箱。",
      sections: [
        { title: "贡献模型", html: `<p><code>FileWeftPlugin</code> 可以通过已有契约贡献连接器、存储、Doctor checker、任务 handler、审批路由、指标或 Agent。注册表构造时只调用一次贡献 getter，并保存不可变快照。</p>` },
        { title: "发现方式", html: `<p>可以注册经过评审的 Spring Bean，或在宿主支持时使用 Java <code>ServiceLoader</code> 元数据。插件 ID 必须稳定、有界，适合公共清单。贡献 getter 不能远程调用，也不能包含业务副作用。</p>` },
        { title: "验证", html: `<ul><li>单元测试插件决策。</li><li>为每个 Adapter 运行 SPI 契约测试。</li><li>启动匹配的 Starter Context。</li><li>用 Doctor 覆盖缺配置与远端故障。</li><li>确认公共插件清单保持脱敏。</li></ul>` },
      ],
    }),

  "extensions/connectors": page("extensions", 2,
    {
      title: "Connect unreliable systems",
      nav: "Connector engineering",
      lead: "A connector translates a stable FileWeft delivery contract into one vendor integration. Timeouts, retries, idempotency, removal and health are part of its design, not afterthoughts.",
      sections: [
        { title: "Required behavior", html: `<ul><li>Bound every network call with a timeout.</li><li>Use the stable target or document identity as the downstream idempotency key.</li><li>Classify retryable and permanent failures without leaking credentials.</li><li>Return an external ID for later explicit removal.</li><li>Implement a read-only health check suitable for Doctor.</li></ul>` },
        { title: "Multiple targets", html: `<p><code>DocumentDeliveryProfileProvider</code> returns tenant-specific profiles containing required or optional target definitions. <code>DeliveryConnectorResolver</code> maps each stable <code>connectorId</code> to a connector instance without exposing Spring or vendor SDKs to SPI.</p>` },
        { title: "Test the contract", html: `<p>Integration tests should prove idempotent repeated delivery, safe repeated removal, timeout, retry classification, redacted failure text, health behavior and recovery after the external system becomes available.</p>` },
      ],
    },
    {
      title: "连接不可靠的外部系统",
      nav: "连接器工程",
      lead: "连接器将稳定的 FileWeft 交付契约转换为一个厂商集成。超时、重试、幂等、撤回和健康检查都是设计的一部分。",
      sections: [
        { title: "必备行为", html: `<ul><li>所有网络调用设置有界超时。</li><li>使用稳定目标或文档标识作为下游幂等键。</li><li>区分可重试与永久失败，且不泄漏凭据。</li><li>返回外部 ID，供后续显式撤回。</li><li>提供适合 Doctor 的只读健康检查。</li></ul>` },
        { title: "多个目标", html: `<p><code>DocumentDeliveryProfileProvider</code> 返回租户级档案，其中目标分为必达或可选。<code>DeliveryConnectorResolver</code> 将稳定 <code>connectorId</code> 解析到连接器实例，不向 SPI 泄漏 Spring 或厂商 SDK。</p>` },
        { title: "契约测试", html: `<p>集成测试应证明重复交付幂等、重复撤回安全、超时、重试分类、错误文本脱敏、健康检查，以及外部系统恢复后的收敛。</p>` },
      ],
    }),

  "project/contributing": page("project", 1,
    {
      title: "Contribute without eroding the foundation",
      nav: "Contributing",
      lead: "Changes move from Core through SPI, Domain, Application, Persistence, Starter and Adapter only when the responsibility belongs there. Tests follow the same boundary.",
      sections: [
        { title: "Before code", html: `<ol><li>Read the repository AI implementation manuals and the directly relevant extension material.</li><li>Identify the owning module and existing SPI.</li><li>Describe compatibility and migration impact for architectural changes.</li><li>Design how an operator will diagnose failure.</li></ol>` },
        { title: "Test by layer", html: table(["Layer", "Required evidence"], [["Core / Domain", "Focused unit tests and invariants"], ["SPI", "Contract tests and Java-friendly usage"], ["Adapter / Persistence", "Real integration tests"], ["Starter / Web", "Context and Boot 2/3 contract tests"], ["Release", "Compatibility matrix, Compose acceptance, browser E2E and SBOM"]]) },
        { title: "Change hygiene", html: `<p>Use UTF-8, small focused classes and explicit dependencies. Preserve tenant filtering and lock order. Do not rewrite unrelated user changes. Commit coherent milestones with action-oriented messages.</p>` },
      ],
    },
    {
      title: "贡献功能，不侵蚀地基",
      nav: "参与贡献",
      lead: "只有职责确实属于对应层时，改动才沿 Core、SPI、Domain、Application、Persistence、Starter 与 Adapter 推进；测试也遵守相同边界。",
      sections: [
        { title: "编码之前", html: `<ol><li>阅读仓库 AI 实施手册及直接相关扩展材料。</li><li>确认职责所属模块和现有 SPI。</li><li>架构变化需说明兼容与迁移影响。</li><li>设计运维人员如何诊断失败。</li></ol>` },
        { title: "分层测试", html: table(["层", "必要证据"], [["Core / Domain", "聚焦的单元测试与不变量"], ["SPI", "契约测试与 Java 友好用法"], ["Adapter / Persistence", "真实集成测试"], ["Starter / Web", "Context 与 Boot 2/3 契约测试"], ["Release", "兼容矩阵、Compose 验收、浏览器 E2E 与 SBOM"]]) },
        { title: "变更卫生", html: `<p>使用 UTF-8、小而专注的类和显式依赖；保持租户过滤与锁顺序；不改写无关用户变更；用动作型提交信息记录完整里程碑。</p>` },
      ],
    }),

  "project/security": page("project", 2,
    {
      title: "Report security issues privately",
      nav: "Security",
      lead: "Do not disclose a suspected vulnerability in a public issue. Send a minimal, reproducible report to support@icen.ai and allow time for coordinated remediation.",
      sections: [
        { title: "What to include", html: `<ul><li>Affected FileWeft version and module.</li><li>Deployment assumptions and required privileges.</li><li>Minimal reproduction steps or proof of concept.</li><li>Observed impact and any known mitigation.</li><li>A safe contact for coordinated follow-up.</li></ul>` },
        { title: "Protect sensitive evidence", html: `<p>Remove production credentials, tenant data, user information, object keys and private endpoints. Use synthetic fixtures whenever possible. Never send a live access token in an issue, log excerpt or browser recording.</p>${note("@", "Security contact", "Email support@icen.ai. General usage questions may use the same address, but suspected vulnerabilities should be clearly marked private and security-sensitive.")}` },
      ],
    },
    {
      title: "私密报告安全问题",
      nav: "安全",
      lead: "不要在公开 Issue 披露疑似漏洞。将最小、可复现报告发送到 support@icen.ai，并为协调修复预留时间。",
      sections: [
        { title: "报告内容", html: `<ul><li>受影响的 FileWeft 版本与模块。</li><li>部署前提和所需权限。</li><li>最小复现步骤或概念验证。</li><li>已观察影响和已知缓解方式。</li><li>可用于协调跟进的安全联系方式。</li></ul>` },
        { title: "保护敏感证据", html: `<p>移除生产凭据、租户数据、用户信息、对象键与私有端点，尽量使用合成夹具。不能在 Issue、日志片段或浏览器录屏中发送有效访问令牌。</p>${note("@", "安全联系方式", "发送邮件至 support@icen.ai。一般使用问题也可联系该地址，但疑似漏洞应明确标记为私密、安全敏感。")}` },
      ],
    }),

  "project/release-0-0-1": page("project", 3,
    {
      title: "Release 0.0.1",
      nav: "Release 0.0.1",
      lead: "The first public line establishes ai.icen coordinates, ai.icen.fw packages, namespaced migrations and the production-oriented document, workflow, delivery, Doctor and Web foundations.",
      sections: [
        { title: "Published foundation", html: `<ul><li>Core → SPI → Domain → Application → Persistence → Starter → Adapter module chain.</li><li>Boot 2 and Boot 3 runtime and Web starters.</li><li>PostgreSQL persistence, local and S3-compatible storage paths.</li><li>Durable Outbox, task leases, parallel review and multi-target delivery.</li><li>Formal v1 HTTP surface, catalog-aware ACL, audit, Trace and Doctor.</li></ul>` },
        { title: "Compatibility boundary", html: `<p>The supported namespace is <code>ai.icen:*</code> with JVM package <code>ai.icen.fw</code>. Withdrawn <code>${withdrawnGroup}:*</code> trial artifacts and their shared Flyway history are not automatically adopted.</p>` },
        { title: "License", html: `<p>FileWeft is available under the Apache License 2.0. Copyright belongs to icen.ai. See the repository <code>LICENSE</code> and <code>NOTICE</code> files for authoritative terms.</p>` },
      ],
    },
    {
      title: "0.0.1 正式版",
      nav: "0.0.1 发布说明",
      lead: "首个公开版本建立 ai.icen 坐标、ai.icen.fw 包名、独立迁移命名空间，以及面向生产的文档、审批、交付、Doctor 和 Web 地基。",
      sections: [
        { title: "已发布地基", html: `<ul><li>Core → SPI → Domain → Application → Persistence → Starter → Adapter 模块链路。</li><li>Boot 2 与 Boot 3 运行时及 Web Starter。</li><li>PostgreSQL 持久化、本地与 S3 兼容存储链路。</li><li>持久 Outbox、任务租约、并行审批与多目标交付。</li><li>正式 v1 HTTP、目录 ACL、审计、Trace 与 Doctor。</li></ul>` },
        { title: "兼容边界", html: `<p>受支持命名空间是 <code>ai.icen:*</code>，JVM 包名为 <code>ai.icen.fw</code>。已撤回 <code>${withdrawnGroup}:*</code> 试推制品及其共享 Flyway 历史不会自动收养。</p>` },
        { title: "许可证", html: `<p>FileWeft 使用 Apache License 2.0 开源，版权主体为 icen.ai。权威条款以仓库 <code>LICENSE</code> 与 <code>NOTICE</code> 为准。</p>` },
      ],
    }),
};

export const ui = {
  en: {
    search: "Search documentation", stable: "Stable line", version: "Version", support: "Support",
    onThisPage: "On this page", licensed: "Open infrastructure by icen.ai.", navigate: "navigate",
    open: "open", close: "close", copy: "COPY", copied: "COPIED", copyFailed: "COPY FAILED",
    next: "NEXT", noResults: "No matching documentation", searchPlaceholder: "Search FileWeft",
    skip: "Skip to documentation", navigationLabel: "Documentation navigation", languageLabel: "Documentation language",
    openNav: "Open documentation navigation", closeNav: "Close documentation navigation", closeSearch: "Close search",
    searchTitle: "Search documentation", searchResults: "Search results",
  },
  zh: {
    search: "搜索文档", stable: "稳定版本", version: "版本", support: "支持",
    onThisPage: "本页目录", licensed: "icen.ai 开放基础设施。", navigate: "选择",
    open: "打开", close: "关闭", copy: "复制", copied: "已复制", copyFailed: "复制失败",
    next: "下一页", noResults: "没有匹配的文档", searchPlaceholder: "搜索 FileWeft",
    skip: "跳到文档正文", navigationLabel: "文档导航", languageLabel: "文档语言",
    openNav: "打开文档导航", closeNav: "关闭文档导航", closeSearch: "关闭搜索",
    searchTitle: "搜索文档", searchResults: "搜索结果",
  },
};

export const defaultRoute = "getting-started/introduction";

export function orderedRoutes() {
  const groupOrder = new Map(groups.map((group, index) => [group.id, index]));
  return Object.keys(pages).sort((left, right) => {
    const a = pages[left];
    const b = pages[right];
    return groupOrder.get(a.group) - groupOrder.get(b.group) || a.order - b.order;
  });
}
