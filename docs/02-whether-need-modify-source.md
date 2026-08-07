# 推荐方案是否需要修改官方插件源码

## 直接回答

**是的**，第一份文档推荐的原生 Action 方案**需要修改 `packages/kilo-jetbrains/` 源码**——因为它要直接调用插件内部的 `SessionController.prompt()`，而那是私有 API，外部无法访问。

但有一个**完全不需要改官方插件**的替代方案：**MCP 伴生插件**。

---

## 不改官方插件的方案：独立伴生插件 + MCP Server

### 核心思路反转

```
原生方案（需改源码）:  JetBrains内部Action → 直接调 SessionController.prompt()
MCP方案（不改源码）:   CLI agent → 主动调 MCP工具 → MCP工具读取IDE状态
```

**方向是反的**：不是「用户推送错误给对话」，而是「agent 主动查询 IDE 状态」。CLI 已内置 MCP client，会**主动连接**提供的 MCP server。

### 架构

```
┌──────────────────────────────────────────────┐
│  你的独立 JetBrains 插件（不动官方插件）        │
│                                                │
│  ① MCP HTTP Server (固定端口, 如 localhost:7777)│
│     暴露工具:                                   │
│     ├─ get_build_errors()   ← Problems/Daemon  │
│     ├─ get_editor_selection() ← Editor API     │
│     └─ get_active_file()    ← FileEditorMgr    │
│                                                │
│  ② 直接用 IntelliJ API 读 IDE 状态（同进程）    │
└───────────────┬──────────────────────────────┘
                │ CLI 主动连接（StreamableHTTP）
                ▼
┌──────────────────────────────────────────────┐
│  kilo serve (官方插件启动的，不改动)            │
│    MCP client → 调用你的工具                    │
│    agent 看到 "get_build_errors" 工具           │
└──────────────────────────────────────────────┘
```

### 为什么不需要改官方插件

| 环节 | 如何实现 | 是否改官方 |
|---|---|---|
| CLI 连接你的 MCP server | 官方插件**已有 MCP 设置 UI**（`McpConfigurable.kt`），用户手动添加 remote URL 即可 | 否 |
| MCP 协议支持 | CLI 已支持 StreamableHTTP transport（`packages/opencode/src/mcp/index.ts:287`） | 否 |
| 读取 IDE 状态 | 独立插件运行在**同一个 IntelliJ 进程**，可直接用 `FileEditorManager`、`DaemonCodeAnalyzer` 等 | 否 |
| agent 调用工具 | CLI 自动发现 MCP 工具并注入到 agent 工具列表（`session/tools.ts:430`） | 否 |

### 用户工作流

1. 构建失败 → 直接在 kilocode 对话里输入：「帮我检查编译错误并修复」
2. Agent 自动调用 `get_build_errors` → MCP server 返回错误列表
3. Agent 调用 `get_editor_selection` → 拿到选中代码
4. Agent 修复

或者保留「选中发送」的体验：

1. 插件提供一个 Action「收集错误到上下文」，将错误缓存到内存
2. MCP 工具 `get_pending_context()` 返回缓存内容
3. 用户在对话里说「修复我刚收集的错误」→ agent 调用该工具

---

## 两种方案对比

| 维度 | 方案 A：改官方插件源码 | 方案 B：独立插件 + MCP Server |
|---|---|---|
| 改官方源码 | **是** | **否** |
| 触发方式 | 用户主动选中→发送 | Agent 主动查询（或半自动） |
| 通信 | 复用已有 HTTP 连接，零新增 | CLI 主动连你的 MCP server |
| 实现语言 | Kotlin（插件内） | Kotlin（独立插件）+ MCP SDK |
| MCP SDK 依赖 | 无 | 需要 `io.modelcontextprotocol` Java SDK 或手写 JSON-RPC |
| 合并冲突维护 | 每次升级 kilo 都可能冲突 | **零冲突**，完全解耦 |
| 端口/密码问题 | 无（直接调内部 API） | **无**（CLI 连你，你不需要知道 CLI 端口） |
| 工作量 | ~200 行 | ~400 行（多一层 MCP server） |

---

## 建议

如果**不想维护官方插件 fork**，选 **方案 B（独立插件 + MCP）**。它正是 MCP 的设计初衷——解耦、标准化、零侵入官方代码。唯一代价是触发方式从「用户推送」变成「agent 拉取」，但通过 `get_pending_context` 半自动模式可以兼顾。

如果选择**修改官方插件源码**进行二次开发，请参考 [05-fork-upstream-sync-strategy.md](./05-fork-upstream-sync-strategy.md) 的上游同步策略。
