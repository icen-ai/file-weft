---
route: "project/security"
group: "project"
order: 2
locale: "zh"
nav: "安全"
title: "私密报告安全问题"
lead: "不要在公开 Issue 披露疑似漏洞。将最小、可复现报告发送到 support@icen.ai，并为协调修复预留时间。"
format: "html"
---

<h2 data-step="01">报告内容</h2>
<ul><li>受影响的 FileWeft 版本与模块。</li><li>部署前提和所需权限。</li><li>最小复现步骤或概念验证。</li><li>已观察影响和已知缓解方式。</li><li>可用于协调跟进的安全联系方式。</li></ul>

<h2 data-step="02">保护敏感证据</h2>
<p>移除生产凭据、租户数据、用户信息、对象键与私有端点，尽量使用合成夹具。不能在 Issue、日志片段或浏览器录屏中发送有效访问令牌。</p><aside class="callout" data-mark="@"><div><strong>安全联系方式</strong><p>发送邮件至 support@icen.ai。一般使用问题也可联系该地址，但疑似漏洞应明确标记为私密、安全敏感。</p></div></aside>
