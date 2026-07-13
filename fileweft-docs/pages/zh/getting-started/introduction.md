---
route: "getting-started/introduction"
group: "getting-started"
order: 1
locale: "zh"
nav: "介绍"
title: "为必须长久运行的文件系统打地基"
lead: "FileWeft 是可扩展的 Kotlin/JVM 企业文件基础设施，覆盖文档生命周期、存储、审批、交付与诊断，但不接管宿主的身份、目录树和业务规则。"
format: "html"
---

<h2 data-step="01">FileWeft 是什么</h2>
<p>FileWeft 在稳定的 Application 与 SPI 边界后协调可靠文件操作，负责文档版本、生命周期、审计证据、Outbox 交付和可诊断后台任务。</p><table class="comparison-table"><thead><tr><th>FileWeft 负责</th><th>宿主负责</th></tr></thead><tbody><tr><td>文档、版本和交付状态</td><td>认证与用户目录</td></tr><tr><td>Outbox、任务租约和审计证据</td><td>目录拓扑与目录 ACL</td></tr><tr><td>稳定的存储及连接器契约</td><td>业务策略与界面</td></tr></tbody></table>

<h2 data-step="02">设计立场</h2>
<p>外部系统默认不可靠。FileWeft 先提交本地业务状态，在同一事务记录持久任务，再在数据库长事务之外调用存储或下游连接器。</p><aside class="callout" data-mark="SPI"><div><strong>优先扩展而不是修改</strong><p>存储、身份、授权、租户、目录、工作流、连接器和 AI 行为都通过契约进入。Core 与 Domain 不依赖 Spring、数据库或厂商 SDK。</p></div></aside>

<h2 data-step="03">选择接入方式</h2>
<ul><li><b>仅 SPI：</b>不引入 Spring 运行时，只实现或使用契约。</li><li><b>运行时 Starter：</b>为 Spring Boot 2 或 3 装配持久化、Worker 与应用服务。</li><li><b>Web Starter：</b>为同一 Boot 代际增加稳定的 <code>/fileweft/v1</code> HTTP 接口。</li></ul>
