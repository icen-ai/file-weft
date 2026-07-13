---
route: "concepts/tenant-catalog"
group: "concepts"
order: 3
locale: "zh"
nav: "租户与文件树"
title: "租户与目录隔离"
lead: "租户来自可信上下文，宿主文件树则由 DocumentCatalogProvider 提供，并构成真实授权边界。"
format: "html"
---

<h2 data-step="01">租户贯穿所有层</h2>
<p>租户上下文约束数据库查询、存储路径、事件、任务、日志和缓存。请求参数永远不是可信租户来源，即使 ID 看似全局唯一，仓储也必须按租户过滤。</p>

<h2 data-step="02">目录归宿主所有</h2>
<p>浏览器只提交有界的不透明 <code>folderId</code>。FileWeft 使用可信租户、用户和操作上下文调用宿主目录，再将返回的 canonical ID 作为 <code>catalog.folder-id</code> 元数据保存。对象键不包含目录 ID。</p><aside class="callout" data-mark="ACL"><div><strong>不会静默降级</strong><p>目录模式开启后，缺少安全修改能力会返回功能不可用，不会降级到租户级写入路径。</p></div></aside>

<h2 data-step="03">稳定 ID</h2>
<p>目录 ID 可以来自数字、UUID 或外部组合键，但应转换为稳定字符串。目录改名或换父级时保持 canonical ID；确需换 ID 时执行显式目录移动。</p>
