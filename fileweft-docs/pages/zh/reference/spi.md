---
route: "reference/spi"
group: "reference"
order: 1
locale: "zh"
nav: "SPI 索引"
title: "SPI 总览"
lead: "契约让基础设施和宿主策略可替换，同时保持可信上下文与 Java 友好的公共 API。"
format: "html"
---

<h2 data-step="01">主要扩展族</h2>
<table class="comparison-table"><thead><tr><th>领域</th><th>契约职责</th></tr></thead><tbody><tr><td>身份与租户</td><td>可信当前租户、当前用户与授权决策</td></tr><tr><td>存储</td><td>租户作用域对象与分片操作</td></tr><tr><td>目录</td><td>宿主目录拓扑、canonical ID 与动作级 ACL</td></tr><tr><td>工作流</td><td>审批路由与任务定义</td></tr><tr><td>连接器</td><td>幂等下游交付、撤回与健康检查</td></tr><tr><td>Doctor 与指标</td><td>有界诊断、计数、Gauge 与 Trace scope</td></tr><tr><td>Agent 与任务</td><td>持久 Handler 与 AI 贡献</td></tr></tbody></table>

<h2 data-step="02">公共 API 纪律</h2>
<p>公共契约面向 Java 调用方，不暴露 suspend、Kotlin Flow、value class、sealed interface 或 data object。ID 保持不透明字符串，厂商 SDK 模型只留在 Adapter。</p>
