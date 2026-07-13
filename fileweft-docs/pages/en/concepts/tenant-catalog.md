---
route: "concepts/tenant-catalog"
group: "concepts"
order: 3
locale: "en"
nav: "Tenancy & file trees"
title: "Tenant and catalog isolation"
lead: "Tenant scope is trusted context, while the host's file tree is an authorization surface supplied through DocumentCatalogProvider."
format: "html"
---

<h2 data-step="01">Tenant everywhere</h2>
<p>Tenant context constrains database reads, storage paths, events, tasks, logs and caches. A request parameter is never a trusted tenant source. Repository implementations must filter by tenant even when an ID appears globally unique.</p>

<h2 data-step="02">Folders remain host-owned</h2>
<p>The browser submits only a bounded opaque <code>folderId</code>. FileWeft asks the host catalog with trusted tenant, user and operation context, then stores the returned canonical ID as <code>catalog.folder-id</code> metadata. Object keys do not contain folder IDs.</p><aside class="callout" data-mark="ACL"><div><strong>No silent fallback</strong><p>Once catalog mode is enabled, missing safe mutation capability returns feature unavailable. It never falls back to a tenant-wide write path.</p></div></aside>

<h2 data-step="03">Stable IDs</h2>
<p>Folder IDs may begin as numbers, UUIDs or composite external keys, but are converted to stable strings. Rename or re-parent a folder without changing its canonical ID. A real ID change requires an explicit catalog move.</p>
