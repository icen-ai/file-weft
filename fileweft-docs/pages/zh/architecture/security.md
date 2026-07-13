---
route: "architecture/security"
group: "architecture"
order: 2
locale: "zh"
nav: "安全架构"
title: "所有边界都失败关闭"
lead: "只有完整安全边界存在时才装配能力。上下文缺失或 Provider 歧义会让操作不可用，而不是静默扩大访问范围。"
format: "html"
---

<h2 data-step="01">能力装配</h2>
<p>每个边界都要求唯一且无歧义的 Provider。多个目录或生命周期候选如果会改变安全语义，就不会通过猜测或 <code>@Primary</code> 选择。自定义持久化必须先提供真实修改锁与原子幂等 claim，才能开放受保护写入。</p>

<h2 data-step="02">公共投影</h2>
<p>HTTP DTO 不暴露存储 URL、对象键、连接器内部信息、Doctor 原始证据、租户标识或不安全诊断文本。审计视图提供稳定动作和操作者快照，不返回任意 details JSON。</p>

<h2 data-step="03">插件是可信代码</h2>
<aside class="callout warning" data-mark="!"><div><strong>进程内没有沙箱</strong><p>插件与宿主共享 JVM、权限和类路径，只能安装经过评审的制品。不可信扩展应放在独立进程，通过鉴权、限流、审计的协议接入。</p></div></aside>
