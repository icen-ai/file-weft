# 生产部署与恢复

FileWeft 的 Web 节点默认不消费 Outbox 或后台任务。部署时推荐使用相同的应用工件启动两个角色：

```yaml
# Web 节点：默认值，无需配置 fileweft.worker.enabled
fileweft:
  worker:
    enabled: false

# 异步 Worker 节点
fileweft:
  worker:
    enabled: true
    fixed-delay-millis: 1000
    outbox-batch-size: 50
    task-batch-size: 50
    process-outbox: true
    process-tasks: true
```

若需要拆分资源池，可让下游同步节点只开启 `process-outbox`，让 Doctor/Agent 节点只开启 `process-tasks`。所有节点可以水平扩展：Outbox 与后台任务均通过数据库租约/锁领取，重复投递由事件或任务的幂等键约束。

Worker 每轮失败只记录日志，不会丢弃待处理记录；下一轮会继续领取符合重试时间或租约已过期的工作。生产报警应至少覆盖同步失败、任务失败、Doctor 失败和持久化 Outbox 积压。

## 下游连接器韧性

Starter 会为默认的交付解析器、兼容的单连接器同步路径和连接器 Doctor 共享同一个保护实例。它在进程内提供硬超时、有限并发/排队和每个连接器独立的熔断状态；Outbox 仍是唯一的重试调度者，不会在一次投递中隐藏式重放请求。

```yaml
fileweft:
  sync:
    connector-timeout-millis: 30000
    circuit-breaker-failure-threshold: 3
    circuit-breaker-open-duration-millis: 30000
    connector-max-concurrent-invocations: 16
    connector-invocation-queue-capacity: 256
```

连接器抛出异常、超过超时或返回 `RETRYABLE_FAILURE` 都会交给 Outbox 的退避重试，并计入该下游的连续失败阈值；达到阈值后，熔断器直接返回可重试结果而不触达下游。执行池饱和和线程中断同样会交给 Outbox 重试，但不会误开某个下游的熔断器，因为它们是本地容量信号。冷却窗口结束后只允许一个真实调用作为恢复探针。`PERMANENT_FAILURE` 不会打开熔断器，因为它通常表示下游已收到请求但拒绝了业务内容。已成功的其他目标不会被回滚。

若业务方替换了默认 `DeliveryConnectorResolver` 或 `DocumentSyncService`，应通过 `ConnectorResilienceRegistry.protect(connectorId, connector)` 获取连接器；否则该自定义入口会自行承担超时与熔断责任。

Doctor 的 `agent` 检查只验证已安装的 Agent 能力是否已被运行时登记，不会调用 AI、产生费用或修改文档。未安装 Agent 时结果为 `SKIPPED`，因为 Agent 是可选能力；安装后会报告已登记的能力列表。更细的第三方 AI 连通性检查应由对应插件提供 `DoctorChecker`。

恢复步骤：

1. 存储异常时，先运行 Doctor，核对对象引用和存储健康，再恢复连接并重试。
2. 下游异常时，检查交付记录的目标状态、错误和外部 ID；仅重排失败目标，避免重新推送已成功目标。
3. 怀疑租户越权时，立即停止该租户入口，按审计和操作日志中的 `tenantId`、资源 ID、`traceId` 排查，并确认仓储查询保持租户条件。
4. 仅在专用测试库上运行 `FILEWEFT_RUN_POSTGRES_TESTS=true`；该测试会重置 `public` schema，绝不能指向生产数据库。
