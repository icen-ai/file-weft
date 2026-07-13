---
route: "extensions/connectors"
group: "extensions"
order: 2
locale: "zh"
nav: "连接器工程"
title: "连接不可靠的外部系统"
lead: "连接器将稳定的 FileWeft 交付契约转换为一个厂商集成。超时、重试、幂等、撤回和健康检查都是设计的一部分。"
format: "html"
---

<h2 data-step="01">必备行为</h2>
<ul><li>所有网络调用设置有界超时。</li><li>使用稳定目标或文档标识作为下游幂等键。</li><li>区分可重试与永久失败，且不泄漏凭据。</li><li>返回外部 ID，供后续显式撤回。</li><li>提供适合 Doctor 的只读健康检查。</li></ul>

<h2 data-step="02">多个目标</h2>
<p><code>DocumentDeliveryProfileProvider</code> 返回租户级档案，其中目标分为必达或可选。<code>DeliveryConnectorResolver</code> 将稳定 <code>connectorId</code> 解析到连接器实例，不向 SPI 泄漏 Spring 或厂商 SDK。</p>

<h2 data-step="03">契约测试</h2>
<p>集成测试应证明重复交付幂等、重复撤回安全、超时、重试分类、错误文本脱敏、健康检查，以及外部系统恢复后的收敛。</p>
