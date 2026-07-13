---
route: "getting-started/introduction"
group: "getting-started"
order: 1
locale: "en"
nav: "Introduction"
title: "Infrastructure for files that must endure"
lead: "FileWeft is an extensible Kotlin/JVM foundation for enterprise document lifecycles, storage, approvals, delivery and diagnostics — without taking ownership of your identity, folder tree or business rules."
format: "html"
---

<h2 data-step="01">What FileWeft is</h2>
<p>FileWeft coordinates durable file operations behind stable application and SPI boundaries. It owns document versions, lifecycle transitions, audit evidence, Outbox delivery and diagnosable background work.</p><table class="comparison-table"><thead><tr><th>FileWeft owns</th><th>Your host owns</th></tr></thead><tbody><tr><td>Document, version and delivery state</td><td>Authentication and user directory</td></tr><tr><td>Outbox, task leases and audit evidence</td><td>Folder topology and folder ACL</td></tr><tr><td>Stable storage and connector contracts</td><td>Business-specific policy and presentation</td></tr></tbody></table>

<h2 data-step="02">Design posture</h2>
<p>External systems are assumed to fail. FileWeft commits local business state first, records durable work in the same transaction, and calls storage or downstream connectors outside long-running database transactions.</p><aside class="callout" data-mark="SPI"><div><strong>Extend before modifying</strong><p>Storage, identity, authorization, tenant, catalog, workflow, connector and AI behavior enter through contracts. Core and Domain do not depend on Spring, databases or vendor SDKs.</p></div></aside>

<h2 data-step="03">Choose your entry point</h2>
<ul><li><b>SPI only:</b> implement or consume contracts without a Spring runtime.</li><li><b>Runtime Starter:</b> assemble persistence, workers and application services for Spring Boot 2 or 3.</li><li><b>Web Starter:</b> add the stable <code>/fileweft/v1</code> HTTP surface for the same Boot generation.</li></ul>
