# 旧版 vs 新版插件：功能差距分析

> 对比对象：
> - **旧版**：`kilocode-legacy-main/jetbrains/`（Kotlin 壳 + Node.js ExtHost 双进程架构）
> - **新版**：`packages/kilo-jetbrains/`（Kotlin 壳 + CLI 进程 HTTP API 架构，含 `custom/` 定制模块）
>
> 目的：梳理旧版中「优秀好用但新版尚未实现」的功能，评估在新 CLI 架构下的可移植性，给出实现优先级建议。

---

## 一、架构差异（理解差距的前提）

| 维度 | 旧版 | 新版 |
|------|------|------|
| AI 推理位置 | Node.js ExtHost（完整 VSCode 扩展） | 独立 CLI 进程（`kilo serve`，HTTP API） |
| Kotlin 侧角色 | RPC 桥接层，转发所有逻辑 | 直接通过 HTTP 调用 CLI，自管会话编排 |
| Webview | JCEF 渲染 React 前端 | 纯 Swing 原生 UI（SessionModel/Controller/View） |
| 国际化 | 自研 `DynamicBundle`（namespace:key） | IntelliJ 标准 `ResourceBundle`（18 种语言） |

> **关键结论**：旧版中大量「依赖 ExtHost」的功能不是被删除了，而是**架构变了**。评估差距时，只看**用户能感知的功能**是否缺失，不看内部桥接代码。

---

## 二、功能差距总览

### 🔴 完全缺失（旧版有，新版无任何实现）

| # | 功能 | 用户价值 | 旧版实现位置 | 新版可移植性 |
|---|------|---------|-------------|------------|
| 1 | **行内代码补全（Inline Completion）** | ⭐⭐⭐⭐⭐ | `inline/` 整个目录 | 需 CLI 暴露补全 API |
| 2 | **Git 提交信息生成** | ⭐⭐⭐⭐ | `git/` 整个目录 | ✅ 高（复用 CLI 聊天） |
| 3 | **IDE 终端集成（Shell Integration）** | ⭐⭐⭐ | `terminal/` 整个目录 | ⚠️ 中（OSC 633 解析可移植） |
| 4 | **URL 协议认证（OAuth 回调）** | ⭐⭐ | `commands/KiloCodeAuthProtocolCommand.kt` | ⚠️ 取决于认证模型 |
| 5 | **资源泄漏监控（Thread/Disposable/Scope）** | ⭐（开发诊断） | `monitoring/` 整个目录 | ✅ 高（纯 IntelliJ，独立） |

### 🟡 部分实现 / 质量差距（新版有基础但远不如旧版）

| # | 功能 | 旧版 | 新版现状 | 差距 |
|---|------|------|---------|------|
| 6 | **代码选中操作菜单** | Explain/Fix/Improve/Add-to-Context 四动作 + Prompt 模板引擎 + 诊断接入 + 「填充 vs 发送」分流 | `custom/SendSelectionAction` 单一动作 + 硬编码中文 prompt | 缺多动作、缺模板系统、缺诊断接入 |

### ✅ 已具备等价或更优能力（非差距）

| 功能 | 旧版 | 新版 | 说明 |
|------|------|------|------|
| 国际化 i18n | 自研 DynamicBundle | ✅ 18 种语言 ResourceBundle | 新版更完善 |
| 拖拽文件 | `DragDropHandler`（mock DOM 事件） | ✅ `DnDSupport` + `SessionDropOverlay` | 新版原生 Swing 更稳 |
| 多文件 Diff | `MultiDiffCommands`（SimpleDiffRequestChain） | ✅ `KiloDiffEditorContent` + `DiffFullReconstruct` + LRU 缓存 | 新版更完善（全文重建） |
| 聊天 UI | JCEF React | ✅ Swing SessionModel 三层架构 | 架构不同，功能等价 |
| 文件/选择发送 | 右键 Chat 菜单 | ✅ `SendFileAction` / `SendSelectionAction` | 新版有基础 |

---

## 三、重点缺失功能详解

### 1. 行内代码补全（Inline Completion）⭐⭐⭐⭐⭐

**用户场景**：编辑代码时实时显示灰色幽灵文本（ghost text）作为 AI 补全建议，Tab 接受——类似 GitHub Copilot 的核心体验。

**旧版实现要点**：
- `KiloCodeInlineCompletionProvider` 实现 IntelliJ `InlineCompletionProvider` 接口
- 捕获光标位置 + 语言 ID，发送**完整文件内容**给 AI
- `InlineCompletionService` 含 10 秒超时 + `AtomicReference<requestId>` 防竞态（丢弃过期响应）
- `InlineCompletionManager` 通过 Extension Point 动态注册 Provider，支持 document selector
- 接受补全后触发 telemetry 上报

**新版现状**：插件描述文本声称支持 "inline autocomplete"，但**代码中无任何 `InlineCompletionProvider` 实现**。`KiloAutocompleteSettingsService` 仅用于迁移旧版设置（持久化开关），不提供实际补全功能。

**可移植性**：⚠️ **需 CLI 支持**。需 CLI 暴露 `POST /completions` 端点。IntelliJ 侧的 Provider 注册、幽灵文本渲染、防竞态逻辑可完全复用旧版设计。

**建议优先级**：**P1**（最高用户感知，但依赖 CLI 侧改造）

---

### 2. Git 提交信息生成（Commit Message Generator）⭐⭐⭐⭐

**用户场景**：在 Commit 对话框点击"Generate Message"按钮，AI 自动分析 diff 生成规范的 commit message 并回填输入框。

**旧版实现要点**：
- `CommitMessageHandlerFactory` 注册为 `CheckinHandlerFactory`，在 Commit 面板注入按钮
- `FileDiscoveryService` 用**三级策略**发现变更文件：
  1. `VcsDataKeys` 选中项（最精确）
  2. `CheckinProjectPanel` 待提交列表
  3. `ChangeListManager` 全量 fallback
- `WorkspaceResolver` 获取仓库路径
- 调用 AI 生成（30 秒超时）→ `panel.setCommitMessage()` 回填
- 也支持从 VCS 工具栏按钮触发，通过 `PENDING_COMMIT_MESSAGE_KEY` 实现"先生成再打开对话框"

**新版现状**：❌ **完全缺失**。无 `git4idea` 集成、无 `CheckinProjectPanel` 注入、无 commit message 生成。仅有 `@git-changes` 提及功能（把 diff 作为聊天上下文，但用户需手动输入"帮我写 commit message"）。

**可移植性**：✅ **高**。核心是「采集 diff → 发给 CLI 聊天 → 回填」。新版的 `DmcBridgeService.sendPrompt()` 或 `insertPromptText()` 可直接复用。`FileDiscoveryService` 的三级文件发现策略是纯 IntelliJ 逻辑，可直接移植。

**实现路径**：
```
custom/src/main/kotlin/com/dmc/git/
├── DmcCommitMessageHandlerFactory.kt   # CheckinHandlerFactory 注册
├── DmcFileDiscoveryService.kt          # 三级文件发现（移植旧版）
└── DmcCommitMessageGenerator.kt        # 调用 CLI 生成
```

**建议优先级**：**P0**（用户价值高 + 实现成本低 + 不依赖 CLI 改造）

---

### 3. IDE 终端集成（Shell Integration）⭐⭐⭐

**用户场景**：AI Agent 在 IDE 终端执行命令时，精确感知命令生命周期（开始/结束/退出码）、工作目录变化、命令输出——让 AI 像在 VSCode 终端里一样可控。

**旧版实现要点**：
- `TerminalInstance` 用 `ProxyPtyProcess` 包装 `PtyProcess`，拦截原始 STDOUT/STDERR 数据流
- `ShellIntegrationOutputState` 解析 **OSC 633 协议标记**（VSCode shell integration 标准），识别命令开始(C)/结束(D)/命令行(E)/属性(P, Cwd=)/提示符(A/B)，含 50ms 缓冲 flush
- `TerminalShellIntegration` 将解析后的事件通过 RPC 转发给 AI
- `TerminalCommands` 实现终端选中文本复制到剪贴板

**新版现状**：⚠️ **部分实现**。仅有终端输出**渲染**（`MdTerminal` 解码 ANSI/SGR、`ShellToolView` 渲染 shell 命令输出），但**无** IDE 终端集成——即 AI 无法接管/感知 IDE 内置终端。

**可移植性**：⚠️ **中**。`ShellIntegrationOutputState`（OSC 633 解析器）是纯协议解析，可完全移植。但新版 AI 执行命令走 CLI 自己的 bash tool，是否需要 IDE 终端集成取决于产品定位。

**建议优先级**：**P3**（新版 AI 已能通过 CLI 执行命令，IDE 终端集成是锦上添花）

---

### 4. URL 协议认证（OAuth 回调）⭐⭐

**用户场景**：用户在浏览器完成 OAuth 登录后，通过 `jetbrains://idea/...?token=HERE` URL 回调自动完成登录。

**旧版实现**：`KiloCodeAuthProtocolCommand` 继承 `JBProtocolCommand`，解析 URL token，转发完成认证。

**新版现状**：❌ **缺失**。新版认证可能走 CLI 自己的流程（API Key 配置等）。

**可移植性**：⚠️ 取决于新版认证模型。若 CLI 支持 OAuth，可用 `JBProtocolCommand` 模式实现回调。

**建议优先级**：**P4**（待确认新版认证需求）

---

### 5. 资源泄漏监控 ⭐（开发诊断工具）

**用户场景**：插件运行时监控线程数、内存、Disposable 和 CoroutineScope 生命周期，诊断资源泄漏。

**旧版实现**：
- `ThreadMonitor`：每 60 秒检查线程数（>500 告警，>1000 dump 线程栈）+ 内存日志
- `DisposableTracker`：`ConcurrentHashMap` 全局注册所有 Disposable，支持 `logActiveDisposables()` / `disposeAll()`
- `ScopeRegistry`：追踪所有 CoroutineScope 的 active/inactive 状态

**新版现状**：❌ **缺失**。

**可移植性**：✅ **高**——纯 IntelliJ 基础设施，不涉及 RPC/CLI，三个 tracker 可直接移植。

**建议优先级**：**P3**（提升插件稳定性，开发期尤其有用）

---

### 6. 代码选中操作菜单（质量差距）⭐⭐⭐⭐

**用户场景**：选中代码右键，出现 Explain / Fix / Improve / Add-to-Context 等智能动作。

**旧版 vs 新版对比**：

| 维度 | 旧版 | 新版 custom |
|------|------|------------|
| 动作数量 | 4 个（Explain/Fix/Improve/Add-to-Context） | 1 个（Send Selection） |
| Prompt 系统 | 模板引擎 + `${placeholder}` 替换 | 硬编码中文 prompt |
| 诊断接入 | FIX 动作接入 IDE 编译错误/警告 | ❌ 无 |
| 交互分流 | 「填充输入框」vs「立即发送」区分 | 仅 `insertPromptText`（填充） |
| 行为语义 | Add-to-Context 只填不发；其余立即发 | 统一追加固定问句 |

**新版 custom 现有问题**：
- `SendSelectionAction` 末尾硬编码 `"请基于以上代码片段分析，处理一下问题"`（疑似笔误，应为"以下问题"）
- `DmcBridgeService` 已定义但**从未被调用**（死代码）
- 构建的 `PromptPartDto` 对象**未实际使用**（死代码），只走纯文本插入

**可移植性**：✅ **极高**——纯 custom 模块增强，参考旧版 `ActionConstants.kt` 的模板系统即可。

**实现路径**：增强 `custom/src/main/kotlin/com/dmc/actions/`，引入旧版的 `SupportPrompt` 模板机制，拆分为多动作。

**建议优先级**：**P0**（用户价值高 + 实现成本最低 + 已有基础）

---

## 四、实现优先级建议

按「用户价值 × 实现成本」排序：

| 优先级 | 功能 | 用户价值 | 成本 | 依赖 | 理由 |
|--------|------|---------|------|------|------|
| **P0** | 代码选中操作菜单增强 | ⭐⭐⭐⭐ | 低 | 无 | 已有基础，最快见效；修复死代码；引入多动作+模板 |
| **P0** | Git 提交信息生成 | ⭐⭐⭐⭐ | 低-中 | 无 | 复用 CLI 聊天 + CheckinHandler；FileDiscoveryService 可移植 |
| **P1** | 行内代码补全 | ⭐⭐⭐⭐⭐ | 高 | **需 CLI 暴露补全 API** | 最高用户感知，但需 CLI 侧配合 |
| **P2** | 资源监控移植 | ⭐⭐ | 低 | 无 | 提升稳定性，纯基础设施可快速移植 |
| **P3** | IDE 终端集成 | ⭐⭐⭐ | 高 | OSC 633 解析 | 新版 AI 已能执行命令，优先级降低 |
| **P4** | URL 协议认证 | ⭐⭐ | 中 | 待确认认证模型 | 取决于产品认证方案 |

---

## 五、关键源码索引

### 旧版（借鉴来源）

| 功能 | 文件路径 |
|------|---------|
| 行内补全 | `inline/KiloCodeInlineCompletionProvider.kt`、`inline/InlineCompletionService.kt`、`inline/InlineCompletionManager.kt` |
| 终端集成 | `terminal/TerminalInstance.kt`、`terminal/ShellIntegrationOutputState.kt`（OSC 633 解析）、`terminal/TerminalShellIntegration.kt` |
| Git 提交信息 | `git/CommitMessageHandlerFactory.kt`、`git/CommitMessageHandler.kt`、`git/CommitMessageService.kt`、`git/FileDiscoveryService.kt`（三级发现）、`git/WorkspaceResolver.kt` |
| 多文件 Diff | `editor/MultiDiffCommands.kt` |
| 拖拽 | `webview/DragDropHandler.kt` |
| 资源监控 | `monitoring/ThreadMonitor.kt`、`monitoring/DisposableTracker.kt`、`monitoring/ScopeRegistry.kt` |
| 文档同步 | `service/DocumentSyncService.kt`（`shouldHandleFileEvent` 过滤规则可复用） |
| 国际化 | `i18n/I18n.kt`、`i18n/I18nUtils.kt` |
| URL 认证 | `commands/KiloCodeAuthProtocolCommand.kt` |
| 代码选中动作 | `actions/RegisterCodeActions.kt`、`actions/ActionConstants.kt`（SupportPrompt 模板） |

### 新版（改造目标）

| 功能 | 文件路径 |
|------|---------|
| 定制动作（待增强） | `custom/src/main/kotlin/com/dmc/actions/SendSelectionAction.kt`、`SendFileAction.kt` |
| 会话桥接 | `custom/src/main/kotlin/com/dmc/bridge/DmcBridgeService.kt`（死代码待启用）、`DmcSessionResolver.kt` |
| 动作注册 | `custom/src/main/resources/kilo.jetbrains.custom.xml` |
| 聊天管理 | `backend/src/main/kotlin/ai/kilocode/backend/app/KiloBackendChatManager.kt` |
| 会话 RPC | `backend/src/main/kotlin/ai/kilocode/backend/rpc/KiloSessionRpcApiImpl.kt` |
| Diff 系统 | `backend/src/main/kotlin/ai/kilocode/backend/diff/DiffFullReconstruct.kt` |
| 迁移（含旧版 autocomplete 设置） | `backend/src/main/kotlin/ai/kilocode/backend/migration/` |

---

## 六、总结

新版插件在**核心聊天能力**（CLI 集成、SSE 流式、工具调用可视化、多文件 diff、权限审批、18 语言 i18n）上已超越旧版，且架构更现代（纯 Swing、无 JCEF）。

**最值得补齐的 3 个功能**（按 ROI 排序）：

1. **Git 提交信息生成**——低成本高价值，CheckinHandler + CLI 聊天即可，1-2 天可落地
2. **代码选中操作菜单增强**——零外部依赖，复用旧版 Prompt 模板系统，顺便清理死代码
3. **行内代码补全**——最高用户感知，但需推动 CLI 暴露补全 API，周期较长
