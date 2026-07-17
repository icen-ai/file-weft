# ADR 0003：FlowWeft 通用工作流与人工作业平台

- 状态：已接受
- 日期：2026-07-15
- 决策者：项目维护者
- 适用范围：FlowWeft `1.0.0`
- 关联：[ADR 0001](0001-flowweft-1.0-product-scope.md)、
  [ADR 0002](0002-flowweft-product-rename.md)

## 决策摘要

FlowWeft 1.0 提供可独立安装、可热插拔的通用工作流产品族。企业可以不安装任何
文件模块，只通过发布的 Workflow JAR 运行请假、报销、采购、法务、知识治理或其他
业务流程。文件审批是通用工作流的一个可选 subject/动作适配器，不再是引擎内核。

Agent 在可信当前用户权限内覆盖 Workflow 的全部公开用例，包括查询、建模、校验、
启动、办理、催办、运维、迁移和诊断。Agent 没有超级权限；所有调用在执行瞬间重新
授权，高风险动作还要绑定精确参数、资源版本和一次性人工确认。

## 为什么现有能力不足

当前 `WorkflowInstance`、`WorkflowTask` 和 `DocumentReviewRouteProvider` 是
`0.0.x` 文档审批兼容模型。它只表达：

- 一个工作流固定关联一个 document；
- 提交时一次性创建全部任务；
- 多任务只支持并行全员会签；
- 任一驳回结束、全部通过发布文档；
- 受理人只有单个用户 ID 或未分配；
- 没有流程图、顺序节点、条件、规则、定时器、消息、子流程或运行中迁移；
- 没有部门、岗位、角色、上下级、多领导、代理人或候选组解析；
- 没有表单版本、字段权限、评论线程、安全 `@用户`、通知、SLA 或升级；
- 没有独立于文件的业务 subject、任务中心和通用 HTTP/Application API。

继续向这些已发布构造器、枚举和 V005/V017/V021/V022/V026/V029 表中增加语义，会
破坏 ABI、迁移 checksum 和旧宿主行为，也会把文件生命周期耦合进所有业务流程。
因此采用新增模块、新表和适配器，不原地改造成通用引擎。

## 为什么 1.0 必须交付

审批链路差异不是企业的边缘定制：金额、文件类别、风险、部门、管理层级、岗位、
地区、代理和多领导都会改变路径。若框架只给一个“审批人列表”SPI，每个企业仍要
自行开发任务引擎、组织解析、重试、审计、表单和前端，缺失就会成为产品痛点。

与此同时，组织主数据、法务政策和报销制度属于宿主事实，FlowWeft 不能假装拥有。
正确边界是：FlowWeft 提供完整、可运行的通用语义和安全默认实现；企业通过小而
稳定的 SPI 接入身份、组织、规则、日历、通知和外部作业，而不是重写工作流内核。

## 模块与依赖决策

| 模块 | 职责 | 禁止依赖 |
| --- | --- | --- |
| `flowweft-workflow-api` | Java 友好的定义、实例、任务、命令、查询和事件公共合同 | Spring、JDBC、文件 Domain |
| `flowweft-workflow-spi` | 组织/参与者、规则、日历、表单、通知、签名、服务任务、定义 codec 扩展点 | 厂商 SDK、Spring |
| `flowweft-workflow-domain` | 定义/实例/token/人工作业的确定性状态机 | 数据库、HTTP、外部 SPI 调用 |
| `flowweft-workflow-runtime` | 用例、持久编排、授权、租约、Outbox、计时器、事故与审计 | 文件 repository、厂商 SDK |
| `flowweft-workflow-persistence-jdbc` | 三方言 repository 与 V030+ 迁移 | 业务策略、外部调用 |
| `flowweft-workflow-web-*` | 通用 HTTP DTO/门面和 Boot 2/3 路由 | repository、厂商 SDK |
| `flowweft-workflow-spring-boot2/3-starter` | 可独立启动的装配、配置 metadata 与 Doctor | 文件 Starter 强依赖 |
| `flowweft-workflow-document` | 文档 subject、生命周期、发布/退回动作与旧审批兼容桥 | 重新实现 Workflow Runtime |
| `flowweft-agent-workflow` | Workflow 用例的窄 Agent tools 与 capability discovery | 绕过授权的管理后门 |

BPMN、DMN、CMMN、Open Workflow 和 SCIM 是可选标准协议/codec adapter，不属于
“RustFS、Dify、OSS 三个厂商参考集成”的计数。它们仍必须版本锁定、通过合约测试并
明确支持矩阵，不能只因使用标准名称就宣称完整兼容。

## 中立流程模型

Runtime 执行 FlowWeft 自有、厂商中立且版本化的定义模型。1.0 至少包含：

- start/end、人工、服务、规则、脚本替代的受控表达式、手工和 call activity；
- 顺序流、排他/并行/包容网关、循环、有界顺序/并行 multi-instance；
- timer、message/signal、错误、取消、升级和补偿边界；
- 嵌入/调用子流程，以及法律审查所需的受控 ad-hoc stage；
- 输入/输出映射、变量 schema、敏感级别和 secret reference；
- 固定版本的表单、规则、参与者选择器和外部任务 descriptor；
- 明确的完成、拒绝、终止、取消、挂起、失败和事故语义。

公共扩展 ID 使用有界稳定字符串，避免 1.0 后给 public enum 加值。已发布流程版本
不可变；实例默认钉住定义、表单、规则和 assignment schema 的部署版本。`latest`
绑定只允许显式选择并记录实际解析版本，审计关键流程默认禁止。

## 标准基线与真实声明

截至 2026-07-15，标准基线是：

- BPMN 2.0.2：确定性业务流程的交换和可视化；
- DMN 1.5：金额、风险、文件类型等决策规则；
- CMMN 1.1：法律审查等事件驱动、知识工作和 ad-hoc case；
- Open Workflow Specification 1.0.3：云原生服务/事件编排 DSL；
- JSON Schema 2020-12：变量和表单数据校验；
- SCIM 2.0（RFC 7643/7644）：用户、组、企业用户、经理和部门投影。

FlowWeft 只对通过 conformance manifest 和测试的子集作执行承诺。BPMN XML 可包含
引擎不支持的合法元素，因此导入必须返回逐元素 `SUPPORTED/LOSSY/UNSUPPORTED`，
出现 `UNSUPPORTED` 时不得发布执行。DMN/CMMN/Open Workflow 同理；不允许静默
忽略元素、表达式或厂商扩展。

## 组织、身份与参与者解析

FlowWeft 不保存宿主密码，也不成为 HR/AD 的组织主数据源。它提供以下通用 SPI：

- 人员、组、角色、岗位、组织单元和直接/有效成员查询；
- 单/多上级、组织负责人、成本中心负责人和管理链解析；
- 代理/请假替班、授权期限、地域/法人/租户边界；
- 当前用户是否属于候选集合的权威复核；
- 组织 revision、解析回执、超时、容量和健康检查。

内建 selector 至少覆盖 `USER`、`GROUP`、`ROLE`、`POSITION`、
`ORG_UNIT_MEMBER`、`ORG_UNIT_MANAGER`、`MANAGER_CHAIN`、`INITIATOR`、
`VARIABLE_USER` 和 `CUSTOM`。多领导和矩阵组织返回有序层级及每层候选集合，任务
策略再决定该层是一人、全员、人数 quorum 还是比例 quorum。

每个 selector 明确 `ACTIVATION_SNAPSHOT` 或 `CURRENT_MEMBERSHIP`：前者保留当时
解析结果，后者在查询、claim 和 decision 时用当前目录复核。两种模式都记录
authority、revision、selector digest 和解析时间。目录不可用、结果超限、循环、
跨租户或缺失上级时失败关闭或走定义中显式的升级分支，绝不猜人、截断或自动放行。

SCIM 只提供身份交换，不定义授权。SCIM group、department 或 manager 信息必须先
进入组织 SPI，再由流程 selector 和 `AuthorizationProvider` 解释；不能把组成员关系
直接等同于审批权限。

## 人工作业与企业审批语义

人工作业至少支持：

- direct assignee、candidate users/groups/selectors、claim/unclaim；
- 串行审批、并行会签、或签、人数/比例 quorum、顺序/并行 multi-instance；
- 同意、驳回、请求修改、退回指定可达节点、撤回、取消；
- 委派、转签、前/后/并行加签、抄送、关注和只读观察者；
- 发起人与审批人分离、四眼原则、同人重复节点策略和利益冲突 Policy SPI；
- due/follow-up、工作日历、提醒、催办、超时升级和替补；
- 不可变决定证据、comment、附件引用、表单提交 digest 和可选电子签名/见证 SPI。

自动跳过、自动同意、找不到领导时降级、同人合并多级审批等高风险捷径全部默认
关闭，只有流程定义和租户策略同时显式允许才可执行并写审计。

审批决定绑定 tenant、instance/task、definition/form/rule version、subject/version/
digest、决定动作、规范化输入、操作者、授权 revision、时间和一次性 nonce。办理前
和产生外部副作用前都重新授权；审批不是长期 bearer token。

## 表单、评论与安全 `@用户`

- 表单数据使用 JSON Schema 2020-12；UI schema 独立版本化，避免把前端布局当业务
  校验。服务端始终重新校验类型、范围、条件、字段权限和大小。
- 表单支持字段级 read/write/redact、敏感分类、计算字段、附件引用和固定版本绑定；
  secret 只保存引用，不进入普通变量、历史、日志或 Agent prompt。
- 评论使用版本化结构化 token/AST，不接受或保存任意 HTML。文本与 mention 是不同
  token；mention 只保存稳定 principal ID、类型和显示名快照。
- `@` 搜索通过 tenant/权限约束的 Directory SPI 分页、限流和最小查询长度；隐藏用户
  不得通过建议数量、时序或错误泄漏。
- 服务端验证 mention principal 可见性和 token 结构；前端用 React text node 与固定
  组件渲染，禁止 `dangerouslySetInnerHTML`、不可信 URL 和事件属性。
- mention 只产生通知意图，不授予任务、流程、文件或评论读取权限。通知 Worker 在
  发送时再次检查接收者可见性，撤权后不发送敏感摘要。

## 持久执行、版本与运维

- 所有命令有 tenant-scoped idempotency、乐观版本和稳定回执；Worker 使用租约和
  fence，失租者不得提交。
- 外部组织、规则、通知和 service task 调用在数据库事务外；本地状态和 Outbox 在
  短事务原子提交。
- timer、subscription、job、incident 和补偿全部持久化；崩溃、重启或网络中断后
  从事件/检查点恢复，而不是依赖 JVM 线程等待。
- 运行中迁移必须显式指定 source/target definition、节点映射、变量转换、timer/
  subscription 处理和实例集合。先 dry-run，再逐实例审计；未映射活动节点失败关闭。
- Definition draft/publish/retire、instance suspend/resume/cancel/terminate、incident
  retry/skip/repair 都是独立授权动作，不复用普通 task approve 权限。
- Doctor 检查定义不可达/死锁风险、未解析 selector、timer/job lag、失效租约、事故、
  通知积压、组织 revision 漂移、迁移不一致和标准 adapter 支持状态。

## Agent 集成

`flowweft-agent-workflow` 只包装 Workflow 的公开 Application ports。工具按 capability
发现，至少覆盖定义、实例、任务、评论、表单、迁移和 Doctor；未安装文件 adapter
时仍可完整工作。

- 每次 tool proposal 和 authorized invocation 都绑定 tenant、principal ID/type、
  action、resource、purpose、run/step、tool/schema/arguments digest、authorization
  provider/revision/expiry 和一次性 execution context。
- 查询工具仍服从当前用户数据范围；Agent 看不到用户自己看不到的实例、候选任务、
  评论、组织人选或流程变量。
- 生成流程只能保存为 draft。publish/retire、批量迁移、terminate、delegate/transfer、
  add-sign 和任何 approve/reject 都属于高风险工具，要求精确确认与执行前重新授权。
- Agent 可以帮助解释规则和选择器，但不能自行把模型输出当组织事实、DMN 结果、
  审批决定或权限证明。
- 代用户办理时，UI 必须显示实际 task、subject、关键字段、附件/文档版本、将发生的
  后续路径和外部副作用；自然语言摘要不能替代结构化确认材料。

## 文档审批适配与旧模型兼容

- 旧 `fileweft-domain` workflow、`DocumentReviewRouteProvider`、HTTP、表和迁移保持
  0.0.x 行为，不增加通用语义。
- `flowweft-workflow-document` 把 document/version 作为 generic subject，提供提交、
  修改退回、批准发布、驳回和撤回的受控动作。它只调用现有文件 Application 用例，
  不访问 repository 或对象存储 SDK。
- 新流程可以按知识文件、法律文件、金额、metadata、目录或宿主规则选择不同定义，
  但真实 metadata 与授权仍由受控 subject resolver 提供，不能信任表单自报值。
- “退回前一审批节点”且不修改文件时只移动流程 token；“退回提交人补材料”则进入
  明确的 subject revision cycle：保存旧 document version/hash 审批证据，使用新的
  受控文件 Application 用例把 `PENDING_REVIEW` 恢复为 `DRAFT`，创建不可变新版本，
  再次提交后把同一通用实例绑定到新 version/hash 并从定义指定节点继续。整个周期
  记录 cycle number、前后 subject digest、操作者、原因和审计，不能在原版本上覆盖
  内容，也不能只退流程而让文件保持不可编辑。
- 旧活动审批不会自动迁移。切换前必须停旧写入口、处理或明确映射在途实例，并用
  新的 document-workflow binding 保证同一文档不会在两套引擎中同时活动。
- 已完成旧历史继续可读；统一历史视图标注 `LEGACY_DOCUMENT_REVIEW` 或
  `GENERIC_WORKFLOW` 来源，不伪造不存在的节点、表单或参与者证据。

## 模板决策

1.0 提供可复制、不可直接携带真实人员的版本化模板：

- 通用单人、串行多级、并行会签、quorum 审批；
- 请假：发起人管理链、可选 HR 抄送和代理人分支；
- 报销：金额/成本中心规则、直属领导、财务与高额复核、职责分离；
- 知识文件：内容 owner、知识管理员、可选安全/合规复核；
- 法律文件：业务 owner、法务、按风险/金额动态专家、多领导会签和 ad-hoc 补充审查。

模板是安全起点，不是企业政策。导入后必须由管理员绑定本组织 selector、规则、表单
和 SLA，完成 lint/simulation 后发布。示例不能写死“部门经理字段”“某个用户 ID”或
自动同意缺失节点。

## 数据库迁移策略

- V001–V029 永不改写。通用 Workflow 使用 V030+ 新表，不能复用旧
  `fw_workflow_instance/fw_workflow_task` 表表达新状态。
- 新表至少独立保存 definition/version、instance、token/node execution、human task/
  candidate/decision、variable、form/version/submission、timer/subscription/job/lease、
  incident、comment/mention、migration 和不可变 event/receipt。
- 所有业务表包含 tenant、id、created/updated time；不可变事件和决定使用 occurred
  time。subject 采用 type/id/version/digest，不建文件外键。
- PostgreSQL、MySQL、KingbaseES 使用相同逻辑约束和锁序。fresh install、0.0.1/
  0.0.2/0.0.3 upgrade、workflow-only install 都是发布门禁。
- 通用模块单独安装时只扫描它拥有的 migration location，并使用独立
  `flowweft_workflow_schema_history`；版本仍从仓库 V030+ 分配。完整 FlowWeft 的
  migrate-and-exit Job 按固定顺序运行 legacy、Workflow、Agent 等 migration line。
  同一 migration 只有一个资源所有者，不能复制脚本或让两个 history 记录同名但
  不同字节的版本。

## 测试与发布门禁

- Workflow Patterns：control-flow、data、resource、exception 和 event log 代表用例；
- 定义：schema、lint、不可达、死锁、网关、循环上限、版本、codec loss 报告；
- 人工任务：串/并/或/quorum、退回、加签、转签、代理、SLA、同人/职责分离竞态；
- 组织：多上级、矩阵组织、嵌套组循环、撤权、目录 revision、超限和不可用；
- 持久化：崩溃恢复、租约、重复消息、timer、incident、补偿、迁移和三方言实库；
- 安全：跨租户/IDOR、候选枚举、过期授权、审批重放、表单注入、XSS/mention 泄漏；
- Agent：跨主体重放、权限撤销、精确确认、流程 draft/publish、代办和文件适配边界；
- UI：设计/发布、表单预览、任务中心、键盘/读屏、双语、安全 `@`、运维修复 E2E；
- 兼容：旧 Java/Kotlin/Boot 2/3 消费者、V001–V029 checksum、旧 HTTP 和历史读取；
- 容量：大候选组、深链、宽并行、timer/job backlog、长运行实例和历史归档基准。

任何只在内存、单节点、单方言、mock 组织或截图中通过的结果都不是 1.0 完成证据。

## 主要参考基线

- [OMG BPMN 2.0.2](https://www.omg.org/spec/BPMN/2.0.2/About-BPMN)
- [OMG DMN 1.5](https://www.omg.org/spec/DMN/1.5/About-DMN)
- [OMG CMMN 1.1](https://www.omg.org/spec/CMMN/1.1/About-CMMN)
- [Open Workflow Specification 1.0.3](https://serverlessworkflow.io/)
- [Workflow Patterns](https://www.workflowpatterns.com/)
- [JSON Schema 2020-12](https://json-schema.org/draft/2020-12)
- [SCIM Core Schema（RFC 7643）](https://www.rfc-editor.org/rfc/rfc7643.html)
- [SCIM Protocol（RFC 7644）](https://www.rfc-editor.org/rfc/rfc7644.html)
- [Camunda 8 User Tasks](https://docs.camunda.io/docs/components/modeler/bpmn/user-tasks/)
- [Camunda 8 Process Instance Migration](https://docs.camunda.io/docs/components/concepts/process-instance-migration/)
- [OWASP XSS Prevention](https://cheatsheetseries.owasp.org/cheatsheets/Cross_Site_Scripting_Prevention_Cheat_Sheet.html)

## 后果

FlowWeft 从“带简单文件审批的文件基础设施”扩展为可独立部署的流程平台，同时文件、
Agent 和其他宿主共享同一个可审计工作流内核。代价是 1.0 增加了新的公共 API、
持久状态机、三方言迁移、标准适配和完整 UI 门禁；不得用一个更大的
`DocumentReviewRouteProvider`、第三方 BPM 引擎硬依赖或前端假流程来缩减该范围。
