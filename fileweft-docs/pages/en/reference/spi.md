---
route: "reference/spi"
group: "reference"
order: 1
locale: "en"
nav: "SPI index"
title: "SPI surface"
lead: "Contracts keep infrastructure and host policy replaceable while preserving trustworthy context and Java-friendly APIs."
format: "html"
---

<h2 data-step="01">Primary extension families</h2>
<table class="comparison-table"><thead><tr><th>Area</th><th>Contract responsibility</th></tr></thead><tbody><tr><td>Identity & tenant</td><td>Trusted current tenant, current user and authorization decisions</td></tr><tr><td>Storage</td><td>Tenant-scoped object and multipart operations</td></tr><tr><td>Catalog</td><td>Host folder topology, canonical IDs and action-aware ACL</td></tr><tr><td>Workflow</td><td>Approval routes and task definitions</td></tr><tr><td>Connector</td><td>Idempotent downstream delivery, removal and health</td></tr><tr><td>Doctor & metrics</td><td>Bounded diagnostics, counters, gauges and trace scope</td></tr><tr><td>Agent & task</td><td>Durable handlers and AI contributions</td></tr></tbody></table>

<h2 data-step="02">Public API discipline</h2>
<p>Public contracts target Java callers. Avoid suspend functions, Kotlin Flow, value classes, sealed interfaces and data objects. IDs remain opaque strings; vendor SDK models remain inside adapters.</p>
