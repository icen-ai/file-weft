---
route: "guides/workflows-uploads"
group: "guides"
order: 2
locale: "zh"
nav: "工作流与上传"
title: "审批、断点字节与 Agent"
lead: "长任务必须显式、持久且具备围栏。审批路由、分片上传和 AI 处理都可以替换，但不能削弱事务边界。"
format: "html"
---

<h2 data-step="01">审批路由</h2>
<p><code>DocumentReviewRouteProvider</code> 在数据库事务外返回一个或多个审批任务。并行会签要求全部通过，任一驳回结束流程；最终事务在提交前重新检查文档状态。</p><p>尚未发布的 <code>0.0.2-SNAPSHOT</code> 开发线会为每个新决策不可变保存操作者 ID、可选安全显示名快照和决策时间。普通历史继续隐藏身份；受权 <code>/fileweft/v1/documents/{id}/workflow-decisions</code> 同时要求 <code>document:audit</code> 与 <code>document:read</code>。迁移不会猜测遗留完成任务的操作者，因此明确显示为 <code>UNKNOWN</code>。</p>

<h2 data-step="02">断点续传</h2>
<ol><li>使用调用方稳定幂等键启动。</li><li>上传编号分片并持久化每次确认。</li><li>重连后从服务端检查会话。</li><li>幂等完成以创建对象、资产与事件；明确放弃时终止。</li></ol><p>会话绑定可信租户和用户，底层 upload ID 与对象路径不会交给浏览器。</p>

<h2 data-step="03">Agent 任务</h2>
<p>AI 与诊断任务应进入持久化 <code>fw_task</code> handler。Worker 使用租约和幂等任务 ID，只有匹配任务在围栏下成功终态后，Agent 结果才可见。</p>
