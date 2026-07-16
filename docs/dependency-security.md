# FlowWeft 依赖安全发布门禁

FlowWeft 1.0 的依赖漏洞门禁以 JVM 正式发布物和 Console 生产锁文件分别生成的
CycloneDX runtime closure 为输入，而不是 Gradle 构建环境、测试依赖或开发依赖集合。
门禁脚本是 `.ci/scripts/verify-osv-release.mjs`，默认只读取：

- `build/reports/cyclonedx-release/bom.json`（默认 JVM 发布闭包）
- `gradle/osv-vex.json`

Console 使用 `flowweft-console/build/reports/cyclonedx/bom.json` 与空的、受控 Console
VEX 文件 `gradle/osv-vex-console.json` 单独调用同一脚本。两个生态的结果都是发布证据，
任一失败都必须停止发布。`releaseArtifactCheck` 会生成、查询并把 Console BOM 与它的
SHA-256 一并放入发布 ZIP。

因此调用顺序必须先生成并验证发布 SBOM，再执行 OSV 门禁：

```powershell
.\gradlew.bat verifySbom
node .ci/scripts/verify-osv-release.mjs
```

本脚本不替代 `verifySbom` 对模块库存、依赖图完整性、许可、坐标和 JSON/XML
一致性的校验。它只对该门禁已经确认过的发布 runtime closure 做漏洞判断。

## 阻断策略

门禁把 CycloneDX `components` 中的 Maven 与 npm purl 发送给 OSV。Maven purl 会先
解析并去除 qualifiers；npm purl 必须是规范的未限定形式，scope 的 `@` 必须写成
`%40`。两者都与组件的 `version` 做精确一致性校验，npm 还会核对组件名称与
scope/name。其他生态不会发送，并进入稳定 JSON 摘要的 `ignoredOtherComponents`
计数；不能假定本门禁覆盖这些生态。摘要分别给出 `mavenComponents` 与
`npmComponents`，因此缺失 Console 闭包不会被 Maven 数量掩盖。

策略不是“允许 N 个漏洞”，而是逐个判断 runtime component 与 advisory 的组合：

- CVSS v3 Base Score `9.0–10.0`（Critical）阻断；
- CVSS v3 Base Score `7.0–8.9`（High）阻断；CVSS v2 的 `7.0–10.0` 均为 High，
  同样阻断；
- `0.0–6.9` 不阻断，但保留汇总计数；
- 没有可用 CVSS、CVSS 数据损坏、出现未知 severity 类型，或记录含有当前脚本尚未
  计算的 CVSS v4 向量时，不得默认为低危：门禁关闭失败或以 `UNKNOWN` 阻断。即使
  同一记录另带较低的 v2/v3 分值，v4 也不能被该旧分值降级。

脚本按 [OSV schema](https://ossf.github.io/osv-schema/) 读取顶层或匹配 Maven/npm
package 的 `affected[].severity`，计算 CVSS v2/v3 Base Score，并采用最高的可用分值。
High/Critical 分界遵循
[FIRST CVSS v3.1 qualitative severity scale](https://www.first.org/cvss/v3-1/specification-document)。

## OSV 协议和网络边界

第一次查询使用官方
[`POST /v1/querybatch`](https://google.github.io/osv.dev/post-v1-querybatch/)；该接口只返回
advisory ID 与修改时间，所以脚本随后通过官方 `GET /v1/vulns/{id}` 取得完整 OSV 记录，
不能仅凭批量响应猜测严重度。批量结果顺序按 OSV 契约绑定输入，并处理有界分页。

CI 只需允许到 `https://api.osv.dev:443` 的 DNS 与 HTTPS 出站访问，不需要 OSV
令牌，也不应注入 Authorization header。CLI 会主动删除继承的
`NODE_TLS_REJECT_UNAUTHORIZED`，因此外层 shell 不能把门禁降级成不验证证书的请求；
私有 CA 只能走经评审的标准证书信任机制。固定保护边界如下：

- 每次 HTTP 请求 10 秒超时，最多 3 次尝试；
- 仅对 `408/425/429/500/502/503/504` 和传输失败重试，退避固定为 250/500 ms；
- 每批最多 128 个 Maven/npm purl，每个查询最多 4 页；
- 最多 4,096 个 SBOM component、200 个唯一 advisory、256 次 HTTP 尝试；
- BOM、VEX 与单个响应分别有 16 MiB、256 KiB 与 4 MiB 上限；
- 重定向、无效 UTF-8、错误响应结构、超过上限、超时和 OSV 不可用均 fail-closed。
- `modified`/`withdrawn` 必须是 OSV 规定的 UTC RFC3339 时间；未来的 `withdrawn`
  在到达该时刻前仍按活动漏洞处理，畸形时间不能让漏洞被跳过。

脚本只输出固定字段的 JSON 摘要。异常对象、HTTP 响应正文、请求 header、环境变量、
绝对文件路径和 purl qualifiers 不会写入摘要；因此失败日志不会回显代理凭据、签名 URL
或环境中的秘密。

## 限域 VEX 契约

`gradle/osv-vex.json` 是发布门禁使用的受控 VEX exception manifest。它借用
[CycloneDX VEX](https://cyclonedx.org/capabilities/vex/)“在具体产品上下文表达可利用性”的
语义，但不是可以替代正式 CycloneDX VEX/VDR 的通用交换文档。它故意只允许一个极小、
无通配符的本仓库契约：

```json
{
  "schemaVersion": 1,
  "exceptions": [
    {
      "advisory": "GHSA-5jmj-h7xm-6q6v",
      "purl": "pkg:maven/com.fasterxml.jackson.core/jackson-databind",
      "version": "2.21.5",
      "scope": "runtime-closure",
      "evidenceUrl": "https://github.com/FasterXML/jackson-databind/security/advisories/GHSA-5jmj-h7xm-6q6v",
      "owner": "FlowWeft Security",
      "expiresAt": "2026-08-15T00:00:00Z"
    }
  ]
}
```

校验规则全部 fail-closed：

- 顶层与 entry 出现未知字段即失败；
- `advisory + purl + version + scope` 重复即失败；
- purl 必须是无版本、无 qualifier、无通配符的规范 Maven package purl；
- `version` 必须精确存在于本次发布 BOM，错版本在访问 OSV 前失败；
- `scope` 只能是 `runtime-closure`；
- evidence 必须是无账号、无 query、无 fragment 的 HTTPS URL；
- owner 必填；到期时间必须是精确 UTC 秒格式，且不得超过当前时间 90 天；
- 到期时刻及之后立即失败，不能继续沿用；
- 豁免只匹配 OSV record 的精确 `id` 或 `aliases`，并同时匹配同一 purl 和版本。

当前唯一 entry 用于 OSV 数据仍可能误把 Jackson Databind `2.21.5` 判断为受影响的
短期对账窗口。FasterXML 维护者的
[官方 advisory](https://github.com/FasterXML/jackson-databind/security/advisories/GHSA-5jmj-h7xm-6q6v)
明确把 `2.21.5` 列为修复版本。该 entry 于 2026-08-15 到期；在此之前如果 OSV 已修正，
摘要会把它显示为未命中，安全负责人应删除它，而不是续期。若确需新增 entry，必须先取得
供应商/维护者证据、指定 owner，并接受同样的精确绑定和短时到期约束。

## 命令、退出码与测试

默认发布输入：

```powershell
node .ci/scripts/verify-osv-release.mjs
```

只为受控调试或 CI 工件路径覆盖输入（不会改变 OSV endpoint）：

```powershell
node .ci/scripts/verify-osv-release.mjs --bom <cyclonedx-release-bom.json> --vex <osv-vex.json>
```

Console 的等价显式命令是：

```powershell
npm --prefix flowweft-console run sbom
node .ci/scripts/verify-osv-release.mjs `
  --bom flowweft-console/build/reports/cyclonedx/bom.json `
  --vex gradle/osv-vex-console.json
```

- `0`：OSV 查询完成，且没有未豁免的 runtime High/Critical/Unknown finding；
- `1`：发现阻断项，或 BOM/VEX/OSV/网络/参数任一环节不能完成可信判断。

离线契约测试不会访问网络：

```powershell
node --test .ci/test/osv-release.test.mjs
```

测试覆盖 Maven/npm（含 scoped npm）High/Critical 阻断、Low/Medium 放行、API 失败、全响应超时、恶意 SBOM、
过期/错版本/通配/重复/未知字段 VEX、Jackson 精确误报豁免、未知分值阻断和日志脱敏。

## 已知边界

- 当前脚本验证和计算 CVSS v2/v3；CVSS v4-only 记录保守地按 Unknown 阻断，后续应引入
  经 FIRST 测试向量验证的 v4 计算器，而不是自行猜测分数。
- OSV 是发布时在线证据源；没有经过签名且有时效的离线镜像，因此 OSV 不可用时发布会
  停止。若未来引入镜像，必须同时冻结数据快照身份、更新时间和完整性证据。
- 本文件的窄 VEX manifest 是内部门禁输入；对外发布标准 VEX/VDR、制品签名与 SLSA
  provenance 仍由 1.0 供应链收口工作负责。
