# FileWeft

FileWeft 是面向企业的 Kotlin/JVM 文件智能基础设施。

当前仓库从实施手册定义的第一阶段启动：Gradle 多模块骨架已就位，后续按 `core → spi → domain → application → persistence → starter → adapter → doctor → agent` 的顺序推进。

## 构建要求

- 构建运行时：JDK 17+（当前验证环境为 JDK 21）
- 核心及除 Spring Boot 3 Starter 外的模块：产物字节码兼容 Java 8
- Spring Boot 3 Starter：产物字节码兼容 Java 17

## 验证

```powershell
.\gradlew.bat check
```

依赖版本通过 `gradle/libs.versions.toml` 管理，所有配置启用依赖锁定。
