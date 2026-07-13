---
route: "project/release-0-0-2-development"
group: "project"
order: 4
locale: "zh"
nav: "0.0.2 开发中"
title: "0.0.2 开发线"
lead: "0.0.2-SNAPSHOT 是尚未发布的开发线；在发布门禁和远端制品验收完成前，稳定正式版仍是 ai.icen:*:0.0.1。"
format: "html"
---

<h2 data-step="01">工作流决策证据</h2>
<p>新审批和驳回不可变保存操作者 ID、可选安全显示名快照与 <code>decidedTime</code>。受权 <code>GET /fileweft/v1/documents/{id}/workflow-decisions</code> 同时要求 <code>document:audit</code> 与 <code>document:read</code>；普通 <code>/workflows</code> 历史继续隐藏身份。</p><aside class="callout" data-mark="?"><div><strong>遗留证据保持未知</strong><p>V026 不从受理人、当前用户目录或可选审计行推断操作者，既有完成任务保持 UNKNOWN。</p></div></aside>

<h2 data-step="02">身份契约</h2>
<p>宿主用户 ID 是区分大小写的不透明字符串，最多 256 个 UTF-16 code unit，并遵守固定安全字符契约。Long、Int、UUID 和外部目录标识必须由宿主使用永久稳定的格式转成字符串。</p>

<h2 data-step="03">V026 上线顺序</h2>
<ol><li>运行 <code>docs/sql/postgresql-v026-workflow-decision-evidence-preflight.sql</code>，不截断、不猜测地修复不安全宿主映射。</li><li>关闭审批命令，停止全部旧 API 节点并等待在途决策结束。</li><li>重跑预检，执行迁移，核验列和约束，再仅启动理解 V026 的新节点。</li><li>应用回滚也要保留 V026 列、约束和证据；不能缩窄身份列，也不能让旧二进制重新开放审批写入。</li></ol>
