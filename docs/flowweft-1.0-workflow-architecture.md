# FlowWeft 1.0 通用工作流目标架构

本文把 [ADR 0003](decisions/0003-generic-workflow-platform.md) 转成可实现、可测试的
边界。它是目标状态，不代表当前 `0.0.x` 文档审批已经具备这些能力；交付状态只认
[1.0 交付总账](flowweft-1.0-delivery-ledger.md)。

## 依赖与部署形态

```text
workflow Boot 2/3 starter / workflow web
                    |
                    v
             workflow-runtime
              /      |       \
             v       v        v
 workflow-domain  workflow-spi  workflow-api
             |                       |
             +----------> core <-----+

optional adapters:
  workflow-document -> file application ports + workflow application ports
  agent-workflow    -> agent-api + workflow application ports
  BPMN/DMN/CMMN/Open Workflow/SCIM -> workflow-spi/api
```

`workflow-runtime` 在没有文件、检索、Agent、对象存储和 Connector 的 classpath 中也
必须完整启动。`workflow-document` 和 `agent-workflow` 只在对应能力同时存在时装配；
缺失依赖是可诊断的 `NOT_INSTALLED`，不能让整个独立 Workflow 产品启动失败。

## 当前差距基线

| 能力 | 0.0.x 现状 | 1.0 目标 |
| --- | --- | --- |
| subject | 固定 document ID | 任意 type/id/version/digest，文件为可选 adapter |
| definition | submit 时生成 task 列表 | draft/publish/retire 的不可变版本化图 |
| control flow | 全部任务并行、全通过 | 顺序、排他/并行/包容、循环、multi-instance、子流程 |
| rule/case | 无 | 决策 SPI、DMN；ad-hoc stage、CMMN adapter |
| assignee | 单个用户或空 | 用户/组/角色/岗位/部门/管理链/变量/自定义 selector |
| decision | approve/reject/withdraw | quorum、退回、修改、委派、转签、加签、抄送、升级 |
| forms | 无 | JSON Schema 数据、UI schema、字段 ACL、固定版本 |
| collaboration | 单条 comment | 线程、结构化 mention、通知、附件引用、关注者 |
| durability | workflow/task 两张主表 | token、job、timer、subscription、lease、incident、checkpoint |
| version change | 无 | 显式 dry-run migration plan 与逐实例证据 |
| operations | 文档待办/历史 | 通用 inbox、定义/实例/事故/迁移/容量/Doctor |
| Agent | 无通用工具 | 当前用户权限内覆盖全部 Workflow application ports |

## 定义模型

### Definition envelope

每个发布版本包含：

- tenant、definition key、version、name、description、labels；
- input/output JSON Schema、变量分类和默认值；
- node、flow、boundary event、data mapping；
- form、decision、participant selector、service descriptor 的精确 binding；
- SLA/calendar、权限策略、历史保留和可观测性策略；
- canonical digest、作者、审核者、发布时间和前一版本；
- 标准来源、原文 hash、codec 版本和逐元素 conformance report。

Draft 可编辑；Published 永不可变；Retired 禁止新启动但不改变在途实例。复制或修改
产生新版本。删除已引用版本只允许按 retention/legal hold 策略做受控归档。

### Node families

| family | 1.0 核心语义 |
| --- | --- |
| Event | none/message/signal/timer/error/escalation/cancel/terminate |
| Human | user task、manual task、ad-hoc review task |
| Automated | service task、decision task、受控 data mapping |
| Routing | exclusive/parallel/inclusive gateway、event gateway |
| Composition | embedded subprocess、call activity、ad-hoc stage |
| Repetition | bounded loop、sequential/parallel multi-instance |
| Recovery | retry、incident、boundary handler、compensation |

不提供任意 JVM class、SpEL、JavaScript、shell、SQL 或任意 URL 节点。表达式引擎通过
descriptor/capability SPI 注册，输入输出有 schema、复杂度预算、deadline 和确定性
声明；定义发布时必须验证。

## 实例与 token

实例固定保存 definition version 和 subject binding。Runtime 使用持久 token/node
execution 推进图，不把 JVM 调用栈当流程状态。

```text
CREATED -> RUNNING -> COMPLETED
              |  \-> REJECTED
              |  \-> CANCELLED / TERMINATED
              |  \-> FAILED
              +-> SUSPENDED -> RUNNING
              +-> WAITING(task/timer/message/job) -> RUNNING
```

公共状态以稳定字符串 code 输出；未知未来状态由客户端按 `UNKNOWN` 展示，不要求给
public enum 增值。每次 transition 写 sequence、expected version、command ID、actor/
trigger、definition/node、前后状态、reason code、occurred time 和 trace link。

## 人工作业模型

一个 Human node 可以创建一个或多个 work item。完成策略与分配策略正交：

| 维度 | 支持值 |
| --- | --- |
| activation | 一次、顺序 multi-instance、并行 multi-instance、ad-hoc |
| completion | any-one、all、fixed quorum、percentage quorum、expression provider |
| reject | fail-fast、quorum-impossible、collect-all、return target |
| binding | activation snapshot、current membership |
| claim | direct、candidate claim、auto-claim explicitly enabled |
| duplicate user | keep-each、collapse-consecutive、skip-after-proof；默认 keep-each |

每个办理命令都使用 `expectedTaskVersion` 和幂等键。claim、delegate、transfer、add-sign
和 decision 在同一任务锁/CAS 上串行；候选资格、流程权限和 subject 权限在命令执行
时重新验证。完成策略的计数分母、弃权、取消和候选变化必须固定在 activation receipt
中，避免组织变化暗中改变已开始的 quorum。

## 参与者与组织 SPI

### 输入

- 可信 tenant、initiator、subject 与 definition/node version；
- selector type/schema/payload digest；
- 只读流程变量投影，敏感字段按 selector 声明最小化；
- requestedAt、deadline、max levels/candidates 和 purpose。

### 输出

- 有序层级，每层一个或多个 stable principal reference；
- authority/provider instance、directory revision、有效期；
- selector/input/result digest、截断标志必须为 false；
- 缺失/循环/跨租户/超限的稳定错误分类；
- 可选 opaque cohort，配套 current-membership 权威检查。

Runtime 不把 display name、邮箱、职位名或部门名当 principal ID。结果不能 trim、
case-fold 或推测补齐。多数据源宿主必须提供一个确定的聚合 provider；多个无法排序的
provider 让 Starter 启动失败，而不是依赖 Spring `@Primary` 偶然选择。

## 规则、表单与数据

- `DecisionProvider` 输入 schema、模型 key/version 和 canonical data，输出 typed result、
  rule revision、digest 与 trace-safe evidence；调用失败不默认走“同意”分支。
- 内建简单条件只允许受控比较、布尔、集合和空值运算；复杂规则交给 DMN/宿主 SPI。
- 表单 submission 是版本化记录；完成 task 时将 schema/form/version/data digest 与
  decision 原子绑定。后续编辑产生新版本，不能改写当时证据。
- 流程变量区分 PUBLIC/INTERNAL/CONFIDENTIAL/SECRET_REFERENCE，读写由 node 和角色
  allowlist 控制。历史/日志/Agent/通知各自使用最小投影。
- 数据映射失败产生 incident；不能把字段丢失、类型转换失败或未知 expression 当 null
  继续执行。

## 评论、mention、通知

```text
CommentDocument(version)
  blocks[]
    tokens[] = TEXT | MENTION

MENTION = principalType + principalId + displaySnapshot
```

token type 是稳定字符串；TEXT 限长且拒绝非法控制字符；MENTION 不允许自带 HTML、
URL、CSS、事件或头像地址。评论读取先授权实例/task/subject；mention principal 解析
和通知接收是两个独立权限检查。编辑保留 revision，删除写 tombstone，审计不保存
已被权限投影隐藏的正文副本。

通知使用 Outbox 和 `NotificationProvider`，模板只接收已分级的安全字段。email/IM/
webhook endpoint 由管理员 profile/组织 provider 决定，流程变量不能提交任意目标。
失败重试、去重、退信、撤权、quiet hours 和 escalation 均有稳定状态与 Doctor。

## 服务任务与外部副作用

`WorkflowServiceTaskHandler` 通过 descriptor 声明 input/output schema、幂等、timeout、
retry、compensation、data residency 和 egress。Worker 先持久化 attempt/checkpoint，再
在事务外调用。结果未知的非幂等调用进入 reconciliation incident，不自动重放。

Handler 只能收到最小任务上下文和 secret handle，不能收到 repository、DataSource、
完整用户 session 或未授权 subject 内容。文件、Agent 和第三方系统动作都通过这种
受控 application adapter，而不是内嵌 SDK。

## 标准 adapter 分层

| 标准 | 角色 | 1.0 声明方式 |
| --- | --- | --- |
| BPMN 2.0.2 | 业务流程交换/图形 | 可执行子集 + 元素级 conformance manifest |
| DMN 1.5 | 决策 | provider/codec；固定模型版本与 FEEL capability |
| CMMN 1.1 | case/ad-hoc 知识工作 | experimental adapter；核心 ad-hoc 语义单独测试 |
| Open Workflow 1.0.3 | 服务/事件 DSL | experimental codec；不冒充完整人工作业模型 |
| SCIM 2.0 | 身份/组投影 | 组织 provider adapter；授权仍由宿主 Policy 决定 |

Round-trip 测试必须区分语义保持和仅图形保持。未知 extension 保存以便重新导出也不
代表可以执行；发布门禁读取的是 conformance report，而不是 XML/JSON 解析成功。

## Document adapter

Document adapter 注册 subject resolver 和窄动作：读取安全摘要、提交审查、请求修改、
批准发布、拒绝、撤回。知识文件/法律文件选择流程定义时只使用宿主授权提供的分类和
规则结果。流程变量中的 `documentType=LEGAL` 不能反向覆盖真实文档类型。

最终发布动作重新检查：当前 tenant/user、document/version/hash/lifecycle、目录 ACL、
legal hold、definition/task/decision version 和幂等 claim。流程完成事件只代表“可以
尝试发布”，不是绕过这些检查的授权票据。

退回分为两种不可混淆的语义：

- `RETURN_WITHOUT_SUBJECT_CHANGE`：仅把 token 移到定义中允许的已执行节点，文档仍
  绑定同一不可变 version/hash；
- `REQUEST_SUBJECT_REVISION`：实例进入持久的 `WAITING_SUBJECT_REVISION`，adapter
  原子记录 cycle 和旧 subject digest，再经受控文件用例恢复草稿。提交人创建新文档
  版本后，adapter 重新授权、验证目录/metadata/hash，将 binding 更新到新版本并从
  定义指定节点恢复。

补正不能原地修改旧版本，也不能伪造成一次 withdraw/restart 而丢失同一业务流程的
连续证据。旧 DocumentReview 仍保持原有“驳回 → revise → 新提交”语义；只有新
document adapter 使用 revision cycle。

## Agent adapter

Agent tools 与普通 HTTP/Application 调用共享同一 command/query，不维护第二套业务
实现。Tool catalog 根据安装模块和当前 principal capability 动态投影：

- definition：list/get/create-draft/edit/validate/simulate/publish/retire/diff；
- instance：start/get/list/suspend/resume/cancel/terminate/migrate；
- work item：inbox/get/claim/unclaim/complete/reject/return/delegate/transfer/add-sign；
- collaboration：comment/mention/watch/notify/remind；
- operations：incident/retry/reconcile/doctor/metrics-safe-summary。

模型永远不能直接提交 `authorized=true`、principal、tenant、candidate eligibility、
policy revision 或 approval receipt。Authorized invocation 必须由确定性 Policy Gate
构建；任何 arguments、definition、task、subject、tool descriptor 或授权 revision
变化都使旧确认失效。

## 持久化边界

建议逻辑表族如下，最终 DDL 以三方言设计评审为准：

- `fw_flow_definition`、`fw_flow_definition_version`；
- `fw_flow_instance`、`fw_flow_token`、`fw_flow_node_execution`；
- `fw_flow_human_task`、`fw_flow_task_candidate`、`fw_flow_task_decision`；
- `fw_flow_variable`、`fw_flow_form`、`fw_flow_form_version`、`fw_flow_submission`；
- `fw_flow_timer`、`fw_flow_subscription`、`fw_flow_job`、`fw_flow_incident`；
- `fw_flow_comment`、`fw_flow_comment_revision`、`fw_flow_mention`；
- `fw_flow_migration_plan`、`fw_flow_migration_result`、`fw_flow_event`；
- `fw_flow_subject_binding`，供文件 adapter 等保证活动流程唯一性。

大 payload、表单 schema 和定义原文可以使用受控 blob/object reference，但关系库保存
hash、长度、MIME、tenant、版本和所有权。任何 object 不可用都必须表现为 incident，
不能让流程在缺少定义或证据时继续。

Workflow JDBC 模块拥有独立 migration location 与
`flowweft_workflow_schema_history`，迁移版本从全仓 V030+ 注册表分配。完整发行的
migrate-and-exit Job 负责按固定顺序运行各 migration line；workflow-only 安装不应
创建文件或旧 Agent 表。

## 交付切片

1. API/SPI 与 TestKit：定义、subject、组织、规则、表单、任务、通知、service task。
2. Domain：图校验、token、人工任务与确定性状态机。
3. Runtime/Persistence：command、query、worker、timer、incident、三方言 V030+。
4. 标准 adapter 与模板：BPMN/DMN/SCIM、请假/报销/知识/法律模板。
5. Document/Agent adapter：旧审批桥、受权 tools 和完整重放防护。
6. Web/Starter/Console：设计器、表单、任务中心、历史、事故、迁移和 Doctor。
7. 兼容、容量、安全、灾备和发布证据。

每个切片只有在代码、合约、真实三方言/浏览器或所需外部环境证据齐全后才能在交付
总账标为已验证。
