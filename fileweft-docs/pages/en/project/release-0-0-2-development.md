---
route: "project/release-0-0-2-development"
group: "project"
order: 4
locale: "en"
nav: "0.0.2 development"
title: "0.0.2 development line"
lead: "0.0.2-SNAPSHOT is an unreleased development line. The stable published version remains ai.icen:*:0.0.1 until release gates and remote artifact verification finish."
format: "html"
---

<h2 data-step="01">Workflow decision evidence</h2>
<p>New approvals and rejections preserve an immutable operator ID, optional safe display-name snapshot and <code>decidedTime</code>. The privileged <code>GET /fileweft/v1/documents/{id}/workflow-decisions</code> projection requires both <code>document:audit</code> and <code>document:read</code>; ordinary <code>/workflows</code> history remains identity-redacted.</p><aside class="callout" data-mark="?"><div><strong>Legacy evidence stays unknown</strong><p>V026 does not infer an actor from an assignee, current directory entry or optional audit row. Completed legacy tasks remain UNKNOWN.</p></div></aside>

<h2 data-step="02">Identity contract</h2>
<p>Host user IDs are opaque, case-sensitive strings with a 256 UTF-16-code-unit limit and a fixed safe-character contract. Long, Int, UUID and external directory identifiers must be converted by the host using one permanently stable representation.</p>

<h2 data-step="03">V026 rollout</h2>
<ol><li>Run <code>docs/sql/postgresql-v026-workflow-decision-evidence-preflight.sql</code> and repair unsafe host mappings without truncating or guessing.</li><li>Close review commands, stop every old API node and wait for in-flight decisions.</li><li>Rerun preflight, migrate, validate columns and constraints, then start only V026-aware nodes.</li><li>Keep the V026 columns, constraints and evidence during rollback; never shrink identity columns or reopen review writes on an old binary.</li></ol>
