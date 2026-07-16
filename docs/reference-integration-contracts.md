# FlowWeft 1.0 参考集成能力合同

本文定义 RustFS、Dify 知识库与阿里云 OSS 三套参考实现可以承诺什么、必须失败
关闭什么。它是实现输入，不代表对应适配器已经交付；完成状态只认
[1.0 交付总账](flowweft-1.0-delivery-ledger.md)。

## 共同原则

- 厂商 SDK、HTTP DTO、错误对象和凭据只存在于 adapter；Core、Domain、SPI 与
  Application 不出现厂商类型。
- “兼容 S3”“支持 metadata filter”或一个 2xx 响应不是能力证据。只有锁定版本、
  合约测试和真实环境矩阵验证过的子集才能 advertise。
- endpoint、workspace、bucket、dataset、region 和 secret 都来自管理员批准的
  server-side profile。浏览器、模型、文档 metadata 和请求参数不能覆盖。
- 远端调用有 deadline、重试分类、幂等、对账、健康检查和脱敏诊断；数据库事务
  内不调用远端系统。
- 内容完整性以 FlowWeft 保存的 SHA-256 为准。ETag 只是不透明变化/条件请求标识，
  不能普遍解释为内容 MD5。

## RustFS：经验证的 S3 子集

RustFS 复用通用 S3 adapter，不新增 RustFS 专属 Core/SPI。到 2026-07-15，官方
最新发布仍是 `1.0.0-beta.8`，README 同时把 distributed、lifecycle 和 KMS 标为
testing，因此参考环境必须固定版本和镜像 digest，不能使用 `latest`，也不能把
“S3 compatible”扩大为未验证能力。

首个可 advertise 子集只能是 `rustFsIntegrationCheck` 真实验证过的：

- SigV4、HTTPS、endpoint override、region 与 path-style；
- Put/Head/Get/Delete；
- 严格 Range；
- presigned PUT/GET；
- multipart create/upload/complete/abort；
- tenant 前缀、不可变对象 key、FlowWeft SHA-256 与错误分类。

ListParts、条件请求、checksum、lifecycle、distributed 和 KMS 默认是
`capability=false`。需要它们的宿主必须先增加独立真实环境合约，缺失时启动或
能力协商失败关闭，不能退化成整对象读取、覆盖写或共享前缀。

依据：[S3 兼容说明](https://docs.rustfs.com/features/s3-compatibility/)、
[Java SDK 示例](https://docs.rustfs.com/developer/sdk/java)、
[beta.8 发布](https://github.com/rustfs/rustfs/releases/tag/1.0.0-beta.8)、
[已实现 s3-tests](https://github.com/rustfs/rustfs/blob/64c0ede0261eeb7ccd415221d6f102aa70829b6a/scripts/s3-tests/implemented_tests.txt)、
[未实现 s3-tests](https://github.com/rustfs/rustfs/blob/64c0ede0261eeb7ccd415221d6f102aa70829b6a/scripts/s3-tests/unimplemented_tests.txt)。

## Dify：知识投影，不是权限或删除真相

Dify 的 API key 隔离到 workspace/tenant，但这不是 FlowWeft 终端用户 ACL。当前
检索 API 没有终端用户身份；token 以 workspace 身份访问知识库。锁定且通过合约
测试的 Dify 版本可以使用 manual metadata server-side pre-filter，但过滤器必须由
FlowWeft 从可信访问计划编译，客户端不能传入。

参考投影至少保存以下保留字段：

```text
fw_projection_id
fw_tenant_id
fw_document_id
fw_source_version_id
fw_content_sha256
fw_acl_cohort
fw_acl_revision
fw_projection_state
```

FlowWeft 数据库保存权威映射：`tenant/document/version/connector generation` 到
`workspace/dataset/document/batch/index state`。Dify metadata 只是索引投影。查询
返回后必须解析本地映射，验证 active generation、内容摘要、ACL revision，并做
当前权威授权；任一映射缺失或不一致时整批失败关闭。

特别约束：Dify 的空 `conditions` 会退化为检索全部可用文档。无可见 cohort/ID 时
FlowWeft 必须在网络前直接返回空，永远不能发送空 conditions。复杂访问计划无法
无损翻译、过滤字段未知或容量超限时同样禁止调用。

Dify 删除接口的 `204` 只表示请求被接受并删除数据库行；向量清理由异步任务执行，
异常可能留下 orphan。参考实现只能记录：

```text
DELETE_REQUESTED
DELETE_ACCEPTED
API_ABSENT
RETRIEVAL_ABSENT
```

能力固定声明 `verifiablePurge=false`。不能把 204、GET 404 或一次检索未命中写成
“已物理清除”。FlowWeft 自己持久化删除任务、tombstone、探测和对账证据。

依据：[API key 指南](https://docs.dify.ai/en/api-reference/guides/get-started)、
[知识库检索 API](https://docs.dify.ai/api-reference/knowledge-bases/retrieve-chunks-from-a-knowledge-base-test-retrieval)、
[tenant token 校验源码](https://github.com/langgenius/dify/blob/3aa26fb6374bbd47e5469f7d7cc25f3e0075a60c/api/controllers/service_api/wraps.py#L284)、
[metadata 先过滤再检索源码](https://github.com/langgenius/dify/blob/3aa26fb6374bbd47e5469f7d7cc25f3e0075a60c/api/services/hit_testing_service.py#L125)、
[空 conditions 行为源码](https://github.com/langgenius/dify/blob/3aa26fb6374bbd47e5469f7d7cc25f3e0075a60c/api/core/rag/retrieval/dataset_retrieval.py#L1340)、
[异步清理源码](https://github.com/langgenius/dify/blob/3aa26fb6374bbd47e5469f7d7cc25f3e0075a60c/api/tasks/clean_document_task.py#L62)。

## 阿里云 OSS：稳定 V1、严格 Range 与服务端分片账本

Java SDK V2 仍由官方标为 preview，首个生产参考适配器使用稳定的
`com.aliyun.oss:aliyun-sdk-oss:3.18.5`，显式配置 SigV4、region 与 HTTPS，并以
RAM Role/STS 最小权限运行。必须验证 JDK 8 与 JDK 25，厂商依赖不能进入 SPI。

上传要求：

- multipart 权威状态存在 FlowWeft 数据库：upload ID、part size、源 fingerprint、
  part number/ETag、过期时间和状态；SDK 本地 checkpoint 不是分布式真相；
- 只有源 fingerprint 未变才允许同 part 幂等重传；Complete 前排序和完整校验；
- Complete 超时属于 outcome unknown，先 HEAD/状态对账，不能盲目重开；
- presigned PUT 使用短 TTL、唯一不可变 tenant key，并签入
  `x-oss-forbid-overwrite:true`、Content-Type、校验头和必要 metadata；URL 不进日志；
- 客户端完成后由服务端 HEAD 核对 key、size、metadata、version/checksum，再发布。

下载续传先 HEAD 固定 length、ETag/versionId；Range 请求添加
`x-oss-range-behavior:standard` 与 `If-Match` 或精确 versionId，只接受匹配的
`206`、`Content-Range` 和长度。非零 offset 收到 `200`、`412`、`416` 或对象版本
变化都必须失败/重启，绝不能把整对象追加到旧文件。multipart 最终 ETag 不是
内容 MD5；Range 完成后仍计算 FlowWeft SHA-256。

依据：[Java V1 SDK](https://help.aliyun.com/en/oss/developer-reference/oss-java-sdk/)、
[V1 3.18.5](https://github.com/aliyun/aliyun-oss-java-sdk/releases/tag/3.18.5)、
[V2 preview](https://help.aliyun.com/en/oss/developer-reference/oss-sdk-for-java-2-0/)、
[multipart](https://help.aliyun.com/en/oss/developer-reference/java-multipart-upload)、
[严格 Range](https://help.aliyun.com/en/oss/developer-reference/range-download-8)、
[禁止覆盖](https://help.aliyun.com/en/oss/developer-reference/prevent-objects-from-being-overwritten-by-objects-that-have-the-same-names-3)、
[multipart ETag](https://help.aliyun.com/en/oss/developer-reference/completemultipartupload)。
