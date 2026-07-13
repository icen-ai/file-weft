---
route: "reference/configuration"
group: "reference"
order: 3
locale: "zh"
nav: "配置"
title: "配置地图"
lead: "安全生产默认值不会隐式选择租户、本地存储或迁移行为。每个 fallback 和运行角色都必须显式开启。"
format: "html"
---

<h2 data-step="01">持久化</h2>
<div class="code-block"><div class="code-label"><span>YAML</span></div><pre><code>fileweft:
  persistence:
    migration-mode: validate # migrate | validate | disabled
    schema: fileweft
    create-schema: false</code></pre></div>

<h2 data-step="02">Worker 与观测</h2>
<div class="code-block"><div class="code-label"><span>YAML</span></div><pre><code>fileweft:
  worker:
    enabled: true
    process-outbox: true
  outbox:
    backlog-metrics-enabled: true
    backlog-metrics-interval-millis: 30000
    backlog-metrics-query-timeout-seconds: 5</code></pre></div>

<h2 data-step="03">显式开发 fallback</h2>
<div class="code-block"><div class="code-label"><span>Properties</span></div><pre><code>fileweft.default-tenant-enabled=true
fileweft.default-tenant-id=tenant-a
fileweft.storage.local-enabled=true
fileweft.storage.local-root=/var/lib/fileweft</code></pre></div><aside class="callout warning" data-mark="!"><div><strong>不适用于多节点生产</strong><p>固定租户与本地文件系统只适合经过评审的单租户或开发部署，Doctor 会将其报告为警告。</p></div></aside>
