---
route: "getting-started/first-integration"
group: "getting-started"
order: 3
locale: "en"
nav: "First integration"
title: "Wire a trustworthy host"
lead: "A production host must provide trusted tenant, identity and authorization context, a shared persistent StorageAdapter, and an explicit migration policy."
format: "html"
---

<h2 data-step="01">Supply trust context</h2>
<p>Implement <code>TenantProvider</code>, <code>UserRealmProvider</code> and <code>AuthorizationProvider</code> from data already authenticated by your host. Controllers must never accept tenant IDs, user IDs, roles or permission results as business parameters.</p><aside class="callout" data-mark="ID"><div><strong>User IDs are opaque safe strings</strong><p>Long, Int, UUID and external directory identifiers must be converted to one permanently stable string format by the host. IDs are case-sensitive, at most 256 UTF-16 code units, have no leading or trailing Unicode whitespace, and exclude control and FileWeft-rejected format characters.</p></div></aside>

<h2 data-step="02">Choose storage and database ownership</h2>
<p>Multi-node deployments need a shared persistent <code>StorageAdapter</code>. For PostgreSQL, set the DataSource current schema and the FileWeft schema assertion to the same value.</p><div class="code-block"><div class="code-label"><span>YAML</span></div><pre><code>spring:
  datasource:
    url: jdbc:postgresql://db:5432/app?currentSchema=fileweft

fileweft:
  persistence:
    migration-mode: validate
    schema: fileweft
    create-schema: false</code></pre></div>

<h2 data-step="03">Separate runtime roles</h2>
<p>Run API nodes without queue consumption. Run separate Worker nodes against the same database and storage with only the processors they need. This keeps HTTP latency and background leases independent.</p>
