export const groups = [
  {
    "id": "getting-started",
    "index": "01",
    "en": "Getting started",
    "zh": "开始使用"
  },
  {
    "id": "concepts",
    "index": "02",
    "en": "Concepts",
    "zh": "核心概念"
  },
  {
    "id": "guides",
    "index": "03",
    "en": "Guides",
    "zh": "使用指南"
  },
  {
    "id": "architecture",
    "index": "04",
    "en": "Architecture",
    "zh": "架构"
  },
  {
    "id": "reference",
    "index": "05",
    "en": "Reference",
    "zh": "参考"
  },
  {
    "id": "operations",
    "index": "06",
    "en": "Operations",
    "zh": "生产运维"
  },
  {
    "id": "extensions",
    "index": "07",
    "en": "Extensions",
    "zh": "扩展"
  },
  {
    "id": "project",
    "index": "08",
    "en": "Project",
    "zh": "项目"
  }
];

export const defaultRoute = "getting-started/introduction";

export const ui = {
  "en": {
    "search": "Search documentation",
    "stable": "Stable line",
    "version": "Version",
    "support": "Support",
    "onThisPage": "On this page",
    "licensed": "Open infrastructure by icen.ai.",
    "navigate": "navigate",
    "open": "open",
    "close": "close",
    "copy": "COPY",
    "copied": "COPIED",
    "copyFailed": "COPY FAILED",
    "next": "NEXT",
    "noResults": "No matching documentation",
    "searchPlaceholder": "Search FileWeft",
    "skip": "Skip to documentation",
    "navigationLabel": "Documentation navigation",
    "languageLabel": "Documentation language",
    "openNav": "Open documentation navigation",
    "closeNav": "Close documentation navigation",
    "closeSearch": "Close search",
    "searchTitle": "Search documentation",
    "searchResults": "Search results"
  },
  "zh": {
    "search": "搜索文档",
    "stable": "稳定版本",
    "version": "版本",
    "support": "支持",
    "onThisPage": "本页目录",
    "licensed": "icen.ai 开放基础设施。",
    "navigate": "选择",
    "open": "打开",
    "close": "关闭",
    "copy": "复制",
    "copied": "已复制",
    "copyFailed": "复制失败",
    "next": "下一页",
    "noResults": "没有匹配的文档",
    "searchPlaceholder": "搜索 FileWeft",
    "skip": "跳到文档正文",
    "navigationLabel": "文档导航",
    "languageLabel": "文档语言",
    "openNav": "打开文档导航",
    "closeNav": "关闭文档导航",
    "closeSearch": "关闭搜索",
    "searchTitle": "搜索文档",
    "searchResults": "搜索结果"
  }
};

export const pages = {
  "getting-started/introduction": {
    group: "getting-started",
    order: 1,
    en: { nav: "Introduction", title: "Infrastructure for files that must endure", lead: "FileWeft is an extensible Kotlin/JVM foundation for enterprise document lifecycles, storage, approvals, delivery and diagnostics — without taking ownership of your identity, folder tree or business rules.", file: "pages/en/getting-started/introduction.md" },
    zh: { nav: "介绍", title: "为必须长久运行的文件系统打地基", lead: "FileWeft 是可扩展的 Kotlin/JVM 企业文件基础设施，覆盖文档生命周期、存储、审批、交付与诊断，但不接管宿主的身份、目录树和业务规则。", file: "pages/zh/getting-started/introduction.md" }
  },
  "getting-started/installation": {
    group: "getting-started",
    order: 2,
    en: { nav: "Installation", title: "Install the 0.0.1 line", lead: "Published artifacts use Maven group ai.icen and JVM package ai.icen.fw. Choose matching Spring Boot generations; never combine Boot 2 and Boot 3 starters.", file: "pages/en/getting-started/installation.md" },
    zh: { nav: "安装", title: "安装 0.0.1 正式版", lead: "正式制品使用 Maven group ai.icen，JVM 包名为 ai.icen.fw。Spring Boot 代际必须匹配，不能混用 Boot 2 与 Boot 3 Starter。", file: "pages/zh/getting-started/installation.md" }
  },
  "getting-started/first-integration": {
    group: "getting-started",
    order: 3,
    en: { nav: "First integration", title: "Wire a trustworthy host", lead: "A production host must provide trusted tenant, identity and authorization context, a shared persistent StorageAdapter, and an explicit migration policy.", file: "pages/en/getting-started/first-integration.md" },
    zh: { nav: "首次接入", title: "装配可信宿主", lead: "生产宿主必须提供可信租户、身份与授权上下文、共享持久化 StorageAdapter，以及明确的迁移策略。", file: "pages/zh/getting-started/first-integration.md" }
  },
  "getting-started/quickstart": {
    group: "getting-started",
    order: 4,
    en: { nav: "Quick start", title: "Quick start", lead: "Get a FileWeft endpoint running in a Spring Boot host with a minimal SPI implementation.", file: "pages/en/getting-started/quickstart.md" },
    zh: { nav: "快速开始", title: "快速开始", lead: "用最小的 SPI 实现，在 Spring Boot 宿主里跑通一个 FileWeft 端点。", file: "pages/zh/getting-started/quickstart.md" }
  },
  "concepts/module-boundaries": {
    group: "concepts",
    order: 1,
    en: { nav: "Module boundaries", title: "Boundaries before features", lead: "FileWeft keeps policy, orchestration and vendor integration in separate modules so extension does not erode compatibility.", file: "pages/en/concepts/module-boundaries.md" },
    zh: { nav: "模块边界", title: "先守边界，再加功能", lead: "FileWeft 将策略、编排与厂商集成拆分在不同模块，保证扩展不会侵蚀兼容性。", file: "pages/zh/concepts/module-boundaries.md" }
  },
  "concepts/lifecycle-delivery": {
    group: "concepts",
    order: 2,
    en: { nav: "Lifecycle & delivery", title: "Lifecycle is evidence, not a flag", lead: "Drafts become reviewable, published and removable through explicit transitions. Delivery to multiple downstream systems is tracked per target and per generation.", file: "pages/en/concepts/lifecycle-delivery.md" },
    zh: { nav: "生命周期与交付", title: "生命周期是证据，不是一个布尔值", lead: "草稿通过显式状态转换进入审批、发布和撤回；多个下游按目标、按发布代次分别跟踪。", file: "pages/zh/concepts/lifecycle-delivery.md" }
  },
  "concepts/tenant-catalog": {
    group: "concepts",
    order: 3,
    en: { nav: "Tenancy & file trees", title: "Tenant and catalog isolation", lead: "Tenant scope is trusted context, while the host's file tree is an authorization surface supplied through DocumentCatalogProvider.", file: "pages/en/concepts/tenant-catalog.md" },
    zh: { nav: "租户与文件树", title: "租户与目录隔离", lead: "租户来自可信上下文，宿主文件树则由 DocumentCatalogProvider 提供，并构成真实授权边界。", file: "pages/zh/concepts/tenant-catalog.md" }
  },
  "guides/spring-boot": {
    group: "guides",
    order: 1,
    en: { nav: "Spring Boot hosting", title: "Assemble Spring Boot safely", lead: "The runtime and Web starters are additive adapters. Your host remains responsible for authentication, gateway policy, DataSource ownership and explicit capability selection.", file: "pages/en/guides/spring-boot.md" },
    zh: { nav: "Spring Boot 宿主", title: "安全装配 Spring Boot", lead: "运行时与 Web Starter 都是加法适配器。认证、网关策略、DataSource 所有权和能力选择仍由宿主负责。", file: "pages/zh/guides/spring-boot.md" }
  },
  "guides/workflows-uploads": {
    group: "guides",
    order: 2,
    en: { nav: "Workflow & uploads", title: "Reviews, resumable bytes and agents", lead: "Long-running work is explicit, persistent and fenced. Approval routing, multipart upload and AI processing remain replaceable without weakening transaction boundaries.", file: "pages/en/guides/workflows-uploads.md" },
    zh: { nav: "工作流与上传", title: "审批、断点字节与 Agent", lead: "长任务必须显式、持久且具备围栏。审批路由、分片上传和 AI 处理都可以替换，但不能削弱事务边界。", file: "pages/zh/guides/workflows-uploads.md" }
  },
  "guides/catalog-provider": {
    group: "guides",
    order: 3,
    en: { nav: "Catalog provider", title: "Implement a catalog provider", lead: "Plug your host folder tree into FileWeft by implementing DocumentCatalogProvider.", file: "pages/en/guides/catalog-provider.md" },
    zh: { nav: "目录 Provider", title: "实现目录 Provider", lead: "通过实现 DocumentCatalogProvider，把宿主目录树接入 FileWeft。", file: "pages/zh/guides/catalog-provider.md" }
  },
  "guides/storage-adapter": {
    group: "guides",
    order: 4,
    en: { nav: "Storage adapter", title: "Implement a storage adapter", lead: "Add a new backend by implementing the StorageAdapter SPI. The example below stores objects on the local filesystem.", file: "pages/en/guides/storage-adapter.md" },
    zh: { nav: "存储适配器", title: "实现存储适配器", lead: "通过实现 StorageAdapter SPI 添加新后端。下面的示例把对象存到本地文件系统。", file: "pages/zh/guides/storage-adapter.md" }
  },
  "guides/agent-handler": {
    group: "guides",
    order: 5,
    en: { nav: "Agent handler", title: "Implement a durable task handler", lead: "Add background work by implementing FileWeftTaskHandler. Workers use leases and idempotent task IDs.", file: "pages/en/guides/agent-handler.md" },
    zh: { nav: "Agent Handler", title: "实现持久任务 Handler", lead: "通过实现 FileWeftTaskHandler 添加后台工作。Worker 使用租约和幂等任务 ID。", file: "pages/zh/guides/agent-handler.md" }
  },
  "guides/resumable-upload": {
    group: "guides",
    order: 6,
    en: { nav: "Resumable upload", title: "Resumable upload protocol", lead: "Upload large files over unreliable networks using caller-stable idempotency keys and numbered parts.", file: "pages/en/guides/resumable-upload.md" },
    zh: { nav: "断点续传", title: "断点续传协议", lead: "使用调用方稳定的幂等键和编号分片，在不稳定的网络里上传大文件。", file: "pages/zh/guides/resumable-upload.md" }
  },
  "architecture/consistency": {
    group: "architecture",
    order: 1,
    en: { nav: "Consistency model", title: "Local atomicity, explicit convergence", lead: "FileWeft does not promise a distributed transaction across PostgreSQL, object storage and downstream systems. It makes local state atomic and remote convergence observable.", file: "pages/en/architecture/consistency.md" },
    zh: { nav: "一致性模型", title: "本地原子，显式收敛", lead: "FileWeft 不承诺跨 PostgreSQL、对象存储和多个下游的分布式事务，而是保证本地状态原子，并让远端收敛过程可观测。", file: "pages/zh/architecture/consistency.md" }
  },
  "architecture/security": {
    group: "architecture",
    order: 2,
    en: { nav: "Security architecture", title: "Fail closed at every boundary", lead: "Capabilities are installed only when their complete security boundary exists. Missing context or ambiguous providers make the operation unavailable instead of silently broadening access.", file: "pages/en/architecture/security.md" },
    zh: { nav: "安全架构", title: "所有边界都失败关闭", lead: "只有完整安全边界存在时才装配能力。上下文缺失或 Provider 歧义会让操作不可用，而不是静默扩大访问范围。", file: "pages/zh/architecture/security.md" }
  },
  "reference/spi": {
    group: "reference",
    order: 1,
    en: { nav: "SPI index", title: "SPI surface", lead: "Contracts keep infrastructure and host policy replaceable while preserving trustworthy context and Java-friendly APIs.", file: "pages/en/reference/spi.md" },
    zh: { nav: "SPI 索引", title: "SPI 总览", lead: "契约让基础设施和宿主策略可替换，同时保持可信上下文与 Java 友好的公共 API。", file: "pages/zh/reference/spi.md" }
  },
  "reference/http-api": {
    group: "reference",
    order: 2,
    en: { nav: "HTTP API v1", title: "HTTP API v1", lead: "The formal surface lives under /fileweft/v1 and returns a stable JSON envelope, except for authorized binary downloads.", file: "pages/en/reference/http-api.md" },
    zh: { nav: "HTTP API v1", title: "HTTP API v1", lead: "正式接口统一位于 /fileweft/v1；除授权二进制下载外，响应使用稳定 JSON 外层。", file: "pages/zh/reference/http-api.md" }
  },
  "reference/configuration": {
    group: "reference",
    order: 3,
    en: { nav: "Configuration", title: "Configuration map", lead: "Production-safe defaults disable implicit tenant, local storage and migration behavior. Enable each fallback or runtime role deliberately.", file: "pages/en/reference/configuration.md" },
    zh: { nav: "配置", title: "配置地图", lead: "安全生产默认值不会隐式选择租户、本地存储或迁移行为。每个 fallback 和运行角色都必须显式开启。", file: "pages/zh/reference/configuration.md" }
  },
  "operations/deployment": {
    group: "operations",
    order: 1,
    en: { nav: "Production deployment", title: "Deploy distinct runtime roles", lead: "Use one validated artifact with intentionally different API, Worker and migration-job configurations. Share database and object storage; do not share privileges unnecessarily.", file: "pages/en/operations/deployment.md" },
    zh: { nav: "生产部署", title: "按运行角色部署", lead: "同一份已验证制品通过不同配置承担 API、Worker 与迁移 Job。共享数据库和对象存储，但不应无差别共享权限。", file: "pages/zh/operations/deployment.md" }
  },
  "operations/doctor-observability": {
    group: "operations",
    order: 2,
    en: { nav: "Doctor & observability", title: "Operate from evidence", lead: "Doctor explains component health through safe projections; metrics show bounded trends; audit and Trace locate tenant and resource evidence without high-cardinality labels.", file: "pages/en/operations/doctor-observability.md" },
    zh: { nav: "Doctor 与可观测性", title: "从证据出发运维", lead: "Doctor 通过安全投影解释组件健康；指标展示有界趋势；审计与 Trace 定位租户和资源证据，同时避免高基数标签。", file: "pages/zh/operations/doctor-observability.md" }
  },
  "operations/migrations-release": {
    group: "operations",
    order: 3,
    en: { nav: "Migrations & releases", title: "Migrate and release deliberately", lead: "FileWeft owns a namespaced Flyway location and history table. Release gates test compatibility, real infrastructure paths, SBOM and reproducible dependency state.", file: "pages/en/operations/migrations-release.md" },
    zh: { nav: "迁移与发布", title: "审慎迁移与发布", lead: "FileWeft 使用独立 Flyway 资源路径和历史表。发布门禁覆盖兼容矩阵、真实基础设施链路、SBOM 和可复现依赖状态。", file: "pages/zh/operations/migrations-release.md" }
  },
  "extensions/plugins": {
    group: "extensions",
    order: 1,
    en: { nav: "Plugin development", title: "Build a disciplined plugin", lead: "Plugins aggregate existing SPI contributions. They do not create a new architectural layer or an in-process security sandbox.", file: "pages/en/extensions/plugins.md" },
    zh: { nav: "插件开发", title: "编写克制的插件", lead: "插件聚合已有 SPI 贡献，不会形成新的架构层，也不是进程内安全沙箱。", file: "pages/zh/extensions/plugins.md" }
  },
  "extensions/connectors": {
    group: "extensions",
    order: 2,
    en: { nav: "Connector engineering", title: "Connect unreliable systems", lead: "A connector translates a stable FileWeft delivery contract into one vendor integration. Timeouts, retries, idempotency, removal and health are part of its design, not afterthoughts.", file: "pages/en/extensions/connectors.md" },
    zh: { nav: "连接器工程", title: "连接不可靠的外部系统", lead: "连接器将稳定的 FileWeft 交付契约转换为一个厂商集成。超时、重试、幂等、撤回和健康检查都是设计的一部分。", file: "pages/zh/extensions/connectors.md" }
  },
  "project/contributing": {
    group: "project",
    order: 1,
    en: { nav: "Contributing", title: "Contribute without eroding the foundation", lead: "Changes move from Core through SPI, Domain, Application, Persistence, Starter and Adapter only when the responsibility belongs there. Tests follow the same boundary.", file: "pages/en/project/contributing.md" },
    zh: { nav: "参与贡献", title: "贡献功能，不侵蚀地基", lead: "只有职责确实属于对应层时，改动才沿 Core、SPI、Domain、Application、Persistence、Starter 与 Adapter 推进；测试也遵守相同边界。", file: "pages/zh/project/contributing.md" }
  },
  "project/security": {
    group: "project",
    order: 2,
    en: { nav: "Security", title: "Report security issues privately", lead: "Do not disclose a suspected vulnerability in a public issue. Send a minimal, reproducible report to support@icen.ai and allow time for coordinated remediation.", file: "pages/en/project/security.md" },
    zh: { nav: "安全", title: "私密报告安全问题", lead: "不要在公开 Issue 披露疑似漏洞。将最小、可复现报告发送到 support@icen.ai，并为协调修复预留时间。", file: "pages/zh/project/security.md" }
  },
  "project/release-0-0-1": {
    group: "project",
    order: 3,
    en: { nav: "Release 0.0.1", title: "Release 0.0.1", lead: "The first public line establishes ai.icen coordinates, ai.icen.fw packages, namespaced migrations and the production-oriented document, workflow, delivery, Doctor and Web foundations.", file: "pages/en/project/release-0-0-1.md" },
    zh: { nav: "0.0.1 发布说明", title: "0.0.1 正式版", lead: "首个公开版本建立 ai.icen 坐标、ai.icen.fw 包名、独立迁移命名空间，以及面向生产的文档、审批、交付、Doctor 和 Web 地基。", file: "pages/zh/project/release-0-0-1.md" }
  },
  "project/release-0-0-2-development": {
    group: "project",
    order: 4,
    en: { nav: "0.0.2 development", title: "0.0.2 development line", lead: "0.0.2-SNAPSHOT is an unreleased development line. The stable published version remains ai.icen:*:0.0.1 until release gates and remote artifact verification finish.", file: "pages/en/project/release-0-0-2-development.md" },
    zh: { nav: "0.0.2 开发中", title: "0.0.2 开发线", lead: "0.0.2-SNAPSHOT 是尚未发布的开发线；在发布门禁和远端制品验收完成前，稳定正式版仍是 ai.icen:*:0.0.1。", file: "pages/zh/project/release-0-0-2-development.md" }
  },
  "project/roadmap": {
    group: "project",
    order: 5,
    en: { nav: "Roadmap", title: "Roadmap with proof, not promises", lead: "This backlog is the hand-off contract for future FileWeft development. A version moves from planned to complete only when its listed evidence exists in the repository or the named real environment; an implementation, a green narrow test or a written intention is never enough by itself.", file: "pages/en/project/roadmap.md" },
    zh: { nav: "开发待办", title: "用证据推进的开发路线图", lead: "这份待办是后续 FileWeft 开发的交接契约。只有仓库或指定真实环境中存在对应证据，版本才能从计划转为完成；仅有实现、局部绿测或书面意图，都不能单独作为完成依据。", file: "pages/zh/project/roadmap.md" }
  }
};

export function orderedRoutes() {
  return Object.keys(pages).sort((a, b) => {
    const pa = pages[a];
    const pb = pages[b];
    const ga = groups.findIndex((g) => g.id === pa.group);
    const gb = groups.findIndex((g) => g.id === pb.group);
    if (ga !== gb) return ga - gb;
    return pa.order - pb.order;
  });
}
