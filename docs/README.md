# DMC Project - 技术调研文档

基于 kilo-jetbrains 插件二次开发的技术调研与可行性分析。

## 文档索引

| 文档 | 内容 | 核心结论 |
|---|---|---|
| [01-mcp-tool-feasibility.md](./01-mcp-tool-feasibility.md) | MCP 工具可行性分析：获取编译错误、选区内容、发送至对话 | 推荐原生 Action 方案，MCP 为备选 |
| [02-whether-need-modify-source.md](./02-whether-need-modify-source.md) | 推荐方案是否需要修改官方插件源码 | 原生方案需要，MCP 伴生插件不需要 |
| [03-separate-plugin-communication.md](./03-separate-plugin-communication.md) | 独立插件能否与 kilocode 插件通信 | 无法直接通信，给出替代路径 |
| [04-jetbrains-cross-package-deps.md](./04-jetbrains-cross-package-deps.md) | kilo-jetbrains 与其他子包的依赖关系 | 仅依赖 ui（构建时）和 opencode（仅 dev） |
| [05-fork-upstream-sync-strategy.md](./05-fork-upstream-sync-strategy.md) | 私有 fork + 上游同步策略 | 隔离 + 标记 + 定期合并 |
| [06-standalone-project-feasibility.md](./06-standalone-project-feasibility.md) | dmc-kilo-project 独立项目可行性 | 完全可行，sparse clone 方案 |

## 调研日期

2026-08-08

## 调研对象

- 项目版本: kilo-jetbrains 7.0.14 / CLI 7.4.20
- 源码路径: `packages/kilo-jetbrains/`、`packages/opencode/`、`packages/sdk/js/`
