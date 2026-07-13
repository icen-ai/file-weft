---
route: "concepts/module-boundaries"
group: "concepts"
order: 1
locale: "en"
nav: "Module boundaries"
title: "Boundaries before features"
lead: "FileWeft keeps policy, orchestration and vendor integration in separate modules so extension does not erode compatibility."
format: "html"
---

<h2 data-step="01">Dependency direction</h2>
<div class="architecture-stack"><div>starter → application → domain → core</div><div>adapter → spi</div></div><p>Core contains identifiers, results, errors, events and context only. Domain contains business rules. Application owns use cases. Adapters own external implementations.</p>

<h2 data-step="02">Forbidden shortcuts</h2>
<ul><li>Core must not depend on Spring or a database.</li><li>Domain must not call MinIO, Dify or another vendor SDK.</li><li>SPI must not expose vendor types.</li><li>Controllers validate and convert; they do not access storage or repositories.</li></ul>
