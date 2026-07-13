---
route: "reference/http-api"
group: "reference"
order: 2
locale: "en"
nav: "HTTP API v1"
title: "HTTP API v1"
lead: "The formal surface lives under /fileweft/v1 and returns a stable JSON envelope, except for authorized binary downloads."
format: "html"
---

<h2 data-step="01">Resource families</h2>
<ul><li><code>/fileweft/v1/documents</code> — list, create, inspect, rename, version and lifecycle.</li><li><code>/fileweft/v1/workflows/tasks</code> — trusted-user review inbox and decisions.</li><li><code>/fileweft/v1/documents/{id}/workflows</code> — identity-redacted review history for readers.</li><li><code>/fileweft/v1/documents/{id}/workflow-decisions</code> — privileged immutable actor evidence requiring both <code>document:audit</code> and <code>document:read</code> (unreleased <code>0.0.2-SNAPSHOT</code>).</li><li><code>/fileweft/v1/documents/{id}/sync-status</code> — safe delivery projection and explicit retry commands.</li><li><code>/fileweft/v1/documents/{id}/doctor</code> and <code>/fileweft/v1/doctor</code> — document and system diagnostics.</li><li><code>/fileweft/v1/plugins</code> and <code>/fileweft/v1/health</code> — safe inventory and process liveness.</li></ul><aside class="callout" data-mark="ACL"><div><strong>Decision evidence is privileged</strong><p>New decisions expose the immutable operator ID, optional safe name snapshot and decided time only through the privileged view. Legacy completed tasks return decisionEvidenceRecorded=false with null evidence fields; FileWeft never infers an actor from assignment or optional audit rows.</p></div></aside>

<h2 data-step="02">Stable envelope</h2>
<div class="code-block"><div class="code-label"><span>JSON</span></div><pre><code>{
  "code": "OK",
  "message": "OK",
  "data": {},
  "error": null,
  "traceId": "optional-host-trace-id"
}</code></pre></div>

<h2 data-step="03">Idempotent commands</h2>
<p>Lifecycle, review, delivery recovery and Doctor scheduling require exactly one <code>Idempotency-Key</code>. The server stores only a tenant-scoped SHA-256 digest and binds it to the trusted operator, action, resource and typed-command fingerprint.</p><aside class="callout" data-mark="KEY"><div><strong>Replay still authorizes</strong><p>Authentication, action permission and catalog visibility run again before every replay. An idempotency record is not an authorization cache.</p></div></aside>
