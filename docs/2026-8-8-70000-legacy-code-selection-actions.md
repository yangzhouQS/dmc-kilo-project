# 旧版插件「选中代码块」操作分析与借鉴

> 调研对象：`kilocode-legacy-main/jetbrains/plugin/`（Kilo Code JetBrains 旧版插件）
> 目的：梳理「在编辑器中选中代码块后」可用的所有操作，提炼实现思路与交互习惯，供 DMC 定制模块借鉴。

---

## 一、操作总览

选中代码块后，旧版插件在**编辑器右键菜单**中注入一个分组 `Kilo Code > Chat`，该分组**动态生成**子动作，且**仅当存在选中文本时才可见**。核心共 4 个操作：

| # | 菜单文案 | Command ID | 行为模式 | 发送到 Webview 的指令 |
|---|---------|-----------|---------|---------------------|
| 1 | **Add to Context** | `kilo-code.addToContext` | 仅填充输入框，**不自动发送** | `setChatBoxMessage` |
| 2 | **Explain Code in Current Task** | `kilo-code.explainCodeInCurrentTask` | 在当前会话**立即发送** | `sendMessage` |
| 3 | **Fix Logic in Current Task** | `kilo-code.fixCodeInCurrentTask` | 在当前会话**立即发送** | `sendMessage` |
| 4 | **Improve Code in Current Task** | `kilo-code.improveCodeInCurrentTask` | 在当前会话**立即发送** | `sendMessage` |

> 旧版曾设计「New Task / Current Task」成对动作（`createActionPair`），当前代码中已精简为**仅保留 "In Current Task"** 一个分支。

除右键菜单外，还有两类操作（不依赖代码选中，但属同一交互体系）：

- **工具栏按钮动作**：New Task / History / Profile / Settings / Marketplace（`VSCodeCommandActions.kt`）
- **Git 提交信息**：`Generate Commit Message`（注入 VCS 提交面板，`GitCommitMessageAction.kt`）

---

## 二、菜单注册结构（plugin.xml.template）

关键在于用**动态分组（`class=` 指向 `DefaultActionGroup` 子类）**而非静态 `<action>` 列表：

```xml
<group id="kilocode.RightClickMenu" text="Kilo Code"
       description="kilocode main menu"
       icon="/icons/kilo-dark-small.svg" popup="true">
    <!-- 动态分组：内容由 Kotlin 代码运行时填充 -->
    <group id="kilocode.RightClick.Chat"
           class="ai.kilocode.jetbrains.actions.RightClickChatActionGroup"
           text="Chat" description="kilocode chat tool"/>
    <!-- 锚定到编辑器右键菜单的第一位 -->
    <add-to-group group-id="EditorPopupMenu" anchor="first"/>
</group>
```

借鉴要点：
- **父分组** `popup="true"` 自带图标，子分组用 `class=` 动态装载。
- **`add-to-group group-id="EditorPopupMenu"`** 是挂载到编辑器右键菜单的标准入口。
- 独立 Action（如 Plus/History）也单独声明，并通过 `<group>` + `<reference>` 组成工具栏。

---

## 三、动态分组实现（RightClickChatActionGroup.kt）

```kotlin
class RightClickChatActionGroup : DefaultActionGroup(), DumbAware, ActionUpdateThreadAware {
    private val codeActionProvider = CodeActionProvider()

    override fun update(e: AnActionEvent) {
        removeAll()                              // 每次菜单弹出前清空重建
        val editor = e.getData(CommonDataKeys.EDITOR)
        val hasSelection = editor?.selectionModel?.hasSelection() == true
        if (hasSelection) loadDynamicActions(e)  // 有选区才装载子动作
        e.presentation.isVisible = hasSelection  // 无选区时整个分组隐藏
    }

    override fun getActionUpdateThread() = ActionUpdateThread.EDT
}
```

借鉴要点（交互习惯）：
1. **`removeAll()` + 重建**：`update()` 每次菜单显示时调用，必须先清空再装载，否则会重复。
2. **可见性绑定选区**：`isVisible = hasSelection()`——无选中代码时整个 "Chat" 分组消失，避免菜单噪声。
3. **`DumbAware`**：保证索引期间（Dumb 模式）菜单仍可用。
4. **`ActionUpdateThread.EDT`**：`update()` 涉及读选区，必须在 EDT 上执行。

---

## 四、子动作与上下文提取（RegisterCodeActions.kt）

### 4.1 选区数据结构

```kotlin
data class EffectiveRange(
    val text: String,      // selectedText
    val startLine: Int,    // 0-based
    val endLine: Int,      // 0-based
)
```

提取逻辑（`getEffectiveRange`）：
- 仅在 `selectionModel.hasSelection()` 时返回有效值，否则 `null`。
- 起止行通过 `document.getLineNumber(selectionStart/selectionEnd)` 计算（**0-based**）。
- **发送给 Webview 时统一 +1 转 1-based**（`startLine + 1`、`endLine + 1`）。

### 4.2 动态创建 Action（工厂模式）

```kotlin
private fun createAction(title: String, command: String): AnAction {
    return object : AnAction(title) {
        override fun actionPerformed(e: AnActionEvent) {
            val project = e.project ?: return
            val editor = e.getData(CommonDataKeys.EDITOR) ?: return
            val file = e.dataContext.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
            val effectiveRange = getEffectiveRange(editor) ?: return
            val args = mapOf(
                "filePath"     to file.path,
                "selectedText" to effectiveRange.text,
                "startLine"    to effectiveRange.startLine + 1,  // 1-based
                "endLine"      to effectiveRange.endLine + 1,    // 1-based
            )
            handleCodeAction(command, title, args, project)
        }
    }
}
```

借鉴要点：
- **匿名 `AnAction` 子类**按需创建，标题与命令分离（`title` 给用户看，`command` 给后端用）。
- 上下文三件套：`filePath` / `selectedText` / `startLine`-`endLine`，是发给 LLM 的标准上下文载荷。
- 通过 `e.dataContext.getData(CommonDataKeys.VIRTUAL_FILE)` 拿当前文件路径。

### 4.3 动作装载顺序（CodeActionProvider.provideCodeActions）

```
Add to Context
Explain Code (in Current Task)
Fix Logic   (in Current Task)
Improve Code(in Current Task)
```

顺序即菜单从上到下顺序——先「加上下文」（只读不触发），后三个是「触发任务」。

---

## 五、Prompt 模板系统（ActionConstants.kt）

这是借鉴价值最高的部分——**模板 + 占位符替换**，把交互动作与提示词解耦。

### 5.1 模板定义（节选）

```
EXPLAIN:  "Explain the following code from file path ${filePath}:${startLine}-${endLine} ..."
FIX:      "Fix any issues in the following code ... ${diagnosticText} ..."  // 支持诊断信息
IMPROVE:  "Improve the following code ... readability/performance/best practices ..."
ADD_TO_CONTEXT: "${filePath}:${startLine}-${endLine}\n```\n${selectedText}\n```"  // 纯引用，无指令
```

### 5.2 占位符替换引擎

```kotlin
private fun createPrompt(template: String, params: PromptParams): String {
    val pattern = Regex("""\$\{(.*?)}""")
    return pattern.replace(template) { mr ->
        val key = mr.groupValues[1]
        when {
            key == "diagnosticText" -> generateDiagnosticText(params["diagnostics"])  // 特殊处理
            params.containsKey(key) -> params[key]?.toString() ?: ""
            else -> ""
        }
    }
}
```

借鉴要点：
- 占位符语法 `${key}`，缺失的 key 替换为空串（容错）。
- `diagnosticText` 是**特殊占位符**：从 IDE 诊断（编译错误/告警）动态拼接，让 FIX 动作能感知具体问题。
- 支持自定义模板覆盖（`customSupportPrompts`）。

### 5.3 各操作的语义化 Prompt 特征

| 操作 | Prompt 是否含「指令性内容」 | 是否含诊断 |
|------|--------------------------|-----------|
| ADD_TO_CONTEXT | ❌ 纯代码引用 | ❌ |
| EXPLAIN | ✅ 要求结构化解释（目的/组件/模式） | ❌ |
| FIX | ✅ 要求修复+解释 | ✅ `${diagnosticText}` |
| IMPROVE | ✅ 要求可读性/性能/最佳实践/健壮性四维改进 | ❌ |

---

## 六、与 Webview 通信机制（handleCodeAction）

选中代码后最终通过 **postMessage** 把组装好的 prompt 推给前端会话：

```kotlin
val messageContent = when {
    command.contains("addToContext") ->
        mapOf("type" to "invoke", "invoke" to "setChatBoxMessage",
              "text" to SupportPrompt.create("ADD_TO_CONTEXT", params))
    command.endsWith("InCurrentTask") ->
        mapOf("type" to "invoke", "invoke" to "sendMessage",
              "text" to SupportPrompt.create(basePromptType, params))
    else ->
        mapOf("type" to "invoke", "invoke" to "initClineWithTask",
              "text" to SupportPrompt.create(basePromptType, params))
}
latestWebView.postMessageToWebView(Gson().toJson(messageContent))
```

三种 `invoke` 指令语义（核心交互习惯）：

| invoke 指令 | 含义 | 适用场景 |
|------------|------|---------|
| `setChatBoxMessage` | 填入输入框，**等待用户编辑后再发** | Add to Context |
| `sendMessage` | **立即在当前任务中发送并执行** | Explain/Fix/Improve in Current Task |
| `initClineWithTask` | **新建一个任务并发送** | New Task 变体（当前未挂菜单） |

借鉴要点：
- **「填充 vs 立即发」二分**是关键 UX 设计：上下文类操作不抢跑，触发类操作一气呵成。
- 通过 JSON 协议 + WebView 通道，Kotlin 侧只管「采集选区 + 组装 prompt」，AI 逻辑全在 Webview/扩展侧。

---

## 七、终端选中文本操作（参考扩展）

`ActionConstants.kt` 中还预定义了终端选区的 Prompt 模板（由 VSCode 扩展侧通过 RPC 调用，JetBrains 侧通过 `TerminalCommands.kt` 的 `copySelection` 等命令对接）：

| 模板 | 用途 |
|------|------|
| `TERMINAL_ADD_TO_CONTEXT` | 把终端输出加到上下文 |
| `TERMINAL_FIX` | 让 AI 修复终端报错的命令 |
| `TERMINAL_EXPLAIN` | 让 AI 解释终端命令 |
| `workbench.action.terminal.copySelection` | 复制终端选中文本到剪贴板 |

借鉴要点：终端与编辑器**复用同一套 Prompt 模板引擎**，仅占位符不同（`${terminalContent}` 替代 `${selectedText}`）。

---

## 八、给 DMC 定制模块的借鉴清单

### 8.1 必须借鉴的实现思路
1. **动态 `DefaultActionGroup` + `update()` 控制可见性**：菜单项按选区动态显隐，无选中时分组整体隐藏。
2. **`EffectiveRange` 上下文采集**：`filePath + selectedText + startLine(1-based) + endLine(1-based)` 是最小可用载荷。
3. **Prompt 模板引擎**：`${key}` 占位符 + 正则替换，动作与提示词解耦，便于后续扩展自定义动作。
4. **三种 `invoke` 语义**：`setChatBoxMessage`（填充）/ `sendMessage`（当前任务发）/ `initClineWithTask`（新任务发）。
5. **`DumbAware` + `ActionUpdateThread.EDT`**：保证菜单稳定与线程安全。

### 8.2 建议的交互习惯
- **顺序约定**：先放「加上下文」（低风险、可编辑），再放「触发任务」（执行类）。
- **成对设计预留**：保留「Current Task / New Task」扩展点，即便当前只实现其一。
- **诊断信息接入**：FIX 类动作优先接入 IDE 诊断（编译错误/警告），效果远胜纯代码上下文。
- **行号统一 1-based 对外**：内部 0-based（IntelliJ API），对前端/LLM 统一 +1。
- **占位符容错**：缺失 key 不报错，替换为空串。

### 8.3 DMC 实现路径（遵循 custom/ 隔离铁律）
```
packages/kilo-jetbrains/custom/
├── src/main/resources/dmc.custom.xml       # 注册 <group class=...> 到 EditorPopupMenu
└── src/main/kotlin/com/dmc/actions/
    ├── DmcRightClickActionGroup.kt         # = RightClickChatActionGroup
    ├── DmcCodeActionProvider.kt            # = CodeActionProvider
    └── DmcPromptTemplates.kt              # = SupportPromptConfigs
```

---

## 九、关键源码索引

| 关注点 | 文件路径 |
|-------|---------|
| 动态菜单分组 | `actions/RightClickChatActionGroup.kt:20` |
| 子动作工厂 + 上下文采集 | `actions/RegisterCodeActions.kt:19`（Provider）、`:142`（EffectiveRange）、`:229`（handleCodeAction） |
| 动作名/命令 ID 常量 | `actions/ActionConstants.kt:12`（ActionNames）、`:36`（CommandIds） |
| Prompt 模板 + 替换引擎 | `actions/ActionConstants.kt:69`（SupportPromptConfigs）、`:225`（SupportPrompt） |
| 菜单注册 | `resources/META-INF/plugin.xml.template:114`（RightClickMenu） |
| Webview 通信 | `actions/RegisterCodeActions.kt:229`（postMessageToWebView） |
| 工具栏按钮动作 | `actions/VSCodeCommandActions.kt` |
| 终端选区 Prompt | `actions/ActionConstants.kt:154`（TERMINAL_*） |
| Git 提交信息 | `actions/GitCommitMessageAction.kt` |
