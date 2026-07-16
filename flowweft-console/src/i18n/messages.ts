import type {
  CapabilityPageId,
  NavigationGroupId,
  NavigationId,
} from "@/config/navigation";
import type { Locale } from "@/i18n/locale";

export interface CapabilityCardCopy {
  readonly title: string;
  readonly detail: string;
  readonly boundary: string;
}

export interface CapabilityPageCopy {
  readonly eyebrow: string;
  readonly title: string;
  readonly summary: string;
  readonly deliverable: string;
  readonly cards: readonly CapabilityCardCopy[];
  readonly emptyTitle: string;
  readonly emptyDetail: string;
  readonly proofs: readonly string[];
}

export interface ConsoleMessages {
  readonly brand: {
    readonly name: string;
    readonly product: string;
    readonly edition: string;
  };
  readonly navGroups: Record<NavigationGroupId, string>;
  readonly nav: Record<NavigationId, string>;
  readonly shell: {
    readonly skip: string;
    readonly foundation: string;
    readonly foundationDetail: string;
    readonly sourceLabel: string;
    readonly sourceValue: string;
    readonly sessionLabel: string;
    readonly sessionValue: string;
    readonly login: string;
    readonly logout: string;
    readonly language: string;
    readonly safePreview: string;
  };
  readonly status: {
    readonly frameReady: string;
    readonly contractRequired: string;
    readonly noLiveData: string;
    readonly unavailable: string;
  };
  readonly login: {
    readonly eyebrow: string;
    readonly titleLead: string;
    readonly titleAccent: string;
    readonly description: string;
    readonly panelTitle: string;
    readonly panelStatus: string;
    readonly sourceTitle: string;
    readonly sourceEmpty: string;
    readonly sourceDetail: string;
    readonly sourceConfigured: string;
    readonly sourceConfiguredDetail: string;
    readonly sourceReady: string;
    readonly sourceDegraded: string;
    readonly sourceUnavailable: string;
    readonly oidcMode: string;
    readonly hostExchangeMode: string;
    readonly hostTenant: string;
    readonly hostUsername: string;
    readonly hostPassword: string;
    readonly hostSubmit: string;
    readonly hostHint: string;
    readonly oidc: string;
    readonly oidcHint: string;
    readonly compatibilityTitle: string;
    readonly compatibilityDetail: string;
    readonly preview: string;
    readonly previewHint: string;
    readonly guardrails: readonly { readonly label: string; readonly detail: string }[];
  };
  readonly dashboard: {
    readonly eyebrow: string;
    readonly title: string;
    readonly titleAccent: string;
    readonly summary: string;
    readonly signals: readonly { readonly label: string; readonly detail: string }[];
    readonly weaveTitle: string;
    readonly weaveDetail: string;
    readonly lanesTitle: string;
    readonly lanes: readonly { readonly code: string; readonly title: string; readonly detail: string }[];
    readonly evidenceTitle: string;
    readonly evidenceEmpty: string;
    readonly evidenceDetail: string;
  };
  readonly capability: {
    readonly scope: string;
    readonly currentState: string;
    readonly workstreams: string;
    readonly liveSurface: string;
    readonly evidence: string;
    readonly evidenceHint: string;
  };
  readonly pages: Record<CapabilityPageId, CapabilityPageCopy>;
}

const zh: ConsoleMessages = {
  brand: {
    name: "FLOWWEFT",
    product: "文件智能基础设施",
    edition: "1.0 CONSOLE / FOUNDATION",
  },
  navGroups: {
    work: "文件流",
    assurance: "保障与证据",
    intelligence: "智能运行面",
    administration: "管理",
  },
  nav: {
    dashboard: "总览",
    documents: "文档工作台",
    approvals: "审批中心",
    sync: "同步中心",
    doctor: "Doctor",
    audit: "审计",
    agent: "Agent 对话",
    toolApprovals: "工具确认",
    retrieval: "检索证据",
    evaluations: "评测",
    settings: "设置",
  },
  shell: {
    skip: "跳到主要内容",
    foundation: "基础壳已就位",
    foundationDetail: "未连接宿主、来源或 FlowWeft Runtime",
    sourceLabel: "来源档案",
    sourceValue: "尚未建立可信会话",
    sessionLabel: "会话",
    sessionValue: "未认证 / 无实时数据",
    login: "安全接入说明",
    logout: "退出服务端会话",
    language: "语言",
    safePreview: "产品框架预览",
  },
  status: {
    frameReady: "界面骨架完成",
    contractRequired: "等待正式契约",
    noLiveData: "无实时数据",
    unavailable: "能力未接入",
  },
  login: {
    eyebrow: "00 / 同源接入边界",
    titleLead: "先确认信任，",
    titleAccent: "再让文件流动。",
    description: "生产 Console 只接受管理员批准的来源档案。浏览器永远不能提交任意宿主地址，也不会持有 FlowWeft service token、宿主 refresh token 或 Provider secret。",
    panelTitle: "选择可信来源",
    panelStatus: "阶段三：安全登录",
    sourceTitle: "管理员批准的 Source Profile",
    sourceEmpty: "当前没有可用来源档案",
    sourceDetail: "BFF 只从服务端允许列表返回最小摘要；endpoint 与凭据不会进入浏览器 DTO。",
    sourceConfigured: "已发现可信来源",
    sourceConfiguredDetail: "只有完成 OIDC 端点、PKCE 与服务端会话配置的来源才会开放登录。",
    sourceReady: "可安全登录",
    sourceDegraded: "认证待接线",
    sourceUnavailable: "仅允许列表",
    oidcMode: "OIDC + PKCE",
    hostExchangeMode: "宿主令牌交换",
    hostTenant: "租户别名",
    hostUsername: "宿主账号",
    hostPassword: "一次性密码交换",
    hostSubmit: "登录并建立服务端会话",
    hostHint: "密码仅在本次同源 TLS 请求中到达 BFF，交换完成后不会保存到会话、日志或浏览器存储。",
    oidc: "使用 OIDC + PKCE 登录",
    oidcHint: "登录只会把一次性 state 与授权码带回浏览器；PKCE verifier、访问令牌和会话均留在 BFF。",
    compatibilityTitle: "宿主账号兼容模式",
    compatibilityDetail: "如宿主只能提供账号登录，密码只允许经 TLS 同源 POST 到 BFF，立即交换 token，且不得持久化、记录或回显。",
    preview: "查看无数据产品壳",
    previewHint: "仅用于检查导航、语言与布局；这不是认证绕过，也不展示模拟业务数据。",
    guardrails: [
      { label: "SESSION", detail: "Secure + HttpOnly + SameSite cookie；每次 mutation 重验会话与 CSRF。" },
      { label: "SOURCE", detail: "用户只能选择服务端允许列表中的 opaque profile ID。" },
      { label: "TENANT", detail: "租户别名仅展示；可信 tenant 始终由服务端身份推导。" },
      { label: "SECRET", detail: "Provider 配置只保存 secret reference，创建后不回显。" },
    ],
  },
  dashboard: {
    eyebrow: "00 / 运行总览",
    title: "一张没有假数据的",
    titleAccent: "能力织图。",
    summary: "这一页只展示 Console 第一阶段真实状态：页面与安全边界已经建立，所有业务读数仍等待正式 BFF contract 和服务端授权投影。",
    signals: [
      { label: "来源会话", detail: "未配置" },
      { label: "能力发现", detail: "等待契约" },
      { label: "BFF 连接", detail: "未接入" },
      { label: "可用证据", detail: "0 条" },
    ],
    weaveTitle: "Console / BFF / Runtime",
    weaveDetail: "浏览器只能沿同源 BFF 这条经线进入系统。来源、tenant、授权和 secret 都在服务端纬线完成校验。",
    lanesTitle: "1.0 产品面",
    lanes: [
      { code: "A", title: "文件流", detail: "文档、版本、续传、审批与同步" },
      { code: "B", title: "证据流", detail: "Doctor、审计、诊断与安全导出" },
      { code: "C", title: "智能流", detail: "Agent、检索、工具确认与评测" },
      { code: "D", title: "控制面", detail: "来源、集成、策略、租户别名与运维" },
    ],
    evidenceTitle: "最近运行证据",
    evidenceEmpty: "尚无可信证据",
    evidenceDetail: "接入后只显示经过当前会话授权的最小状态、trace 摘要与修复建议。",
  },
  capability: {
    scope: "交付范围",
    currentState: "当前状态",
    workstreams: "页面工作流",
    liveSurface: "实时业务面",
    evidence: "关闭证据",
    evidenceHint: "以下证据来自 1.0 交付总账，不代表当前已经通过。",
  },
  pages: {
    documents: {
      eyebrow: "01 / 文档工作台",
      title: "让目录与版本成为可追溯的经纬。",
      summary: "面向目录浏览、文件名搜索、文档详情、版本、metadata、生命周期、下载、移动与续传资产认领的统一工作台。",
      deliverable: "FW10-010 / FW10-012 / FW10-042",
      cards: [
        { title: "宿主目录", detail: "只读目录浏览、路径解析与动态 ACL 投影。", boundary: "FlowWeft 不拥有目录 CRUD。" },
        { title: "文档账本", detail: "文档、版本、metadata 与 allowedActions 的最小 DTO。", boundary: "页面不根据生命周期自行推断权限。" },
        { title: "续传认领", detail: "上传完成资产一次性认领为文档或新版本。", boundary: "owner / tenant / expiry / purpose 全部服务端验证。" },
      ],
      emptyTitle: "尚未连接文档投影",
      emptyDetail: "这里不会注入示例合同或模拟目录；正式 DAL 可用后再渲染当前会话可见内容。",
      proofs: ["目录隐藏节点、坏树与竞态合约", "续传资产并发单次消费", "全流程键盘与 Playwright 验收"],
    },
    approvals: {
      eyebrow: "02 / 审批中心",
      title: "每个决定，都留下身份与时间的针脚。",
      summary: "只展示可信身份可处理的待办，服务端在批准、驳回与撤回时重新验证任务归属、tenant、权限和幂等键。",
      deliverable: "FW10-043",
      cards: [
        { title: "我的待办", detail: "服务端过滤后的审批任务与安全文档摘要。", boundary: "浏览器不能传入 assignee 或 tenant filter。" },
        { title: "决策面", detail: "批准、驳回、撤回与双人会签状态。", boundary: "可见按钮不构成授权；mutation 必须重验。" },
        { title: "决策证据", detail: "操作者快照、时间、trace 与可公开评论。", boundary: "隐藏内部策略、主体 ID 与未授权历史。" },
      ],
      emptyTitle: "审批契约尚未接线",
      emptyDetail: "没有虚构待办。正式 inbox projection 到位前，决策控件保持不可用。",
      proofs: ["权限隐藏与服务端拒绝双重 E2E", "并发决定与幂等冲突", "中英文读屏与键盘操作"],
    },
    sync: {
      eyebrow: "03 / 同步中心",
      title: "看见延迟，也看见每一次重排的原因。",
      summary: "聚合文档交付目标、代次、失败分类、撤回与 retry 状态，所有外部操作都在事务外通过 Outbox 和 Worker 执行。",
      deliverable: "FW10-043",
      cards: [
        { title: "异常队列", detail: "当前身份可见的失败、重试与滞后目标。", boundary: "不暴露 endpoint、请求头或厂商原始错误。" },
        { title: "受控重排", detail: "按目标重试交付或撤回，并返回幂等命令摘要。", boundary: "页面不提供手工 Process Outbox 后门。" },
        { title: "交付回执", detail: "generation、状态、重试次数与脱敏修复建议。", boundary: "远端成功不等于本地提交，未知结果进入协调。" },
      ],
      emptyTitle: "同步运行面尚未连接",
      emptyDetail: "接入后优先展示异常和可行动建议，而不是下游系统的原始响应。",
      proofs: ["失败重排与撤回 E2E", "未知完成结果协调", "Connector 错误与 secret 脱敏"],
    },
    doctor: {
      eyebrow: "04 / DOCTOR",
      title: "诊断不是红绿灯，而是一条可修复的证据链。",
      summary: "统一文档即时诊断、持久异步任务和生产系统诊断，输出有界状态、原因、证据摘要与修复建议。",
      deliverable: "FW10-014 / FW10-043",
      cards: [
        { title: "文档诊断", detail: "权限、生命周期、工作流、存储与 Connector 检查。", boundary: "不存在与无权限采用统一安全投影。" },
        { title: "运行诊断", detail: "DB、Worker lease、队列、索引与 tombstone lag。", boundary: "不返回连接串、类名、tenant 名称或原始证据。" },
        { title: "修复路径", detail: "分类原因、责任边界与可重复检查动作。", boundary: "Doctor 不替代授权，也不直接执行修复。" },
      ],
      emptyTitle: "Doctor 数据尚未装配",
      emptyDetail: "当前壳只定义安全呈现方式；所有健康结论必须来自正式 checker inventory。",
      proofs: ["真实 OTel Collector 三信号", "Worker/队列/容量/readiness", "脱敏修复建议契约"],
    },
    audit: {
      eyebrow: "05 / 审计",
      title: "把动作留下，把敏感上下文留在边界内。",
      summary: "面向全局审计查询、单文档轨迹与安全导出；仅显示当前主体有权观察的动作、操作者摘要、时间和 trace。",
      deliverable: "FW10-043",
      cards: [
        { title: "全局查询", detail: "按时间、动作、状态与资源范围查询授权记录。", boundary: "未授权资源不影响 count、filter 或 timing。" },
        { title: "文档轨迹", detail: "生命周期、审批、同步与诊断事件串联。", boundary: "不回显 token、prompt、正文或高基数 secret。" },
        { title: "安全导出", detail: "短时、审计化、权限复核后的最小数据集。", boundary: "导出不是浏览器直接拼接当前表格。" },
      ],
      emptyTitle: "审计查询尚未接线",
      emptyDetail: "页面不会生成演示操作者或 trace；正式全局审计 contract 完成后再开放筛选。",
      proofs: ["跨 tenant 零泄漏", "导出授权、过期与审计", "敏感字段静态与动态扫描"],
    },
    agent: {
      eyebrow: "06 / AGENT 对话",
      title: "让每次推理都能暂停、恢复并被解释。",
      summary: "围绕会话、持久 run、流式消息、取消、恢复、引用与预算的产品面；旧 fileweft-agent 兼容 ABI 不在这里复用。",
      deliverable: "FW10-022 / 023 / 024 / 044",
      cards: [
        { title: "会话与运行", detail: "持久消息、步骤、checkpoint、deadline 与取消。", boundary: "浏览器断线不等于 run 取消，恢复必须重验权限。" },
        { title: "受控上下文", detail: "仅装载经过两阶段授权的引用和最少正文。", boundary: "检索内容始终是不可信数据，不得提升为系统指令。" },
        { title: "预算与状态", detail: "token、cost、工具、轮数与墙钟预算摘要。", boundary: "模型或 Provider 上报不是唯一权威扣减来源。" },
      ],
      emptyTitle: "Agent 能力当前不可用",
      emptyDetail: "只读工作台不会伪造回答或退化为浏览器直连；缺少 Provider、契约或当前授权时会失败关闭。",
      proofs: ["崩溃恢复、租约与取消", "权限撤销与 prompt injection 红队", "引用、预算与双语流式 E2E"],
    },
    toolApprovals: {
      eyebrow: "07 / 工具确认",
      title: "副作用发生之前，先把风险编织成可读提案。",
      summary: "集中处理等待人工确认的工具步骤，展示规范化参数摘要、风险、外发目标、预算与有效期。",
      deliverable: "FW10-023 / 024 / 044",
      cards: [
        { title: "确定性提案", detail: "绑定 run、step、tool/schema digest 与参数 digest。", boundary: "模型文字不能替代 Policy Gate 的结构化决定。" },
        { title: "一次性确认", detail: "批准或拒绝绑定操作者、权限快照、TTL 与 nonce。", boundary: "参数、工具或策略变化后必须重新提案。" },
        { title: "结果协调", detail: "超时且结果未知的非幂等操作进入 reconciliation。", boundary: "不得自动重试或把未知状态显示成失败可重做。" },
      ],
      emptyTitle: "没有已连接的确认队列",
      emptyDetail: "第一阶段不注入高风险工具样例。正式 Runtime 到位后只显示当前主体可处理的提案。",
      proofs: ["确认重放与参数篡改", "并发确认和 TTL 过期", "未知副作用协调 E2E"],
    },
    retrieval: {
      eyebrow: "08 / 检索证据",
      title: "先证明能看见，再让内容进入模型。",
      summary: "呈现安全文件名搜索、候选回执、权威复核、内容水合与引用血缘；高级 Provider 不能安全过滤时必须失败关闭。",
      deliverable: "FW10-020 / 021 / 044",
      cards: [
        { title: "内建文件名搜索", detail: "NFKC 后 exact、prefix、contains 的安全基线。", boundary: "不读取正文，不返回未授权 total、score 或 highlight。" },
        { title: "Provider 回执", detail: "scope digest、policy revision 与候选血缘摘要。", boundary: "缺失或不匹配的 SecurityFilterReceipt 整批拒绝。" },
        { title: "引用链", detail: "document/version/chunk/hash 与当前授权复核。", boundary: "Content Provider 只水合已通过复核的引用。" },
      ],
      emptyTitle: "检索契约尚未连接",
      emptyDetail: "页面不执行本地全文过滤，也不会让浏览器直接访问 Dify、向量库或模型 Provider。",
      proofs: ["动态 ACL 与 scope 过期", "索引 generation / tombstone 传播", "跨阶段零内容泄漏"],
    },
    evaluations: {
      eyebrow: "09 / 评测",
      title: "把智能能力放进可重复的标尺，而不是截图。",
      summary: "管理离线数据集、运行、阈值、引用与工具正确性，以及安全拒绝、成本和延迟回归。",
      deliverable: "FW10-026 / 044",
      cards: [
        { title: "数据集", detail: "带版本、范围、来源与敏感级别的固定用例。", boundary: "评测内容不自动获得生产授权或长期记忆。" },
        { title: "运行与阈值", detail: "固定 Provider revision、策略、预算与可比较指标。", boundary: "LLM judge 不能作为授权或安全门禁的唯一判定器。" },
        { title: "回归证据", detail: "检索、引用、工具、安全、成本与延迟差异。", boundary: "评测结果不进入业务授权判断。" },
      ],
      emptyTitle: "尚无评测 Runtime",
      emptyDetail: "此页目前只冻结信息架构；不会用随机分数或演示图表冒充真实质量证据。",
      proofs: ["固定回归集与可重复 revision", "安全拒绝和 secret 泄漏套件", "成本/延迟阈值与 Provider 漂移"],
    },
    settings: {
      eyebrow: "10 / 设置",
      title: "把地址、权限与密钥留在正确的信任层。",
      summary: "统一来源档案、集成、策略预算、secret reference、租户别名、语言与运维设置，但不允许浏览器配置任意 endpoint。",
      deliverable: "FW10-041 / 043 / 044",
      cards: [
        { title: "来源与身份", detail: "管理员白名单 Source Profile、OIDC 与宿主 token exchange。", boundary: "来源 endpoint 和 refresh token 只存在服务端。" },
        { title: "集成与安全", detail: "模型、检索、Connector、MCP/A2A、策略与 secret reference。", boundary: "secret 创建后不回显；远端地址强制 SSRF 防护。" },
        { title: "租户与运维", detail: "展示别名、语言、插件、Worker、容量、SLO、保留与备份。", boundary: "展示别名不能改变可信 tenant context。" },
      ],
      emptyTitle: "管理契约尚未建立",
      emptyDetail: "配置表单保持不可用，直到服务端具备字段级授权、审计、schema 与 secret-reference 语义。",
      proofs: ["PKCE/session/CSRF/CSP", "SSRF allowlist 与 secret 不回显", "来源、租户和会话隔离"],
    },
  },
};

const en: ConsoleMessages = {
  brand: {
    name: "FLOWWEFT",
    product: "File intelligence infrastructure",
    edition: "1.0 CONSOLE / FOUNDATION",
  },
  navGroups: {
    work: "Document flow",
    assurance: "Assurance & evidence",
    intelligence: "Intelligence plane",
    administration: "Administration",
  },
  nav: {
    dashboard: "Overview",
    documents: "Documents",
    approvals: "Approvals",
    sync: "Synchronization",
    doctor: "Doctor",
    audit: "Audit",
    agent: "Agent conversations",
    toolApprovals: "Tool approvals",
    retrieval: "Retrieval evidence",
    evaluations: "Evaluations",
    settings: "Settings",
  },
  shell: {
    skip: "Skip to main content",
    foundation: "Foundation shell ready",
    foundationDetail: "No host, source, or FlowWeft Runtime is connected",
    sourceLabel: "Source profile",
    sourceValue: "No trusted session established",
    sessionLabel: "Session",
    sessionValue: "Unauthenticated / no live data",
    login: "Secure access notes",
    logout: "End server session",
    language: "Language",
    safePreview: "Product frame preview",
  },
  status: {
    frameReady: "UI frame ready",
    contractRequired: "Contract required",
    noLiveData: "No live data",
    unavailable: "Capability unavailable",
  },
  login: {
    eyebrow: "00 / SAME-ORIGIN ACCESS BOUNDARY",
    titleLead: "Establish trust before",
    titleAccent: "documents begin to move.",
    description: "The production Console accepts administrator-approved source profiles only. The browser can never submit an arbitrary host address or hold a FlowWeft service token, host refresh token, or provider secret.",
    panelTitle: "Choose a trusted source",
    panelStatus: "Phase three: secure login",
    sourceTitle: "Administrator-approved source profile",
    sourceEmpty: "No source profiles are available",
    sourceDetail: "The BFF returns only an allowlisted server-side summary. Endpoints and credentials never enter the browser DTO.",
    sourceConfigured: "Trusted sources discovered",
    sourceConfiguredDetail: "Login is enabled only for sources with complete OIDC endpoint, PKCE, and server-side session configuration.",
    sourceReady: "Ready to authenticate",
    sourceDegraded: "Authentication pending",
    sourceUnavailable: "Allowlist only",
    oidcMode: "OIDC + PKCE",
    hostExchangeMode: "Host token exchange",
    hostTenant: "Tenant alias",
    hostUsername: "Host account",
    hostPassword: "One-time password exchange",
    hostSubmit: "Authenticate into a server session",
    hostHint: "The password reaches the BFF only for this same-origin TLS request and is never kept in the session, logs, or browser storage.",
    oidc: "Continue with OIDC + PKCE",
    oidcHint: "Only one-time state and the authorization code return through the browser. The PKCE verifier, access token, and session remain in the BFF.",
    compatibilityTitle: "Host account compatibility mode",
    compatibilityDetail: "If a host only supports account login, the password may reach the BFF through a same-origin TLS POST once, must be exchanged immediately, and may never be persisted, logged, or echoed.",
    preview: "Inspect the no-data product shell",
    previewHint: "Use this only to inspect navigation, language, and layout. It is not an authentication bypass and contains no simulated business data.",
    guardrails: [
      { label: "SESSION", detail: "Secure, HttpOnly, SameSite cookies; every mutation revalidates the session and CSRF." },
      { label: "SOURCE", detail: "Users select opaque profile IDs from a server-side allowlist only." },
      { label: "TENANT", detail: "Tenant aliases are display-only; trusted tenant context is derived server-side." },
      { label: "SECRET", detail: "Provider configuration stores secret references only and never echoes them." },
    ],
  },
  dashboard: {
    eyebrow: "00 / OPERATIONS OVERVIEW",
    title: "A capability weave",
    titleAccent: "with no counterfeit data.",
    summary: "This page reports only what phase one actually delivers: the product shell and security boundaries exist; every business reading still awaits a formal BFF contract and server-authorized projection.",
    signals: [
      { label: "Source session", detail: "Not configured" },
      { label: "Capability discovery", detail: "Contract pending" },
      { label: "BFF connection", detail: "Not connected" },
      { label: "Trusted evidence", detail: "0 records" },
    ],
    weaveTitle: "Console / BFF / Runtime",
    weaveDetail: "The browser enters the system along one same-origin BFF warp. Source, tenant, authorization, and secret checks remain on server-side weft lines.",
    lanesTitle: "1.0 product planes",
    lanes: [
      { code: "A", title: "Document flow", detail: "Documents, versions, resumable upload, approval, and sync" },
      { code: "B", title: "Evidence flow", detail: "Doctor, audit, diagnostics, and safe export" },
      { code: "C", title: "Intelligence flow", detail: "Agent, retrieval, tool approval, and evaluation" },
      { code: "D", title: "Control plane", detail: "Sources, integrations, policy, tenant alias, and operations" },
    ],
    evidenceTitle: "Recent runtime evidence",
    evidenceEmpty: "No trusted evidence yet",
    evidenceDetail: "Once connected, this area shows only session-authorized status, trace summaries, and repair guidance.",
  },
  capability: {
    scope: "Delivery scope",
    currentState: "Current state",
    workstreams: "Page workstreams",
    liveSurface: "Live business surface",
    evidence: "Closure evidence",
    evidenceHint: "These requirements come from the 1.0 delivery ledger; they have not passed yet.",
  },
  pages: {
    documents: {
      eyebrow: "01 / DOCUMENT WORKBENCH",
      title: "Make catalogs and versions a traceable weave.",
      summary: "A unified workbench for catalog browsing, filename search, document detail, versions, metadata, lifecycle, download, move, and resumable-asset claims.",
      deliverable: "FW10-010 / FW10-012 / FW10-042",
      cards: [
        { title: "Host catalog", detail: "Read-only browsing, path resolution, and dynamic ACL projection.", boundary: "FlowWeft never owns catalog CRUD." },
        { title: "Document ledger", detail: "Minimal document, version, metadata, and allowedActions DTOs.", boundary: "The page never infers permission from lifecycle state." },
        { title: "Resumable claim", detail: "Claim a completed upload once for a document or version.", boundary: "Owner, tenant, expiry, and purpose are verified server-side." },
      ],
      emptyTitle: "Document projections are not connected",
      emptyDetail: "No sample contracts or simulated folders are injected. Visible content appears only after the formal DAL exists.",
      proofs: ["Hidden-node, malformed-tree, and race contracts", "Concurrent one-time upload claim", "Keyboard and Playwright end-to-end flow"],
    },
    approvals: {
      eyebrow: "02 / APPROVAL CENTER",
      title: "Every decision keeps the stitch of identity and time.",
      summary: "Shows only tasks actionable by the trusted identity. Approve, reject, and withdraw revalidate ownership, tenant, permission, and idempotency server-side.",
      deliverable: "FW10-043",
      cards: [
        { title: "My inbox", detail: "Server-filtered tasks and safe document summaries.", boundary: "The browser cannot supply assignee or tenant filters." },
        { title: "Decision surface", detail: "Approve, reject, withdraw, and dual-control states.", boundary: "Visible controls are not authorization; mutations revalidate." },
        { title: "Decision evidence", detail: "Operator snapshot, time, trace, and releasable comments.", boundary: "Internal policies, principal IDs, and hidden history remain private." },
      ],
      emptyTitle: "Approval contracts are not wired",
      emptyDetail: "There are no fictional tasks. Decision controls stay unavailable until the formal inbox projection exists.",
      proofs: ["Control hiding plus server rejection E2E", "Concurrent decisions and idempotency conflicts", "Bilingual screen-reader and keyboard checks"],
    },
    sync: {
      eyebrow: "03 / SYNCHRONIZATION",
      title: "See the lag—and the reason behind every replay.",
      summary: "Aggregates delivery targets, generations, failure classes, withdrawal, and retry state. External work remains outside transactions behind Outbox and Workers.",
      deliverable: "FW10-043",
      cards: [
        { title: "Exception queue", detail: "Visible failed, retrying, and lagging targets.", boundary: "Endpoints, headers, and raw vendor errors are never exposed." },
        { title: "Controlled replay", detail: "Retry delivery or withdrawal with an idempotent command summary.", boundary: "There is no manual Process Outbox backdoor." },
        { title: "Delivery receipt", detail: "Generation, status, attempts, and redacted repair guidance.", boundary: "Remote success is not local commit; unknown outcomes reconcile." },
      ],
      emptyTitle: "The synchronization plane is disconnected",
      emptyDetail: "When connected, the page prioritizes actionable failures rather than raw downstream responses.",
      proofs: ["Failure replay and withdrawal E2E", "Unknown completion reconciliation", "Connector error and secret redaction"],
    },
    doctor: {
      eyebrow: "04 / DOCTOR",
      title: "A diagnosis is a repairable evidence chain—not a traffic light.",
      summary: "Unifies immediate document checks, durable asynchronous tasks, and production system diagnostics with bounded status, reason, evidence, and repair guidance.",
      deliverable: "FW10-014 / FW10-043",
      cards: [
        { title: "Document diagnosis", detail: "Permission, lifecycle, workflow, storage, and connector checks.", boundary: "Missing and forbidden resources share a safe projection." },
        { title: "Runtime diagnosis", detail: "Database, worker lease, queue, index, and tombstone lag.", boundary: "No connection strings, class names, tenant names, or raw evidence." },
        { title: "Repair path", detail: "Classified causes, ownership boundaries, and repeatable checks.", boundary: "Doctor neither replaces authorization nor executes repairs." },
      ],
      emptyTitle: "Doctor data is not assembled",
      emptyDetail: "The shell defines safe presentation only; every health claim must originate in the formal checker inventory.",
      proofs: ["Real OpenTelemetry Collector with three signals", "Worker, queue, capacity, and readiness", "Redacted repair-guidance contract"],
    },
    audit: {
      eyebrow: "05 / AUDIT",
      title: "Keep the action. Keep sensitive context inside its boundary.",
      summary: "Provides global audit query, per-document history, and safe export with only authorized action, operator summary, time, and trace.",
      deliverable: "FW10-043",
      cards: [
        { title: "Global query", detail: "Query authorized records by time, action, state, and resource scope.", boundary: "Hidden resources cannot influence counts, filters, or timing." },
        { title: "Document trail", detail: "Connect lifecycle, approval, sync, and diagnostic events.", boundary: "Never echo tokens, prompts, content, or high-cardinality secrets." },
        { title: "Safe export", detail: "Short-lived, audited, reauthorized minimal datasets.", boundary: "Export is not a client-side copy of the current table." },
      ],
      emptyTitle: "Audit query is not wired",
      emptyDetail: "The page creates no demo operators or traces. Filters open only after the global audit contract exists.",
      proofs: ["Zero cross-tenant leakage", "Export authorization, expiry, and audit", "Static and dynamic sensitive-field scanning"],
    },
    agent: {
      eyebrow: "06 / AGENT CONVERSATIONS",
      title: "Every inference can pause, recover, and explain its evidence.",
      summary: "The product surface for conversations, durable runs, streamed messages, cancellation, recovery, citations, and budgets. It does not reuse the legacy fileweft-agent ABI.",
      deliverable: "FW10-022 / 023 / 024 / 044",
      cards: [
        { title: "Conversation & run", detail: "Durable messages, steps, checkpoints, deadlines, and cancellation.", boundary: "Browser disconnect is not run cancellation; recovery reauthorizes." },
        { title: "Controlled context", detail: "Load only two-stage-authorized citations and minimum content.", boundary: "Retrieved content is untrusted data and never becomes system instruction." },
        { title: "Budget & status", detail: "Token, cost, tool, turn, and wall-clock budget summaries.", boundary: "Provider-reported usage is not the only authoritative deduction." },
      ],
      emptyTitle: "Agent capability is unavailable",
      emptyDetail: "The read-only workbench never fabricates answers or falls back to browser-direct access. Missing providers, contracts or current authority fail closed.",
      proofs: ["Crash recovery, lease, and cancellation", "ACL revocation and prompt-injection red team", "Citation, budget, and bilingual streaming E2E"],
    },
    toolApprovals: {
      eyebrow: "07 / TOOL APPROVALS",
      title: "Turn side effects into a readable proposal before execution.",
      summary: "Collects human-confirmation steps with normalized parameter summaries, risk, egress target, budget, and expiry.",
      deliverable: "FW10-023 / 024 / 044",
      cards: [
        { title: "Deterministic proposal", detail: "Binds run, step, tool/schema digest, and parameter digest.", boundary: "Model prose never replaces the structured Policy Gate decision." },
        { title: "One-time approval", detail: "Approve or reject with operator, permission snapshot, TTL, and nonce.", boundary: "Changed parameters, tools, or policy require a fresh proposal." },
        { title: "Outcome reconciliation", detail: "Non-idempotent timeout with unknown result enters reconciliation.", boundary: "Never auto-retry or present unknown as a safe failure to replay." },
      ],
      emptyTitle: "No approval queue is connected",
      emptyDetail: "Phase one injects no high-risk tool fixtures. The Runtime will later return only proposals actionable by this principal.",
      proofs: ["Approval replay and parameter tampering", "Concurrent approval and TTL expiry", "Unknown side-effect reconciliation E2E"],
    },
    retrieval: {
      eyebrow: "08 / RETRIEVAL EVIDENCE",
      title: "Prove visibility before content reaches a model.",
      summary: "Presents safe filename search, candidate receipts, authoritative review, content hydration, and citation lineage. Unsafe advanced providers fail closed.",
      deliverable: "FW10-020 / 021 / 044",
      cards: [
        { title: "Built-in filename search", detail: "NFKC exact, prefix, and contains as the safe baseline.", boundary: "Reads no body and exposes no unauthorized total, score, or highlight." },
        { title: "Provider receipt", detail: "Scope digest, policy revision, and bounded candidate lineage.", boundary: "A missing or mismatched SecurityFilterReceipt rejects the batch." },
        { title: "Citation chain", detail: "Document, version, chunk, hash, and current authorization review.", boundary: "Content providers hydrate only references that passed review." },
      ],
      emptyTitle: "Retrieval contracts are not connected",
      emptyDetail: "The page performs no client-side full-text filtering and never calls Dify, a vector store, or a model provider directly.",
      proofs: ["Dynamic ACL and expired scope", "Index generation and tombstone propagation", "Zero content leakage across every stage"],
    },
    evaluations: {
      eyebrow: "09 / EVALUATIONS",
      title: "Measure intelligence with repeatable evidence—not screenshots.",
      summary: "Manages offline datasets, runs, thresholds, citation and tool correctness, safety refusal, cost, and latency regressions.",
      deliverable: "FW10-026 / 044",
      cards: [
        { title: "Datasets", detail: "Versioned cases with scope, provenance, and sensitivity.", boundary: "Evaluation content receives no automatic production authorization or memory." },
        { title: "Runs & thresholds", detail: "Pin provider revision, policy, budget, and comparable metrics.", boundary: "An LLM judge is never the sole authorization or security gate." },
        { title: "Regression evidence", detail: "Retrieval, citation, tool, safety, cost, and latency deltas.", boundary: "Evaluation results never influence business authorization." },
      ],
      emptyTitle: "No evaluation Runtime exists",
      emptyDetail: "This page freezes information architecture only; random scores and demo charts do not masquerade as quality evidence.",
      proofs: ["Fixed regression corpus and reproducible revision", "Safety refusal and secret-leak suite", "Cost/latency thresholds and provider drift"],
    },
    settings: {
      eyebrow: "10 / SETTINGS",
      title: "Keep addresses, authority, and secrets in the correct trust layer.",
      summary: "Unifies source profiles, integrations, policy budgets, secret references, tenant aliases, language, and operations without allowing arbitrary browser-configured endpoints.",
      deliverable: "FW10-041 / 043 / 044",
      cards: [
        { title: "Source & identity", detail: "Allowlisted Source Profiles, OIDC, and host token exchange.", boundary: "Source endpoints and refresh tokens remain server-side." },
        { title: "Integration & security", detail: "Models, retrieval, connectors, MCP/A2A, policy, and secret references.", boundary: "Secrets never echo; remote addresses receive strict SSRF controls." },
        { title: "Tenant & operations", detail: "Display aliases, language, plugins, workers, capacity, SLO, retention, and backup.", boundary: "A display alias can never change trusted tenant context." },
      ],
      emptyTitle: "Administration contracts do not exist yet",
      emptyDetail: "Forms stay unavailable until field-level authorization, audit, schema, and secret-reference semantics exist server-side.",
      proofs: ["PKCE, session, CSRF, and CSP", "SSRF allowlist and non-echoed secrets", "Source, tenant, and session isolation"],
    },
  },
};

export const messages: Record<Locale, ConsoleMessages> = { zh, en };

export function getMessages(locale: Locale): ConsoleMessages {
  return messages[locale];
}
