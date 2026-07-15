# FlowWeft Console

`flowweft-console` 是 FlowWeft 1.0 的独立 Next.js 产品控制台与同源 BFF
边界。它与 `fileweft-dev/web` 的验收实验室完全分离，不复用 Dev Session、固定
账号、`/api/**` 开发接口或浏览器 Bearer Token 模型。

## 当前交付状态

这是 FW10-040/FW10-041 的增量实现，认证地基可用，但还不是完成业务接线的产品：

- 已有 Next.js App Router、TypeScript、中文/英文路由与响应式产品 Shell；
- 已有 dashboard、documents、approvals、sync、Doctor、audit、Agent、工具确认、
  retrieval、evaluations 和 settings 的显式页面；
- 已有 request nonce CSP、安全响应头、`server-only` DAL/配置边界与类型化 BFF DTO；
- 页面不注入 mock 文档、假指标、演示身份或假 Agent 回复；
- 已有显式 `/api/bff/source-profiles` 与服务端 Source Profile registry；只返回 opaque ID、
  展示名、认证模式和降级状态，不返回 origin；
- 已有 OIDC Authorization Code + S256 PKCE、一次性 state/nonce、固定 redirect URI、
  DNS 解析后地址固定与私网 SSRF 策略、JWT/JWKS/issuer/audience/nonce/`at_hash` 校验；
- 已有可选宿主账号兼容交换：密码只经同源表单进入 BFF，随后只发送到管理员固定、
  DNS pinning 且不跟随重定向的交换端点；返回身份与租户绑定后只保存 access token，
  并有有界尝试限制；
- 已有可选共享 Redis session store：OIDC 一次性事务与 session 使用原子容量/TTL，
  AES-256-GCM 信封加密并支持密钥轮换；未配置 Redis 时保留 bounded process-local
  单副本 adapter；opaque Secure/HttpOnly/SameSite cookie、同源 mutation、查询与注销
  均不把 access token 暴露给浏览器；
- 已有第一条真实业务 DAL：文档页以服务端 Bearer session 调用固定
  `/fileweft/v1/documents`，严格校验响应并渲染实时只读分页；上游不可用时回退为无假数据
  的能力占位，不泄漏 URL、token、trace 或错误正文；
- 已有实时系统 Doctor DAL：固定调用 `/fileweft/v1/doctor`，校验检查项唯一性与综合状态，
  只渲染公共脱敏 reason/repair，不把拓扑、原始 evidence 或授权差异带到页面；
- 已有当前用户审批任务箱 DAL：固定调用 `/fileweft/v1/workflows/tasks`，只呈现可办理任务、
  最小文档上下文与 opaque cursor，不返回其他办理人标识或审批评论；
- Redis adapter 已用两个独立 Console store 实例和真实 Redis 验证一次性消费、跨实例
  读取/撤销、全局容量和密文不可见；生产应使用 Redis TLS。未配置 Redis 时，生产启用
  认证仍必须显式确认单副本，进程重启会登出。登录尝试限制仍是进程内纵深防御，宿主
  必须实施分布式账号/IP 限速、锁定与审计。宿主交换协议尚未取得真实宿主 E2E 证据；
  refresh 与其余业务 DAL 仍在后续接线范围。

完整能力状态仍以仓库根目录的 `docs/flowweft-1.0-delivery-ledger.md` 为准。

## 信任边界

后续实现必须保持以下路径：

```text
Browser
   │ same-origin only; Secure/HttpOnly/SameSite session + CSRF
   ▼
Console BFF / explicit server DAL
   │ administrator-approved opaque Source Profile
   ▼
FlowWeft / host API
```

禁止新增通用 `app/api/[...path]` 透明代理。每个 BFF 读写必须是独立、类型化的
DAL 方法或 Route Handler，并完成 session、tenant、CSRF、授权、输入 schema、
响应 schema 与大小校验。浏览器不得接收：

- FlowWeft service token 或宿主 refresh token；
- Source Profile 的真实 endpoint 或服务端凭据；
- 模型、OSS、Dify、MCP/A2A secret；
- 数据库连接信息、对象 storage key 或未经授权的内部错误。

来源只通过管理员配置的 opaque profile ID 选择。租户别名仅用于展示，可信 tenant
必须由服务端身份或 token 推导。

## 页面

| 路由 | 当前状态 |
| --- | --- |
| `/{zh,en}` | 需要有效服务端 session；当前仍是无假数据 dashboard 与能力织图 |
| `/{zh,en}/login` | 展示安全 Source Profile 摘要；完整配置的 profile 可发起 OIDC + PKCE 或宿主令牌交换 |
| `/{zh,en}/documents` | 当前会话的实时只读文档分页；不可用时安全降级为状态页 |
| `/{zh,en}/approvals` | 当前用户实时、只读审批任务箱；不可用时安全降级为状态页 |
| `/{zh,en}/sync` | 同步状态页 |
| `/{zh,en}/doctor` | 当前租户实时、脱敏的系统 Doctor；不可用时安全降级为状态页 |
| `/{zh,en}/audit` | 审计状态页 |
| `/{zh,en}/agent` | Agent 对话状态页 |
| `/{zh,en}/tool-approvals` | 工具确认状态页 |
| `/{zh,en}/retrieval` | 检索证据状态页 |
| `/{zh,en}/evaluations` | 评测状态页 |
| `/{zh,en}/settings` | 来源、集成、安全、租户和运维设置状态页 |
| `/api/health` | Console 进程存活投影；明确返回 `backendConnected: false` |
| `/api/bff/source-profiles` | 显式、无缓存的安全来源摘要；不包含 origin/secret |
| `/api/auth/oidc/start` | 同源 POST；按 opaque profile ID 创建一次性 PKCE 授权事务 |
| `/api/auth/oidc/callback` | 固定 callback；服务端换码、验签并创建 opaque session cookie |
| `/api/auth/host-exchange` | 同源 POST；一次性提交 tenant alias/账号/密码，向固定宿主端点交换服务端 session |
| `/api/auth/session` | 只返回安全 session 投影，不返回 access token |
| `/api/auth/logout` | 同源 POST；撤销服务端 session 并清除 cookie |

## 配置

复制 `.env.example` 后按部署环境设置：

| 变量 | 含义 |
| --- | --- |
| `FLOWWEFT_CONSOLE_DEFAULT_LOCALE` | `zh` 或 `en`；当前根路径仍显式进入中文路由 |
| `FLOWWEFT_CONSOLE_SOURCE_PROFILE_IDS` | 逗号分隔的管理员批准 opaque ID；不接受 URL |
| `FLOWWEFT_CONSOLE_SOURCE_PROFILES_JSON` | 可选 version 1 服务端定义；ID 必须与 allowlist 完全一致，生产 origin 必须是无 path/query/credential 的 HTTPS origin |
| `FLOWWEFT_CONSOLE_PUBLIC_ORIGIN` | 启用认证时必填的 Console 精确 origin；不从请求 Host/forwarded header 推断 |
| `FLOWWEFT_CONSOLE_SESSION_COOKIE_NAME` | 生产环境必须使用 `__Host-` 前缀 |
| `FLOWWEFT_CONSOLE_SECURE_COOKIES` | 生产环境必须为 `true` |
| `FLOWWEFT_CONSOLE_SESSION_TTL_SECONDS` | BFF session 上限；仍受 access token 与 ID token 到期时间限制 |
| `FLOWWEFT_CONSOLE_AUTHORIZATION_TTL_SECONDS` | 一次性 state/PKCE 授权事务 TTL，60–600 秒 |
| `FLOWWEFT_CONSOLE_MAX_PENDING_AUTHORIZATIONS` / `FLOWWEFT_CONSOLE_MAX_SESSIONS` | Redis 或 process-local store 的全局/本地硬容量上限 |
| `FLOWWEFT_CONSOLE_REDIS_URL` | 可选共享 session Redis URL；生产必须为 `rediss://`，不得包含 query/fragment |
| `FLOWWEFT_CONSOLE_REDIS_KEY_PREFIX` | Redis key 命名空间；同一部署的所有副本必须一致，不同环境必须隔离 |
| `FLOWWEFT_CONSOLE_SESSION_ENCRYPTION_KEYS_JSON` | AES-256-GCM keyring JSON；第一把是写入密钥，其余只用于读取轮换期旧 session，最多四把 |
| `FLOWWEFT_CONSOLE_SINGLE_REPLICA_SESSION_STORE_ACK` | 只在未配置 Redis 时生效；生产认证必须显式为 `true`，表示接受单副本和重启登出边界 |

配置 schema 在 `src/server/config/schema.ts`，实际读取入口在导入了 `server-only` 的
`src/server/config/index.ts`。Source Profile origin 只存在于 server-only registry，
不会进入浏览器 DTO；不能通过新增 `NEXT_PUBLIC_*` 变量绕过此边界。OIDC profile
显式提供 issuer、authorization/token/JWKS endpoint、client ID、scope、tenant alias
claim 和签名算法 allowlist；三个 endpoint 必须与 issuer 同 origin，生产只允许 HTTPS。
`allowPrivateNetwork` 是管理员对内部 IdP 的显式授权，不是用户输入；服务端换码/JWKS
请求会先解析全部地址、拒绝混合公私网答案并把已核验地址固定到 socket，且从不跟随
redirect。当前只支持无 client secret 的 Authorization Code + PKCE，不要把 secret 放进
JSON 配置。

共享 store 必须同时配置 Redis URL 与 keyring；只配置其中一个会启动失败。生产环境使用
受信 CA 的 `rediss://`，Redis 凭据与 keyring 从部署平台 secret 注入，不提交到仓库。可用
下面的 PowerShell 生成一把 32 字节 key，并把输出填入 JSON 的 `key` 字段：

```powershell
$bytes = New-Object byte[] 32
$rng = [System.Security.Cryptography.RandomNumberGenerator]::Create()
try { $rng.GetBytes($bytes) } finally { $rng.Dispose() }
[Convert]::ToBase64String($bytes).TrimEnd('=').Replace('+','-').Replace('/','_')
```

轮换时先把新 key 放到数组首位并保留旧 key，等待最长 session TTL 过去后再移除旧 key；
不要原地修改已有 key ID 对应的 key。Redis 数据即使被读出也只有带 AAD 的 AES-GCM
信封，解密失败会按共享认证存储不可用处理，而不会降级到未加密或本地 session。

可选 `api.allowPrivateNetwork` 只授权该固定 Source Profile 的正式 BFF DAL 访问内部网络；
它不会放宽用户提供 URL，因为浏览器从未提交 endpoint。未配置时，后端 API 解析出任一
私网、回环、link-local、文档网段或公私混合地址都会失败关闭。

宿主兼容配置只声明固定 `endpointPath` 与是否允许私网，路径必须是无 query、fragment、
百分号和 `.`/`..` 段的规范绝对路径。BFF 以
`application/x-www-form-urlencoded` 发送固定字段 `grant_type`、`tenant_alias`、
`username`、`password`。宿主必须返回且只能返回：

```json
{
  "access_token": "opaque-access-token",
  "token_type": "Bearer",
  "expires_in": 1800,
  "subject_id": "host-user-id",
  "subject_display_name": "Alice",
  "tenant_alias": "Tianjin"
}
```

返回 tenant alias 必须与用户提交值完全一致；token 只进入 server-side session，密码不
写入 session、cookie、日志或响应。宿主仍必须做分布式账号/IP 限速、锁定和审计；Console
的 process-local 尝试限制只是纵深防御，不能替代宿主策略。

## 本地验证

只运行本模块最窄命令，不需要 Gradle：

```powershell
npm.cmd ci --prefix flowweft-console
npm.cmd run typecheck --prefix flowweft-console
npm.cmd test --prefix flowweft-console
npm.cmd run build --prefix flowweft-console
```

真实 Redis contract 额外按需运行；普通 `npm test` 在未设置 URL 时会明确跳过它：

```powershell
docker compose -f .docker/docker-compose.dev.yaml --profile console up -d --wait redis
$env:FLOWWEFT_CONSOLE_TEST_REDIS_URL='redis://127.0.0.1:6379'
npm.cmd run test:redis --prefix flowweft-console
Remove-Item Env:FLOWWEFT_CONSOLE_TEST_REDIS_URL
docker compose -f .docker/docker-compose.dev.yaml --profile console stop redis
```

组件/静态测试覆盖双语信息架构、必需路由、配置 fail-closed、CSP、状态组件、无通用
API catch-all、凭据不进入 URL/token DTO、PKCE RFC 向量、一次性/过期/容量 session
store、OIDC 签名与 claim 绑定、宿主交换/租户防替换/尝试上限、私网地址策略和
`server-only` 边界。

## 容器

`Dockerfile` 使用 Next.js standalone 输出和非 root 用户：

```powershell
docker build -t flowweft-console:foundation flowweft-console
docker run --rm -p 3000:3000 flowweft-console:foundation
```

生产环境必须在支持 TLS 的宿主反向代理之后以同源路径部署。CSP 通过 `src/proxy.ts`
为每个页面请求生成 nonce，因此页面采用动态渲染并设置 `Cache-Control: no-store`。
不要在反向代理中删除 CSP、HSTS、frame、referrer 或 MIME 防护头。

## 下一阶段接线顺序

1. 在已接通的文档与系统 Doctor 只读 contract 上补 capability discovery，并冻结其余 Console↔BFF contract；
2. 为共享 Redis session 增加正式部署/故障演练证据，并设计 refresh/token revocation；
3. 让宿主实现已冻结的临时密码交换端点并取得真实 E2E，再扩展其余显式业务 DAL；
4. 在已接通的文档、Doctor、审批任务只读投影后，继续接 dashboard/目录/同步/审计，再接带 CSRF 与新鲜授权的 mutation；
5. Agent、工具确认和检索页只在对应 1.0 Runtime contract 交付后接线；
6. 增加真实 IdP + Playwright、键盘/读屏、双语、session/source/tenant 隔离和浏览器安全头验收。
