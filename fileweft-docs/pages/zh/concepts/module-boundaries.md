---
route: "concepts/module-boundaries"
group: "concepts"
order: 1
locale: "zh"
nav: "模块边界"
title: "先守边界，再加功能"
lead: "FileWeft 将策略、编排与厂商集成拆分在不同模块，保证扩展不会侵蚀兼容性。"
format: "html"
---

<h2 data-step="01">依赖方向</h2>
<div class="architecture-stack"><div>starter → application → domain → core</div><div>adapter → spi</div></div><p>Core 只放标识、结果、错误、事件与上下文；Domain 放业务规则；Application 负责编排用例；Adapter 负责外部实现。</p>

<h2 data-step="02">禁止走捷径</h2>
<ul><li>Core 不依赖 Spring 或数据库。</li><li>Domain 不调用 MinIO、Dify 等厂商 SDK。</li><li>SPI 不暴露厂商类型。</li><li>Controller 只校验和转换，不访问存储或仓储。</li></ul>
