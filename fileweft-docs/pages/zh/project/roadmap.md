---
route: "project/roadmap"
group: "project"
order: 5
locale: "zh"
nav: "开发待办"
title: "用证据推进的开发路线图"
lead: "这份待办是后续 FileWeft 开发的交接契约。只有仓库或指定真实环境中存在对应证据，版本才能从计划转为完成；仅有实现、局部绿测或书面意图，都不能单独作为完成依据。"
format: "html"
---

<h2 data-step="01">如何阅读路线图</h2>
<table class="comparison-table"><thead><tr><th>状态</th><th>含义</th></tr></thead><tbody><tr><td>计划中</td><td>范围已确定，但禁止宣称已经完成。</td></tr><tr><td>进行中</td><td>已有部分实现或证据；任何未验收项都继续阻塞发布。</td></tr><tr><td>已完成</td><td>所有验收证据都能从干净环境复现，并满足完成边界。​</td></tr></tbody></table><aside class="callout warning" data-mark="!"><div><strong>证据只对被验证版本有效</strong><p>不能把另一种数据库、另一个 Boot 代际、厂商模拟器或旧版本的成功结果挪用为本版本的完成声明。证据不能复现时，该项仍是待办。</p></div></aside>

<h2 data-step="02">0.0.2 · 收口当前开发线</h2>
<table class="comparison-table"><thead><tr><th>交付内容</th><th>验收证据</th><th>禁止冒充完成的边界</th></tr></thead><tbody><tr><td>基于运行时闭包的 SBOM；SNAPSHOT 发布校验器；正式的断点续传、目录与 Agent HTTP 资源。</td><td>SBOM 校验器从所有发布模块的真实 runtime closure 推导组件，并拒绝测试、编译器和构建工具泄漏；发布校验器证明 SNAPSHOT/正式版本规则；Boot 2 与 Boot 3 的契约、Context、MVC 测试及浏览器 E2E 覆盖三组 HTTP 的租户、权限、游标、错误和脱敏行为。</td><td>SBOM 仍只是依赖转储、校验器会接受错误发布身份，或任一资源仅存在于内部服务、dev 路由或单一 Boot 代际时，都不能发布 0.0.2。​</td></tr></tbody></table><aside class="callout" data-mark="进行中"><div><strong>当前交接状态</strong><p>运行时闭包 SBOM 与基于 Maven metadata 的时间戳 SNAPSHOT 仓库校验已经实现并通过本地验证。仍待完成：正式版/SNAPSHOT fixture 及损坏、重复、XXE、路径穿越、混合构建负例；仓库精确库存、artifact 级 metadata/checksum 与危险 JAR entry 校验；正式断点续传、目录、Agent HTTP 资源及其双 Boot 测试、浏览器 E2E；最后再跑干净发布门禁。</p></div></aside>

<h2 data-step="03">0.1.0 · 生产供应链</h2>
<table class="comparison-table"><thead><tr><th>交付内容</th><th>验收证据</th><th>禁止冒充完成的边界</th></tr></thead><tbody><tr><td>使用已确认官方语法的 CNB CI；OSV 漏洞策略；制品签名与 provenance；远端冷缓存消费；从源码到仓库唯一发布身份。</td><td>干净 CNB Runner 使用当前 CNB 官方文档确认过的语法跑完全门禁；固定版本的 OSV 扫描执行明确的严重度和例外策略；消费者可验证签名与 provenance；空 Gradle 缓存的新机器只从 CNB 解析并运行正式制品；Tag、Commit、POM、Module Metadata、SBOM 与 provenance 的版本和源码修订完全一致。</td><td>本地 publish、开发者热缓存、未签名 checksum、未经官方确认的示例 YAML 或仓库页面截图，都不能用于宣称生产发布链路完成。​</td></tr></tbody></table>

<h2 data-step="04">0.2.0 · 数据库与宿主集成</h2>
<table class="comparison-table"><thead><tr><th>交付内容</th><th>验收证据</th><th>禁止冒充完成的边界</th></tr></thead><tbody><tr><td>MySQL 8 支持；扩大可复用 TestKit 的宿主 SPI 覆盖；结构化日志与 OpenTelemetry 集成。</td><td>真实 MySQL 8 和 PostgreSQL 都通过全新安装、受支持版本升级、并发、租户隔离及可安全回滚的迁移套件；外部样例宿主运行身份、授权、租户、目录、工作流、存储、连接器与 Agent 契约套件；OpenTelemetry Collector 测试断言 Trace/Metric/Log 关联、脱敏和有界基数。</td><td>H2 兼容、SQL 解析、Mock、单一 happy path 适配器测试或肉眼查看日志，都不能证明 MySQL、SPI 或可观测性支持。​</td></tr></tbody></table>

<h2 data-step="05">0.3.0 · 规模化运营</h2>
<table class="comparison-table"><thead><tr><th>交付内容</th><th>验收证据</th><th>禁止冒充完成的边界</th></tr></thead><tbody><tr><td>保留与 legal hold 规则；分区生命周期；容量边界；明确 RPO/RTO；备份恢复流程；可度量 SLO。</td><td>可控时间测试证明保留、冻结与安全删除；分区创建、轮转、归档和删除测试保持租户及审计不变量；可重复压测报告公开经过验证的文档、版本、对象和队列上限；故障演练将数据库与对象存储恢复到干净环境，并达到声明的 RPO/RTO；仪表盘和告警按约定可用性、延迟与积压 SLO 实际触发。</td><td>配置示例、未演练 Runbook、未披露瓶颈的合成吞吐量，或从未恢复过的备份，都不能描述为生产规模就绪。​</td></tr></tbody></table>

<h2 data-step="06">0.4.0 · 官方厂商适配器</h2>
<table class="comparison-table"><thead><tr><th>交付内容</th><th>验收证据</th><th>禁止冒充完成的边界</th></tr></thead><tbody><tr><td>面向 OSS、Dify、ESE 与 AppBuilder 官方服务的持续维护适配器。</td><td>隔离凭据的合约环境验证文档所列厂商 API 版本、认证、超时、重试分类、幂等交付、安全重复撤回、分页或上传限制、错误脱敏、健康检查和故障恢复；每个适配器都记录兼容范围与维护责任。</td><td>通用 S3 实现、WireMock 响应、逆向 payload 或 dev 模拟器不能证明官方厂商支持。没有可重复真实服务证据的适配器必须保持 experimental，不能宣传为受支持。​</td></tr></tbody></table>

<h2 data-step="07">1.0.0 · 稳定兼容线</h2>
<table class="comparison-table"><thead><tr><th>交付内容</th><th>验收证据</th><th>禁止冒充完成的边界</th></tr></thead><tbody><tr><td>公共 API/ABI 冻结；受支持迁移升级矩阵；安全审计；完整运维、集成与兼容文档。</td><td>二进制及源码兼容门禁将每个公共制品与声明基线比较；全新安装和所有受支持版本间升级覆盖已公布的 JDK、Spring Boot 与数据库矩阵；威胁模型、依赖发现、租户/授权/存储滥用场景和修复经过评审；文档中的命令与示例全部可执行；支持和弃用策略列出精确版本与日期。</td><td>公共契约仍为 provisional、受支持升级路径未测试、高危安全问题未解决，或兼容声明超出实际验证矩阵时，禁止标记为 1.0。​</td></tr></tbody></table><aside class="callout" data-mark="1.0"><div><strong>稳定性是持续义务</strong><p>通过 1.0 门禁即冻结已声明契约；后续移除必须遵守公开弃用窗口，提供迁移指导和兼容证据。仅创建 Tag 不会自动产生稳定性。</p></div></aside>
