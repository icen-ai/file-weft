---
route: "project/roadmap"
group: "project"
order: 6
locale: "en"
nav: "Roadmap"
title: "Roadmap with proof, not promises"
lead: "This backlog is the hand-off contract for future FileWeft development. A version moves from planned to complete only when its listed evidence exists in the repository or the named real environment; an implementation, a green narrow test or a written intention is never enough by itself."
format: "html"
---

<h2>How to read the roadmap</h2>

<p>Each line below names a version, the concrete deliverables, the evidence required to claim completion, and the boundary that must not be crossed. Treat every unchecked item as release-blocking.</p>

<table class="comparison-table">
<thead><tr><th>State</th><th>Meaning</th></tr></thead>
<tbody>
<tr><td>Planned</td><td>Scope is agreed, but no completion claim is allowed.</td></tr>
<tr><td>In progress</td><td>Some implementation or evidence exists; every unchecked acceptance item remains release-blocking.</td></tr>
<tr><td>Complete</td><td>All acceptance evidence is reproducible from a clean environment and the completion boundary is satisfied.</td></tr>
</tbody>
</table>

<aside class="callout warning" data-mark="!"><div><strong>Evidence is version-specific</strong><p>Do not copy a success from another database, Boot generation, vendor emulator or earlier release into a completion claim. If evidence cannot be reproduced, the item remains open.</p></div></aside>

<h2>0.0.3 · current candidate contract</h2>

<p>The 0.0.3 line adds typed metadata schemas and a complete pending-review withdrawal path without weakening the compatibility and release evidence established by 0.0.2.</p>

<table class="comparison-table">
<thead><tr><th>Deliverables</th><th>Acceptance evidence</th><th>Completion boundary</th></tr></thead>
<tbody>
<tr>
<td>Java-friendly metadata API/runtime modules, exact-version schema resolution, validation, safe public projection and Doctor coverage.</td>
<td>The 19-module publication inventory contains both metadata artifacts; focused unit/contract tests, both Boot generations, public API projection tests, artifact metadata/SBOM checks and anonymous consumers all resolve the same exact release identity.</td>
<td>A Kotlin-only API, unversioned schema selection, internal-only validation, an unsafe projection, a missing published module or a warm local cache cannot prove completion.</td>
</tr>
<tr>
<td>Idempotent withdrawal for pending document review plus V029 workflow-submitter evidence for PostgreSQL, MySQL and KingbaseES.</td>
<td>Application, persistence, Boot 2/3 and formal HTTP tests cover authorization, trusted submitter identity, idempotent replay and terminal-state rejection; every database runs the immutable V001–V029 chain in its own real integration lane.</td>
<td>Do not infer submitter identity from a request, rewrite V001–V028, reuse one dialect's result for another, expose Agent, or claim publication from source, a tag name or partial green evidence.</td>
</tr>
</tbody>
</table>

<aside class="callout" data-mark="CANDIDATE"><div><strong>0.0.3 consumption rule</strong><p>This is a candidate contract, not proof of publication. Consume <code>ai.icen:*:0.0.3</code> only after the guarded <code>v0.0.3</code> tag matches the protected remote <code>main</code> HEAD, every required CNB lane matches that exact commit, and a fresh anonymous consumer resolves all 19 coordinates plus Boot 2, Boot 3 and pure-SPI scenarios.</p></div></aside>

<h2>0.0.2 · previous immutable boundary</h2>

<p>The 0.0.2 line finishes what 0.0.1 started: a clean HTTP surface, trustworthy release metadata and reproducible verification.</p>

<table class="comparison-table">
<thead><tr><th>Deliverables</th><th>Acceptance evidence</th><th>Completion boundary</th></tr></thead>
<tbody>
<tr>
<td>Runtime-closure SBOM; SNAPSHOT release verifier; formal five-operation resumable-upload HTTP resource.</td>
<td>The SBOM verifier derives every published module's actual runtime closure and rejects test, compiler and build-tool leakage; the release verifier proves SNAPSHOT/stable version rules; Boot 2 and Boot 3 contract, context and MVC tests plus browser E2E prove tenant, permission, error, checkpoint and redaction behavior for the formal resumable-upload surface.</td>
<td>Do not publish 0.0.2 while the SBOM is only a dependency dump, while a verifier can accept the wrong release identity, or while resumable upload exists only as an internal service, dev-only route or one-Boot-generation controller.</td>
</tr>
<tr>
<td>0.0.2 persistence evidence for native MySQL 8.0.17+ within 8.x and KingbaseES, with an independent change-scoped gate for each database.</td>
<td>MySQL 8.0.46 and official KingbaseES V008R006C009B0014 environments each pass the fresh 28-migration V001–V028 Flyway chain and JDBC repository suite; `mysqlIntegrationCheck` and `kingbaseIntegrationCheck` fail closed when their named real environment is absent, and CNB schedules them for relevant database changes, nightly full acceptance or release events.</td>
<td>H2, SQL parsing, mocks, PostgreSQL-only success, or a result from the other database does not prove MySQL or KingbaseES. MariaDB and MySQL 9 are outside the native MySQL 8.x support boundary, and the named evidence must not be expanded into proof for every 8.x minor version, collation, deployment topology or vendor connector.</td>
</tr>
</tbody>
</table>

<aside class="callout" data-mark="RELEASE EVIDENCE"><div><strong>0.0.2 consumption rule</strong><p>The release contract contains the runtime-closure SBOM, exact-inventory verification, five-operation formal resumable-upload contract, mirrored Boot 2/3 MVC and browser acceptance, all 28 V001–V028 database migrations, and real MySQL 8.0.46 and KingbaseES migration/repository evidence. Consume <code>ai.icen:*:0.0.2</code> only when the exact commit has every matching CNB lane, guarded tag publication and anonymous cold-cache resolution; this roadmap does not claim those remote steps succeeded.</p></div></aside>

<aside class="callout warning" data-mark="OUT OF 0.0.2"><div><strong>Formal catalog-tree HTTP is not a 0.0.2 deliverable</strong><p>The host-owned catalog SPI and catalog-aware authorization guards remain supported integration boundaries. A separate formal catalog-tree HTTP resource has been removed from 0.0.2, has no committed target version, and must not be treated as an unchecked blocker for this release.</p></div></aside>

<h2>Agent product-capability decision</h2>

<aside class="callout warning" data-mark="DEFERRED"><div><strong>0.0.2 and 0.0.3 do not provide Agent product capability</strong><p>The <code>fileweft-agent</code> artifact, Agent SPI/public ABI, and the Agent-related tables, columns and constraints in V012/V026 remain only for source, binary and database compatibility. The default 0.0.2 and 0.0.3 runtime, Starters, Doctor inventory, plugin inventory, public HTTP API and <code>fileweft-dev</code> do not register, advertise or expose Agent. Any explicit legacy compatibility switch is likewise not a product feature.</p><p>Agent requires a future redesign and is deferred indefinitely. It may be reassessed only after 1.0.0 has been released, and that is not a commitment to 1.x, the next release or any other version.</p></div></aside>

<h2>0.1.0 · production supply chain</h2>

<p>0.1.0 is about proving that published artifacts can be consumed, verified and traced from source to registry.</p>

<table class="comparison-table">
<thead><tr><th>Deliverables</th><th>Acceptance evidence</th><th>Completion boundary</th></tr></thead>
<tbody>
<tr>
<td>CNB CI using confirmed official syntax; OSV vulnerability policy; artifact signing and provenance; remote cold-cache consumption; one release identity from source to registry.</td>
<td>A clean CNB runner executes the full gate with the syntax verified against current CNB documentation; a pinned OSV scan enforces an explicit severity/exception policy; consumers verify signatures and provenance; a clean machine with empty Gradle caches resolves and runs the published artifacts from CNB; tag, commit, POM, module metadata, SBOM and provenance all report the same version and source revision.</td>
<td>Local publication, a warm developer cache, an unsigned checksum, an unverified sample YAML or a registry page screenshot cannot be used to claim a production release pipeline.</td>
</tr>
</tbody>
</table>

<h2>0.2.0 · host integration and observability</h2>

<p>0.2.0 widens reusable host test kits and observability. The current real-database evidence for MySQL 8 and KingbaseES has moved into 0.0.2 instead of remaining a promise for this line.</p>

<table class="comparison-table">
<thead><tr><th>Deliverables</th><th>Acceptance evidence</th><th>Completion boundary</th></tr></thead>
<tbody>
<tr>
<td>Wider reusable TestKit coverage for host SPIs; structured logging and OpenTelemetry integration; future database-matrix expansion backed by equivalent real-environment evidence.</td>
<td>External sample hosts run identity, authorization, tenant, catalog, workflow, storage, connector and generic-task contract kits; an OpenTelemetry Collector test asserts trace/metric/log correlation, redaction and bounded cardinality; each newly added database has its own real migration and repository gate.</td>
<td>Mocks, one happy-path adapter test, visually inspected logs, or treating the compatibility-only Agent ABI as a host product contract do not prove SPI, observability or future database support.</td>
</tr>
</tbody>
</table>

<h2>0.3.0 · operations at scale</h2>

<p>0.3.0 makes FileWeft operable in high-volume, regulated environments with clear recovery objectives.</p>

<table class="comparison-table">
<thead><tr><th>Deliverables</th><th>Acceptance evidence</th><th>Completion boundary</th></tr></thead>
<tbody>
<tr>
<td>Retention and legal-hold rules; partition lifecycle; capacity envelopes; declared RPO/RTO; backup and restore procedures; measurable SLOs.</td>
<td>Time-controlled retention and hold tests prove safe deletion; partition create/rotate/archive/drop tests preserve tenant and audit invariants; repeatable load reports publish tested document, version, object and queue limits; fault drills restore database and object storage into a clean environment within the declared RPO/RTO; dashboards and alerts are exercised against agreed availability, latency and backlog SLOs.</td>
<td>Configuration examples, an untested runbook, synthetic throughput without bottleneck disclosure, or a backup that has never been restored cannot be described as production-scale readiness.</td>
</tr>
</tbody>
</table>

<h2>0.4.0 · official vendor adapters</h2>

<p>0.4.0 ships maintained adapters for named vendor services, each backed by real-service evidence.</p>

<table class="comparison-table">
<thead><tr><th>Deliverables</th><th>Acceptance evidence</th><th>Completion boundary</th></tr></thead>
<tbody>
<tr>
<td>Maintained adapters for OSS, Dify, ESE and AppBuilder against their official services.</td>
<td>Credential-isolated contract environments prove the documented vendor API versions, authentication, timeout, retry classification, idempotent delivery, safe repeated removal, pagination or upload limits, redacted failures, health checks and recovery; compatibility and ownership are documented per adapter.</td>
<td>A generic S3 implementation, WireMock response, reverse-engineered payload or dev simulator does not establish official vendor support. An adapter without repeatable real-service evidence remains experimental and must not be advertised as supported.</td>
</tr>
</tbody>
</table>

<h2>1.0.0 · stable compatibility line</h2>

<p>1.0.0 freezes public contracts and commits to a supported upgrade path.</p>

<table class="comparison-table">
<thead><tr><th>Deliverables</th><th>Acceptance evidence</th><th>Completion boundary</th></tr></thead>
<tbody>
<tr>
<td>Public API and ABI freeze; supported migration and upgrade matrix; security audit; complete operator, integrator and compatibility documentation.</td>
<td>Binary and source compatibility gates compare every public artifact with the declared baseline; clean installs and every supported version-to-version upgrade pass across the published JDK, Spring Boot and database matrix; threat models, dependency findings, tenant/authorization/storage abuse cases and remediation are reviewed; every documented command and example is executable; the support and deprecation policy names exact versions and dates.</td>
<td>Do not label the project 1.0 while a public contract is still provisional, a supported upgrade path is untested, a high-severity security finding is unresolved, or compatibility claims exceed the verified matrix.</td>
</tr>
</tbody>
</table>

<aside class="callout" data-mark="1.0"><div><strong>Stability is a maintained obligation</strong><p>Passing the 1.0 gate freezes the declared contracts; later removals require the published deprecation window, migration guidance and compatibility evidence. A tag alone does not create stability.</p></div></aside>

<h2>FAQ</h2>

<p><strong>Can a planned item slip into a later version?</strong> Yes. If acceptance evidence is missing, the item stays open and may be reassigned to the next line.</p>

<p><strong>Who decides when an item is complete?</strong> The maintainers, based on reproducible evidence in the repository or the named real environment, not on a single green test or written intention.</p>

<h2>Next steps</h2>

<ul>
<li>Read the <a href="#/project/release-0-0-3">Release 0.0.3 contract</a> for the current scope and remote-consumption rule.</li>
<li>Retain <a href="#/project/release-0-0-2-development">Release 0.0.2</a> as the previous immutable upgrade boundary.</li>
<li>See <a href="#/project/contributing">Contributing</a> if you want to help close an open item.</li>
</ul>
