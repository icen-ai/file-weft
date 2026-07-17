# ADR 0006：Agent 协议适配与版本冻结

- 状态：已接受
- 日期：2026-07-15
- 适用范围：FlowWeft 1.0 可选 MCP/A2A adapter
- 关联：[ADR 0001](0001-flowweft-1.0-product-scope.md)、[ADR 0005](0005-agent-durable-persistence.md)

## 背景

FlowWeft Agent 的核心合同是 provider-neutral 的模型、工具、授权、审批、检索、预算和
可恢复运行 SPI。MCP 与 A2A 是边界协议，不是 Agent 内核。它们的版本节奏也不同：

- A2A 当前已发布稳定版 `1.0.0`，规范以 `a2a.proto` 为权威模型，并要求协议协商使用
  `Major.Minor`；
- MCP 当前稳定规范是 `2025-11-25`，其中 Tasks 仍标记为 experimental；
- MCP `2026-07-28` 在本决策日期仍只是 release candidate，且包含无会话 core、扩展轨、
  Tasks 和鉴权语义的破坏性变化，不能提前宣称正式兼容。

参考的权威来源：

- [A2A 1.0 specification](https://github.com/a2aproject/A2A/blob/main/docs/specification.md)
- [A2A normative protobuf](https://github.com/a2aproject/A2A/blob/main/specification/a2a.proto)
- [MCP 2025-11-25 specification](https://modelcontextprotocol.io/specification/2025-11-25/basic)
- [MCP 2025-11-25 Tasks](https://modelcontextprotocol.io/specification/2025-11-25/basic/utilities/tasks)
- [MCP 2026-07-28 RC notice](https://blog.modelcontextprotocol.io/posts/2026-07-28-release-candidate/)

## 决策

1. `flowweft-agent-api` 与 `flowweft-agent-runtime` 不依赖 MCP/A2A SDK、JSON-RPC、gRPC、
   HTTP 或协议 DTO。协议实现只能位于独立、可卸载 adapter。
2. FlowWeft 1.0 的 A2A adapter 目标是稳定 `1.0` protocol line。每次请求显式发送和校验
   `A2A-Version: 1.0`，只接受管理员 profile 中固定的 binding、endpoint 和 Agent Card
   identity/digest；不自动降级到 0.3。
3. FlowWeft 1.0 的 MCP adapter 目标是稳定 `2025-11-25`。Tasks 必须单独显式启用、经过
   双方 capability 和 tool-level `taskSupport` 协商；未启用或对端未声明时不得猜测支持。
4. `2026-07-28` MCP RC 只用于兼容设计和测试 fixture，不进入 1.0 支持声明。最终规范发布后，
   通过新的 protocol profile/codec 加法支持；旧 stable profile 不被静默替换。
5. 所有远端 descriptor、Agent Card、tool schema、capability、binding 和 server identity 生成
   canonical digest。发现结果变化时隔离对应 provider，重新审核后才可恢复；协议 annotations
   只作提示，不能授予权限或降低审批级别。
6. A2A 输入永远标记为 `A2A` 来源；MCP 工具返回保持 `TOOL`/`RETRIEVAL` 等不可信数据来源，
   不得提升为 system/developer 指令。远端记忆、summary、artifact 和 peer message 进入持久状态前
   必须经过内容安全策略、tenant/ACL、大小、MIME、digest 和 retention 校验。
7. 每个 MCP Task/A2A Task 必须绑定本地 tenant、principal、run、operation、authorization context、
   peer identity 与 protocol version。远端 task ID 只是对端 opaque ID，不能单独作为本地授权凭据；
   get/list/result/cancel/update 每次重新授权并防止跨主体枚举。
8. 网络只允许管理员创建的 profile；实现 DNS/IP 重绑定防护、scheme/port/私网策略、TLS、禁止
   自动 redirect、响应大小/时间/并发上限和凭据隔离。OAuth/OIDC token 不进入 prompt、事件、
   memento、日志、trace 或客户端 DTO。
9. 长任务映射到本地 operation ledger 和 reconciliation。轮询遵守对端 interval/backoff；取消是
   请求而不是外部副作用已停止的证明。超时或连接丢失且结果未知时进入 reconciliation，禁止把
   新 attempt 当作同一远端任务盲重放。
10. 协议 adapter 的 telemetry 只记录 provider/profile、协议版本、operation、状态、延迟、重试和
    token/usage 计数；prompt、completion、tool arguments/result 默认不采集。采用的 OpenTelemetry
    GenAI 语义仍未稳定时，属性放在版本化内部 namespace，并通过显式开关迁移。

## 兼容策略

每个 adapter 维护独立支持矩阵：协议版本、binding、auth profile、capability、schema codec、
已知对端实现和测试证据。reader 可以在明确 profile 下读取已声明的旧格式，writer 只发送该
profile 的当前格式。版本不匹配、未知 extension、缺失 capability 或 identity digest 漂移全部
失败关闭，不做“尽量兼容”的字段猜测。

协议升级不修改公共 Agent SPI；必要的协议字段保存在 adapter 私有 envelope 与 operation evidence
中。若新规范需要改变授权、幂等、任务生命周期或内容信任语义，先新增 ADR 和新 profile，再修改
支持声明。

## 验收

- A2A：1.0 三种 binding 的协议 fixture、版本拒绝、Agent Card digest 漂移、task 恢复/取消、
  跨 tenant/principal 隐藏、OAuth/TLS/SSRF/redirect 和恶意 Part 测试；
- MCP：2025-11-25 初始化/capability/tool schema、普通 call、显式 experimental Task、授权上下文
  隔离、cursor/TTL/轮询/取消、未知结果 reconciliation 和恶意 tool/metadata 测试；
- 两者都必须覆盖撤权、credential rotation、peer identity 变化、响应截断/超限、连接中断、
  descriptor rug-pull、memory/context poisoning 与审计脱敏；
- `2026-07-28` 只允许 compatibility fixture 证明“拒绝或显式 profile 处理”，正式发布前不得把
  RC fixture 计为稳定互操作证据。
