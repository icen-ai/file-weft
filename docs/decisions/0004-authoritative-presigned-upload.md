# ADR 0004：预签名直传的权威边界

- 状态：已接受
- 日期：2026-07-15
- 适用范围：FlowWeft 1.0 的可选对象存储直传能力
- 关联：[ADR 0001](0001-flowweft-1.0-product-scope.md)

## 为什么现有扩展点不足

已发布的 `StorageAdapter` 负责服务端流式上传、下载、删除和分片，但不能表达：

- 对一个不可变租户对象键签发短期 HTTP PUT；
- 客户端必须原样发送的已签名 Content-Type、校验和与元数据；
- URL 只返回给客户端、对象位置只保存在服务端的双重边界；
- 上传后通过服务端 HEAD 与条件读取核验对象版本、长度、类型、校验和和实际内容哈希；
- 并发 finalize、崩溃重试和跨租户调用所需的权威 CAS 会话。

把这些字段加入 `StorageAdapter` 的已有方法会破坏已发布 ABI；把位置或无状态签名
令牌交给浏览器再原样信回，则允许绕过 grant 的对象绑定。

## 决策

1. 在 storage SPI 增加可选 `PresignedUploadStorageAdapter`，不修改
   `StorageAdapter`。模型保持厂商中立、Java/JDK 8 友好，不暴露 OSS SDK 类型。
2. Application 先生成会话 ID 和不可变声明。Adapter 的签名动作不得产生远程写；
   Application 必须在 URL 对客户端可见之前，把 grant 返回的 exact 声明与
   `StorageObjectLocation` 原子写入权威会话。面向客户端的结果只包含会话 ID、PUT URL、
   必需请求头和过期时间。
3. finalize API 只接受当前租户中的会话 ID。Application 通过 CAS 抢占会话，并从
   持久化声明构造 `PresignedUploadFinalizeRequest`；不接受客户端提交 location、tenant、
   hash、metadata 或 provider revision。
4. Adapter 必须在 finalize 时执行服务端 HEAD，并把 exact key/tenant/binding、长度、
   Content-Type、元数据、provider 校验和和强 revision 绑定在一起。若声明的是内容哈希，
   还必须针对同一 revision 做实际内容校验；只相信客户端写入的 hash 元数据不够。
   grant 返回的 location 只是 staging/source authority；finalize 必须另行返回绑定不可变
   provider revision 的 opaque final location，并同时回显 tenant、binding 与 source location。
   Application 核对这些字段及全部声明后才可 CAS 完成。
5. URL 过期不代表会话或对象自动消失。过期、失败和 outcome-unknown 会话由后续恢复/
   清理任务按权威状态处理；不得在不确定 PUT 是否已提交时盲删对象。

## OSS 参考实现

- 使用稳定 OSS Java SDK V1 的 `GeneratePresignedUrlRequest` 与 PUT；
- 签入 `Content-MD5`（OSS 可实际校验），不伪造不存在的 SHA-256 请求头；
- 同时签入 Content-Type、`x-oss-forbid-overwrite`、租户/会话/长度/SHA-256 元数据和
  用户元数据，并把所有必需请求头返回给调用方；
- 每次 grant 使用不可变随机对象键，这是防覆盖的主边界；OSS 在启用或暂停 versioning
  时会忽略 `x-oss-forbid-overwrite`，因此该头只是在未启用 versioning 的 bucket 上增加
  provider 侧冲突保护，不能替代唯一键；
- 对 SDK 返回 URL 的 scheme、endpoint/host、port、bucket addressing、exact key path
  和签名 query 失败关闭；
- grant TTL 不得超过 V4 上限或 STS 凭据寿命，并预留请求超时与凭据安全窗；
- finalize 通过 HEAD 校验 Content-MD5、CRC64 证据、版本/ETag、长度、类型和全部元数据，
  再按同一 version 条件读取并计算 SHA-256；直传 finalize 要求 versioning 提供非空
  versionId，不允许退化为 latest key 或只绑定 ETag；
- final location 使用规范、无歧义的 URL-safe opaque 编码绑定 exact key + versionId。
  OSS adapter 对这个位置的完整下载、Range、HEAD metadata、exists、签名 GET 与 delete
  都必须显式携带同一 versionId，并校验 provider 响应，绝不回退到 latest；
- URL 仍可能在过期前重放并产生更新版本。已完成对象的安全性来自 final location 的
  version 绑定，不来自 `x-oss-forbid-overwrite`、MD5 碰撞强度或等待 URL 过期。

## Staging 多版本回收边界

一次 grant 在有效期内可能产生多个 staging key 版本。当前存储 SPI 能精确删除已绑定的
完成版本，也能删除 staging key 的当前版本，但没有“枚举并按授权会话回收该 key 的全部
版本”的能力。清理任务在没有持久化版本清单时不得循环删除到空，因为这可能越过它掌握的
authority。1.0 后续持久化/清理阶段需要增加受限版本枚举或在上传事件中记录全部 versionId；
在此之前，遗留的重放版本是可诊断、可生命周期治理的存储泄漏风险，而不是读取完整性风险。

## 兼容影响与迁移

这是纯加法能力。现有 `StorageAdapter` 实现、服务端上传、断点续传和已发布构造器不变。
宿主只有在显式选择直传时才需要实现新的会话 repository；没有实现
`PresignedUploadStorageAdapter` 时应报告“不支持”，不能退化为不受约束的公开 PUT。
首个 JDBC 持久化实现将使用 V034+ 新表/迁移，不复用已发布的 resumable upload 表。

## 测试计划

- SPI：Kotlin/Java 构造、不可变 headers/metadata、危险 URI/头和参数边界；
- Application：跨租户/非 owner、客户端无法提供 location、CAS 并发 finalize、过期、
  exact completed replay 与失败恢复；
- OSS fake：signed header、endpoint/path/CNAME、短 TTL/STS 过期、HEAD 字段漂移、revision
  漂移、MD5/CRC64/SHA-256 不匹配全部失败关闭；模拟 provider 接受同 MD5、不同 SHA-256
  的 URL 重放后，已完成 opaque location 仍必须精确读旧版本、签旧版本并只删旧版本；
- OSS real lane：真实 PUT、HEAD、版本/ETag、Content-MD5/CRC64、标准 range 语义、exact
  version 删除与 Doctor；同一 URL 再 PUT 产生新版本后，旧完成 location 的 metadata、
  download、accessUrl 和 delete 仍绑定旧 version；SDK wire fixture 对实际 V4 HTTP 请求
  断言 range/overwrite 头；
- overwrite 语义使用第二个专用、未启用 versioning 的私有 bucket 验证：设置
  `FLOWWEFT_RUN_OSS_OVERWRITE_TESTS=true` 与 `FLOWWEFT_OSS_OVERWRITE_BUCKET`，在分片开始后
  制造同 key 对象，并要求 CompleteMultipartUpload 返回 `FileAlreadyExists`；
- 真实 STS lane 必须同时提供 `FLOWWEFT_OSS_SECURITY_TOKEN` 与 ISO-8601
  `FLOWWEFT_OSS_CREDENTIAL_EXPIRES_AT`，并使用启用 versioning 的专用私有 bucket；
- 公共 API：Java getter/constructor 和 JDK 8/25 兼容。
