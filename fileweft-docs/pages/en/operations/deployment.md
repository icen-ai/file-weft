---
route: "operations/deployment"
group: "operations"
order: 1
locale: "en"
nav: "Production deployment"
title: "Deploy distinct runtime roles"
lead: "Use one validated artifact with intentionally different API, Worker and migration-job configurations. Share database and object storage; do not share privileges unnecessarily."
format: "html"
---

<h2 data-step="01">Recommended topology</h2>
<div class="architecture-stack"><div>Migration Job · DDL identity · migrate</div><div>API nodes · read/write identity · validate</div><div>Outbox Workers · queue + connector identity · validate</div><div>Task Workers · task-specific identity · validate</div></div>

<h2 data-step="02">Rollout order</h2>
<ol><li>Back up and verify recovery.</li><li>Run a controlled migration job with exclusive migration ownership.</li><li>Start API and Worker roles in <code>validate</code> mode.</li><li>Observe health, Doctor, Outbox ready age and lease recovery.</li><li>Enable traffic only after validation succeeds.</li></ol>

<h2 data-step="03">Credential boundaries</h2>
<p>Do not give long-lived API or Worker processes schema-creation credentials. Connector credentials belong only to Worker roles that invoke those connectors. Browser clients never receive object-storage credentials or downstream secrets.</p>
