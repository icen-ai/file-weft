---
route: "getting-started/first-integration"
group: "getting-started"
order: 3
locale: "zh"
nav: "首次接入"
title: "装配可信宿主"
lead: "生产宿主必须提供可信租户、身份与授权上下文、共享持久化 StorageAdapter，以及明确的迁移策略。"
format: "html"
---

<h2 data-step="01">提供可信上下文</h2>
<p>从宿主已经认证的数据实现 <code>TenantProvider</code>、<code>UserRealmProvider</code> 与 <code>AuthorizationProvider</code>。Controller 不能把租户 ID、用户 ID、角色或权限结果作为业务参数接收。</p><aside class="callout" data-mark="ID"><div><strong>用户 ID 是不透明安全字符串</strong><p>Long、Int、UUID 与外部目录 ID 必须由宿主永久使用同一种格式转为字符串。ID 区分大小写，最多 256 个 UTF-16 code unit，首尾无 Unicode 空白，并排除控制字符和 FileWeft 固定拒绝的 format 字符。</p></div></aside>

<h2 data-step="02">选择存储和数据库所有权</h2>
<p>多节点部署需要共享、持久的 <code>StorageAdapter</code>。使用 PostgreSQL 时，DataSource 当前 schema 与 FileWeft schema 安全断言必须一致。</p><div class="code-block"><div class="code-label"><span>YAML</span></div><pre><code>spring:
  datasource:
    url: jdbc:postgresql://db:5432/app?currentSchema=fileweft

fileweft:
  persistence:
    migration-mode: validate
    schema: fileweft
    create-schema: false</code></pre></div>

<h2 data-step="03">拆分运行角色</h2>
<p>API 节点不消费队列，独立 Worker 使用相同数据库和存储，仅开启所需处理器。这样 HTTP 延迟与后台租约互不干扰。</p>
