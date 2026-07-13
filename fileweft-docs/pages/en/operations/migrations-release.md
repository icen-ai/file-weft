---
route: "operations/migrations-release"
group: "operations"
order: 3
locale: "en"
nav: "Migrations & releases"
title: "Migrate and release deliberately"
lead: "FileWeft owns a namespaced Flyway location and history table. Release gates test compatibility, real infrastructure paths, SBOM and reproducible dependency state."
format: "html"
---

<h2 data-step="01">Migration namespace</h2>
<p>Resources live only at <code>classpath:ai/icen/fw/db/migration</code> and history lives in <code>fileweft_schema_history</code>. Do not append these resources to the host's Flyway locations or merge them into <code>flyway_schema_history</code>.</p>

<h2 data-step="02">Old trial databases</h2>
<aside class="callout warning" data-mark="!"><div><strong>No automatic adoption</strong><p>A database previously run with com.fileweft trial artifacts must be stopped, backed up and inspected by a DBA. Do not baseline, repair, copy or delete history rows to bypass ownership analysis.</p></div></aside>

<h2 data-step="03">Release gates</h2>
<div class="code-block"><div class="code-label"><span>PowerShell</span></div><pre><code>.\gradlew.bat check --no-daemon
.\gradlew.bat compatibilityCheck --no-daemon
.\gradlew.bat verifySbom --no-daemon</code></pre></div><p>The formal release pipeline also enables PostgreSQL, RustFS, Dev API and browser acceptance suites against the same healthy development stack.</p>
