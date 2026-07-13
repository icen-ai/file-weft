---
route: "architecture/consistency"
group: "architecture"
order: 1
locale: "zh"
nav: "一致性模型"
title: "本地原子，显式收敛"
lead: "FileWeft 不承诺跨 PostgreSQL、对象存储和多个下游的分布式事务，而是保证本地状态原子，并让远端收敛过程可观测。"
format: "html"
---

<h2 data-step="01">事务 Outbox</h2>
<div class="architecture-stack"><div>业务事务 + Outbox 记录</div><div>提交本地事实</div><div>异步 Worker 带租约领取</div><div>连接器调用 + 围栏投影</div></div><p>不能在业务事务中调用下游连接器。连接器重试必须接受稳定幂等标识。</p>

<h2 data-step="02">存储补偿</h2>
<p>上传字节校验失败，或本地事务明确回滚且无持久引用时，FileWeft 删除对象进行补偿。提交结果未知时先对账并保留证据，避免误删已提交数据。</p>

<h2 data-step="03">锁顺序</h2>
<p>目录感知的幂等审批路径遵循 idempotency → document → asset → workflow。外部目录、审批路由和交付策略调用位于最终短事务之外。</p>
