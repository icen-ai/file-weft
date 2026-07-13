---
route: "extensions/plugins"
group: "extensions"
order: 1
locale: "zh"
nav: "插件开发"
title: "编写克制的插件"
lead: "插件聚合已有 SPI 贡献，不会形成新的架构层，也不是进程内安全沙箱。"
format: "html"
---

<h2 data-step="01">贡献模型</h2>
<p><code>FileWeftPlugin</code> 可以通过已有契约贡献连接器、存储、Doctor checker、任务 handler、审批路由、指标或 Agent。注册表构造时只调用一次贡献 getter，并保存不可变快照。</p>

<h2 data-step="02">发现方式</h2>
<p>可以注册经过评审的 Spring Bean，或在宿主支持时使用 Java <code>ServiceLoader</code> 元数据。插件 ID 必须稳定、有界，适合公共清单。贡献 getter 不能远程调用，也不能包含业务副作用。</p>

<h2 data-step="03">验证</h2>
<ul><li>单元测试插件决策。</li><li>为每个 Adapter 运行 SPI 契约测试。</li><li>启动匹配的 Starter Context。</li><li>用 Doctor 覆盖缺配置与远端故障。</li><li>确认公共插件清单保持脱敏。</li></ul>
