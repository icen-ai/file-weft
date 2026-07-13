---
route: "operations/doctor-observability"
group: "operations"
order: 2
locale: "en"
nav: "Doctor & observability"
title: "Operate from evidence"
lead: "Doctor explains component health through safe projections; metrics show bounded trends; audit and Trace locate tenant and resource evidence without high-cardinality labels."
format: "html"
---

<h2 data-step="01">Three Doctor paths</h2>
<table class="comparison-table"><thead><tr><th>Path</th><th>Purpose</th></tr></thead><tbody><tr><td>Immediate document</td><td>Bounded interactive checks after document and catalog authorization</td></tr><tr><td>Asynchronous document</td><td>Durable, idempotent diagnostics with fenced Worker result</td></tr><tr><td>System</td><td>Tenant runtime checks requiring system:doctor:read</td></tr></tbody></table>

<h2 data-step="02">Core metrics</h2>
<p>Count uploads, synchronization, delivery removal, task and Doctor outcomes under the <code>fileweft.</code> prefix. Outbox gauges expose fixed <code>ready</code>, <code>delayed</code>, <code>running</code>, <code>expired</code> and <code>failed</code> states plus the oldest ready age.</p><aside class="callout" data-mark="#"><div><strong>Keep labels bounded</strong><p>Default metrics drop tenant, document and user identifiers. Use audit, operation logs and Trace for resource-level investigation.</p></div></aside>
