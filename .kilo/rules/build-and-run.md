# 构建与运行规则

## 构建环境

- JDK 21（必须，不接受 17 或 8）
- Gradle 9.4.1（通过 wrapper，不手动升级）
- `kilo.cli.pinned=true`（默认值，不修改）
- 首次冷构建需要网络（下载 IntelliJ Platform SDK 1.4 GiB + CLI Release）
- 不需要 bun、node、turbo、opencode

## 构建命令

| 操作 | 命令 |
|---|---|
| 类型检查 | `.\gradlew.bat typecheck` |
| 运行测试 | `.\gradlew.bat test` |
| 打包 zip | `.\gradlew.bat buildPlugin` |
| 沙箱运行 | `.\gradlew.bat runIde` |

所有命令在 `packages/kilo-jetbrains/` 目录下执行。

## 依赖管理

- 第三方库在 `custom/build.gradle.kts` 中用 `implementation` 声明
- 版本号硬编码或引用 `libs.*`（不新增版本目录条目）
- `kotlinx.coroutines` 不打包（IntelliJ Platform 提供）
- 不使用平台内置库（OkHttp、Gson 等）——必须 `implementation` 打包

## Gradle 缓存

默认位置：`C:\Users\<user>\.gradle\caches\`
如需迁移：设置环境变量 `GRADLE_USER_HOME`
