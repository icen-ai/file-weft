---
route: "operations/deployment"
group: "operations"
order: 1
locale: "zh"
nav: "生产部署"
title: "按运行角色部署"
lead: "同一份已验证制品通过不同配置承担 API、Worker 与迁移 Job。共享数据库和对象存储，但不应无差别共享权限。"
format: "html"
---

<h2 data-step="01">推荐拓扑</h2>
<div class="architecture-stack"><div>Migration Job · DDL 身份 · migrate</div><div>API 节点 · 业务读写身份 · validate</div><div>Outbox Worker · 队列与连接器身份 · validate</div><div>Task Worker · 任务专用身份 · validate</div></div>

<h2 data-step="02">发布顺序</h2>
<ol><li>备份并实际验证恢复。</li><li>由唯一迁移所有者运行受控迁移 Job。</li><li>以 <code>validate</code> 启动 API 和 Worker。</li><li>观察 health、Doctor、Outbox ready age 和租约恢复。</li><li>校验通过后再开放流量。</li></ol>

<h2 data-step="03">凭据边界</h2>
<p>长期运行的 API 或 Worker 不应持有建 schema 权限。连接器凭据只交给实际调用该连接器的 Worker。浏览器不会获得对象存储凭据或下游密钥。</p>
