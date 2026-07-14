---
route: "project/release-0-0-3"
group: "project"
order: 5
locale: "zh"
nav: "0.0.3 发布合同"
title: "0.0.3 发布合同"
lead: "当前候选合同新增 metadata schema 与审批撤回，并继续以标签发布门禁、受保护主干和匿名远端证据作为可消费前提。"
format: "markdown"
---

## 合同内容

0.0.3 版本线新增 Java 友好的 metadata schema 契约、运行时校验、安全 schema 投影与 Metadata Doctor；同时新增待审流程的幂等撤回和 V029 提交者证据。发布库存增加到 19 个模块，三种受支持数据库都包含 V001–V029。既有公共构造器和 SPI 边界保持兼容；Agent 仍仅为兼容保留，默认不暴露。

## 坐标何时可消费

本文描述候选发布合同，不证明发布已经完成。只有受发布门禁约束的 `v0.0.3` 标签匹配受保护远端 `main` HEAD、精确提交的全部必需 CNB lane 成功，并且匿名消费者以全新隔离缓存回读全部 19 个坐标及 Boot 2、Boot 3、纯 SPI 消费者后，才可消费 `ai.icen:*:0.0.3`。源码、文档、标签名称、本地制品或部分绿灯都不能替代这些证据。

## 升级边界

应用 V029 前必须关闭 submit、approve、reject、withdraw 写入，停止旧节点并等待在途审批事务结束；迁移和校验完成后再启动 0.0.3 节点。应用回滚保留 V029 列与已记录的提交者证据。0.0.2 的 V001–V028 资源继续保持不可改写。

源码树和发布包都在 `docs/releases/0.0.3.md` 提供完整合同；[0.0.2 发布说明](#/project/release-0-0-2-development) 继续作为上一条不可改写边界。
