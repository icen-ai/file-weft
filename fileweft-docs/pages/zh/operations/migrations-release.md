---
route: "operations/migrations-release"
group: "operations"
order: 3
locale: "zh"
nav: "迁移与发布"
title: "审慎迁移与发布"
lead: "FileWeft 使用独立 Flyway 资源路径和历史表。发布门禁覆盖兼容矩阵、真实基础设施链路、SBOM 和可复现依赖状态。"
format: "html"
---

<h2 data-step="01">迁移命名空间</h2>
<p>迁移资源只位于 <code>classpath:ai/icen/fw/db/migration</code>，历史只写入 <code>fileweft_schema_history</code>。不能把资源追加到宿主 Flyway locations，也不能合并进 <code>flyway_schema_history</code>。</p>

<h2 data-step="02">早期试推数据库</h2>
<aside class="callout warning" data-mark="!"><div><strong>不会自动收养</strong><p>运行过 com.fileweft 试推制品的数据库必须停机、备份并由 DBA 核验。不能通过 baseline、repair、复制或删除 history 行绕过所有权分析。</p></div></aside>

<h2 data-step="03">发布门禁</h2>
<div class="code-block"><div class="code-label"><span>PowerShell</span></div><pre><code>.\gradlew.bat check --no-daemon
.\gradlew.bat compatibilityCheck --no-daemon
.\gradlew.bat verifySbom --no-daemon</code></pre></div><p>正式发布流水线还会在同一套健康开发编排上开启 PostgreSQL、RustFS、Dev API 与浏览器验收。</p>
