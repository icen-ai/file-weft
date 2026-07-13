---
route: "reference/configuration"
group: "reference"
order: 3
locale: "en"
nav: "Configuration"
title: "Configuration map"
lead: "Production-safe defaults disable implicit tenant, local storage and migration behavior. Enable each fallback or runtime role deliberately."
format: "html"
---

<h2 data-step="01">Persistence</h2>
<div class="code-block"><div class="code-label"><span>YAML</span></div><pre><code>fileweft:
  persistence:
    migration-mode: validate # migrate | validate | disabled
    schema: fileweft
    create-schema: false</code></pre></div>

<h2 data-step="02">Workers and observation</h2>
<div class="code-block"><div class="code-label"><span>YAML</span></div><pre><code>fileweft:
  worker:
    enabled: true
    process-outbox: true
  outbox:
    backlog-metrics-enabled: true
    backlog-metrics-interval-millis: 30000
    backlog-metrics-query-timeout-seconds: 5</code></pre></div>

<h2 data-step="03">Explicit development fallbacks</h2>
<div class="code-block"><div class="code-label"><span>Properties</span></div><pre><code>fileweft.default-tenant-enabled=true
fileweft.default-tenant-id=tenant-a
fileweft.storage.local-enabled=true
fileweft.storage.local-root=/var/lib/fileweft</code></pre></div><aside class="callout warning" data-mark="!"><div><strong>Not a multi-node production setup</strong><p>Fixed tenant and local filesystem fallbacks are for reviewed single-tenant or development deployments. Doctor reports them as warnings.</p></div></aside>
