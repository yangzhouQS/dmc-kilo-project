# MCP 工具可行性分析：编译错误 / 选区 / 发送至对话

## 一、验证结论（先说结论）

| 需求 | 可行性 | 推荐方案 |
|---|---|---|
| 获取编译错误 | **可行** | IntelliJ Build/Problems API（插件原生） |
| 获取选中内容 | **可行** | IntelliJ Editor API（插件原生） |
| 选择文件 → 发送至对话 | **已具备基础设施** | `SessionController.prompt(text, fileParts)` |
| 选择代码片段 → 发送至对话 | **已具备基础设施** | file part + `PartSourceTextDto` 行范围 |
| 用 **MCP** 实现上述功能 | **可行但过度设计** | 不推荐，原因见下 |

**核心结论**：上述 4 个需求 **不需要 MCP**。JetBrains 插件已经持有 CLI 的完整 HTTP 访问能力（端口 + 密码），且 `SessionController.prompt(text, files)` 已支持文件/选区作为 `PromptPartDto` 发送到对话。缺的只是「读取 IDE 状态」和「一个触发 Action」这两块拼图。

MCP 是为「**让 AI agent 主动调用工具**」设计的，而本场景是「**用户主动把错误/选区推给对话**」，方向相反，强用 MCP 反而引入鸡生蛋问题。

---

## 二、关键架构发现

### 1. 通信方向：JetBrains 永远是客户端，CLI 永远是服务端

```
JetBrains 插件  ──spawns──>  kilo serve --port 0 (HTTP server)
                ──HTTP REST──>  POST /session/{id}/prompt_async
                <──SSE──────  GET /global/event (事件流)
```

来源：`KiloBackendCliManager.kt:154` 生成命令，`KiloBackendChatManager.kt:177` 发送 prompt，`AGENTS.md` Server Protocol 章节。

**关键点**：CLI 无法反向调用 JetBrains。MCP 在本项目中 **CLI 永远是 MCP 客户端**（`packages/opencode/src/mcp/index.ts:16` 全部是 client 导入，无 server 实现）。若要让 agent 调用 JetBrains 内的工具，JetBrains 必须自己起一个 HTTP MCP server，再用 `POST /mcp` 反向注册自己——这是可行的但复杂。

### 2. 发送对话的完整链路已存在

```
SessionController.prompt(text, files)          [frontend, SessionController.kt:264]
  → PromptDto(parts = [text part, file parts...])  [:1823]
  → KiloSessionService.prompt(id, dir, dto)     [RPC]
  → KiloBackendChatManager.prompt()             [backend, KiloBackendChatManager.kt:166]
  → POST /session/$id/prompt_async              [HTTP]
```

`PromptPartDto` 结构（`ChatDto.kt:128-135`）已支持：

```kotlin
data class PromptPartDto(
    val type: String,           // "text" | "file"
    val text: String? = null,
    val mime: String? = null,
    val url: String? = null,    // "file://相对路径"
    val filename: String? = null,
    val source: PartSourceDto? = null,  // ← 选区/符号信息
)
```

`PartSourceDto`（`ChatDto.kt:91-99`）携带选区文本 + 行范围：

```kotlin
data class PartSourceDto(val type: String, val text: PartSourceTextDto, val path: String?, ...)
data class PartSourceTextDto(val value: String, val start: Double, val end: Double)
```

`KiloCliDataParser.buildPromptJson()`（`:798`）和 `buildPromptPartJson()`（`:829`）已正确序列化 file part + source。

### 3. 缺失的两块拼图

| 缺失能力 | VS Code 扩展是否有 | JetBrains 是否有 |
|---|---|---|
| 读取当前编辑器选中文本 | 有 `vscode.window.activeTextEditor.selection` | 无 |
| 读取当前打开文件路径 | 有 `gatherEditorContext()` `KiloProvider.ts:4661` | 无 editorContext 发送 |
| 读取编译/构建错误 | 无 | 无 |
| 选区/文件 → PromptPartDto | 有 `message-files.ts` | DTO 已定义，但无调用方 |

---

## 三、推荐方案：JetBrains 原生 Action（非 MCP）

### 方案架构

```
┌─────────────────────────────────────────────────────┐
│  JetBrains 插件 (已有进程，已有 CLI 连接)              │
│                                                      │
│  ① 新增 Action（右键菜单 / 快捷键）                    │
│     ├─ "Send Build Errors to Kilo"                   │
│     ├─ "Send Selection to Kilo"                      │
│     └─ "Send File to Kilo"                           │
│                                                      │
│  ② 新增 EditorContextService（读取 IDE 状态）          │
│     ├─ 选中文本: selectionModel.selectedText          │
│     ├─ 文件路径: FileDocumentManager                  │
│     └─ 构建错误: ProblemView / Build API              │
│                                                      │
│  ③ 构建 PromptPartDto                                │
│     └─ 直接调用 SessionController.prompt()  ◄── 已存在 │
└─────────────────────────────────────────────────────┘
                        │ HTTP (已有连接)
                        ▼
              kilo serve → AI 修复
```

### 各需求实现要点

#### 需求 1：获取编译错误

IntelliJ 有多种错误来源，按可靠性排序：

```kotlin
// 方案 A：读取 Problems 工具窗口（最通用，覆盖编译+检查+外部标注）
val toolWindow = ToolWindowManager.getInstance(project).getToolWindow("Problems")
// 获取 ContentManager → 提取当前文件或全项目的 error 项

// 方案 B：读取当前文件的代码检查结果（DaemonCodeAnalyzer）
val errors = mutableListOf<HighlightInfo>()
val highlights = DaemonCodeAnalyzer.getInstance(project).runMainPasses(file, editor.document)
// 过滤 severity == ERROR

// 方案 C（推荐组合）：优先读 Problems 面板，降级读 DaemonCodeAnalyzer
```

关键 API（均为公开 API）：

- `ToolWindowManager.getInstance(project).getToolWindow("Problems")` — 读取问题面板
- `DaemonCodeAnalyzer.getInstance(project)` — 当前文件检查结果
- `com.intellij.codeInsight.daemon.DaemonCodeAnalyzerImpl` — 获取已高亮的问题
- `InspectionProfile` / `GlobalInspectionContext` — 全项目级别

#### 需求 2：获取选中的内容

```kotlin
// 标准 IntelliJ API，所有操作在 EDT 上
val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: return
val selection = editor.selectionModel
val selectedText = selection.selectedText        // 选中文本
val startLine = selection.selectionStartPosition?.line
val endLine = selection.selectionEndPosition?.line
val vFile = FileDocumentManager.getInstance().getFile(editor.document)
val relativePath = project.basePath?.let { base -> vFile?.path?.removePrefix(base)?.removePrefix("/") }
```

#### 需求 3 & 4：发送文件/选区到对话

```kotlin
// 构建 file part（文件引用）
val filePart = PromptPartDto(
    type = "file",
    mime = "text/plain",
    url = "file://$relativePath",      // CLI 会从磁盘读取
    filename = fileName,
)

// 构建 file part（带选区范围）— 让 AI 直接看到选中的代码片段
val selectionPart = PromptPartDto(
    type = "file",
    mime = "text/plain",
    url = "file://$relativePath",
    filename = fileName,
    source = PartSourceDto(
        type = "file",
        path = relativePath,
        text = PartSourceTextDto(
            value = selectedText,
            start = startLine.toDouble(),
            end = (endLine + 1).toDouble(),
        ),
    ),
)

// 发送 — 复用现有 SessionController
sessionController.prompt(
    "请修复以下编译错误：\n$errorSummary",
    files = listOf(selectionPart),   // 可叠加多个 part
)
```

`SessionController.prompt(text, files)` 的实现（`SessionController.kt:264`）会把 text 和 files 合并进 `PromptDto.parts`（`:1827-1830`），然后走现有的 RPC → HTTP 链路发送。**零新增通信代码。**

---

## 四、为什么不推荐 MCP 方案（对比说明）

| 维度 | 原生 Action 方案 | MCP Server 方案 |
|---|---|---|
| 触发方式 | 用户主动右键/快捷键 | AI agent 主动调用工具 |
| 通信复杂度 | **零新增**（复用现有 HTTP 连接） | 需在 JetBrains 内新建 HTTP server + 反向注册 |
| 鸡生蛋问题 | 无 | JetBrains 需先等 CLI 启动→发现端口→再 `POST /mcp` 注册自己 |
| 代码量 | ~200 行（Action + EditorContextService） | ~600+ 行（MCP server 协议实现 + 工具定义 + 注册逻辑） |
| Agent 是否知道何时调用 | 不需要 | 需要写 tool description 让 agent 理解何时该查错误 |
| 适合场景 | 用户驱动：选中→发送→修复 | Agent 驱动：AI 自主排查 |

**结论**：本场景明确是「用户选中→发送→修复」，这是用户驱动的工作流。原生 Action 方案就是 VS Code 扩展的做法（`KiloProvider.ts:3676` 的 `promptAsync` + file parts），JetBrains 插件应当对齐这个模式。
