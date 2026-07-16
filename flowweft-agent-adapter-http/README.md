# FlowWeft Agent HTTP protocol adapters

本模块只实现被管理员 profile 固定版本、binding、endpoint、Agent Card digest、TLS identity
和 capability 后的协议编解码。远端文本、文件 URL、metadata、message、artifact 和错误信息始终是
`A2A` 来源的不可信数据；codec 不获取 URL、不执行指令，也不把远端字段转换成授权事实。

## A2A 1.0 JSON-RPC compatibility matrix

权威输入是 A2A 1.0 的
[specification](https://github.com/a2aproject/A2A/blob/main/docs/specification.md) 和
[normative protobuf](https://github.com/a2aproject/A2A/blob/main/specification/a2a.proto)。写端固定
`A2A-Version: 1.0` 和 PascalCase JSON-RPC 方法名，不降级到 0.3。规范要求客户端发送该请求头，
没有要求服务端回显；因此响应缺少该头可以接受，但一旦回显就必须精确为 `1.0`。

| Operation | FlowWeft 1.0 JSON-RPC/HTTP | 安全边界 |
| --- | --- | --- |
| Agent Card admission | canonical `INITIALIZE` | `AgentRemotePeerProfile` 与每次调用前的 `AgentRemotePeerObservation` 固定 card descriptor、capabilities、security scheme、binding 和 TLS identity digest |
| Public Agent Card fetch | observation provider 负责，不由 JSON-RPC codec 猜 URL | A2A 公共 card discovery 是独立 HTTP GET，不是 JSON-RPC 方法；本模块也不会把 `GetExtendedAgentCard` 冒充 public card |
| `SendMessage` | 支持 | 保留既有 ABI；message ID/digest、可选远端 tenant alias 和 context ID 进入授权 binding |
| `GetTask` | 支持受限安全 profile | canonical capability `remote.a2a.task.get`；必须绑定当前 tenant/principal、父 `SendMessage`、远端 task、tenant alias、context、fresh auth 和一次性执行上下文 |
| `ListTasks` | 支持受限安全 profile | canonical capability `remote.a2a.task.list`；只列出当前主体已绑定父 `SendMessage` 的一个精确 context，不提供全 peer 枚举 |
| `CancelTask` | 支持 | 保留既有 ABI；task 必须来自同主体、同 run、同 peer 的父 `SendMessage` |
| streaming / subscribe / push config / extended card | 未支持 | 不猜 method、字段或重试语义；需要独立 additive slice |

这里的 `INITIALIZE` 是 FlowWeft 的 Agent Card 审核/观测合同，不是一个虚构的 A2A JSON-RPC
方法。HTTP runtime 在发出每个 A2A operation frame 前都要求 fresh observation 与管理员审核快照
完全一致。公共 card 的网络获取由 host 提供的 observation provider 完成，因为 profile 只批准精确
资源 URI，不能从 RPC endpoint 推导另一个 URL。

## Read-only request profile

`GetTask` 的 invocation payload 是完整的 ProtoJSON `GetTaskRequest` params 对象，并显式包含：

```json
{"tenant":"remote-tenant-alias","id":"task-id","historyLength":0}
```

`ListTasks` 的 invocation payload 是完整的 ProtoJSON `ListTasksRequest` params 对象。FlowWeft
要求 `tenant`、`contextId`、`pageSize`、`historyLength`、`includeArtifacts` 显式存在；其余正式字段
`status`、`pageToken`、`statusTimestampAfter` 可选。未知字段失败关闭。

```json
{
  "tenant": "remote-tenant-alias",
  "contextId": "server-issued-owned-context",
  "pageSize": 25,
  "historyLength": 0,
  "includeArtifacts": false
}
```

这是完整 A2A `ListTasks` 的有意受限子集：A2A 的 `contextId` 是 server-generated opaque ID，
调用方不能自行编造。FlowWeft 只接受已经由同 tenant、principal、run、peer 和 profile 的父
`SendMessage` 绑定的 context。若宿主需要管理员级跨 context 检索，应新增明确授权的独立 profile，
不能移除这个限制。

分页和披露规则：

- request `pageSize` 为最大值，范围 `1..100`；response `pageSize` 可以更小，但不能更大；
- operation 的 `a2aMaximumVisibleTasks` 必须为 `1..1_000_000`，同时限制 request `pageSize`
  和 response `totalSize`；`0` 非法，避免被误读成无限制；
- `nextPageToken` 必须存在，末页必须为空字符串；cursor 只作为不可信 opaque data 返回；
- `includeArtifacts=false` 时每个 Task 必须完全省略 `artifacts`；
- List 响应的 Task、status message、history、part 和 artifact metadata 全部拒绝，避免远端把
  未授权 tenant/principal 信息塞进自由 metadata；
- 每个 Task 的 `contextId` 必须等于授权 context，task ID 不得重复；`totalSize` 只按 A2A 规范
  解释为认证调用者在该 tenant/context/filter 下可见集合的数量，并仍受本地上限约束。

远端错误按 A2A 1.0 的 `-32001..-32009` 和 JSON-RPC 标准错误分类成稳定安全码，远端 message
不会进入异常文本。传输成功但响应身份、schema、context、分页或披露边界不确定时不返回 payload，
也绝不把 `OUTCOME_UNKNOWN` 当成只读成功。
