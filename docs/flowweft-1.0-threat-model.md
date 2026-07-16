# FlowWeft 1.0 威胁模型

状态：设计基线，随实现持续更新。它覆盖 FlowWeft 自有代码、控制台、Provider、
参考 Adapter、数据库、对象存储、索引和远端 Agent/工具。宿主身份系统、目录和
业务策略虽不由 FlowWeft 实现，但它们返回的身份与授权结果位于信任边界上。

## 资产与安全目标

- 文件正文、文件名、metadata、目录位置、向量、摘要、prompt、会话和引用；
- tenant/user 身份、授权策略、审批、审计与 legal hold；
- 模型、Dify、OSS、MCP/A2A 和宿主 token/secret；
- Agent 工具副作用、预算、用量、运行状态和发布供应链身份。
- 流程定义、实例变量、候选人、任务决定、表单、评论/mention、组织投影、计时器、
  迁移计划、通知和电子签名/见证证据。

首要不变量是：未授权主体不能观察文档存在性、标题、正文、分数、数量、引用、
缓存或时间差形成的可利用信号；Agent 不能获得超过当前用户和当前工具策略的能力；
异步索引、删除或权限投影落后时必须产生漏召回而不是越权放行。

## 信任边界

| 边界 | 默认信任 |
| --- | --- |
| 浏览器 → Console BFF | 全部输入不可信；opaque session ID 必须只作服务端 digest 查询，并复核过期和来源 |
| Console BFF → Redis | Redis 与网络按敏感认证基础设施防护；生产使用 TLS，记录做带 AAD 的 AES-GCM 信封加密，解密/可用性失败关闭 |
| BFF → FlowWeft/宿主 API | 只信允许列表 source profile 与服务端凭据；响应仍做 schema/大小校验 |
| 请求 → Tenant/User Context | 只信宿主认证 Provider 的服务端结果，不信 body/header 中自报身份 |
| FlowWeft → Model/Retriever/Tool | 外部系统不可信且可能被攻陷、漂移、限流或返回恶意内容 |
| 文档/metadata/OCR → Agent | 一律是不可信数据，不能成为系统指令 |
| Plugin → JVM | 仅允许审查过的可信代码；普通 Plugin 不是沙箱 |
| Worker → DB/Object/Index | 使用最小权限、租约、幂等和 generation fence；网络成功不等于本地提交 |
| 构建 → 发布仓库 | 只信匹配受保护 tag/SHA 的签名、provenance 和冷读证据 |

## 主要威胁与控制

### 跨租户、IDOR 与 confused deputy

- tenant 只来自可信上下文，所有表查询、对象 key、事件、任务、缓存和索引都绑定
  tenant；客户端 tenant/ACL filter 永远忽略或拒绝。
- 每个检索子通道执行 mandatory tenant+ACL pre-filter，并验证安全回执；随后用
  当前 `AuthorizationProvider` 权威复核。
- HTTP 统一无权限/不存在投影，隐藏内部 ID、数量、score、storage key 和错误细节。

### ACL 过期与检索侧信道

- Access plan 有 policy revision、expiry 和 scope digest；缓存键包含 tenant、subject/
  scope digest、policy revision、index generation 和 pipeline revision。
- stale allow 被最终复核立即拒绝；stale deny 只导致暂时漏召回。严格模式可要求索引
  ACL revision 追平。
- 未授权 hit 不进入 fusion、rerank、LLM、citation、日志或共享缓存；不返回授权前
  total、highlight、aggregation 或 autocomplete。

### Prompt injection、RAG poisoning 与工具投毒

- 内容块标注 `SYSTEM/DEVELOPER/USER/RETRIEVAL/TOOL/A2A/MEMORY` 来源和信任级别；
  后五类不能提升为控制指令。
- Planner 与工具执行由确定性 Policy Gate 约束；读写工具拆分，禁止任意 HTTP、
  shell、SQL、文件系统和 repository。
- 工具 descriptor、JSON Schema、server identity 和 artifact digest 变化后隔离，必须
  重新评审；MCP annotations 只作提示，不能作为安全证明。
- 结构、MIME、大小、URL、Unicode 和引用 hash 在各边界校验；安全 scanner 只是
  纵深防御，不替代授权。

### SSRF、凭据与数据外传

- Source、MCP、A2A、webhook 和远端知识库 endpoint 仅管理员 profile 可配置；做
  DNS/IP 重绑定、私网/link-local/loopback、端口、scheme、重定向和响应大小限制。
- secret 只以引用/句柄传递，不进入 prompt、事件、检查点、客户端 DTO、日志、trace
  或异常；密码只在 BFF 内做一次 token exchange。
- 外部 reranker/embedding/model 只收到已授权的最少 excerpt，并服从 tenant data
  egress/residency policy。

### Console 会话劫持、重放与共享存储泄漏

- 浏览器 cookie 只保存随机 opaque session ID；服务端只保存其 SHA-256 digest，cookie
  使用 `Secure`、`HttpOnly`、`SameSite` 与生产 `__Host-` 约束，mutation 额外做精确
  origin/CSRF 检查。
- OIDC state 是一次性原子消费；session 创建、容量和 TTL 在共享 Redis 内原子执行，
  多副本不能各自绕过上限。Redis 不可用、信封损坏或 key 不匹配一律失败关闭，不回退
  到本地 session。
- Redis 值以 AES-256-GCM 加密，AAD 绑定记录类型与 digest；第一把 key 只负责新写入，
  旧 key 只在不超过最长 session TTL 的轮换窗口读取。Redis URL、凭据和 keyring 只从
  server-side secret 注入，不进入浏览器 DTO、日志、trace、异常或仓库。
- 未配置 Redis 的 process-local adapter 只能在生产显式确认单副本后使用，进程重启即
  登出；它不是多副本降级机制。账号/IP 分布式限速、锁定和审计仍由宿主身份边界负责。

### 工具副作用、重试与审批重放

- 逻辑幂等键固定为 tenant/run/step/operation；attempt 单独记录。
- 非幂等操作超时且结果未知时进入 reconciliation，不自动重试。
- 审批绑定 tenant、run、step、工具/schema digest、规范化参数 digest、操作者、
  策略/权限快照、外发目标、预算、有效期和一次性 nonce；参数编辑后重新提案。
- 恢复执行前重验权限、资源版本、工具 digest、预算与审批有效性，防止 TOCTOU。

### 工作流越权、候选枚举与审批证据伪造

- definition、instance、task、subject 和组织 selector 都绑定 tenant；候选列表、待办
  数量、mention 建议和组织搜索必须先按当前用户可见范围投影。
- 组织解析回执绑定 provider instance、directory revision、selector/input/result
  digest、有效期和完整性；循环、超限、跨租户、缺失领导或目录不可用失败关闭，
  不截断、不猜测、不自动同意。
- task claim/decision 在执行瞬间复核候选资格、流程动作和 subject 权限。决定绑定
  definition/form/rule/subject/task version、规范化输入、操作者、授权 revision、
  nonce 和时间；转签、委派、加签与 Agent 代办不能扩大权限。
- 发布定义、运行中迁移、跳过事故、终止实例和修改候选是独立高风险权限；普通
  approve 权限不能调用运维修复动作。

### 流程定义、表达式与持久执行攻击

- 导入 BPMN/DMN/CMMN/Open Workflow 时逐元素报告支持度；未知元素、表达式或厂商
  extension 不能被静默忽略后发布。
- 禁止任意 SpEL/JavaScript/JVM class/shell/SQL/URL 节点。表达式和 service task
  provider 受 schema、预算、deadline、egress、幂等和能力 digest 约束。
- token、timer、subscription、job、incident 和 migration 使用乐观版本、租约和
  fence；重复事件、旧 worker、旧 timer 或旧定义不得复活已离开的节点。
- 运行中迁移必须映射每个活动 token、timer、subscription 和变量；dry-run 或映射
  缺失时失败关闭，不能把实例悄悄移动到“看起来相近”的节点。

### 表单、评论、mention 与通知泄漏

- 表单数据服务端按固定 JSON Schema 和字段 ACL 复核；secret 只保存引用。隐藏字段
  不进入历史 DTO、通知、日志或 Agent 上下文。
- 评论存结构化 TEXT/MENTION token，不存任意 HTML；前端不使用
  `dangerouslySetInnerHTML`，不可信 URL/事件/CSS 一律拒绝。
- mention 不授予访问权。通知发送前重新检查接收者和 subject 可见性，撤权后不发送
  标题、表单摘要、评论或附件信息；发送目标不能由流程变量提供任意 URL。

### 索引复活、删除不完整与模型漂移

- Outbox intent 带 event ID、aggregate revision、desired generation 和 content hash；
  consumer 使用幂等、revision 和 fence 拒绝旧 upsert。
- offline/archive/delete 先使业务读取不可见，再删除 active/building/rollback 全部
  generation 的 chunk、embedding、摘要和 cache，并保存 provider receipt。
- embedding generation 固定 provider/model revision、dimension、distance、normalization、
  parser/chunker 和 ACL schema；禁止原地换模型或混合向量空间。

### DoS、失控成本与委派爆炸

- 每个 tenant/run 有墙钟、轮数、模型调用、token、工具、检索字节、重试、A2A
  深度/fan-out 和货币预算；子任务只能消费父任务显式分配的剩余预算。
- Provider 不报告 usage 时按保守估算扣减；队列、上传、索引和 Agent 都有背压、
  熔断、限流、deadline 和有界响应。

### 遥测、记忆与推理状态泄漏

- prompt、正文、query、向量、工具参数/结果和原始 chain-of-thought 默认不采集。
- Provider 的加密推理状态按 opaque secret 数据保存，不解释、不展示。
- 长期记忆默认关闭；启用时必须有来源、置信度、tenant/user scope、TTL、替代和删除，
  永远不能成为授权事实。

### 供应链与发布身份混淆

- 依赖执行 OSV 策略，产物生成 runtime-closure SBOM、签名和 SLSA provenance。
- Tag、commit、POM/module metadata、SBOM、镜像 label 和 provenance 必须指向同一
  revision；只接受匹配完整 SHA/event/ref 的 CNB lane 与匿名冷缓存验证。

## 必须进入发布门禁的攻击用例

1. 两个 tenant 使用相同 document/chunk ID，伪造 tenant、目录、group、ACL filter。
2. 权限在召回前后撤销/授予，索引 ACL 落后/超前，scope 过期或 receipt 不匹配。
3. 文档正文、metadata、HTML 注释、隐藏 Unicode、OCR 图片和 poisoned chunk 中的
   system/tool/secret 指令。
4. 工具 schema/二进制漂移、确认重放、参数修改、失租、超时结果未知和重复 webhook。
5. DNS rebinding、IPv4/IPv6 私网、userinfo URL、多跳重定向、巨大/压缩炸弹响应。
6. delete 后旧 upsert 重放、双 generation、部分 bulk、backend 成功但 receipt 写入失败。
7. 跨 tenant cache、score/count/timing、引用伪造、过期引用与未授权历史版本。
8. budget 绕过、无限工具循环、A2A fan-out、Provider 不报 usage 和取消后副作用完成。
9. secret、正文、query、tenant/user/doc 高基数进入日志、metric、trace、异常或 UI。
10. 多上级/嵌套组循环、候选超限、任务 claim 与撤权竞态、quorum 分母被组织变化改写。
11. 恶意流程定义/表达式、重复 timer/message、旧 worker 推进、迁移漏映射活动 token。
12. 表单字段越权、评论/mention XSS、隐藏用户枚举、mention 冒充授权和通知撤权后泄漏。

安全用例必须断言数据在 retriever、fusion、rerank、model、citation、cache 和 telemetry
各阶段均为零泄漏。LLM judge 和固定字符串 scanner 不能作为授权或安全发布的唯一
判定器。

## 参考

- [Google Zanzibar](https://research.google/pubs/zanzibar-googles-consistent-global-authorization-system/)
- [OpenFGA：Search with permissions](https://openfga.dev/docs/interacting/search-with-permissions)
- [NIST AI 600-1](https://doi.org/10.6028/NIST.AI.600-1)
- [NIST AI 100-2e2025](https://doi.org/10.6028/NIST.AI.100-2e2025)
- [OWASP Prompt Injection Prevention](https://cheatsheetseries.owasp.org/cheatsheets/LLM_Prompt_Injection_Prevention_Cheat_Sheet.html)
- [NSA MCP Security Design Considerations](https://www.nsa.gov/Portals/75/documents/Cybersecurity/CSI_MCP_SECURITY.pdf)
- [SLSA 1.2](https://slsa.dev/spec/v1.2/)
- [FlowWeft 通用 Workflow ADR](decisions/0003-generic-workflow-platform.md)
