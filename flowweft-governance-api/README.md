# FlowWeft Governance API

`flowweft-governance-api` 是 FlowWeft 1.0 的厂商中立数据治理公共边界。第一阶段定义 retention、legal hold 与安全删除的确定性契约，不包含数据库、对象存储、搜索引擎、HTTP、Spring 或厂商 SDK。

## 不变量

- tenant、当前 principal、purpose、资源 revision/digest、授权 revision/expiry、幂等键和 CAS fence 必须同时绑定；请求参数中的 tenant 或 principal 不是授权证据。
- retention 使用显式的可控 `GovernanceEffectiveClock`，不在值对象内部读取系统时间，便于审计、重放和确定性测试。
- legal hold 优先于 retention。任何 active hold、hold 解析不完整、过期或未知都会失败关闭，不能生成可执行删除计划。
- released hold 必须携带不可变 release evidence；删除不能把“未查到 hold”解释为“hold 已释放”。
- 安全删除顺序固定为：持久化不可见 tombstone → 决策审计 → Outbox tombstone → 清理全部索引 projection/generation → 清理对象 → 最终清理 metadata → 完成审计。
- 每个删除步骤都产生与 plan、step、provider revision、幂等键和执行请求绑定的不可变 receipt。外部 index/object 步骤只有 `verified-absent` 才算成功。
- 调用超时且远端结果不确定时必须使用 `outcome-unknown`，禁止盲目重试；后续只能带新鲜授权进行 reconciliation。
- 每个后续步骤重新要求新鲜 legal-hold resolution。执行中新增 hold 会阻止剩余破坏性步骤，即使旧计划曾经合法。
- dry-run 计划与可执行计划使用不同摘要，且执行端口拒绝 dry-run。
- Capability 中未明确声明的能力按不支持处理；Doctor 只返回值无关的诊断码，不执行真实删除。

公共类型不携带正文、对象 key、SQL、URL、密码、私钥、token、secret 或厂商异常文本。外部引用均为不透明标识和规范 SHA-256 摘要。

本模块不拥有宿主 catalog CRUD，也不提供绕过宿主资源、身份、授权、审计或保留策略的后门。资源引用只用于治理决策和执行绑定；宿主继续负责 catalog 的创建、修改、查询与业务可见性。

## 与历史实现的关系

已有 `fileweft-domain` / `fileweft-application` / `fileweft-spi` retention 类型和行为保持不变。本模块是新增的 1.0 公共边界，补充精确摘要、授权时效、priority/scope/release evidence、dry-run、CAS、四类删除 surface、不可变逐步回执和 reconciliation。后续 runtime 可显式映射历史实现，但不得重解释历史 ABI。

历史边界仍适合 0.0.x 的内建文档删除，但不足以单独作为 1.0 的通用治理 API：它没有完整绑定资源 digest、principal type、purpose 与新鲜授权有效期；legal hold 没有通用 scope、priority 和 release decision evidence；外部 Provider 仅覆盖 index/object，metadata/Outbox 没有同构逐步回执；也没有公共 dry-run、治理 CAS、明确 `outcome-unknown` 与禁止盲重试的 attempt 链。

兼容映射必须是显式 adapter：

- 旧 retention policy 只有在宿主能补齐规范 policy/resource digest、精确版本和时效证据时，才能映射为新 snapshot；否则映射为 `unknown` 并失败关闭。
- 旧 active hold 可映射为 active snapshot；旧 released hold 若缺少 release authorization/decision evidence，不能伪造为已释放，只能令 resolution 为 `unknown`。
- 旧 `VERIFIED_ABSENT` 可映射为外部 surface 的 `verified-absent`；`ACCEPTED_UNVERIFIED` 必须映射为 `outcome-unknown` 并进入 reconciliation，不能直接视为完成。
- 新 API 的 receipt/CAS/authorization digest 不反写或塞入旧表的既有列；需要持久化时使用后续新增 schema 和 codec。

## 测试与迁移边界

第一阶段只交付 API 值对象、确定性规则、Java/Kotlin 消费契约和端口，不包含 runtime、repository、DDL、Flyway migration 或真实 Provider 测试，因此本模块本身没有数据库迁移。后续实现至少需要：

- runtime 的可控时间、hold-priority、dry-run、CAS 冲突、step 顺序、重放与 outcome-unknown/reconciliation 测试；
- PostgreSQL、MySQL、Kingbase 的新增表/索引/tenant 约束与 fresh/upgrade migration 证据；
- 对象、metadata、全部 index generation/cache 与 Outbox tombstone 的故障注入和恢复测试；
- active/unknown/expired hold 在计划、每一步执行和恢复竞态中始终阻止新的破坏性副作用。

在这些 runtime、三方言与真实环境证据完成前，FW10-050 不能标记为整体完成。
