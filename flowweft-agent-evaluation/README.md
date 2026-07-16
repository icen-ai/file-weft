# FlowWeft Agent Evaluation

`flowweft-agent-evaluation` 是 FW10-026 第一阶段的固定回归集与确定性评分实现。它复用
`flowweft-agent-api` 已发布的 suite、case、provider snapshot 和 observation 契约，不复制、
不改写现有 Agent ABI，也不依赖任何模型厂商或网络 SDK。

## 边界

- 受信 Agent runtime 负责读取 fixture、调用配置好的 Provider、执行当前用户权限检查并形成 observation。
- 本模块只接收 `fixtureId`、输入/输出 SHA-256、结构化 observation 和租户/主体绑定。
- prompt、生成正文、检索正文、工具参数、Provider endpoint、credential 和异常文本不得进入本模块。
- 评分是诊断证据，不是授权决策；它不会调用工具、发布内容、修改业务数据，也没有 superuser 语义。

## 已实现

- `AgentEvaluationSuite` 的 `suiteId + version + suiteDigest` 精确注册；相同版本禁止重绑不同 digest。
- Provider 的 id、实现版本、能力集合、descriptor digest 和完整 snapshot digest 固定。
- Evaluator 的 id、实现版本、支持标准、配置 digest 和 descriptor binding 固定。
- 检索、引用、工具决策、安全拒绝、成本、延迟六类二值断言。
- 缺 observation、未授权检索证据、跨租户引用、过期授权/无效审批绑定均失败关闭。
- 整数 basis points（0..10000）与规范化顺序生成确定性 case/report digest。
- `InMemoryAgentEvaluationRunner`、数据集/Provider/Evaluator 内存注册表。
- Provider 不可用、配置漂移、Provider snapshot 过期、Evaluator 不支持以及回归失败的脱敏 Doctor snapshot。

## 最小装配

```kotlin
val evaluator = DeterministicAgentEvaluationEvaluator()
val runner = InMemoryAgentEvaluationRunner(
    InMemoryAgentEvaluationDatasetRegistry(listOf(suite)),
    InMemoryAgentEvaluationProviderInventory(listOf(providerSnapshot)),
    InMemoryAgentEvaluationEvaluatorRegistry(listOf(evaluator)),
)

val report = runner.run(
    AgentEvaluationRegressionRun(
        AgentEvaluationDatasetReference.from(suite),
        providerSnapshot,
        AgentEvaluationEvaluatorReference.from(evaluator.descriptor()),
        AgentEvaluationSubjectBinding.from(observationContext),
        evidenceBatches,
        evaluatedAt,
    ),
)
```

生产宿主应从现有 `flowweft-agent-runtime` 的受信执行边界形成
`AgentEvaluationEvidenceBatch`。不要为了构造 batch 把 fixture 或输出正文写入数据库；持久化
`evidenceDigest`、`scoreDigest`、`reportDigest` 和稳定 Doctor code 即可。Provider inventory 的
`current` 也是本地配置快照读取，不能借 Doctor/runner 隐式发起远程探测。
