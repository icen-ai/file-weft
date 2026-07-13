---
route: "project/contributing"
group: "project"
order: 1
locale: "en"
nav: "Contributing"
title: "Contribute without eroding the foundation"
lead: "Changes move from Core through SPI, Domain, Application, Persistence, Starter and Adapter only when the responsibility belongs there. Tests follow the same boundary."
format: "html"
---

<h2 data-step="01">Before code</h2>
<ol><li>Read the repository AI implementation manuals and the directly relevant extension material.</li><li>Identify the owning module and existing SPI.</li><li>Describe compatibility and migration impact for architectural changes.</li><li>Design how an operator will diagnose failure.</li></ol>

<h2 data-step="02">Test by layer</h2>
<table class="comparison-table"><thead><tr><th>Layer</th><th>Required evidence</th></tr></thead><tbody><tr><td>Core / Domain</td><td>Focused unit tests and invariants</td></tr><tr><td>SPI</td><td>Contract tests and Java-friendly usage</td></tr><tr><td>Adapter / Persistence</td><td>Real integration tests</td></tr><tr><td>Starter / Web</td><td>Context and Boot 2/3 contract tests</td></tr><tr><td>Release</td><td>Compatibility matrix, Compose acceptance, browser E2E and SBOM</td></tr></tbody></table>

<h2 data-step="03">Change hygiene</h2>
<p>Use UTF-8, small focused classes and explicit dependencies. Preserve tenant filtering and lock order. Do not rewrite unrelated user changes. Commit coherent milestones with action-oriented messages.</p>
