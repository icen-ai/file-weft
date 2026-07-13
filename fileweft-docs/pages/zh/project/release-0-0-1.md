---
route: "project/release-0-0-1"
group: "project"
order: 3
locale: "zh"
nav: "0.0.1 发布说明"
title: "0.0.1 正式版"
lead: "首个公开版本建立 ai.icen 坐标、ai.icen.fw 包名、独立迁移命名空间，以及面向生产的文档、审批、交付、Doctor 和 Web 地基。"
format: "html"
---

<h2 data-step="01">已发布地基</h2>
<ul><li>Core → SPI → Domain → Application → Persistence → Starter → Adapter 模块链路。</li><li>Boot 2 与 Boot 3 运行时及 Web Starter。</li><li>PostgreSQL 持久化、本地与 S3 兼容存储链路。</li><li>持久 Outbox、任务租约、并行审批与多目标交付。</li><li>正式 v1 HTTP、目录 ACL、审计、Trace 与 Doctor。</li></ul>

<h2 data-step="02">兼容边界</h2>
<p>受支持命名空间是 <code>ai.icen:*</code>，JVM 包名为 <code>ai.icen.fw</code>。已撤回 <code>com.fileweft:*</code> 试推制品及其共享 Flyway 历史不会自动收养。</p>

<h2 data-step="03">许可证</h2>
<p>FileWeft 使用 Apache License 2.0 开源，版权主体为 icen.ai。权威条款以仓库 <code>LICENSE</code> 与 <code>NOTICE</code> 为准。</p>
