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

<h2>如何阅读路线图</h2>

<p>下面每一行列出版本、具体交付内容、声称完成所需的证据，以及不可跨越的边界。任何未验收项都继续阻塞发布。</p>

<table class="comparison-table">
<thead><tr><th>状态</th><th>含义</th></tr></thead>
<tbody>
<tr><td>计划中</td><td>范围已确定，但禁止宣称已经完成。</td></tr>
<tr><td>进行中</td><td>已有部分实现或证据；任何未验收项都继续阻塞发布。</td></tr>
<tr><td>已完成</td><td>所有验收证据都能从干净环境复现，并满足完成边界。</td></tr>
</tbody>
</table>

<aside class="callout warning" data-mark="!"><div><strong>证据只对被验证版本有效</strong><p>不能把另一种数据库、另一个 Boot 代际、厂商模拟器或旧版本的成功结果挪用为本版本的完成声明。证据不能复现时，该项仍是待办。</p></div></aside>

<h2>0.0.2 · 发布合同</h2>

<p>0.0.2 完成 0.0.1 未尽之事：干净的 HTTP 表面、可信的发布元数据与可复现的校验。</p>

<table class="comparison-table">
<thead><tr><th>交付内容</th><th>验收证据</th><th>禁止冒充完成的边界</th></tr></thead>
<tbody>
<tr>
<td>基于运行时闭包的 SBOM；SNAPSHOT 发布校验器；正式五操作断点续传 HTTP 资源。</td>
<td>SBOM 校验器从所有发布模块的真实 runtime closure 推导组件，并拒绝测试、编译器和构建工具泄漏；发布校验器证明 SNAPSHOT/正式版本规则；Boot 2 与 Boot 3 的契约、Context、MVC 测试及浏览器 E2E 覆盖正式续传的租户、权限、错误、检查点和脱敏行为。</td>
<td>SBOM 仍只是依赖转储、校验器会接受错误发布身份，或续传资源仅存在于内部服务、dev 路由或单一 Boot 代际时，都不能发布 0.0.2。</td>
</tr>
<tr>
<td>原生 MySQL 8.x 中 8.0.17+ 与 KingbaseES 的 0.0.2 持久化支持证据，以及各自独立的按需门禁。</td>
<td>MySQL 8.0.46 与官方 KingbaseES V008R006C009B0014 环境分别通过由完整 28 个迁移（V001–V028）组成的 Flyway 链和 JDBC repository 实库套件；`mysqlIntegrationCheck` 与 `kingbaseIntegrationCheck` 在缺少指定真实环境时失败关闭，并由 CNB 为相关数据库变更、夜间全量验收或发布事件调度。</td>
<td>H2、SQL 解析、Mock、仅 PostgreSQL 绿测或另一种数据库的结果，都不能证明 MySQL 或 KingbaseES。MariaDB 与 MySQL 9 不在原生 MySQL 8.x 支持边界内，当前实证也不能扩大为每个 8.x 小版本、排序规则、部署拓扑或厂商连接器支持。</td>
</tr>
</tbody>
</table>

<aside class="callout" data-mark="发布证据"><div><strong>0.0.2 消费规则</strong><p>发布合同包含运行时闭包 SBOM、精确库存校验、五操作正式断点续传契约、Boot 2/3 镜像 MVC 和浏览器验收、完整 28 个 V001–V028 数据库迁移，以及 MySQL 8.0.46 与 KingbaseES 的真实迁移/repository 证据。只有精确提交具备全部匹配 CNB lane、受保护标签发布和匿名冷缓存解析结果时，才能消费 <code>ai.icen:*:0.0.2</code>；本路线图不会提前声称这些远端步骤已经成功。</p></div></aside>

<aside class="callout warning" data-mark="移出 0.0.2"><div><strong>正式目录树 HTTP 不是 0.0.2 交付项</strong><p>宿主拥有的目录 SPI 与目录感知授权 guard 继续是受支持的集成边界；独立正式目录树 HTTP 资源已经移出 0.0.2，没有承诺目标版本，也不得再作为本次发布的未验收阻断项。</p></div></aside>

<h2>Agent 产品能力决策</h2>

<aside class="callout warning" data-mark="延期"><div><strong>0.0.2 不提供 Agent 产品能力</strong><p><code>fileweft-agent</code> 制品、Agent SPI/公共 ABI，以及 V012/V026 中与 Agent 有关的表、列和约束仅为源码、二进制和数据库兼容而保留。0.0.2 默认 Runtime、Starter、Doctor 清单、插件清单、公共 HTTP API 与 <code>fileweft-dev</code> 都不注册、宣传或暴露 Agent。任何显式遗留兼容开关也不是 0.0.2 功能。</p><p>Agent 将来需要重新设计，但无限期延期；最早只能在 1.0.0 已发布之后重新评估，而且这不承诺 1.x、下一版本或任何其他版本会交付。</p></div></aside>

<h2>0.1.0 · 生产供应链</h2>

<p>0.1.0 证明已发布制品可被消费、验证并从源码追溯到仓库。</p>

<table class="comparison-table">
<thead><tr><th>交付内容</th><th>验收证据</th><th>禁止冒充完成的边界</th></tr></thead>
<tbody>
<tr>
<td>使用已确认官方语法的 CNB CI；OSV 漏洞策略；制品签名与 provenance；远端冷缓存消费；从源码到仓库唯一发布身份。</td>
<td>干净 CNB Runner 使用当前 CNB 官方文档确认过的语法跑完全门禁；固定版本的 OSV 扫描执行明确的严重度和例外策略；消费者可验证签名与 provenance；空 Gradle 缓存的新机器只从 CNB 解析并运行正式制品；Tag、Commit、POM、Module Metadata、SBOM 与 provenance 的版本和源码修订完全一致。</td>
<td>本地 publish、开发者热缓存、未签名 checksum、未经官方确认的示例 YAML 或仓库页面截图，都不能用于宣称生产发布链路完成。</td>
</tr>
</tbody>
</table>

<h2>0.2.0 · 宿主集成与可观测性</h2>

<p>0.2.0 为宿主作者扩大可复用测试套件和可观测性；MySQL 8 与 KingbaseES 的当前实库证据已前移到 0.0.2，不再作为本阶段的未来承诺。</p>

<table class="comparison-table">
<thead><tr><th>交付内容</th><th>验收证据</th><th>禁止冒充完成的边界</th></tr></thead>
<tbody>
<tr>
<td>扩大可复用 TestKit 的宿主 SPI 覆盖；结构化日志与 OpenTelemetry 集成；按同等真实环境证据扩展未来数据库矩阵。</td>
<td>外部样例宿主运行身份、授权、租户、目录、工作流、存储、连接器和通用任务契约套件；OpenTelemetry Collector 测试断言 Trace/Metric/Log 关联、脱敏和有界基数；任何新增数据库都具备独立的真实迁移与 repository 门禁。</td>
<td>Mock、单一 happy path 适配器测试、肉眼查看日志，或把兼容保留的 Agent ABI 当作宿主产品契约，都不能证明 SPI、可观测性或未来数据库支持。</td>
</tr>
</tbody>
</table>

<h2>0.3.0 · 规模化运营</h2>

<p>0.3.0 让 FileWeft 在高容量、受监管环境中可运维，并具备明确恢复目标。</p>

<table class="comparison-table">
<thead><tr><th>交付内容</th><th>验收证据</th><th>禁止冒充完成的边界</th></tr></thead>
<tbody>
<tr>
<td>保留与 legal hold 规则；分区生命周期；容量边界；明确 RPO/RTO；备份恢复流程；可度量 SLO。</td>
<td>可控时间测试证明保留、冻结与安全删除；分区创建、轮转、归档和删除测试保持租户及审计不变量；可重复压测报告公开经过验证的文档、版本、对象和队列上限；故障演练将数据库与对象存储恢复到干净环境，并达到声明的 RPO/RTO；仪表盘和告警按约定可用性、延迟与积压 SLO 实际触发。</td>
<td>配置示例、未演练 Runbook、未披露瓶颈的合成吞吐量，或从未恢复过的备份，都不能描述为生产规模就绪。</td>
</tr>
</tbody>
</table>

<h2>0.4.0 · 官方厂商适配器</h2>

<p>0.4.0 发布针对指定厂商服务的持续维护适配器，每个都有真实服务证据。</p>

<table class="comparison-table">
<thead><tr><th>交付内容</th><th>验收证据</th><th>禁止冒充完成的边界</th></tr></thead>
<tbody>
<tr>
<td>面向 OSS、Dify、ESE 与 AppBuilder 官方服务的持续维护适配器。</td>
<td>隔离凭据的合约环境验证文档所列厂商 API 版本、认证、超时、重试分类、幂等交付、安全重复撤回、分页或上传限制、错误脱敏、健康检查和故障恢复；每个适配器都记录兼容范围与维护责任。</td>
<td>通用 S3 实现、WireMock 响应、逆向 payload 或 dev 模拟器不能证明官方厂商支持。没有可重复真实服务证据的适配器必须保持 experimental，不能宣传为受支持。</td>
</tr>
</tbody>
</table>

<h2>1.0.0 · 稳定兼容线</h2>

<p>1.0.0 冻结公共契约，并承诺受支持的升级路径。</p>

<table class="comparison-table">
<thead><tr><th>交付内容</th><th>验收证据</th><th>禁止冒充完成的边界</th></tr></thead>
<tbody>
<tr>
<td>公共 API/ABI 冻结；受支持迁移升级矩阵；安全审计；完整运维、集成与兼容文档。</td>
<td>二进制及源码兼容门禁将每个公共制品与声明基线比较；全新安装和所有受支持版本间升级覆盖已公布的 JDK、Spring Boot 与数据库矩阵；威胁模型、依赖发现、租户/授权/存储滥用场景和修复经过评审；文档中的命令与示例全部可执行；支持和弃用策略列出精确版本与日期。</td>
<td>公共契约仍为 provisional、受支持升级路径未测试、高危安全问题未解决，或兼容声明超出实际验证矩阵时，禁止标记为 1.0。</td>
</tr>
</tbody>
</table>

<aside class="callout" data-mark="1.0"><div><strong>稳定性是持续义务</strong><p>通过 1.0 门禁即冻结已声明契约；后续移除必须遵守公开弃用窗口，提供迁移指导和兼容证据。仅创建 Tag 不会自动产生稳定性。</p></div></aside>

<h2>常见问题</h2>

<p><strong>计划中的项可以延后到后续版本吗？</strong> 可以。如果缺少验收证据，该项保持开放，并可能被重新分配到下一版本。</p>

<p><strong>谁决定一项是否完成？</strong> 维护者基于仓库或指定真实环境中可复现的证据判定，而不是单凭一次绿测或书面意图。</p>

<h2>下一步</h2>

<ul>
<li>阅读 <a href="#/project/release-0-0-2-development">0.0.2 发布说明</a>，了解精确范围与远端消费规则。</li>
<li>想帮忙关闭待办项，参见 <a href="#/project/contributing">参与贡献</a>。</li>
</ul>
