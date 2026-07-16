# FlowWeft 1.0 交付总账

本文件是 `1.0.0` 的唯一完成清单。状态只能是“未开始、进行中、已验证”。只有
代码、测试、真实环境和发布证据全部存在时才能标为“已验证”。当前稳定版仍是
`0.0.3`；本表不提前宣称 1.0 能力已经可用。

## 发布阻断项

| ID | 当前状态 | 交付内容 | 关闭证据 |
| --- | --- | --- | --- |
| FW10-000 | 进行中 | FlowWeft 品牌、1.0 `flowweft-*` 规范坐标与旧 FileWeft 兼容命名迁移 | 名称分类清单；旧/new Maven 与 Gradle 消费；配置/HTTP/DB/event golden baseline；SBOM/签名/镜像/站点同一发布身份 |
| FW10-001 | 进行中 | 1.0 ADR、目标架构、威胁模型和 public/experimental 清单 | 决策经仓库评审；每项公共面都有 owner、兼容与测试策略 |
| FW10-010 | 进行中 | 完成续传资产一次性认领并安全创建文档/新增版本 | owner/tenant/purpose/expiry/单次消费/并发锁/幂等测试；Boot 2/3 与浏览器 E2E |
| FW10-011 | 进行中 | 通用 migrate-and-exit CLI/Job | 三方言全新/升级/失败退出码/凭据最小化/容器 Job 验收 |
| FW10-012 | 进行中 | 宿主目录只读 facade 与受控文档移动 | 动态 ACL、隐藏节点、循环/坏树、竞态、分页、Boot 2/3 合约；无目录 CRUD |
| FW10-013 | 进行中 | 补齐 TestKit | Logger/Metrics/Gauges/TraceScope、插件冲突、Metadata、Agent/检索 Provider 合约由外部样例宿主运行；已新增 provider-neutral Reliability 与 Governance TestKit，覆盖确定性时钟/ID、单库与多组件拓扑、retention/legal-hold 失败关闭、mutation/精确对账探针、崩溃后最多一次外部变更、tenant/CAS/围栏、持久状态重载及 canonical digest 重建 |
| FW10-014 | 进行中 | 生产 Doctor 和可观测性 | DB/history、Worker lease、队列/索引/tombstone lag、容量/readiness；真实 OTel Collector 三信号脱敏证据 |
| FW10-020 | 进行中 | `flowweft-retrieval-api` 与内建安全文件名搜索 | Java 互操作、tenant/ACL 二次授权、分页/上限/模糊输入、无正文泄漏和 Provider 缺失降级测试 |
| FW10-021 | 进行中 | 内容抽取、索引、全文、向量、混合、重排 SPI | 契约套件覆盖血缘、版本、generation 切换、失败保留、重建、ACL 更新和删除传播 |
| FW10-022 | 进行中 | `flowweft-agent-api` | 模型/消息/工具/审批/预算/取消/引用/评测契约，Java 8 友好及源码/二进制 baseline |
| FW10-023 | 进行中 | `flowweft-agent-runtime` 持久编排 | 崩溃恢复、租约、幂等、超时、取消、预算、并发确认、重试分类、无事务外泄 |
| FW10-024 | 进行中 | Agent 权限与内容安全 | 跨租户、ACL 撤销、间接 prompt injection、tool poisoning、secret exfiltration、越权工具、引用伪造红队套件 |
| FW10-025 | 进行中 | MCP 与 A2A 可选 adapter | 已建立复用 canonical 远程边界的 MCP 目录、能力 digest、调度证据绑定和无敏感值 Doctor；继续补齐当前规范操作、OAuth/TLS、SSRF/重定向/私网阻断、凭据隔离、取消和协议兼容矩阵 |
| FW10-026 | 进行中 | Agent 评测与运行诊断 | 固定回归集、检索/引用/工具正确性、安全拒绝、成本/延迟阈值；Provider 不可用与漂移 Doctor |
| FW10-027 | 进行中 | `flowweft-workflow-api/spi`、中立定义模型与标准支持矩阵 | Java 8 互操作；定义 schema/lint/version/digest；BPMN 2.0.2、DMN 1.5、CMMN 1.1、Open Workflow 1.0.3 元素级 conformance report；未知语义失败关闭 |
| FW10-028 | 进行中 | 独立 Workflow Domain/Runtime/JDBC 与持久执行 | 无文件模块启动；token/job/timer/subscription/incident/lease/compensation/迁移；幂等和崩溃恢复；三方言 V030+ fresh/upgrade/workflow-only 实库 |
| FW10-029 | 进行中 | 组织/参与者 SPI 与完整人工作业语义 | 用户/组/角色/岗位/部门/多上级/管理链/代理；串并行、或签、quorum、退回、加/转/委派、职责分离、SLA；撤权/循环/超限/目录不可用测试 |
| FW10-030 | 进行中 | RustFS 参考集成 | S3 adapter 的真实 RustFS 分片/续传/Range/ETag/预签名/幂等删除/Doctor CNB lane |
| FW10-031 | 进行中 | Dify 知识库参考集成 | 官方 API 版本矩阵；同步、状态、更新、删除、幂等、限流、错误脱敏；安全检索不满足时失败关闭 |
| FW10-032 | 进行中 | 阿里云 OSS 参考集成 | 真实 OSS 分片/续传/Range/ETag/预签名/幂等删除/错误分类/Doctor 隔离凭据 lane |
| FW10-033 | 进行中 | Workflow 表单、评论、安全 `@`、通知、日历与签名边界 | 已补电子签名/见证的同步 ABI 与异步 capability、接收/挂起/终态、原请求对账、取消和无敏感值 Doctor SPI；继续收口 JSON Schema 2020-12/字段 ACL/不可变 submission、结构化 token 无 XSS、用户枚举防护及通知撤权/去重/退信 |
| FW10-034 | 进行中 | Document Workflow adapter 与流程模板 | 旧 DocumentReview ABI/表/HTTP 不变；活动流唯一；在途补正/revision cycle；请假、报销、知识文件、法律文件模板 lint/simulation/端到端证据 |
| FW10-035 | 进行中 | Workflow Agent tools | 在当前 principal 权限内覆盖 definition/instance/task/collaboration/operations；跨主体重放、撤权、参数/版本漂移、一次性确认和无文件部署测试 |
| FW10-036 | 进行中 | 独立 Workflow Web/Boot 2/3、任务中心与运维面 | 已接 Console 固定 Workflow Web v1 路由的定义、任务、实例、历史、评论与表单只读面，含严格 envelope/对象/主体/游标绑定、隐藏态失败关闭及有界响应；继续完成 workflow-only 启动、设计/发布/办理/事故/迁移/Doctor mutation、权限和浏览器 E2E |
| FW10-040 | 进行中 | 独立 `flowweft-console` 基础与设计系统 | Next.js/BFF、双语、响应式、主题、WCAG 2.2 AA 基础、组件/视觉回归、独立镜像 |
| FW10-041 | 进行中 | Source profile、OIDC 与宿主账号登录 | PKCE、AES-GCM 共享 Redis session/密钥轮换/原子 TTL 与容量、单副本降级、临时密码交换、无 localStorage token、CSRF/CSP、SSRF allowlist、tenant alias 安全测试；仍需真实 IdP/宿主和 CNB Redis 故障演练证据 |
| FW10-042 | 进行中 | 文档工作台 | 已接服务端会话派生权限下的目录/生命周期筛选、分页、文档详情与严格校验版本链，隐藏与不存在保持不可区分；继续补安全下载、续传上传、metadata、受控移动、生命周期 mutation 与全流程 Playwright |
| FW10-043 | 进行中 | 审批、同步、Doctor、审计与运维 UI | 系统 Doctor 与通用 Workflow 只读工作台已接实时脱敏 DAL；待办、实例、历史、评论、表单摘要和定义诊断均由当前会话服务端授权投影，且不回退旧审批接口；仍需带 CSRF/幂等/If-Match 的 mutation、同步/失败重排/审计和安全导出 E2E |
| FW10-044 | 进行中 | Agent 对话、检索证据、工具确认、配置和评测 UI | 已建立 framework-neutral Web/Application API、耐久运行时及 Boot 2/3 HTTP Starter：25 路由后端编排、精确授权 scope、稳定 digest 幂等、ETag/游标/SSE 恢复、权限过滤引用、一次性确认、secret-reference 配置、Doctor/评测，以及 start/cancel/evaluation 未知结果只读对账；Console 已接同源服务端只读工作台，使用固定 Agent Web v1 路由、严格 envelope/游标/对象绑定、当前授权引用最小投影和双语隐藏/能力不可用状态；继续补 persistence、带 CSRF/幂等/If-Match 的 mutation、SSE 断线恢复和安全 E2E |
| FW10-050 | 进行中 | retention、legal hold 与安全删除 | 已建立公开治理契约、canonical durable rehydration、provider-neutral CAS/outbox 七阶段运行时、V041 JDBC 持久化及可复用 Governance TestKit，含 legal-hold 优先、fresh authorization、tenant-bound ID digest、原子状态/outbox、未知结果原操作对账、并发 CAS、围栏 worker、持久重载、Doctor 与指标；继续补适配器、对象/索引删除一致性、审计和 PostgreSQL/MySQL/Kingbase 实库测试 |
| FW10-051 | 进行中 | 容量、分区与背压 | 已建立 provider-neutral 容量 API、运行时及三方言 JDBC Provider：严格层级策略快照、标准单位/水位、原子准入、稳定 digest 幂等/CAS、围栏租约、两阶段 intent/canonical outcome/outbox、显式节流/拒绝/容量降级、事务外调用、未知结果只读对账及 Doctor/指标；继续实现分区适配、实库证据、公开基准环境和上限，以及队列/索引/上传/Agent 背压与故障测试 |
| FW10-052 | 进行中 | SLO、备份恢复、RPO/RTO | 已建立 provider-neutral 可靠性 API、耐久运行时、TestKit 及 V040 JDBC 持久化：精确 ppm SLO/error budget/burn rate、单或多组件 consistent-cut 不可变清单、短授权与最长七天异步期限、intent/outbox/CAS/围栏 worker、clean-target 恢复、真实故障时钟 RPO/RTO、原操作只读对账、缺数失败关闭告警、canonical durable rehydration，以及三方言 tenant/CAS/fencing/atomic outbox 仓储；继续实现 Provider、仪表盘并取得 PostgreSQL/MySQL/Kingbase 与数据库+对象+索引真实恢复演练证据 |
| FW10-060 | 进行中 | 1.0 API/ABI 与配置冻结 | 所有 public 制品/HTTP/event/config baseline，Java/Kotlin/Boot 2/3 消费者兼容门禁 |
| FW10-061 | 未开始 | 全升级矩阵 | `0.0.1`/`0.0.2`/`0.0.3` 到 1.0 的三方言、JDK、Boot 代际升级与回滚边界证据 |
| FW10-062 | 进行中 | 安全与供应链收口 | 威胁模型、高危清零、OSV 策略、完整 SBOM、签名、SLSA provenance、可复现构建 |
| FW10-063 | 进行中 | 文档、支持与弃用合同 | 所有命令/示例可执行；集成/运维/灾备/安全/Agent/UI 文档；明确版本和日期策略 |
| FW10-064 | 未开始 | 1.0 发布 | 精确 tag/SHA 的全部 CNB lane 成功；所有坐标、镜像、签名/provenance 匿名冷读并记录发布说明 |

## 明确不做

- 不拥有宿主目录 CRUD、用户密码库、HR/AD 组织主数据和企业具体审批政策；但必须
  提供可运行的通用工作流引擎、组织/参与者/规则 SPI、安全默认语义与可配置模板，
  不能把引擎缺口转嫁给企业重写。
- 不交付 ESE、AppBuilder 或其他经常变化的厂商“官方适配器”。
- 不内置某个向量库、全文引擎、模型厂商或 Agent 框架为不可替换依赖。
- 不允许 Agent 直接操作数据库、对象存储 SDK、repository、任意 HTTP、shell 或
  本地文件系统。
- 不把 mock、页面截图、旧 SHA、部分绿灯或另一厂商环境当成真实验收证据。

## 每项通用完成定义

1. 架构边界与 Java 友好公共 API 通过静态门禁。
2. 聚焦测试、模块测试和 `fastCheck` 通过；只运行变更要求的外部 lane。
3. 任何数据库/厂商/UI/发布变化取得对应的独立真实环境证据。
4. Doctor、日志、metric、trace、错误分类和修复建议已实现且脱敏。
5. 文档、示例、配置 metadata、升级和安全说明与实现同一提交。
6. 推送后只以匹配完整 SHA、event、sourceRef、targetRef 的 CNB 结果闭环。
