---
route: "operations/doctor-observability"
group: "operations"
order: 2
locale: "zh"
nav: "Doctor 与可观测性"
title: "从证据出发运维"
lead: "Doctor 通过安全投影解释组件健康；指标展示有界趋势；审计与 Trace 定位租户和资源证据，同时避免高基数标签。"
format: "html"
---

<h2 data-step="01">三条 Doctor 路径</h2>
<table class="comparison-table"><thead><tr><th>路径</th><th>用途</th></tr></thead><tbody><tr><td>即时文档</td><td>通过文档和目录授权后的有界交互式检查</td></tr><tr><td>异步文档</td><td>持久、幂等且具备 Worker 围栏的诊断</td></tr><tr><td>系统</td><td>要求 system:doctor:read 的租户运行时检查</td></tr></tbody></table>

<h2 data-step="02">核心指标</h2>
<p>上传、同步、撤回、任务和 Doctor 结果以 <code>fileweft.</code> 前缀计数。Outbox Gauge 只包含固定的 <code>ready</code>、<code>delayed</code>、<code>running</code>、<code>expired</code>、<code>failed</code> 与最老 ready age。</p><aside class="callout" data-mark="#"><div><strong>保持标签有界</strong><p>默认指标会丢弃租户、文档和用户标识。资源级排障应使用审计、操作日志与 Trace。</p></div></aside>
