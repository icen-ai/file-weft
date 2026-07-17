# ADR 0002：产品更名为 FlowWeft

- 状态：已接受
- 日期：2026-07-15
- 决策者：项目维护者
- 适用范围：`0.0.3` 之后的开发、`1.0.0` 及后续版本
- 关联：[ADR 0001](0001-flowweft-1.0-product-scope.md)

## 决策摘要

产品、站点、控制台和 `1.0` 新能力的正式名称改为 **FlowWeft**。`Flow` 同时覆盖
文件流、工作流、检索流和 Agent 编排，比只强调 File 更符合产品已经确定的边界。

更名不是删除历史兼容面。下列标识保持不变：

- Java/Kotlin package `ai.icen.fw`；
- 已发布的 public 类、接口、枚举、方法和序列化名称中的 `FileWeft`；
- 数据库表前缀 `fw_`、V001–V029 迁移内容和 checksum；
- 已发布的 HTTP 根路径 `/fileweft/v1`；
- 已发布的配置键、插件描述符字段、事件 type 和 Maven 坐标；
- `0.0.x` 发布说明、历史设计文本、tag 和制品名称。

这些名字是机器合同或历史事实，不是当前品牌文案。文档应把它们标记为兼容标识，
不能为了视觉统一而破坏升级。

## 为什么需要分层更名

直接全局替换 `FileWeft` 会同时破坏源码兼容、二进制链接、配置、HTTP 客户端、
数据库升级、Flyway checksum、插件发现、监控查询和发布证据。只改 README 标题又会
让新制品继续积累旧品牌，导致未来再次迁移。

因此名称分为三层：

| 层级 | 1.0 决策 | 示例 |
| --- | --- | --- |
| 人类品牌 | 全部使用 FlowWeft | 网站、Console、当前文档、镜像说明、错误页 |
| 1.0 新机器标识 | 使用 `flowweft` | 新 artifact、npm package、镜像、Workflow/Agent 新配置 |
| 已发布兼容标识 | 原样保留并记录别名 | `ai.icen.fw`、`FileWeftPlugin`、`/fileweft/v1`、`fw_*` |

## Maven、Gradle 与模块策略

- Maven `groupId` 继续为 `ai.icen`。
- 1.0 新产品模块使用 `flowweft-*` artifact ID，例如
  `flowweft-workflow-api`、`flowweft-workflow-runtime` 和
  `flowweft-workflow-document`。
- 已在 `0.0.x` 发布的 `fileweft-*` artifact 不会无提示消失。若其实现改用
  `flowweft-*` 坐标，则旧坐标发布 Maven relocation/兼容 POM，并在真实 Maven 与
  Gradle 消费者中验证依赖解析、源码、二进制和传递依赖。
- 不能同时发布两个包含相同 class 的完整 jar。旧坐标必须是 relocation/薄兼容层，
  或继续作为唯一物理制品；最终方案按每个 artifact 的消费兼容测试决定。
- Gradle project 名和磁盘目录属于构建实现，可以在机械迁移阶段调整；不得让目录
  改名先于 publish、release smoke、SBOM、CNB path rules 和文档链接的同步修改。
- 1.0 BOM 的规范名称为 `flowweft-bom`。若继续提供旧 BOM 坐标，它只负责兼容
  导向，不能形成两个互相漂移的版本平台。

## 配置、HTTP 与持久化

- 已发布的 `fileweft.*` 配置键在 1.x 保持有效。新能力使用 `flowweft.*` 规范键；
  如果同一能力需要旧键别名，必须有确定优先级、冲突启动失败和 metadata 测试。
- `/fileweft/v1` 在 1.x 继续是稳定 HTTP 合同。是否增加新别名必须另做 API ADR；
  1.0 不同时维护两套行为可能漂移的 Controller。
- `fw_` 是 FlowWeft 与历史 FileWeft 共同适用的短前缀，不迁表、不建同义表、不复制
  数据。V001–V029 永不改写；新迁移仍从 V030 前进。
- 旧事件、审计 action、metric 名和 trace attribute 先按兼容表逐项审计。任何改名都
  要么保留稳定 alias，要么进入公开弃用周期，不能静默切断告警和消费者。

## 源码与公共 API

- `ai.icen.fw` 永久作为 1.x package 根；JPMS automatic module name 和 OSGi metadata
  也不得因品牌文案意外变化。
- 已发布的 `FileWeft*` 类型继续存在并保持 ABI。不要创建只改前缀、行为相同的重复
  类型；新 API 优先使用领域名，例如 `WorkflowEngine`、`AgentRuntime`，只有确实
  需要产品级类型时才使用 `FlowWeft*`。
- 旧 public 类型如需未来更名，必须采用新增类型、适配器、正式 `@Deprecated`、
  替代文档和至少一个公开弃用周期，不在 1.0 删除。
- 内部实现、测试 fixture 和未发布的 1.0 snapshot 名称可在冻结前直接收敛，但仍要
  通过全仓架构门禁和消费者测试。

## 仓库、网站和发布身份

- 当前 GitHub/CNB 仓库 slug 可以暂时保留，以免破坏 clone、知识库、Webhook、
  徽章和构建身份。远端改名只有在平台重定向、镜像、CNB repo 参数、签名、
  provenance 与旧链接全部验证后才执行。
- README、当前架构、Console title/metadata、容器 label、站点导航和 1.0 发布说明
  使用 FlowWeft；历史 release note 不做全局改写，只加当前品牌说明。
- 签名、SBOM、POM、module metadata、镜像 label、tag 和 CNB 完整 SHA 必须对同一
  FlowWeft 1.0 发布身份闭环，不能出现新旧名称分别指向不同二进制。

## 迁移与验证顺序

1. 建立全仓名称清单，区分品牌、新标识、已发布兼容标识和历史文本。
2. 先更新 ADR、架构、交付总账与新模块命名，停止新增旧品牌机器标识。
3. 机械迁移当前品牌文案、Console、站点、镜像和未发布模块。
4. 为每个旧 artifact/config/event 建立明确兼容策略和 golden baseline。
5. 更新 CI path rules、release smoke、SBOM、文档链接和发布脚本。
6. 使用 Maven、Gradle、Boot 2/3、Java/Kotlin 外部消费者验证旧、新坐标。
7. 完成匿名冷缓存消费与精确 CNB SHA 证据后，才发布 1.0。

## 不接受的做法

- 对仓库执行无分类的 `FileWeft -> FlowWeft` 全局替换；
- 改写 Flyway 迁移、历史 release note、tag 或旧制品内容；
- 发布两份包含同一 package/class 的实现 jar；
- 只在文案里承诺旧坐标兼容而没有真实 Maven/Gradle 解析测试；
- 为了新品牌同时维护两套会逐渐漂移的 HTTP 或数据库模型；
- 把 package 未改名解释为产品没有完成更名。

## 兼容影响

现有用户仍可使用 `ai.icen.fw`、旧 public 类型、旧 HTTP、旧数据库和受支持旧配置。
新用户看到的产品和新增模块是 FlowWeft。1.0 发布说明必须提供旧依赖到新规范坐标的
逐项映射、自动/手动迁移边界和回滚方法。
