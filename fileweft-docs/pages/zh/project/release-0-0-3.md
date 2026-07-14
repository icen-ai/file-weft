---
route: "project/release-0-0-3"
group: "project"
order: 5
locale: "zh"
nav: "0.0.3 发布说明"
title: "0.0.3 发布说明"
lead: "稳定版新增 metadata schema 与审批撤回；精确标签、12/12 CNB 门禁和 19/19 匿名制品回读均已闭环。"
format: "markdown"
---

## 发布内容

0.0.3 版本线新增 Java 友好的 metadata schema 契约、运行时校验、安全 schema 投影与 Metadata Doctor；同时新增待审流程的幂等撤回和 V029 提交者证据。发布库存增加到 19 个模块，三种受支持数据库都包含 V001–V029。既有公共构造器和 SPI 边界保持兼容；Agent 仍仅为兼容保留，默认不暴露。

## 稳定发布证据

`v0.0.3` 已于 2026-07-14 稳定发布，固定提交为 `dbf2a50fbca41e2ac5b5cf18bb44f9287c153637`。CNB 构建 `cnb-cl8-1jtgih45j` 的 `tag_push` 流水线 12/12 成功且 0 失败；发布器完成身份复核、19 模块上传、令牌销毁和冷缓存消费者验证。独立匿名回读还确认 19/19 个 POM、主 JAR 与 `.sha256` 均可访问且校验一致，因此 `ai.icen:*:0.0.3` 现在可以稳定消费。

## 升级边界

应用 V029 前必须关闭 submit、approve、reject、withdraw 写入，停止旧节点并等待在途审批事务结束；迁移和校验完成后再启动 0.0.3 节点。应用回滚保留 V029 列与已记录的提交者证据。0.0.2 的 V001–V028 资源继续保持不可改写。

源码树和发布包都在 `docs/releases/0.0.3.md` 提供完整说明；[CNB Release](https://cnb.cool/china.ai/file-weft/-/releases/tag/v0.0.3) 记录公开发布页面，[0.0.2 发布说明](#/project/release-0-0-2-development) 继续作为上一条不可改写边界。
