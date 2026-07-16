# FlowWeft 1.0 目标架构

本文把 [ADR 0001](decisions/0001-flowweft-1.0-product-scope.md) 转成代码边界。
它描述目标状态，不代表对应代码已经交付；完成状态只认
[1.0 交付总账](flowweft-1.0-delivery-ledger.md) 的证据。

## 总体依赖

```text
flowweft-console (Next.js/BFF)
            |
            v
web Spring Boot 2/3 -> web-runtime -> application -> domain -> core
                                               |          ^
                                               v          |
                                     agent-runtime -> agent-api
                                               |          |
                                               v          v
                                      retrieval-api      spi
                                               ^          ^
                                               |          |
                       Dify / model / search adapters   S3 / OSS adapters

workflow Boot 2/3 -> workflow-runtime -> workflow-domain -> core
                           |       ^
                           v       |
                      workflow-spi/api
                           ^
                           |
          document adapter / Agent tools / standard adapters
```

`core`、`domain`、`spi` 不依赖 Agent、Spring、数据库或厂商 SDK。新 API 模块
只包含 Java 友好契约；Runtime 才负责策略和编排；Adapter 才允许引入外部 SDK。

## 独立通用 Workflow

`flowweft-workflow-*` 是独立产品族，不依赖文件 Domain/Application。它提供版本化
定义、持久 token/job/timer、完整人工作业、组织/参与者/规则/表单/通知 SPI 和通用
Web/Starter。企业可以只安装 Workflow JAR；文件审批通过可选 document adapter
接入，Agent 通过可选 tool adapter 在当前 principal 权限内调用相同 Application
ports。详细边界与 2026-07 标准基线见
[Workflow 目标架构](flowweft-1.0-workflow-architecture.md) 和
[ADR 0003](decisions/0003-generic-workflow-platform.md)。

旧 `WorkflowInstance`、`WorkflowTask`、`DocumentReviewRouteProvider` 和 V001–V029
继续是文档专用兼容岛，不原地扩成通用引擎。新表从 V030 追加；新旧文档审批切换
必须保证同一文档最多一个活动 review，并保留历史来源。

## 旧 Agent 与新 Agent

| 旧兼容面 | 1.0 新面 |
| --- | --- |
| `AgentCapability` 四值枚举 | 稳定字符串 capability/tool/model ID 与协商能力 |
| 单次同步 `FileWeftAgent.execute` | 可恢复的 run/step 状态机 |
| 事件触发的建议 | 用户会话、后台工作流、只读查询和受控工具调用 |
| `fw_agent_result` 结果投影 | 独立 run、step、message、approval、usage、checkpoint 表 |
| 无检索语义 | 两阶段权限检索、引用、索引血缘和删除传播 |
| 只捕获成功/失败 | 取消、超时、预算、重试分类、等待确认和部分进度 |

两者并存。1.0 Runtime 不适配或代理旧 Agent；只有显式 legacy 开关继续运行旧路径。

## 核心运行状态机

```text
QUEUED -> RUNNING -> COMPLETED
              |  \-> FAILED
              |  \-> CANCELLED
              |  \-> EXPIRED
              |
              +-> WAITING_APPROVAL -> RUNNING
              +-> WAITING_TOOL ----> RUNNING
```

每次状态变化都带乐观版本、tenant、run ID、操作者/触发源、时间、租约 token 和
审计原因。Provider 调用前提交 checkpoint，调用后以 idempotency key 写回；
Worker 失租不得覆盖新领取者。取消是持久请求：Runtime 在模型流、工具循环、重试
和水合之间检查，并尽力取消外部句柄。

## Provider 契约族

所有 ID 都是稳定、有界、不透明字符串；所有集合做防御性复制；异常分成可重试、
永久、限流、认证、授权、配额、取消和协议错误。

- `LanguageModelProvider`：模型描述、结构化消息、工具定义、生成、可选流式 observer、
  token usage 和 provider request ID。
- `EmbeddingProvider`：模型/维度/版本、批量上限和确定的内容 hash 输入。
- `RetrievalCandidateProvider`：只返回 tenant 内文档/版本/chunk 引用、分数和检索
  证据，不返回未授权正文。
- `RetrievalContentProvider`：只接受 Runtime 已授权的引用并水合正文。
- `RetrievalAuthorizationPlanner`：把可信身份和授权策略编译成短期、带
  tenant/user/action/policy revision/expiry/scope digest 的强制 access plan。
- `BatchAuthorizationProvider`：对候选做当前权威批量复核；旧单条授权 SPI 可在
  严格数量上限内兼容适配。
- `TextSearchProvider`、`VectorSearchProvider`、`Reranker`：可由一个实现组合，
  Runtime 不假定 Elasticsearch、OpenSearch、pgvector 或某个云服务。
- `ContentExtractor`、`ContentChunker`：输出版本、offset/page、MIME、hash 和来源
  血缘；解析失败不能污染旧的可用索引。
- `AgentTool`：descriptor 与 executor 分离；descriptor 变更会改变 digest 并触发
  策略复核。
- `AgentPolicyProvider`：模型/工具允许列表、风险确认、预算、数据驻留和 egress。
- `AgentEvaluator`：离线数据集与在线抽样接口，评测结果不进入业务授权判断。

SPI 不包含 SDK 客户端、Spring Bean、HTTP DTO、数据库实体、Kotlin coroutine 或
`Flow`。

## 权限检索链

1. Web/任务入口建立可信 `TenantContext` 与 `UserIdentity`。
2. Application 验证 `agent:query` 或对应业务动作，生成内部 authorization scope。
3. 每个 Candidate Provider 强制 tenant filter 和 host-compiled access plan
   pre-filter，并返回 scope digest 完全匹配的安全回执。
4. Runtime 对每个候选的当前 document/version 执行 `document:read` 权威复核，
   并验证候选
   仍指向同一内容 hash 与策略 revision。
5. Content Provider 只水合已通过的引用；水合后再次核对 hash/tenant。
6. Reranker 和模型只接收允许内容。响应引用再次经过安全投影，不暴露 storage key、
   内部策略或未授权相邻文本。

动态 ACL 无法安全编译成 Provider 支持的 profile 时，该高级 Provider 必须失败
关闭；不能用全库召回加 post-filter 替代。系统仍可使用走同一授权链的内建文件名
搜索。Provider 若只能“搜索并返回正文”，必须证明其远端前置过滤已达到相同
边界，否则配置状态为不健康且不参与查询。

内建文件名搜索只支持 Unicode NFKC 后的 exact/prefix/contains，使用稳定大小写
规则，转义 SQL `LIKE` 通配符，并限制输入、页大小和游标。它固定 tenant、可见
lifecycle 和当前版本，先应用 access plan，再逐文档复核；未授权候选不计 total、
不返回 score/highlight，只产生 document-level evidence，不读取正文。

## 索引与删除传播

文档版本提交后只写业务事务和 Outbox。索引 Worker 在事务外抽取/分块/嵌入，
将新 generation 写成不可见，完成计数/hash 校验后原子切换 active generation。
失败保留上一可用 generation。下线、删除、ACL 收紧和 legal hold 变化产生高优先级
tombstone/authorization-refresh 事件；查询端在异步索引追平前仍以实时二次授权
失败关闭。

## 工具与互操作

内建工具只是现有 Application 用例的窄包装，例如读取文档、发起上传、提交审批、
重排同步；不会开放 repository、SQL、任意 HTTP、任意文件路径或 shell。

MCP adapter 维护管理员批准的 server profile、OAuth metadata、允许工具 digest、
egress 规则和 secret reference。A2A adapter 验证 Agent Card、服务身份、技能允许
列表、任务归属和 push URL。远端返回的文字、schema 和 URL 都是数据，不是可信
策略。协议版本冻结、RC 处理和长任务授权边界见
[ADR 0006](decisions/0006-agent-protocol-versioning.md)。

## Console/BFF 边界

浏览器只与同源 BFF 通信。BFF 用服务端 DAL 调用一个管理员允许的 FlowWeft/宿主
source profile；每次 mutation 重验 session、tenant、CSRF、授权和输入 schema。
浏览器不持有 FlowWeft service token、宿主 refresh token、模型 key、OSS secret 或
数据库凭据。

认证事务与 session 默认可落到共享 Redis：只以浏览器随机 ID 的 digest 为键，值使用
AAD 绑定类型/digest 的 AES-256-GCM 信封，Redis 原子执行一次性消费、TTL 和全局容量。
生产 Redis 必须使用 TLS；keyring 第一把写入、旧 key 只用于受限轮换期读取。存储或
解密失败时请求失败关闭，不降级成另一个副本不可见的本地 session。process-local
adapter 仅是显式单副本兼容边界。

生产控制台与 `fileweft-dev` 分离。Dev 继续是验收夹具；Console 可独立容器部署，
也可由宿主反向代理到同源路径。页面必须根据 capability discovery 关闭不可用操作，
但服务端仍是最终授权者。

## 可观测性与诊断

每次 run 关联 HTTP/Task/Outbox trace，使用低基数 run status、operation、provider、
model、tool ID 和 error class。token、cost、latency、queue age、approval age、index
lag、tombstone lag、retrieval candidate/authorized count 是指标。正文、prompt、
用户名、tenant 名称、URL query、secret 和任意 tool payload 默认不记录。

Doctor 区分未安装、错误配置、认证失败、限流、预算耗尽、索引落后、删除积压、
模型不支持工具、MCP 能力漂移和数据库迁移不一致，并给出不含 secret 的修复建议。

## 1.0 冻结边界

进入 API/ABI baseline 的是明确标注 public 的 Maven API、HTTP/OpenAPI、配置属性、
迁移资源、事件 schema、插件/provider 契约和 Console↔BFF contract。实现类、内部
repository、表索引名、CSS 结构和实验性协议 adapter 不自动承诺兼容，但必须在文档
中明确标注。1.0 发布前所有 public/experimental 标记必须完成一次逐项审计。
