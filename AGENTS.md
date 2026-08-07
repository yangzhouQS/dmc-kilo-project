# AGENTS.md — DMC Kilo Project

二次开发项目，基于 kilo-jetbrains 插件 fork，定期同步上游官方源码。

## 核心铁律

**所有定制代码必须放在 `packages/kilo-jetbrains/custom/` 模块内。**

`custom/` 目录在上游不存在，永远不会产生合并冲突。这是保持上游同步顺畅的唯一保证。

## 绝对禁止

- 禁止修改 `shared/`、`frontend/`、`backend/`、`build-tasks/` 模块内的任何文件
- 禁止在 `custom/` 之外创建新文件
- 禁止删除上游文件
- 禁止重命名上游文件或目录
- 禁止修改 `gradle/libs.versions.toml`（版本目录）
- 禁止将 `kilo.cli.pinned` 设为 `false`（仅 dev 模式，不可发布）

## 受限修改文件（仅 5 个）

以下文件**允许修改**，但必须满足两个条件：
1. 每处修改用 `// custom_change` 或 `<!-- custom_change -->` 标记
2. 改动最小化（理想状态：每个文件只加 1 行）

| 文件 | 允许的改动 |
|---|---|
| `settings.gradle.kts` | `include("custom")` |
| `src/main/resources/META-INF/plugin.xml` | `<content>` 中加 custom 模块行 + 改 `<name>` |
| `build.gradle.kts` | `pluginConfiguration` 中改插件 ID |
| `gradle.properties` | 版本号 |
| `package.json` | name、version |

## 上游同步

- 同步脚本：`scripts/sync-upstream.ps1`
- 同步点记录：`.upstream-sync`（git commit hash）
- 保护文件（上述 5 个）sync 时显示 diff，需手动确认
- 安全文件（其余所有文件）sync 时自动覆盖
- `custom/` 目录 sync 时**永不触碰**

详见 `docs/06-standalone-project-feasibility.md` 和 `docs/05-fork-upstream-sync-strategy.md`。

## 构建环境

- JDK 21（必须）
- Gradle 9.4.1（wrapper 自动下载）
- `kilo.cli.pinned=true`（默认，从 GitHub Release 下载 CLI）
- 不需要 bun / node / turbo

## 定制模块结构

```
packages/kilo-jetbrains/custom/
├── build.gradle.kts
├── src/main/resources/
│   └── dmc.custom.xml              ← 模块描述符（actions、services 注册）
└── src/main/kotlin/com/dmc/
    ├── bridge/                      ← 会话桥接（发送内容到 kilocode 对话）
    └── actions/                     ← 右键菜单 Action
```

## 编码规范

- 遵循上游 `packages/kilo-jetbrains/AGENTS.md` 中的 IntelliJ UI 指南和线程规则
- 第三方库必须打包进插件（`implementation`），不依赖平台内置库
- `kotlinx.coroutines` 例外——由 IntelliJ Platform 提供，不打包
