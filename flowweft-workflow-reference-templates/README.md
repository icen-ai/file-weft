# FlowWeft Workflow Reference Templates

这是 FlowWeft 1.0 的参考模板第一切片。模块只依赖
`flowweft-workflow-api`，不包含 Spring、数据库、厂商 SDK 或宿主用户。

提供四个固定版本、始终为 `DRAFT` 的工厂：

- 请假：发起人一/二级管理链与条件二级审批；
- 报销：直属负责人、金额规则、成本中心、高额复核和财务 quorum；
- 知识文件：内容 owner、知识管理员、可选安全合规复核和补正回环；
- 法律文件：业务 owner、动态专家、法务 quorum、多领导全员会签和加签。

调用方必须绑定精确的 subject、组织、表单/规则、predicate、工作日历和补正服务
profile。profile 只保存注册表 ID、版本和 digest，不保存 URL、凭据或真实人员。

`WorkflowReferenceTemplateLinter` 检查图可达性、终态路径、循环预算、selector/form/
subject/profile 绑定、危险默认和版本/digest。工厂在返回前执行同一 lint。

`WorkflowTemplateSimulationPlanner` 只生成确定性执行计划。现有 Domain 引擎在人工
任务、predicate 和补正服务处要求可信外部证据，因此本模块不会制造回执来声称测试
通过。能力缺失返回 `UNSUPPORTED`；能力声明齐全也只返回 `PLAN_READY`，真实运行
仍需 Runtime、Provider 和授权门禁。

当前 API 的明确表达缺口是：逐循环持久预算、SLA 调度和 subject revision 创建仍是
绑定元数据/运行时能力，不是 Definition 内建语义。部署门禁必须验证这些能力后才能
发布模板实例。
