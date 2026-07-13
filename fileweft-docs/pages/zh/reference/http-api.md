---
route: "reference/http-api"
group: "reference"
order: 2
locale: "zh"
nav: "HTTP API v1"
title: "HTTP API v1"
lead: "正式接口统一位于 /fileweft/v1；除授权二进制下载外，响应使用稳定 JSON 外层。"
format: "html"
---

<h2 data-step="01">资源族</h2>
<ul><li><code>/fileweft/v1/documents</code> — 列表、创建、详情、改名、版本与生命周期。</li><li><code>/fileweft/v1/workflows/tasks</code> — 当前可信用户的审批待办与决策。</li><li><code>/fileweft/v1/documents/{id}/workflows</code> — 面向普通读取者的身份脱敏审批历史。</li><li><code>/fileweft/v1/documents/{id}/workflow-decisions</code> — 同时要求 <code>document:audit</code> 与 <code>document:read</code> 的不可变操作者证据（尚未发布的 <code>0.0.2-SNAPSHOT</code>）。</li><li><code>/fileweft/v1/documents/{id}/sync-status</code> — 安全交付投影与显式重试。</li><li><code>/fileweft/v1/documents/{id}/doctor</code> 和 <code>/fileweft/v1/doctor</code> — 文档与系统诊断。</li><li><code>/fileweft/v1/plugins</code> 与 <code>/fileweft/v1/health</code> — 安全清单与进程存活。</li></ul><aside class="callout" data-mark="ACL"><div><strong>决策证据是受权数据</strong><p>新决策只通过受权视图提供不可变操作者 ID、可选安全名称快照和决定时间。遗留完成任务返回 decisionEvidenceRecorded=false 和空证据字段；FileWeft 不会从受理人或可选审计行推断操作者。</p></div></aside>

<h2 data-step="02">稳定外层</h2>
<div class="code-block"><div class="code-label"><span>JSON</span></div><pre><code>{
  "code": "OK",
  "message": "OK",
  "data": {},
  "error": null,
  "traceId": "optional-host-trace-id"
}</code></pre></div>

<h2 data-step="03">幂等命令</h2>
<p>生命周期、审批、交付恢复与 Doctor 排队必须带且只带一个 <code>Idempotency-Key</code>。服务端只保存租户作用域 SHA-256 摘要，并绑定可信操作者、动作、资源和 typed-command 指纹。</p><aside class="callout" data-mark="KEY"><div><strong>重放仍需授权</strong><p>每次重放前都会重新执行认证、动作权限和目录可见性检查，幂等记录不是权限缓存。</p></div></aside>
