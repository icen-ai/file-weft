# FileWeft 实现对照与发布门槛

本文记录仓库相对 `.ai/` 实施手册的可验证范围，避免将“基础能力已验收”和“已具备完整开源发布治理”混为一谈。

## `.ai` 十阶段基础能力

| 阶段 | 已交付能力 | 验证方式 |
| --- | --- | --- |
| Core | 标识、上下文、结果、错误与 Outbox 模型，不依赖 Spring 或厂商 SDK | Core 单元测试、架构依赖检查 |
| SPI | 身份、授权、租户、存储、连接器、交付、任务、诊断、Agent、审批路由契约 | SPI 模型与合约测试 |
| Domain | 文件、文档版本、生命周期、工作流、审计与操作日志领域规则 | Domain 单元测试 |
| Application | 上传、下载、审批、并行会签、发布、下线/归档撤回、同步、Doctor、任务与 Agent 用例 | Application 单元测试 |
| Persistence | PostgreSQL/Flyway、租户条件、Outbox 租约、任务、审计、交付及撤回状态 | PostgreSQL 集成测试 |
| Starter | Boot 2 / Boot 3 自动装配、安全默认实现与客户替换点 | Starter 上下文测试 |
| Adapter | 本地存储、S3 兼容存储、连接器弹性包装、Micrometer 指标 | Adapter 与 TestKit 合约测试 |
| Doctor | 权限、生命周期、存储、连接器与 Agent 的诊断及持久化历史 | 单元与 Dev 验收测试 |
| Agent | 可恢复任务、建议确认、审计和操作记录 | 单元与 Dev 验收测试 |
| Hardening | 多租户隔离、Outbox、重试、熔断、限流、Trace、完整性校验、断点续传、下游撤回 | 全仓检查与 Compose 验收 |

目前的关键验收命令：

```powershell
.\gradlew.bat check --no-daemon

$env:FILEWEFT_RUN_POSTGRES_TESTS='true'
.\gradlew.bat :fileweft-persistence:test --no-daemon

$env:FILEWEFT_RUN_DEV_E2E='true'
.\gradlew.bat :fileweft-dev:test --tests 'com.fileweft.dev.e2e.DevAcceptanceIntegrationTest' --no-daemon
```

Dev 编排验证真实 PostgreSQL、RustFS、S3 预签名下载和独立下游平台；覆盖双租户、角色授权、上传、版本、单人审批、双人会签、多下游投递、失败重试、下线撤回、Doctor、Agent 与审计。

## 当前明确不包含的厂商实现

手册列出 OSS、CenterFile、Dify、ESE、AppBuilder 等适配方向。这些不是安全的“空壳默认实现”：它们各自需要稳定的厂商 API 契约、凭据模型、超时/重试策略和真实环境合约测试。因此仓库当前提供相应的通用 SPI 与 S3/本地基线，宿主可以按需实现或以插件形式贡献适配器；不得把厂商 SDK 泄漏到 Core、Domain 或 SPI。

若要新增某一厂商适配器，应先确定：目标产品及版本、认证方式、租户映射、幂等键语义、删除/撤回语义、超时与重试上限，以及可运行的测试环境。随后以独立 `adapter-*` 或插件模块交付并运行对应 TestKit 合约。

## 开源发布仍需项目所有者决策

基础能力验收通过不等于可以自行声明“最终开源发布完成”。以下事项需要仓库所有者明确决定或提供外部信息：

- 开源许可证：许可证决定再分发、专利、商标和商业集成边界，不能由实现代理擅自选择。
- 安全披露渠道与维护承诺：需要一个可用的私密漏洞报告入口、响应时限和受支持版本范围。
- 发布目标：当前远端是 CNB；CI、制品签名、依赖漏洞扫描、SBOM、发布仓库和版本策略应按实际发布平台配置，而不是假设 GitHub Actions。
- 生产容量与恢复目标：需要给出 SLO、数据保留、RPO/RTO、单机/多 Worker 并发量和目标对象存储/下游平台，才能完成压力、故障注入和灾备演练验收。

在这些决策明确前，FileWeft 已具备可运行、可扩展、可验证的基础设施基线；但不会把未验证的厂商兼容性或未定义的发布治理误报为完成。
