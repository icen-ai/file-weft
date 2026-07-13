---
route: "concepts/lifecycle-delivery"
group: "concepts"
order: 2
locale: "zh"
nav: "生命周期与交付"
title: "生命周期是证据，不是一个布尔值"
lead: "草稿通过显式状态转换进入审批、发布和撤回；多个下游按目标、按发布代次分别跟踪。"
format: "html"
---

<h2 data-step="01">文档路径</h2>
<p>常见受控路径是 <code>DRAFT → PENDING_REVIEW → PUBLISHING → PUBLISHED</code>。返工使用 <code>PUBLISHED → OFFLINE → restore → DRAFT</code>，归档必须显式触发。状态、审计与 Outbox 在同一事务提交。</p>

<h2 data-step="02">下游部分失败</h2>
<p>必达目标失败会阻止已发布投影；可选目标失败时文档仍可保持发布。已成功目标不会自动回滚，运维人员只重试失败的交付或撤回目标。</p><aside class="callout" data-mark="N"><div><strong>代次围栏</strong><p>每次重新发布都会形成新的交付代次，旧代次的迟到结果不能覆盖当前状态。</p></div></aside>
