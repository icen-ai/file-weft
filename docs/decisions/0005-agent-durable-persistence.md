# ADR 0005：Agent 可恢复持久化与独立数据库命名空间

- 状态：已接受
- 日期：2026-07-15
- 适用范围：FlowWeft 1.0 Agent Runtime
- 关联：[ADR 0001](0001-flowweft-1.0-product-scope.md)

## 为什么现有扩展点不足

`flowweft-agent-runtime` 已经定义原子 create、租约、fencing token、状态 CAS、顺序事件和
outcome-unknown reconciliation，但当前只有端口和测试内存实现。旧 `fileweft-agent` 是
0.0.x 兼容制品，旧 V012/V026 也是兼容数据库形状；把新编排状态写入其中会把新产品语义
错误地绑定到历史 ABI 和迁移。

直接把 `AgentDurableRunState` 交给 Jackson 或 Java serialization 也不成立：状态包含开放的
`AgentContentBlock`、一次性执行回执和授权/审批证据。默认多态反序列化会扩大 gadget 面，
Java serialization 不具备跨版本合同，丢失 dispatch fence、receipt 或预算 reservation 又会
使崩溃恢复重复执行外部工具。

## 决策

1. 新增 `flowweft-agent-persistence-jdbc`，只依赖新的 Agent API/Runtime，不依赖旧
   `fileweft-agent`，也不复用主文件数据库表。
2. 使用独立迁移目录
   `ai/icen/fw/agent/db/migration/{postgres,mysql,kingbase}`、独立 history 表
   `flowweft_agent_schema_history`，首版从 V030 开始并 baseline 到 29。它与 Workflow 的
   独立 history 可以安全使用同一版本号；两个 runner 都不得扫描对方或主 FileWeft 目录。
3. 当前状态使用“可查询列 + 有界二进制 memento”保存；事件、幂等键和外部操作证据使用
   独立表。状态 payload 不是事件源的替代品，事件和操作 ledger 也不能只埋在 blob 中。
4. 编解码格式必须有 magic、格式版本、长度前缀、集合/文本/总 payload 上限、尾随字节
   检查和 SHA-256 digest。禁止 Java serialization、Jackson default typing、类名反射恢复和
   未限定 JSON 多态。
5. 内建 text/binary/tool-call/tool-result/citation block 使用规范 codec。开放内容 block 只有在
   宿主显式注册 `kind + codecVersion` codec 后才能进入持久运行；未知、重复、保留 kind 冒充、
   origin/digest 不一致全部在 create 前失败关闭。codec 只接收有界 canonical bytes，不接收
   JDBC connection。
6. API/Runtime 为持久化增加显式 restore/memento 边界，恢复时重新执行全部构造约束和 digest
   校验。restore 是纯加法 ABI；不开放无校验构造器，也不保存原始 idempotency key、token、
   provider secret 或 cancellation callback。
7. `create` 原子写 run、owner-scoped idempotency 绑定和初始事件。`claimLease` 使用
   `(tenant, run, stateVersion, lease tuple)` CAS，单调增加 fencing token；请求时间早于持久
   `updated_time` 时失败关闭。`commit` 原子写下一状态、连续事件、累计 usage 和 operation
   evidence，外部模型/工具调用永远不在事务内。
8. JDBC commit 抛异常时结果未知，adapter 不伪造 `APPLIED` 或 conflict。协调器必须重读
   exact version/event/operation receipt 后决定成功或进入 reconciliation，不能盲重试工具。
9. Agent Starter 为独立、显式启用模块。未配置 DataSource、迁移或必需 provider 时不注册
   运行服务；Doctor 报告能力缺失，但不得在默认 FileWeft 兼容 runtime 中宣传 Agent。

## 首版数据库职责

- `fw_agent_run`：租户、run、capability、状态、state/event/checkpoint version、deadline、
  lease tuple、当前 operation 摘要、payload digest/payload、创建更新时间。
- `fw_agent_idempotency`：tenant/principal type/principal/capability/key digest 到 run 的唯一绑定，
  不保存原始 key。
- `fw_agent_event`：`(tenant, run, sequence)` 唯一的有界事件 payload 和 digest。
- `fw_agent_operation`：每次模型/工具 attempt 的 phase、logical operation digest、invocation、
  execution-context receipt、dispatch-fence receipt、dispatch time、预算 reservation、outcome 和
  reconciliation evidence。pending 状态被清除后仍保留该历史。

所有表包含 `tenant_id`、`created_time`、`updated_time`；所有查询显式带 tenant。数据库约束
固定状态集合、非负序列、时间顺序、lease 字段全有或全无，以及 receipt/dispatch/reservation
的成组一致性。MySQL identifier 列使用二进制 collation，PostgreSQL/Kingbase 使用等价的大小写
敏感语义。

## 兼容与迁移

这是对 1.0 新制品的纯加法。19 个历史 `fileweft-*` 制品、旧 Agent SPI/ABI、主
`fileweft_schema_history` 和 V001–V034 不改变含义。没有 Agent 表的宿主仍可只使用文件或
Workflow；需要 Agent 的宿主先运行独立 Agent migration job，再显式启用 Starter。

二进制格式升级遵循“新 reader 至少读取上一格式、writer 只写当前格式”。删除 codec 或
extension kind 前必须完成离线可审计迁移；未知格式不得跳过或降级为空状态。

## 测试计划

- codec golden：每个内建 block、所有 pending model/tool phase、审批/授权/消费/fence receipt、
  usage/取消/失败/incident/event round-trip；截断、超长、未知版本/kind、digest 漂移失败关闭；
- store：原子 idempotent create、跨租户隐藏、state/event CAS、lease fencing、时钟回退、
  recoverable 顺序、pending 清除后 operation history仍在；
- 崩溃点：provider 调用前、dispatch fence 已消费后、工具返回前、DB commit outcome unknown；
- H2 仅做快速并发回归；PostgreSQL/MySQL/Kingbase 分别做 fresh/upgrade、真实并发和 migration
  namespace 隔离；
- Java 8/Kotlin 消费者、JDK 25 运行、Boot 2/3 显式启用/默认不注册和 Doctor 脱敏；
- 依次运行聚焦类、模块 test、`fastCheck`，三方言实库与故障恢复放 CNB/nightly/release lane。
