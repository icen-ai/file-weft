# ADR 0001：FlowWeft 1.0 产品范围与智能能力边界

> 品牌说明：本 ADR 在产品更名决定之前形成。当前产品名是 **FlowWeft**；
> `FileWeft` 在本文中保留为当时名称和已发布兼容标识。更名规则见
> [ADR 0002](0002-flowweft-product-rename.md)。

- 状态：已接受
- 日期：2026-07-15
- 决策者：项目维护者
- 适用范围：`0.0.3` 之后至 `1.0.0` 的开发与发布
- 取代：将 Agent 无限期延后到 1.0 之后的未来开发决策

## 历史事实与新决策

`0.0.2` 和 `0.0.3` 没有 Agent 产品能力，旧 `fileweft-agent`、
`ai.icen.fw.spi.ai` 公共 ABI 与 V012/V026 相关数据库结构只为兼容保留。这些
历史事实不变。

新的产品决策是：FileWeft 直接收敛 `1.0.0`。1.0 必须包含重新设计的 Agent、
权限过滤的内容检索、全文/向量扩展点、持久编排、完整产品控制台、三个参考
集成，以及公开 API/ABI 的冻结和升级承诺。旧 Agent 模型不能被改名后冒充
新实现。

## 为什么现有扩展点不足

旧模型固定为“Outbox 事件 → 四种枚举能力之一 → 单个同步 `execute` → 建议
确认”。它没有以下不可省略的语义：

- 模型提供方、结构化输出、流式响应与用量统计；
- 多步工具调用、风险分级、人工确认、取消、截止时间和预算；
- 会话、运行、步骤、恢复点与外部调用幂等；
- 文档级权限预过滤、结果后二次授权、索引血缘和删除传播；
- 全文、向量、混合检索、重排、抽取和嵌入的提供方边界；
- MCP/A2A 互操作及其授权、SSRF、凭据和能力变更风险；
- 评测、引用、提示注入防护和 GenAI 可观测性。

向已发布的枚举、构造器、返回模型或 V012 表中强塞这些语义，会破坏源码、
二进制或数据库兼容，也会让旧消费者误解行为，因此必须新增契约。

## 为什么现在必须实现

FileWeft 的产品定位是文件智能基础设施。仅有同步和审批不足以支撑权限内问答、
内容检索、自动化编排和纯后端项目的可用产品面。项目维护者已经把上述能力与
1.0 稳定线绑定；如果先冻结旧公共面，再在 1.x 引入完整智能模型，兼容成本会
更高。

## 决策

### 1. 版本与交付方式

主开发线目标是 `1.0.0-SNAPSHOT`。原路线图中的 0.1–0.4 工作改为 1.0 内部
证据里程碑，不再要求先发布四个公共小版本。任何里程碑未通过，1.0 都不能
标记或发布。

### 2. 增量模块，不改义复用旧 Agent

计划新增以下公共或运行时边界；最终名称可在首次代码提交前按依赖图微调，
但职责不能合并回旧兼容 ABI：

| 模块 | 职责 |
| --- | --- |
| `flowweft-retrieval-api` | Java 友好的候选检索、授权范围、内容水合、索引和血缘契约 |
| `flowweft-agent-api` | 模型、消息、运行、工具、审批、预算、取消、引用和评测契约 |
| `fileweft-agent-runtime` | 持久状态机、策略执行、工具循环、恢复、诊断和可观测性 |
| `fileweft-adapter-dify` | Dify 知识库参考同步/检索适配器，权限能力不足时失败关闭 |
| `fileweft-adapter-oss` | 阿里云 OSS 参考存储适配器，不向 SPI 泄漏厂商 SDK |
| `flowweft-console` | 独立 Next.js 产品控制台与同源 BFF |

RustFS 继续由 `fileweft-adapter-s3` 的 S3 兼容实现承载，并提供真实环境配置、
合约与运维文档，不制造一个只换名称的重复适配器。

旧 `fileweft-agent` 继续发布并保持默认不自动暴露。新贡献使用独立的 1.0
插件/Provider 契约，不向 `FileWeftPlugin.agents()` 或
`agentTaskTriggers()` 增加新语义。

### 3. 检索安全模型

FileWeft 内建能力只做文件名匹配。内容抽取、全文、向量、混合检索、查询改写、
嵌入和重排全部由 SPI 提供。

高级检索采用“强制前置过滤 + 权威复核”的两层安全协议：

1. `RetrievalAuthorizationPlanner` 从可信 tenant/user/action/policy revision 生成
   短期 `RetrievalAccessPlan`。计划只允许三种显式 profile：有界授权 ID 集、可信
   claim/ACL 过滤器，或经过严格 opt-in 的后端原生 ACL。用户、模型和 metadata
   filter 都不能生成或覆盖它。
2. 每个全文、稀疏、向量或远端知识库通道必须在 ANN/BM25/远端查询期间同时执行
   tenant 和 access plan 前置过滤，并返回匹配 scope digest/policy revision 的
   `SecurityFilterReceipt`。缺少回执、回执不匹配、计划过期或 Provider 不支持该
   profile 时，整批失败关闭。
3. Provider 只返回有界的文档/版本/分块引用与内部排序证据，不向调用方暴露前置
   授权前的 hit 数、分数、标题、highlight 或聚合。
4. FileWeft 立即使用当前权威授权逐条或有界批量复核。只有通过复核的引用才允许
   水合正文、重排、生成摘要或进入模型上下文；Reranker 只能重排或删除输入引用，
   不能新增候选或改写证据 ID。
5. 索引记录绑定 tenant、document、version、asset、catalog、内容哈希、ACL/
   policy revision、抽取/分块/嵌入版本。Outbox 驱动增量更新、重建与 tombstone
   删除传播。

“先全库召回再 post-filter”不是生产降级路径，因为它会造成未授权内容处理、侧
信道和 authorized top-k 饥饿。任何无法证明强制 tenant/ACL 前置过滤、稳定血缘
或删除收敛的外部检索模式必须失败关闭，并回退到内建文件名搜索。Dify 参考
适配器不能把“已接入知识库”冒充为“已满足 FileWeft 权限模型”。

### 4. Agent 安全与执行模型

- 租户和用户只来自可信请求/任务上下文，不能来自 prompt、HTTP body、模型或
  工具参数。
- Agent 不持有 repository 或领域修改接口。工具只能包装现有 Application 用例，
  并在执行瞬间重新授权。
- 工具风险至少分为只读、可逆写、不可逆/外部副作用；后两类按策略要求人工
  确认。确认绑定运行、步骤、参数摘要、操作者和有效期。
- 所有运行持久化状态、步骤、预算、截止时间、租约、取消请求和幂等键；外部
  模型/工具调用不得位于数据库事务内。
- 检索内容、工具输出和远端 Agent 数据都是不可信数据，不得提升为 system/
  developer 指令。输出必须保留引用和来源血缘。
- MCP 是工具适配协议，A2A 是远端 Agent 互操作协议；二者都是可选 adapter，
  不是 Core 依赖。远端地址必须由管理员允许列表管理，并强制 TLS、凭据隔离、
  超时、重试、响应上限、私网/重定向限制和能力变更复核。
- 默认遥测记录操作名、Provider/模型标识、状态、延迟和 token/cost 聚合；prompt、
  文档正文、工具参数/结果和 secret 默认不进入日志、span 或 metric label。

MCP HTTP 授权实现遵循当前发布的 2025-11-25 规范；A2A adapter 以当前 1.0
规范的 Agent Card、任务取消和服务端授权为互操作基线。协议升级需独立兼容
测试，不能让最新草案隐式改变 1.0 行为。

### 5. 目录归宿主

FileWeft 不实现目录创建、改名、删除、排序或组织树维护。1.0 只增加安全的
只读目录浏览/解析 HTTP facade，并保留受控文档移动命令。宿主
`DocumentCatalogProvider` 仍负责真实树、ACL 和 canonical ID。

### 6. 三个参考集成

1. RustFS：S3 兼容存储的真实环境合约、断点续传、Range/ETag、错误分类和
   Doctor 证据。
2. Dify：知识库创建/选择、文档同步、删除、幂等、状态回读和满足安全模式时的
   检索参考；API 变化以显式兼容矩阵处理。
3. 阿里云 OSS：对象读写、分片、预签名、Range/ETag、幂等删除、错误分类和
   Doctor 参考。

它们是开源参考实现，不承诺替宿主维护 ESE、AppBuilder 或其他厂商 SDK。

### 7. 产品控制台

控制台采用 Next.js App Router、TypeScript 和同源 BFF。它覆盖登录/来源选择、
仪表盘、目录和文档、续传上传、审批、同步、Doctor、审计、Agent 对话、工具
确认、检索证据、配置、评测、租户别名与运维设置。

- 首选 OIDC Authorization Code + PKCE。
- 兼容宿主用户名/密码时，密码只在 TLS 同源 POST 中到达 BFF，并立即交换为
  宿主 token；不得持久化、记录、回显或写入浏览器存储。
- 来源 endpoint 是管理员创建并允许列表校验的 profile，登录用户不能提交任意
  URL。租户别名只用于展示，可信 tenant 始终由服务端身份/token 推导。
- session 使用 Secure、HttpOnly、SameSite cookie；服务端 DAL 每次做授权并只
  返回最小 DTO；配置 CSP、CSRF、点击劫持和敏感缓存控制。
- Provider secret 只保存 secret reference，创建后不回显。
- 中文和英文为 1.0 必测语言，目标为 WCAG 2.2 AA。

### 8. 其他 1.0 必交付项

- 断点续传完成资产的一次性安全认领；
- 可复用、执行后退出的迁移 CLI/Job；
- TestKit 扩充、真实 OTel Collector 证据与生产 Doctor；
- retention、legal hold、容量、SLO、RPO/RTO 与备份恢复演练；
- API/ABI 基线、全升级矩阵、威胁模型、SBOM、签名和 provenance。

完整清单和关闭证据见 `docs/flowweft-1.0-delivery-ledger.md`。

## 兼容影响

- `0.0.1`–`0.0.3` 的发布说明、坐标和迁移 checksum 不变。
- 旧 Agent 类型、构造器、枚举、Plugin getter、行为和表继续通过二进制/源码/
  数据库兼容测试。
- 新功能默认通过新模块和显式配置启用；未安装 Provider 时仍可使用全部非 Agent
  文档能力和文件名搜索。
- 1.0 首次发布后，所有声明为 public 的契约进入公开弃用策略，禁止无迁移路径
  删除。

## 数据库迁移策略

- 只从 V030 继续追加三种方言迁移；V001–V029 永不改写。
- 新 Agent 运行、步骤、消息、工具确认、预算、索引投影和删除 tombstone 使用
  新表。不得复用 `fw_agent_result` 表表达新状态机。
- 迁移先落 nullable/兼容结构，再由可恢复后台任务回填索引；切换读路径前验证
  计数、tenant、hash、ACL revision 和 tombstone。
- 任何 destructive cleanup 都延后到 1.0 之后的公开弃用周期，且 1.0 不删除旧
  Agent 表。

## 测试与发布计划

- API：Kotlin/Java 单元、契约、源码兼容、二进制兼容和序列化 golden tests。
- 安全：跨租户、对象 ID 猜测、动态 ACL 撤销、prompt injection、工具投毒、
  SSRF、重定向、secret/PII 泄漏、预算绕过和确认重放。
- Runtime：崩溃恢复、租约丢失、取消、超时、幂等、并发确认、Provider 降级和
  删除传播。
- Adapter：RustFS、Dify、OSS 的隔离真实环境 lane；一个模拟器的绿测不能替代
  对应服务证据。
- UI：组件、API contract、Playwright、键盘/读屏基础、双语、浏览器安全头和
  session/来源隔离。
- 数据库：PostgreSQL、MySQL、KingbaseES 全新 V001+ 与每个受支持稳定版本升级。
- 生产：真实 OTel Collector、容量基准、备份恢复、故障注入和 SLO 告警。
- 发布：JDK 8/11/17/21/25、Boot 2/3、API/ABI baseline、OSV 策略、SBOM、签名、
  SLSA provenance、冷缓存匿名消费和精确 CNB SHA 证据。

## 主要参考基线

- [MCP 2025-11-25 规范](https://modelcontextprotocol.io/specification/2025-11-25/basic)
- [A2A 1.0 规范](https://a2a-protocol.org/latest/specification/)
- [OAuth 2.0 Security BCP（RFC 9700）](https://www.rfc-editor.org/rfc/rfc9700.html)
- [OAuth 2.0 Protected Resource Metadata（RFC 9728）](https://www.rfc-editor.org/rfc/rfc9728.html)
- [OpenTelemetry Semantic Conventions](https://opentelemetry.io/docs/specs/semconv/)
- [NIST AI RMF Generative AI Profile](https://nvlpubs.nist.gov/nistpubs/ai/NIST.AI.600-1.pdf)
- [Next.js Authentication](https://nextjs.org/docs/app/guides/authentication)
- [Next.js Data Security](https://nextjs.org/docs/app/guides/data-security)
- [WCAG 2.2](https://www.w3.org/TR/WCAG22/)
- [SLSA 1.2](https://slsa.dev/spec/v1.2/)

## 后果

好处是 1.0 在冻结前形成完整且可替换的智能能力边界，宿主可以只使用文件基础
设施，也可以选择任意模型和检索栈。代价是 1.0 范围较大，数据库、UI、安全和
真实环境证据都成为发布阻断项。不得通过减少测试、把参考适配器宣传成无限厂商
支持，或让模型绕过 Application/Authorization 来压缩范围。
