---
route: "architecture/security"
group: "architecture"
order: 2
locale: "en"
nav: "Security architecture"
title: "Fail closed at every boundary"
lead: "Capabilities are installed only when their complete security boundary exists. Missing context or ambiguous providers make the operation unavailable instead of silently broadening access."
format: "html"
---

<h2 data-step="01">Capability assembly</h2>
<p>A single unambiguous provider is required at each boundary. Multiple catalog or lifecycle candidates are not resolved by guessing or <code>@Primary</code> when that could change security semantics. Custom persistence must implement real mutation locks and atomic idempotency claims before guarded writes are exposed.</p>

<h2 data-step="02">Public projections</h2>
<p>HTTP DTOs omit storage URLs, object keys, connector internals, raw Doctor evidence, tenant identifiers and unsafe diagnostic text. Audit views expose stable action and operator snapshots, not unrestricted details JSON.</p>

<h2 data-step="03">Plugins are trusted code</h2>
<aside class="callout warning" data-mark="!"><div><strong>No in-process sandbox</strong><p>A plugin shares the host JVM, permissions and classpath. Install only reviewed artifacts. Run untrusted extensions in a separate process behind authenticated, limited and audited protocols.</p></div></aside>
