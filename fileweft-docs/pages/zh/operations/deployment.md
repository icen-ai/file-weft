---
route: "operations/deployment"
group: "operations"
order: 1
locale: "zh"
nav: "生产部署"
title: "按运行角色部署"
lead: "将同一份已验证的 FileWeft 制品以四种有意区分的角色运行——API、Outbox Worker、任务 Worker 与迁移 Job——让每个进程只拥有它所需的凭据、配置和爆炸半径。"
format: "markdown"
---

## 这页解决什么问题

生产环境的 FileWeft 不是把全部能力一把打开的单一 jar。本页说明如何把同一个已完成远端验证的 `ai.icen:*:0.0.2` 制品拆分为不同运行角色：共享数据库与对象存储，但绝不共享不必要的权限。

## 推荐拓扑

把部署想象成一个小型控制平面：一个角色写 schema，一个承接流量，一个消费队列，一个执行任务。

| 角色 | Flyway 模式 | 典型凭据 | 原因 |
|------|-------------|----------|------|
| 迁移 Job | `migrate` | 具备 DDL 权限、短生命周期 | schema 变更应在人为控制下只执行一次 |
| API 节点 | `validate` | 业务读写身份 | 面对不可信流量；不轮询队列 |
| Outbox Worker | `validate` | 队列与连接器密钥 | 调用下游系统；不监听 HTTP |
| 任务 Worker | `validate` | 仅任务相关资源 | 执行后台 Handler；不监听 HTTP |

> [!WARNING]
> 长期运行的 API 或 Worker 不应持有建 schema 权限。连接器凭据只交给实际调用该连接器的 Worker。

## 发布顺序

1. 备份数据库并实际验证恢复。
2. 停止冲突写入方；由唯一迁移所有者运行受控迁移 Job。
3. 以 `validate` 模式启动 API 和 Worker 角色。
4. 观察 `/fileweft/v1/health`、Doctor 结果、Outbox ready age 与租约恢复情况。
5. 校验通过后再开放流量。

## 各角色 Spring Boot 配置

### API 角色

```yaml
# API 角色：不轮询队列，不改 schema
fileweft:
  persistence:
    migration-mode: validate
    schema: fileweft
    create-schema: false
  worker:
    enabled: false
  upload:
    resumable-session-ttl-millis: 86400000
```

### Outbox Worker 角色

```yaml
# Outbox Worker 角色：消费队列并调用连接器
fileweft:
  persistence:
    migration-mode: validate
  worker:
    enabled: true
    fixed-delay-millis: 1000
    outbox-batch-size: 50
    process-outbox: true
    process-tasks: false
    process-upload-cleanup: false
  sync:
    connector-timeout-millis: 30000
    connector-max-concurrent-invocations: 16
```

### 任务 Worker 角色

```yaml
# 任务 Worker 角色：执行后台 Handler
fileweft:
  persistence:
    migration-mode: validate
  worker:
    enabled: true
    task-batch-size: 50
    process-outbox: false
    process-tasks: true
    process-upload-cleanup: true
  task:
    lease-duration-millis: 60000
```

### 迁移 Job 角色

```yaml
# 迁移 Job 角色：一次性 schema 变更
fileweft:
  persistence:
    migration-mode: migrate
    schema: fileweft
    create-schema: true
  worker:
    enabled: false
```

FileWeft 的启动初始化器会执行迁移，但不会在成功后自动终止 Spring 进程。因此，给普通长期运行的 Web 宿主追加上述参数，并不会让它变成一次性迁移 Job。请由宿主提供一个非 Web 的迁移可执行程序或专用 profile，在初始化成功后显式关闭 Spring 上下文并以 0 退出，例如：

```bash
java -jar fileweft-migration-job-0.0.2.jar \
  --spring.main.web-application-type=none \
  --fileweft.persistence.migration-mode=migrate \
  --fileweft.worker.enabled=false
```

这里的 `fileweft-migration-job-0.0.2.jar` 是宿主自行打包的可执行程序名，并不是 FileWeft 发布的独立应用。只有宿主实现了迁移完成后的显式退出，上述命令才是一项真正的一次性 Job。

## 健康与就绪检查

使用正式 HTTP 接口，不要把 Dev-only 的 `/api/**` 当作公共协议：

```bash
# 存活检查
curl -sf http://api:8080/fileweft/v1/health

# 文档级 Doctor（需先完成认证）
curl -sf http://api:8080/fileweft/v1/documents/doc_123/doctor \
  -H "Authorization: Bearer ${HOST_TOKEN}" \
  -H "Idempotency-Key: $(uuidgen)"
```

## 凭据边界

- 浏览器端永远不会获得对象存储凭据或下游密钥。
- API 节点不需要连接器密钥。
- 不承接流量的 Worker 节点不应开放 HTTP 端口。
- DDL（迁移 Job）与 DML（运行时角色）使用不同的数据库身份。

## 常见问题

**本地开发时可以把所有角色跑在一个进程里吗？**
可以，但仅限经过评审的固定单租户或开发 fallback。不要把它说成生产多租户方案。

**如果 Worker 在迁移 Job 完成前启动会怎样？**
它会因 `validate` 模式失败而退出。这是故意的：没有运行时进程会在 schema 版本未知的情况下静默运行。

## 下一步

- 学习 Doctor 与指标如何指导日常运维：[Doctor 与可观测性](doctor-observability)
- 查看完整配置地图：[配置](../reference/configuration)
- 在首次发布前理解迁移命名空间：[迁移与发布](migrations-release)
