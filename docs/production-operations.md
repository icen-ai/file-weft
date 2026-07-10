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

Doctor 的 `agent` 检查只验证已安装的 Agent 能力是否已被运行时登记，不会调用 AI、产生费用或修改文档。未安装 Agent 时结果为 `SKIPPED`，因为 Agent 是可选能力；安装后会报告已登记的能力列表。更细的第三方 AI 连通性检查应由对应插件提供 `DoctorChecker`。

恢复步骤：

1. 存储异常时，先运行 Doctor，核对对象引用和存储健康，再恢复连接并重试。
2. 下游异常时，检查交付记录的目标状态、错误和外部 ID；仅重排失败目标，避免重新推送已成功目标。
3. 怀疑租户越权时，立即停止该租户入口，按审计和操作日志中的 `tenantId`、资源 ID、`traceId` 排查，并确认仓储查询保持租户条件。
4. 仅在专用测试库上运行 `FILEWEFT_RUN_POSTGRES_TESTS=true`；该测试会重置 `public` schema，绝不能指向生产数据库。
