---
route: "concepts/lifecycle-delivery"
group: "concepts"
order: 2
locale: "en"
nav: "Lifecycle & delivery"
title: "Lifecycle is evidence, not a flag"
lead: "Drafts become reviewable, published and removable through explicit transitions. Delivery to multiple downstream systems is tracked per target and per generation."
format: "html"
---

<h2 data-step="01">Document path</h2>
<p>A common controlled path is <code>DRAFT → PENDING_REVIEW → PUBLISHING → PUBLISHED</code>. Rework uses <code>PUBLISHED → OFFLINE → restore → DRAFT</code>; archive is explicit. State changes, audit and Outbox work commit together.</p>

<h2 data-step="02">Partial downstream failure</h2>
<p>Required targets block the published projection when failing; optional targets may fail while the document remains published. Successful targets are not rolled back automatically. Operators retry only the failed delivery or removal target.</p><aside class="callout" data-mark="N"><div><strong>Generation fencing</strong><p>Each new publication creates a delivery generation. Late results from an older generation cannot overwrite current state.</p></div></aside>
