# FileWeft 文档精修指南

> 目标：不求内容多，而求用词精确、符合各国程序员本土化表达习惯。所有改动以提升质量为准，不新增页面，不扩展篇幅。

## 一、总体原则

1. **读者是程序员**：不要用营销话术、空洞形容词或过度热情的语气。用直接、准确、可执行的表达。
2. **技术术语一致**：同一概念在一种语言中保持同一译法/写法。
3. **避免翻译腔**：中文不要像英文直译；英文不要像中翻英。
4. **精确优先**：能用数字/代码说明的，不用模糊描述。
5. **实例真实**：代码示例、YAML、curl 要接近生产可用，避免 toy example。

## 二、英文审稿重点

- 使用工程师熟悉的短语：`configure`, `wire up`, `plug in`, `fail closed`, `at-least-once`, `out-of-the-box`。
- 避免冗长从句，多用短句。
- 避免 "very", "really", "simply", "just", "easily" 等弱化词。
- 命令式说明步骤：`Add the dependency`, `Provide a bean`, `Run the test`。
- 错误示例 vs 正确示例：
  - ❌ "FileWeft is a very powerful and extensible foundation..."
  - ✅ "FileWeft is a Kotlin/JVM infrastructure framework for document lifecycles."
  - ❌ "You just need to implement the SPI."
  - ✅ "Implement the SPI."
  - ❌ "It should be noted that..."
  - ✅ "Note that..." 或直接陈述。

## 三、中文审稿重点

- 技术名词优先采用业界通用译法，必要时保留英文：
  - tenant → 租户
  - outbox → Outbox（保留英文，便于搜索）
  - connector → 连接器
  - adapter → 适配器
  - handler → Handler（保留英文）
  - SPI → SPI（保留英文）
  - fallback → fallback / 回退
  - lease → 租约
  - idempotency → 幂等
  - at-least-once → 至少一次
  - circuit breaker → 熔断
  - presigned URL → 预签名 URL
- 避免生造词：
  - ❌ "失败关闭" → ✅ "默认拒绝" / "故障关闭"
  - ❌ "本地原子，显式收敛" → ✅ "本地事务原子，远程状态显式收敛"
  - ❌ "装配"（过多使用） → 根据语境用 "接入"/"配置"/"组装"
- 中文标点与空格：英文/数字与中文之间留一个空格；代码片段前后不加空格。
- 命令式步骤：用 "添加"、"配置"、"运行"，不用 "你需要"、"我们应当"。

## 四、中英对应重点

- 同一页面的中英文标题、摘要、小节要语义对应，但不要逐字翻译。
- 代码示例在中英文页面中保持一致（代码本身就是通用语言）。
- 专有名词（FileWeft、Outbox、SPI、Doctor）大小写一致。

## 五、禁止事项

- 不新增页面或章节。
- 不编造 API、配置或行为。
- 不改变架构事实。
- 不为了改而改：只改确实能提升质量的地方。

## 六、输出要求

每改完一组页面，返回：
1. 修改了哪些文件。
2. 每页最重要的 1-3 处改进（用词、句式、准确性）。
3. 未改动但检查过的页面可简单标注 "no change"。
