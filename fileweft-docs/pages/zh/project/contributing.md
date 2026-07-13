---
route: "project/contributing"
group: "project"
order: 1
locale: "zh"
nav: "参与贡献"
title: "贡献功能，不侵蚀地基"
lead: "只有职责确实属于对应层时，改动才沿 Core、SPI、Domain、Application、Persistence、Starter 与 Adapter 推进；测试也遵守相同边界。"
format: "html"
---

<h2 data-step="01">编码之前</h2>
<ol><li>阅读仓库 AI 实施手册及直接相关扩展材料。</li><li>确认职责所属模块和现有 SPI。</li><li>架构变化需说明兼容与迁移影响。</li><li>设计运维人员如何诊断失败。</li></ol>

<h2 data-step="02">分层测试</h2>
<table class="comparison-table"><thead><tr><th>层</th><th>必要证据</th></tr></thead><tbody><tr><td>Core / Domain</td><td>聚焦的单元测试与不变量</td></tr><tr><td>SPI</td><td>契约测试与 Java 友好用法</td></tr><tr><td>Adapter / Persistence</td><td>真实集成测试</td></tr><tr><td>Starter / Web</td><td>Context 与 Boot 2/3 契约测试</td></tr><tr><td>Release</td><td>兼容矩阵、Compose 验收、浏览器 E2E 与 SBOM</td></tr></tbody></table>

<h2 data-step="03">变更卫生</h2>
<p>使用 UTF-8、小而专注的类和显式依赖；保持租户过滤与锁顺序；不改写无关用户变更；用动作型提交信息记录完整里程碑。</p>
