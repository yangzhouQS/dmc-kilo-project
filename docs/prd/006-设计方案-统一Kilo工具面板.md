# Kilo 工具面板 — 统一设计方案

> 将 KiloWiki + KiloPrompts 合并为统一的 KiloTools 面板，左右分栏布局。

---

## 一、布局结构

```
┌─ Kilo 工具 ───────────────────────────────────────────────────┐
│ ┌─ 左侧导航 ──────┐  ┌─ 右侧内容（随导航切换）──────────────┐ │
│ │                  │  │                                      │ │
│ │  📁 Wiki 文档    │  │  【Wiki 文档】                        │ │
│ │  📇 知识卡片     │  │                                      │ │
│ │  🧠 记忆         │  │  文档列表 + 搜索...                   │ │
│ │  ─────────────  │  │                                      │ │
│ │  💬 提示词库     │  │  （选中 Wiki 时显示）                  │ │
│ │  🔧 MCP 工具     │  │                                      │ │
│ │  ─────────────  │  │  （选中 提示词库 时切换为              │ │
│ │  ⚙️ 设置         │  │   提示词列表 + 编辑面板）              │ │
│ │                  │  │                                      │ │
│ └──────────────────┘  └──────────────────────────────────────┘ │
└────────────────────────────────────────────────────────────────┘
```

---

## 二、导航项

| 图标 | 名称 | 内容面板 | 来源 |
|---|---|---|---|
| 📁 | Wiki 文档 | 文件列表 + 预览 | 复用 `WikiBrowserPanel` |
| 📇 | 知识卡片 | 卡片列表 | 复用 `KnowledgeCardPanel` |
| 🧠 | 记忆 | 搜索 + 列表 | 复用 `MemoryPanel` |
| 💬 | 提示词库 | 列表 + 编辑 + AI优化 | 复用 `PromptMainPanel` |
| 🔧 | MCP 工具 | 工具列表 + 选择 | 新建 `McpToolsPanel` |

---

## 三、文件清单

| 文件 | 操作 | 说明 |
|---|---|---|
| `tools/KiloToolWindowFactory.kt` | 新建 | 统一面板工厂 + 左侧导航 + 右侧切换 |
| `tools/McpToolsPanel.kt` | 新建 | MCP 工具列表（读 mcp-tools.json） |
| `kilo.jetbrains.custom.xml` | 修改 | 删除 KiloWiki + KiloPrompts，新增 KiloTools |
| `WikiToolWindowFactory.kt` | 保留 | Wiki/卡片/记忆面板类不变，仅不再注册为独立 ToolWindow |
| `PromptToolWindowFactory.kt` | 保留 | PromptMainPanel 类不变，仅不再注册为独立 ToolWindow |

### 上游改动

**0 处。**

---

## 四、核心代码结构

```kotlin
class KiloToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = KiloToolsPanel(project)
        val content = ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }
}

class KiloToolsPanel(project: Project) : JPanel(BorderLayout()) {

    private val navItems = listOf(
        NavItem("Wiki 文档", AllIcons.FileTypes.Text) { WikiBrowserPanel(project) },
        NavItem("知识卡片", AllIcons.Nodes.Folder) { KnowledgeCardPanel(project) },
        NavItem("记忆", AllIcons.Actions.Preview) { MemoryPanel(project) },
        NavItem("提示词库", AllIcons.Actions.EditSource) { PromptMainPanel(project) },
        NavItem("MCP 工具", AllIcons.Nodes.DataTables) { McpToolsPanel(project) },
    )

    init {
        val splitter = JBSplitter(false, 0.18f)
        splitter.firstComponent = createNavPanel()
        splitter.secondComponent = createContentPanel()
        add(splitter, BorderLayout.CENTER)
    }
}
```

---

## 五、XML 改动

**删除**：
```xml
<toolWindow id="KiloWiki" ... />
<toolWindow id="KiloPrompts" ... />
```

**新增**：
```xml
<toolWindow
    id="KiloTools"
    anchor="right"
    icon="AllIcons.Toolwindows.ToolWindowToolset"
    factoryClass="com.dmc.tools.KiloToolWindowFactory"/>
```

---

## 六、扩展机制

后续新增功能只需在 `navItems` 列表添加一项：

```kotlin
NavItem("代码审查", AllIcons.Actions.Inspect) { CodeReviewPanel(project) },
NavItem("Git 工具", AllIcons.Vcs.Vcs) { GitToolsPanel(project) },
```

---

## 七、验证用例

| # | 操作 | 预期 |
|---|---|---|
| 1 | IDE 右侧出现 "Kilo 工具" 面板 | ✅ |
| 2 | 点击 "Wiki 文档" | 右侧显示 Wiki 列表 |
| 3 | 点击 "提示词库" | 右侧切换为提示词编辑面板 |
| 4 | 点击 "MCP 工具" | 右侧显示 MCP 工具列表 |
| 5 | 导航项选中态高亮 | ✅ |
| 6 | 旧的 KiloWiki / KiloPrompts 面板不再出现 | ✅ |
