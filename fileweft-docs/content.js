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
    "stable": "Release contract",
    "version": "Version",
    "support": "Support",
    "skill": "SKILL",
    "skillDownloadAria": "Download SKILL.md integration guide",
    "skillDownloaded": "SKILL.md downloaded",
    "skillDownloadFailed": "SKILL.md download failed",
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
    "stable": "发布合同",
    "version": "版本",
    "support": "支持",
    "skill": "SKILL",
    "skillDownloadAria": "下载 SKILL.md 集成指南",
    "skillDownloaded": "SKILL.md 已下载",
    "skillDownloadFailed": "SKILL.md 下载失败",
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
    "group": "getting-started",
    "order": 1,
    "en": {
      "nav": "Introduction",
      "title": "Infrastructure for files that must endure",
      "lead": "Understand what FileWeft is, where its responsibilities end, and which integration path fits your team.",
      "file": "pages/en/getting-started/introduction.md"
    },
    "zh": {
      "nav": "介绍",
      "title": "为必须长久运行的文件系统打地基",
      "lead": "了解 FileWeft 的定位、边界与三种接入方式，找到适合你团队的起点。",
      "file": "pages/zh/getting-started/introduction.md"
    }
  },
  "getting-started/installation": {
    "group": "getting-started",
    "order": 2,
    "en": {
      "nav": "Installation",
      "title": "Install the 0.0.3 line",
      "lead": "Add FileWeft to your build, align Spring Boot generations, and verify the dependency tree.",
      "file": "pages/en/getting-started/installation.md"
    },
    "zh": {
      "nav": "安装",
      "title": "安装 0.0.3 版本线",
      "lead": "将 FileWeft 加入构建，对齐 Spring Boot 代际，并验证依赖树。",
      "file": "pages/zh/getting-started/installation.md"
    }
  },
  "getting-started/first-integration": {
    "group": "getting-started",
    "order": 3,
    "en": {
      "nav": "First integration",
      "title": "Wire a trustworthy host",
      "lead": "Move from a dependency declaration to a production host by providing trusted identity context, shared storage, PostgreSQL schema ownership, and separate runtime roles.",
      "file": "pages/en/getting-started/first-integration.md"
    },
    "zh": {
      "nav": "首次接入",
      "title": "接入可信宿主",
      "lead": "从依赖声明走向生产宿主：提供可信身份上下文、共享存储、PostgreSQL schema 归属，并拆分运行时角色。",
      "file": "pages/zh/getting-started/first-integration.md"
    }
  },
  "getting-started/quickstart": {
    "group": "getting-started",
    "order": 4,
    "en": {
      "nav": "Quick start",
      "title": "Quick start",
      "lead": "Run a complete FileWeft endpoint on your laptop with a minimal Spring Boot host and a few curl commands.",
      "file": "pages/en/getting-started/quickstart.md"
    },
    "zh": {
      "nav": "快速开始",
      "title": "快速开始",
      "lead": "用最小的 Spring Boot 宿主和几个 curl 命令，在笔记本上跑通一个完整的 FileWeft 端点。",
      "file": "pages/zh/getting-started/quickstart.md"
    }
  },
  "concepts/module-boundaries": {
    "group": "concepts",
    "order": 1,
    "en": {
      "nav": "Module boundaries",
      "title": "Module boundaries",
      "lead": "Why does FileWeft split its code into separate modules? Because a storage SDK that leaks into your domain makes the framework impossible to upgrade or replace. This page shows the dependency direction, what each module owns, and how to add a new adapter without crossing the line.",
      "file": "pages/en/concepts/module-boundaries.md"
    },
    "zh": {
      "nav": "模块边界",
      "title": "模块边界",
      "lead": "为什么 FileWeft 要把代码拆到多个模块？因为一旦存储 SDK 泄漏到领域层，框架就变得难以升级和替换。本页说明依赖方向、每个模块的职责，以及如何在不过线的前提下新增适配器。",
      "file": "pages/zh/concepts/module-boundaries.md"
    }
  },
  "concepts/lifecycle-delivery": {
    "group": "concepts",
    "order": 2,
    "en": {
      "nav": "Lifecycle & delivery",
      "title": "Lifecycle & delivery",
      "lead": "A published document is not a single checkbox: it is a chain of evidence that must survive review, partial downstream failures, and later removal. This page explains FileWeft's state machine, how delivery to multiple systems is tracked, and why generation fencing matters.",
      "file": "pages/en/concepts/lifecycle-delivery.md"
    },
    "zh": {
      "nav": "生命周期与交付",
      "title": "生命周期与交付",
      "lead": "“已发布”不是单个布尔值，而是一条必须经受审批、下游部分失败和后续撤回考验的证据链。本页说明 FileWeft 的状态机、如何按目标跟踪多下游交付，以及代次围栏为何重要。",
      "file": "pages/zh/concepts/lifecycle-delivery.md"
    }
  },
  "concepts/tenant-catalog": {
    "group": "concepts",
    "order": 3,
    "en": {
      "nav": "Tenancy & file trees",
      "title": "Tenant & catalog isolation",
      "lead": "Multi-tenancy is more than adding a column: it must flow from trusted context into every query, path, and event. File trees, on the other hand, are owned by the host. This page shows how tenant isolation and catalog authorization work together without leaking folder structure into storage keys.",
      "file": "pages/en/concepts/tenant-catalog.md"
    },
    "zh": {
      "nav": "租户与文件树",
      "title": "租户与目录隔离",
      "lead": "多租户不只是加一列 tenant_id，它必须从可信上下文流入每一次查询、每一条路径、每一个事件。而文件树归宿主所有。本页说明租户隔离与目录授权如何协同工作，同时不把目录结构泄漏到存储键中。",
      "file": "pages/zh/concepts/tenant-catalog.md"
    }
  },
  "concepts/security-model": {
    "group": "concepts",
    "order": 4,
    "en": {
      "nav": "Security model",
      "title": "Fail-closed security model",
      "lead": "FileWeft does not guess who you are or what you can do. It delegates identity, tenant, and authorization to the host and treats a missing or ambiguous boundary as a denial. This page explains the three provider contracts, the fail-closed rule, and what stays out of public responses.",
      "file": "pages/en/concepts/security-model.md"
    },
    "zh": {
      "nav": "安全模型",
      "title": "故障关闭安全模型",
      "lead": "FileWeft 不会猜测调用者是谁、能做什么。它把身份、租户和授权委托给宿主，并把缺失或歧义的安全边界视为拒绝。本页说明三个提供者契约、故障关闭规则，以及公共响应中不会暴露什么。",
      "file": "pages/zh/concepts/security-model.md"
    }
  },
  "concepts/outbox": {
    "group": "concepts",
    "order": 5,
    "en": {
      "nav": "Outbox & workers",
      "title": "Outbox: never call downstream inside a transaction",
      "lead": "The fastest way to lose consistency is to send an HTTP request inside a database commit. FileWeft writes events to an outbox table in the same business transaction, then lets workers deliver them asynchronously. This page explains the pattern, the worker configuration, and how to observe backlog.",
      "file": "pages/en/concepts/outbox.md"
    },
    "zh": {
      "nav": "Outbox 与 Worker",
      "title": "Outbox：永远不要在事务里调用下游",
      "lead": "毁掉一致性最快的方式，是在数据库提交里发送 HTTP 请求。FileWeft 在同一个业务事务里把事件写入 Outbox 表，再由 Worker 异步交付。本页说明该模式、Worker 配置以及如何观察积压。",
      "file": "pages/zh/concepts/outbox.md"
    }
  },
  "guides/spring-boot": {
    "group": "guides",
    "order": 1,
    "en": {
      "nav": "Spring Boot hosting",
      "title": "Assemble FileWeft into Spring Boot",
      "lead": "Learn how to embed FileWeft as an infrastructure library inside your Spring Boot application while keeping full control over authentication, DataSource ownership, and gateway policy.",
      "file": "pages/en/guides/spring-boot.md"
    },
    "zh": {
      "nav": "Spring Boot 宿主",
      "title": "将 FileWeft 装配到 Spring Boot",
      "lead": "学习如何把 FileWeft 作为基础设施库嵌入到 Spring Boot 应用中，同时保留对认证、DataSource 所有权和网关策略的完全控制。",
      "file": "pages/zh/guides/spring-boot.md"
    }
  },
  "guides/workflows-uploads": {
    "group": "guides",
    "order": 2,
    "en": {
      "nav": "Workflow & uploads",
      "title": "Approval, resumable uploads, and durable tasks",
      "lead": "Understand how FileWeft handles long-running work—document review, multipart uploads, and generic background tasks—without weakening transaction boundaries or leaking storage internals.",
      "file": "pages/en/guides/workflows-uploads.md"
    },
    "zh": {
      "nav": "工作流与上传",
      "title": "审批、断点续传与持久任务",
      "lead": "理解 FileWeft 如何处理长周期工作——文档审批、分片上传和通用后台任务——同时不削弱事务边界，也不向外部泄漏存储内部细节。",
      "file": "pages/zh/guides/workflows-uploads.md"
    }
  },
  "guides/catalog-provider": {
    "group": "guides",
    "order": 3,
    "en": {
      "nav": "Catalog provider",
      "title": "Implement a catalog provider",
      "lead": "Bind FileWeft documents to your host folder tree while keeping control of directory paths, ACLs, and display names.",
      "file": "pages/en/guides/catalog-provider.md"
    },
    "zh": {
      "nav": "目录 Provider",
      "title": "实现目录 Provider",
      "lead": "将 FileWeft 文档绑定到宿主目录树，同时保留对目录路径、ACL 和显示名称的控制。",
      "file": "pages/zh/guides/catalog-provider.md"
    }
  },
  "guides/storage-adapter": {
    "group": "guides",
    "order": 4,
    "en": {
      "nav": "Storage adapter",
      "title": "Implement a storage adapter",
      "lead": "Add a new object backend by implementing the StorageAdapter SPI. FileWeft builds object names and metadata; your adapter only materializes bytes.",
      "file": "pages/en/guides/storage-adapter.md"
    },
    "zh": {
      "nav": "存储适配器",
      "title": "实现存储适配器",
      "lead": "通过实现 StorageAdapter SPI 添加新的对象后端。FileWeft 负责构造对象名和元数据，你的适配器只需把字节落地。",
      "file": "pages/zh/guides/storage-adapter.md"
    }
  },
  "guides/agent-handler": {
    "group": "guides",
    "order": 5,
    "en": {
      "nav": "Task handler",
      "title": "Implement a durable task handler",
      "lead": "Add background work such as OCR, virus scanning, or host-owned extraction by implementing FileWeftTaskHandler. Workers use leases and idempotent task IDs.",
      "file": "pages/en/guides/agent-handler.md"
    },
    "zh": {
      "nav": "任务 Handler",
      "title": "实现持久任务 Handler",
      "lead": "通过实现 FileWeftTaskHandler 添加 OCR、病毒扫描或宿主自有抽取等后台工作。Worker 使用租约和幂等任务 ID。",
      "file": "pages/zh/guides/agent-handler.md"
    }
  },
  "guides/resumable-upload": {
    "group": "guides",
    "order": 6,
    "en": {
      "nav": "Resumable upload",
      "title": "Resumable upload protocol",
      "lead": "Transfer large files over unreliable networks by resuming from server-acknowledged parts through the formal v1 upload resource.",
      "file": "pages/en/guides/resumable-upload.md"
    },
    "zh": {
      "nav": "断点续传",
      "title": "断点续传协议",
      "lead": "通过正式 v1 上传资源，在不稳定网络中从服务端已确认的分片恢复，安全传输大文件。",
      "file": "pages/zh/guides/resumable-upload.md"
    }
  },
  "architecture/consistency": {
    "group": "architecture",
    "order": 1,
    "en": {
      "nav": "Consistency model",
      "title": "Local atomicity, explicit convergence",
      "lead": "FileWeft does not promise a distributed transaction across PostgreSQL, object storage, and downstream systems. It keeps every local state change atomic and makes remote convergence observable, retryable, and safe to reason about.",
      "file": "pages/en/architecture/consistency.md"
    },
    "zh": {
      "nav": "一致性模型",
      "title": "本地事务原子，远程状态显式收敛",
      "lead": "FileWeft 不承诺跨 PostgreSQL、对象存储和多个下游系统的分布式事务。它保证每一次本地状态变更的原子性，并让远端收敛过程可观测、可重试且易于推理。",
      "file": "pages/zh/architecture/consistency.md"
    }
  },
  "architecture/security": {
    "group": "architecture",
    "order": 2,
    "en": {
      "nav": "Security architecture",
      "title": "Fail closed at every boundary",
      "lead": "FileWeft assembles a capability only when its complete security boundary is present. Missing context, ambiguous providers, or unverified custom persistence make the operation unavailable instead of silently widening access.",
      "file": "pages/en/architecture/security.md"
    },
    "zh": {
      "nav": "安全架构",
      "title": "所有边界默认拒绝",
      "lead": "FileWeft 只在完整安全边界存在时才装配能力。上下文缺失、Provider 歧义或未经验证的自定义持久化都会让操作不可用，而不是静默扩大访问范围。",
      "file": "pages/zh/architecture/security.md"
    }
  },
  "reference/spi": {
    "group": "reference",
    "order": 1,
    "en": {
      "nav": "SPI index",
      "title": "SPI surface",
      "lead": "FileWeft is built around contracts, not concrete vendors. The SPI lets you plug in identity, storage, catalogs, workflows, connectors, diagnostics, and background tasks without changing the framework internals.",
      "file": "pages/en/reference/spi.md"
    },
    "zh": {
      "nav": "SPI 索引",
      "title": "SPI 总览",
      "lead": "FileWeft 围绕契约而非具体厂商构建。SPI 让你在不修改框架内部的前提下，接入身份、存储、目录、工作流、连接器、诊断和后台任务。",
      "file": "pages/zh/reference/spi.md"
    }
  },
  "reference/http-api": {
    "group": "reference",
    "order": 2,
    "en": {
      "nav": "HTTP API v1",
      "title": "HTTP API v1",
      "lead": "The formal public protocol lives under /fileweft/v1. Every response uses a stable JSON envelope, except for authorized binary downloads which stream bytes with fixed security headers.",
      "file": "pages/en/reference/http-api.md"
    },
    "zh": {
      "nav": "HTTP API v1",
      "title": "HTTP API v1",
      "lead": "正式公共协议统一位于 /fileweft/v1。除授权二进制下载直接流式返回字节外，所有响应都使用稳定的 JSON 外层。",
      "file": "pages/zh/reference/http-api.md"
    }
  },
  "reference/configuration": {
    "group": "reference",
    "order": 3,
    "en": {
      "nav": "Configuration",
      "title": "Configuration reference",
      "lead": "FileWeft production defaults are conservative: schema validation, no implicit tenant, no local storage, and no hidden migrations. Enable every fallback and runtime role deliberately.",
      "file": "pages/en/reference/configuration.md"
    },
    "zh": {
      "nav": "配置",
      "title": "配置参考",
      "lead": "FileWeft 的生产默认值是保守的：校验 schema、不隐式选择租户、不使用本地存储、不自动迁移。每个 fallback 和运行角色都必须显式开启。",
      "file": "pages/zh/reference/configuration.md"
    }
  },
  "reference/error-codes": {
    "group": "reference",
    "order": 4,
    "en": {
      "nav": "Error codes",
      "title": "Stable error codes",
      "lead": "When something goes wrong, FileWeft returns a stable machine-readable code inside the JSON envelope. This page explains each code and the recovery action to take.",
      "file": "pages/en/reference/error-codes.md"
    },
    "zh": {
      "nav": "错误码",
      "title": "稳定错误码",
      "lead": "当请求失败时，FileWeft 会在 JSON 外层里返回一个稳定的机器可读错误码。本页说明每个错误码的含义以及恢复操作。",
      "file": "pages/zh/reference/error-codes.md"
    }
  },
  "reference/domain-model": {
    "group": "reference",
    "order": 5,
    "en": {
      "nav": "Domain model",
      "title": "Core domain model",
      "lead": "FileWeft separates business concepts from infrastructure. This page shows the core entities, their responsibilities, and how they connect to storage, workflow, and delivery.",
      "file": "pages/en/reference/domain-model.md"
    },
    "zh": {
      "nav": "领域模型",
      "title": "核心领域模型",
      "lead": "FileWeft 把业务概念与基础设施分离。本页展示核心实体、职责以及它们如何与存储、工作流和交付产生关联。",
      "file": "pages/zh/reference/domain-model.md"
    }
  },
  "operations/deployment": {
    "group": "operations",
    "order": 1,
    "en": {
      "nav": "Production deployment",
      "title": "Deploy distinct runtime roles",
      "lead": "Run one validated FileWeft artifact as four intentionally different roles — API, Outbox worker, Task worker and migration job — so each process gets only the credentials, configuration and blast radius it needs.",
      "file": "pages/en/operations/deployment.md"
    },
    "zh": {
      "nav": "生产部署",
      "title": "按运行角色部署",
      "lead": "将同一份已验证的 FileWeft 制品以四种有意区分的角色运行——API、Outbox Worker、任务 Worker 与迁移 Job——让每个进程只拥有它所需的凭据、配置和爆炸半径。",
      "file": "pages/zh/operations/deployment.md"
    }
  },
  "operations/doctor-observability": {
    "group": "operations",
    "order": 2,
    "en": {
      "nav": "Doctor & observability",
      "title": "Operate from evidence",
      "lead": "Diagnose FileWeft without guessing: Doctor runs safe, authorized checks; metrics expose bounded trends; audit logs and trace IDs carry resource evidence without leaking high-cardinality labels.",
      "file": "pages/en/operations/doctor-observability.md"
    },
    "zh": {
      "nav": "Doctor 与可观测性",
      "title": "从证据出发运维",
      "lead": "无需猜测即可诊断 FileWeft：Doctor 执行安全、已授权的检查；指标展示有界趋势；审计日志与 Trace ID 承载资源证据，同时避免高基数标签泄漏。",
      "file": "pages/zh/operations/doctor-observability.md"
    }
  },
  "operations/migrations-release": {
    "group": "operations",
    "order": 3,
    "en": {
      "nav": "Migrations & releases",
      "title": "Migrate and release deliberately",
      "lead": "FileWeft owns a namespaced Flyway location and history table. Release safely by validating schema compatibility, real infrastructure paths, SBOM integrity and reproducible dependency state.",
      "file": "pages/en/operations/migrations-release.md"
    },
    "zh": {
      "nav": "迁移与发布",
      "title": "审慎迁移与发布",
      "lead": "FileWeft 拥有独立的 Flyway 资源路径和历史表。安全发布需要校验 schema 兼容性、真实基础设施链路、SBOM 完整性和可复现依赖状态。",
      "file": "pages/zh/operations/migrations-release.md"
    }
  },
  "operations/troubleshooting": {
    "group": "operations",
    "order": 4,
    "en": {
      "nav": "Troubleshooting",
      "title": "Diagnose common symptoms",
      "lead": "When FileWeft behaves unexpectedly, follow a structured checklist — check Doctor, metrics, outbox state and trace IDs before changing code or configuration.",
      "file": "pages/en/operations/troubleshooting.md"
    },
    "zh": {
      "nav": "故障排查",
      "title": "诊断常见症状",
      "lead": "当 FileWeft 行为异常时，按结构化清单操作——先检查 Doctor、指标、Outbox 状态与 Trace ID，再改动代码或配置。",
      "file": "pages/zh/operations/troubleshooting.md"
    }
  },
  "extensions/plugins": {
    "group": "extensions",
    "order": 1,
    "en": {
      "nav": "Plugin development",
      "title": "Build production-ready plugins",
      "lead": "Learn how to package FileWeft extensions into a single plugin artifact so operators can discover connectors, storage adapters, Doctor checkers, and task handlers without changing core code.",
      "file": "pages/en/extensions/plugins.md"
    },
    "zh": {
      "nav": "插件开发",
      "title": "编写生产级插件",
      "lead": "学习如何将 FileWeft 扩展打包成单个插件制品，让运维人员无需修改核心代码就能发现连接器、存储适配器、Doctor 检查器和任务处理器。",
      "file": "pages/zh/extensions/plugins.md"
    }
  },
  "extensions/connectors": {
    "group": "extensions",
    "order": 2,
    "en": {
      "nav": "Connector engineering",
      "title": "Build resilient connectors",
      "lead": "Learn how to implement a FileConnector that publishes documents to downstream systems safely using bounded timeouts, retries, idempotency, removal, and health checks.",
      "file": "pages/en/extensions/connectors.md"
    },
    "zh": {
      "nav": "连接器工程",
      "title": "构建弹性连接器",
      "lead": "学习如何实现 FileConnector，通过有界超时、重试、幂等、撤回和健康检查，安全地将文档发布到下游系统。",
      "file": "pages/zh/extensions/connectors.md"
    }
  },
  "project/contributing": {
    "group": "project",
    "order": 1,
    "en": {
      "nav": "Contributing",
      "title": "Contribute without eroding the foundation",
      "lead": "This page shows how to add code, tests and documentation to FileWeft while keeping the Core → SPI → Domain → Application → Persistence → Starter → Adapter chain intact, so the framework stays extensible and Java-friendly.",
      "file": "pages/en/project/contributing.md"
    },
    "zh": {
      "nav": "参与贡献",
      "title": "贡献功能，不侵蚀地基",
      "lead": "本页说明如何在为 FileWeft 添加代码、测试与文档的同时，保持 Core → SPI → Domain → Application → Persistence → Starter → Adapter 的分层完整，让框架保持可扩展且对 Java 友好。",
      "file": "pages/zh/project/contributing.md"
    }
  },
  "project/security": {
    "group": "project",
    "order": 2,
    "en": {
      "nav": "Security",
      "title": "Report security issues privately",
      "lead": "This page explains how to disclose a suspected vulnerability in FileWeft so it can be investigated and fixed before it becomes public knowledge.",
      "file": "pages/en/project/security.md"
    },
    "zh": {
      "nav": "安全",
      "title": "私密报告安全问题",
      "lead": "本页说明如何披露 FileWeft 的疑似漏洞，使其在公开之前得到调查和修复。",
      "file": "pages/zh/project/security.md"
    }
  },
  "project/release-0-0-1": {
    "group": "project",
    "order": 3,
    "en": {
      "nav": "Release 0.0.1",
      "title": "Release 0.0.1",
      "lead": "This page documents what is actually available in the stable 0.0.1 line, including coordinates, module boundaries, delivered capabilities and the limits you should know before integrating.",
      "file": "pages/en/project/release-0-0-1.md"
    },
    "zh": {
      "nav": "0.0.1 发布说明",
      "title": "0.0.1 正式版",
      "lead": "本页记录稳定版 0.0.1 实际交付的内容，包括坐标、模块边界、已交付能力和集成前应了解的限制。",
      "file": "pages/zh/project/release-0-0-1.md"
    }
  },
  "project/release-0-0-2-development": {
    "group": "project",
    "order": 4,
    "en": {
      "nav": "Release 0.0.2",
      "title": "Release 0.0.2",
      "lead": "This page records the exact 0.0.2 release contract and the protected-tag plus anonymous remote-resolution evidence required before its Maven coordinates are consumable.",
      "file": "pages/en/project/release-0-0-2-development.md"
    },
    "zh": {
      "nav": "0.0.2 发布说明",
      "title": "0.0.2 正式版",
      "lead": "本页记录 0.0.2 的精确发布合同，以及 Maven 坐标可消费前必须具备的受保护标签与远端匿名解析证据。",
      "file": "pages/zh/project/release-0-0-2-development.md"
    }
  },
  "project/release-0-0-3": {
    "group": "project",
    "order": 5,
    "en": {
      "nav": "Release 0.0.3",
      "title": "Release 0.0.3 contract",
      "lead": "The current candidate contract adds metadata schemas and review withdrawal while keeping consumption conditional on guarded-tag, protected-main and anonymous remote evidence.",
      "file": "pages/en/project/release-0-0-3.md"
    },
    "zh": {
      "nav": "0.0.3 发布合同",
      "title": "0.0.3 发布合同",
      "lead": "当前候选合同新增 metadata schema 与审批撤回，并继续以标签发布门禁、受保护主干和匿名远端证据作为可消费前提。",
      "file": "pages/zh/project/release-0-0-3.md"
    }
  },
  "project/roadmap": {
    "group": "project",
    "order": 6,
    "en": {
      "nav": "Roadmap",
      "title": "Roadmap with proof, not promises",
      "lead": "This backlog is the hand-off contract for future FileWeft development. A version moves from planned to complete only when its listed evidence exists in the repository or the named real environment; an implementation, a green narrow test or a written intention is never enough by itself.",
      "file": "pages/en/project/roadmap.md"
    },
    "zh": {
      "nav": "开发待办",
      "title": "用证据推进的开发路线图",
      "lead": "这份待办是后续 FileWeft 开发的交接契约。只有仓库或指定真实环境中存在对应证据，版本才能从计划转为完成；仅有实现、局部绿测或书面意图，都不能单独作为完成依据。",
      "file": "pages/zh/project/roadmap.md"
    }
  },
  "project/faq": {
    "group": "project",
    "order": 7,
    "en": {
      "nav": "FAQ",
      "title": "Frequently asked questions",
      "lead": "This page answers the most common questions about FileWeft's purpose, architecture, tenancy, storage and release policy, with pointers to deeper documentation.",
      "file": "pages/en/project/faq.md"
    },
    "zh": {
      "nav": "常见问题",
      "title": "常见问题",
      "lead": "本页回答关于 FileWeft 定位、架构、租户、存储与发布策略的最常见问题，并指向更深入的文档。",
      "file": "pages/zh/project/faq.md"
    }
  },
  "architecture/event-driven": {
    "group": "architecture",
    "order": 3,
    "en": {
      "nav": "Event-driven delivery",
      "title": "Event-driven delivery with Outbox and workers",
      "lead": "FileWeft turns every external side effect into a durable, tenant-scoped event. Workers process those events at-least-once, connectors converge downstream systems, and operators can observe the whole pipeline without peeking at internals.",
      "file": "pages/en/architecture/event-driven.md"
    },
    "zh": {
      "nav": "事件驱动交付",
      "title": "基于 Outbox 与 Worker 的事件驱动交付",
      "lead": "FileWeft 把每一个外部副作用都变成持久、带租户作用域的事件。Worker 以至少一次语义处理这些事件，连接器收敛下游系统，运维人员无需窥探内部即可观测整条流水线。",
      "file": "pages/zh/architecture/event-driven.md"
    }
  },
  "guides/audit-log": {
    "group": "guides",
    "order": 7,
    "en": {
      "nav": "Audit log",
      "title": "Read document audit logs",
      "lead": "Trace who changed a document, when it was published, and whether downstream deliveries succeeded using the formal v1 audit endpoints.",
      "file": "pages/en/guides/audit-log.md"
    },
    "zh": {
      "nav": "审计日志",
      "title": "读取文档审计日志",
      "lead": "通过正式 v1 审计端点，追踪文档修改人、发布时间以及下游交付是否成功。",
      "file": "pages/zh/guides/audit-log.md"
    }
  },
  "guides/multi-tenant": {
    "group": "guides",
    "order": 8,
    "en": {
      "nav": "Multi-tenant",
      "title": "Implement tenant isolation",
      "lead": "Keep tenants safely separated across database queries, storage paths, events, tasks, logs, and caches by implementing the TenantProvider SPI.",
      "file": "pages/en/guides/multi-tenant.md"
    },
    "zh": {
      "nav": "多租户",
      "title": "实现租户隔离",
      "lead": "通过实现 TenantProvider SPI，让租户在数据库查询、存储路径、事件、任务、日志和缓存中都被安全隔离。",
      "file": "pages/zh/guides/multi-tenant.md"
    }
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
