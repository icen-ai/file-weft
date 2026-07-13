---
route: "guides/workflows-uploads"
group: "guides"
order: 2
locale: "en"
nav: "Workflow & uploads"
title: "Reviews, resumable bytes and agents"
lead: "Long-running work is explicit, persistent and fenced. Approval routing, multipart upload and AI processing remain replaceable without weakening transaction boundaries."
format: "html"
---

<h2 data-step="01">Approval routing</h2>
<p><code>DocumentReviewRouteProvider</code> returns one or more review tasks outside the database transaction. All tasks must approve for parallel sign-off; one rejection ends the workflow. The final transaction rechecks document state before committing.</p><p>On the unreleased <code>0.0.2-SNAPSHOT</code> line, each new decision stores an immutable operator ID, optional safe display-name snapshot and decision time. The normal history remains identity-redacted; the privileged <code>/fileweft/v1/documents/{id}/workflow-decisions</code> view requires both <code>document:audit</code> and <code>document:read</code>. Legacy completed tasks remain <code>UNKNOWN</code> because migration never guesses an actor.</p>

<h2 data-step="02">Resumable upload</h2>
<ol><li>Start with a caller-stable idempotency key.</li><li>Upload numbered parts and persist each acknowledgement.</li><li>Inspect the session after reconnecting.</li><li>Complete once to create the object, asset and event; abort when intentionally abandoned.</li></ol><p>Sessions bind to trusted tenant and user identity. Storage upload IDs and object paths never reach the browser.</p>

<h2 data-step="03">Agent work</h2>
<p>AI and diagnostic work belong in durable <code>fw_task</code> handlers. Workers use leases and idempotent task IDs. Agent output becomes visible only after the matching task reaches a fenced successful terminal state.</p>
