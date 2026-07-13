---
route: "architecture/consistency"
group: "architecture"
order: 1
locale: "en"
nav: "Consistency model"
title: "Local atomicity, explicit convergence"
lead: "FileWeft does not promise a distributed transaction across PostgreSQL, object storage and downstream systems. It makes local state atomic and remote convergence observable."
format: "html"
---

<h2 data-step="01">Transactional Outbox</h2>
<div class="architecture-stack"><div>business transaction + Outbox record</div><div>commit local truth</div><div>async Worker claims with lease</div><div>connector call + fenced projection</div></div><p>Never call a downstream connector inside the business transaction. Retried connector calls must accept stable idempotency identity.</p>

<h2 data-step="02">Storage compensation</h2>
<p>When uploaded bytes fail validation or the local transaction definitely rolls back without references, FileWeft compensates by deleting the object. When commit outcome is unknown, it reconciles first and preserves evidence rather than risking deletion of committed data.</p>

<h2 data-step="03">Lock order</h2>
<p>Idempotent catalog-aware review paths follow a stable order: idempotency → document → asset → workflow. External catalog, review-route and delivery-policy calls happen outside this final short transaction.</p>
