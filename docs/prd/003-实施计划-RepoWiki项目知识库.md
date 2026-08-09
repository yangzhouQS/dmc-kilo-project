# RepoWiki 完整实施计划

> 基于 CLI 插件源码（`packages/plugin/src/`）、官方插件文档、JetBrains 插件架构综合分析。
> 两层插件混合架构，0 源码修改。

---

## 一、技术架构总览

### 1.1 双层协作模型

```
JetBrains IDE (JVM/Kotlin)          CLI 子进程 (Node.js/TypeScript)
┌──────────────────────┐            ┌──────────────────────────┐
│  custom/ 插件         │            │  .kilo/plugin/            │
│  ┌──────────────────┐ │            │  ┌─────────────────────┐ │
│  │ WikiToolWindow   │ │            │  │ repowiki.ts         │ │
│  │ (UI 面板)         │ │            │  │                     │ │
│  ├──────────────────┤ │   文件系统   │  │ system.transform ←──┼─┼─ 注入知识上下文
│  │ PsiScanner       │─┼─→ JSON ───→│  │ tool.wiki-generate  │ │
│  │ (代码结构分析)     │ │            │  │ tool.wiki-query     │ │
│  ├──────────────────┤ │            │  │ event.file-edited   │ │
│  │ Settings         │ │            │  │ chat.message        │ │
│  └──────────────────┘ │            │  └─────────────────────┘ │
└──────────────────────┘            └──────────────────────────┘
              ↓                                  ↓
     .kilo/repowiki/  ←——— 共享文件系统 ———→  .kilo/repowiki/
     ├── wiki/                              ├── wiki/
     ├── knowledge_cards/                   ├── knowledge_cards/
     ├── memory/                            ├── memory/
     └── .cache/psi-structure.json          └── .meta/hashes.json
```

### 1.2 关键 Hook 映射

| PRD 功能 | CLI Hook | 签名（已从源码验证） |
|---|---|---|
| 知识库上下文注入 | `experimental.chat.system.transform` | `(input: {sessionID?, model}, output: {system: string[]}) => Promise<void>` |
| Wiki 生成 | `tool["wiki-generate"]` | `ToolDefinition` with Zod args |
| 知识检索 | `tool["wiki-query"]` | `ToolDefinition` with Zod args |
| 文件变更监听 | `event` | `(input: {event: Event}) => Promise<void>` |
| `/remember` 命令 | `chat.message` | `(input, output: {message, parts}) => Promise<void>` |
| 文件修改追踪 | `tool.execute.after` | `(input: {tool, callID}, output: {title, output, metadata}) => Promise<void>` |
| 会话压缩保留 | `experimental.session.compacting` | `(input: {sessionID}, output: {context: string[]}) => Promise<void>` |

---

## 二、CLI 插件实施（`.kilo/plugin/repowiki.ts`）

### 2.1 文件结构

```
项目根/
├── .kilo/
│   ├── plugin/
│   │   └── repowiki.ts          ← CLI 插件入口
│   ├── repowiki/
│   │   ├── wiki_plan.yaml       ← 配置文件
│   │   ├── zh/
│   │   │   ├── wiki/            ← Wiki 文档
│   │   │   ├── knowledge_cards/ ← 知识卡片
│   │   │   └── memory/          ← 项目记忆
│   │   ├── .cache/
│   │   │   └── psi-structure.json  ← JetBrains 写入的代码结构
│   │   └── .meta/
│   │       └── hashes.json      ← 文件哈希（增量比对）
│   └── package.json             ← 插件依赖声明
```

### 2.2 完整 CLI 插件代码

```typescript
// .kilo/plugin/repowiki.ts
import type { Plugin } from "@kilocode/plugin"
import { tool } from "@kilocode/plugin/tool"
import { readFileSync, writeFileSync, readdirSync, existsSync, mkdirSync, statSync } from "node:fs"
import { join, relative, basename } from "node:path"

// ============================================================
// 配置常量
// ============================================================
const MAX_CONTEXT_CHARS = 6000      // System Prompt 注入上限
const MAX_SEARCH_RESULTS = 10       // 检索返回上限
const MAX_WIKI_FILES = 200          // Wiki 文档数量上限
const IGNORED_DIRS = new Set([
  ".git", "node_modules", "dist", "build", "out", "target",
  ".idea", ".vscode", "__generated__", ".gradle",
])

// ============================================================
// 文件读写工具
// ============================================================
function ensureDir(dir: string): void {
  if (!existsSync(dir)) mkdirSync(dir, { recursive: true })
}

function readText(path: string): string | null {
  try { return readFileSync(path, "utf-8") } catch { return null }
}

function writeText(path: string, content: string): void {
  ensureDir(join(path, ".."))
  writeFileSync(path, content, "utf-8")
}

function listMarkdownFiles(dir: string): string[] {
  if (!existsSync(dir)) return []
  try {
    return readdirSync(dir)
      .filter(f => f.endsWith(".md"))
      .map(f => join(dir, f))
  } catch { return [] }
}

// ============================================================
// 知识收集（System Prompt 注入用）
// ============================================================
function collectKnowledge(wikiBase: string): string {
  const lang = detectLanguage(wikiBase)
  const parts: string[] = []

  // 1. 知识卡片（优先级最高）
  const cardsDir = join(wikiBase, lang, "knowledge_cards")
  for (const f of listMarkdownFiles(cardsDir).slice(0, 3)) {
    const text = readText(f)
    if (text) parts.push(truncate(text, 2000))
  }

  // 2. 项目记忆（标签匹配优先）
  const memDir = join(wikiBase, lang, "memory")
  for (const f of listMarkdownFiles(memDir).slice(0, 5)) {
    const text = readText(f)
    if (text) parts.push(truncate(text, 500))
  }

  // 3. 全局记忆
  const globalMemDir = join(wikiBase, "..", "..", "global_memory")
  for (const f of listMarkdownFiles(globalMemDir).slice(0, 3)) {
    const text = readText(f)
    if (text) parts.push(truncate(text, 500))
  }

  if (parts.length === 0) return ""

  let result = "## 项目知识库上下文\n\n" + parts.join("\n\n---\n\n")
  return truncate(result, MAX_CONTEXT_CHARS)
}

function detectLanguage(wikiBase: string): string {
  return existsSync(join(wikiBase, "zh")) ? "zh" : "en"
}

function truncate(text: string, maxChars: number): string {
  if (text.length <= maxChars) return text
  return text.substring(0, maxChars) + "\n...（已截断）"
}

// ============================================================
// 全文搜索
// ============================================================
function searchMarkdown(dir: string, query: string, results: Array<{file: string, snippet: string}>): void {
  for (const filePath of listMarkdownFiles(dir)) {
    const text = readText(filePath)
    if (!text) continue
    const lower = text.toLowerCase()
    const q = query.toLowerCase()
    if (lower.includes(q)) {
      const idx = lower.indexOf(q)
      const start = Math.max(0, idx - 100)
      const end = Math.min(text.length, idx + 200)
      results.push({
        file: basename(filePath),
        snippet: text.substring(start, end),
      })
    }
    if (results.length >= MAX_SEARCH_RESULTS) return
  }
}

function searchAllKnowledge(wikiBase: string, query: string): string {
  const lang = detectLanguage(wikiBase)
  const results: Array<{file: string, snippet: string}> = []

  searchMarkdown(join(wikiBase, lang, "wiki"), query, results)
  searchMarkdown(join(wikiBase, lang, "knowledge_cards"), query, results)
  searchMarkdown(join(wikiBase, lang, "memory"), query, results)

  if (results.length === 0) return `未找到与 "${query}" 相关的知识库内容`

  return results.map((r, i) =>
    `### ${i + 1}. ${r.file}\n\n\`\`\`\n${r.snippet}\n\`\`\``
  ).join("\n\n")
}

// ============================================================
// Wiki 生成
// ============================================================
function generateWikiPage(module: string, psiData: any): string {
  const lines: string[] = []
  lines.push(`# ${module} 模块文档`)
  lines.push("")
  lines.push("> 本文档由 RepoWiki 自动生成，人工编辑请使用 `<!-- kilocode-manual-edit-start/end -->` 标记保护。")
  lines.push("")

  if (psiData?.classes) {
    lines.push("## 类结构")
    for (const cls of psiData.classes) {
      lines.push(`### ${cls.name}`)
      if (cls.methods?.length) {
        lines.push("方法：")
        for (const m of cls.methods) {
          lines.push(`- \`${m.signature}\``)
        }
      }
      lines.push("")
    }
  }

  if (psiData?.interfaces) {
    lines.push("## 接口定义")
    for (const iface of psiData.interfaces) {
      lines.push(`### ${iface.name}`)
      if (iface.methods?.length) {
        for (const m of iface.methods) {
          lines.push(`- \`${m.signature}\``)
        }
      }
      lines.push("")
    }
  }

  return lines.join("\n")
}

// ============================================================
// 记忆保存
// ============================================================
function saveMemory(wikiBase: string, text: string, tag = "general"): void {
  const lang = detectLanguage(wikiBase)
  const memDir = join(wikiBase, lang, "memory")
  ensureDir(memDir)
  const filename = `${tag}-${Date.now()}.md`
  writeText(join(memDir, filename), `<!-- tag: ${tag} -->\n${text}\n`)
}

// ============================================================
// 插件主体
// ============================================================
const RepowikiPlugin: Plugin = async ({ directory }) => {
  const wikiBase = join(directory, ".kilo", "repowiki")

  return {
    // ---- 每次对话前注入知识库上下文 ----
    "experimental.chat.system.transform": async (_input, output) => {
      try {
        const knowledge = collectKnowledge(wikiBase)
        if (knowledge) {
          output.system.push(knowledge)
        }
      } catch {
        // 静默失败，不阻塞对话
      }
    },

    // ---- AI 可调用：生成 Wiki 文档 ----
    tool: {
      "wiki-generate": tool({
        description:
          "扫描项目代码结构并生成 Wiki 文档。" +
          "调用前 JetBrains 插件会写入 PSI 结构到 .kilo/repowiki/.cache/psi-structure.json。" +
          "生成结果写入 .kilo/repowiki/{lang}/wiki/ 目录。",
        args: {
          module: tool.schema.string().describe("要生成文档的模块/目录名"),
          description: tool.schema.string().optional().describe("模块功能简述（可选）"),
        },
        async execute(args, ctx) {
          const psiPath = join(wikiBase, ".cache", "psi-structure.json")
          const psiData = JSON.parse(readText(psiPath) || "{}")

          const content = generateWikiPage(args.module, psiData[args.module] || {})
          const lang = detectLanguage(wikiBase)
          const wikiDir = join(wikiBase, lang, "wiki")
          ensureDir(wikiDir)
          writeText(join(wikiDir, `${args.module}.md`), content)

          return {
            title: `Wiki 生成完成: ${args.module}`,
            output: `已生成 ${args.module}.md，包含 ${(content.match(/\n/g) || []).length + 1} 行内容。`,
            metadata: { module: args.module, file: `${args.module}.md` },
          }
        },
      }),

      // ---- AI 可调用：检索知识库 ----
      "wiki-query": tool({
        description:
          "搜索项目知识库（Wiki 文档 + 知识卡片 + 项目记忆）。" +
          "用户提问涉及项目业务/架构/规约时主动调用。",
        args: {
          query: tool.schema.string().describe("搜索关键词"),
        },
        async execute(args) {
          const result = searchAllKnowledge(wikiBase, args.query)
          return {
            title: `知识检索: "${args.query}"`,
            output: result,
          }
        },
      }),

      // ---- AI 可调用：保存记忆 ----
      "wiki-remember": tool({
        description:
          "将关键决策/踩坑/约束保存到项目记忆。" +
          "完成任务后主动调用，记录有价值的信息供后续对话使用。",
        args: {
          text: tool.schema.string().describe("要保存的记忆内容"),
          tag: tool.schema.enum(["architecture", "bug_fix", "spec", "general"]).describe("记忆标签"),
        },
        async execute(args) {
          saveMemory(wikiBase, args.text, args.tag)
          return {
            title: "记忆已保存",
            output: `已保存 [${args.tag}] 记忆: ${args.text.substring(0, 50)}...`,
          }
        },
      }),
    },

    // ---- 文件变更监听 ----
    event: async ({ event }) => {
      if (event.type === "file.edited") {
        const filePath = (event as any).properties?.path || ""
        // 仅跟踪源码文件
        if (filePath && !filePath.includes(".kilo/repowiki")) {
          markFileChanged(wikiBase, filePath)
        }
      }
    },

    // ---- /remember 指令处理 ----
    "chat.message": async (_input, output) => {
      const textPart = output.parts?.find((p: any) => p.type === "text")
      if (!textPart?.text) return

      const match = textPart.text.match(/^\/remember\s+(.+)/s)
      if (match) {
        saveMemory(wikiBase, match[1].trim())
        // 替换为确认消息
        textPart.text = `已保存到项目记忆: ${match[1].trim().substring(0, 80)}...`
      }
    },

    // ---- 会话压缩时保留关键记忆 ----
    "experimental.session.compacting": async (_input, output) => {
      const knowledge = collectKnowledge(wikiBase)
      if (knowledge) {
        output.context.push(`## 跨压缩保留的知识\n${knowledge}`)
      }
    },
  }
}

function markFileChanged(wikiBase: string, filePath: string): void {
  const metaFile = join(wikiBase, ".meta", "changed-files.json")
  let changed: string[] = []
  try { changed = JSON.parse(readText(metaFile) || "[]") } catch {}
  if (!changed.includes(filePath)) {
    changed.push(filePath)
    writeText(metaFile, JSON.stringify(changed, null, 2))
  }
}

export default { id: "repowiki", server: RepowikiPlugin }
```

### 2.3 插件依赖声明

```json
// .kilo/package.json
{
  "dependencies": {
    "@kilocode/plugin": "latest"
  }
}
```

CLI 启动时自动 `bun install`，无需手动安装。

---

## 三、JetBrains 插件实施（`custom/`）

### 3.1 新增文件清单

| 文件 | 行数估计 | 说明 |
|---|---|---|
| `WikiToolWindowFactory.kt` | ~80 | ToolWindow 注册 + 三 Tab 面板 |
| `WikiBrowserPanel.kt` | ~150 | Wiki 文档列表 + Markdown 预览 |
| `MemoryPanel.kt` | ~120 | 记忆搜索 + 编辑 + 删除 |
| `KnowledgeCardPanel.kt` | ~100 | 知识卡片列表 |
| `PsiStructureScanner.kt` | ~100 | PSI 解析 → JSON 输出 |
| `WikiConfigParser.kt` | ~60 | wiki_plan.yaml 解析 |
| `WikiSettings.kt` | ~50 | 持久化设置 |
| `GenerateWikiAction.kt` | ~60 | 右键"生成 Wiki" |
| `WikiSettingsConfigurable.kt` | ~80 | 设置面板 |
| `kilo.jetbrains.custom.xml` | +15 | 注册 ToolWindow + Actions |

### 3.2 ToolWindow 注册

```xml
<!-- kilo.jetbrains.custom.xml 新增 -->
<extensions defaultExtensionNs="com.intellij">
    <toolWindow
        id="KiloWiki"
        anchor="right"
        icon="AllIcons.Toolwindows.ToolWindowDocumentation"
        factoryClass="com.dmc.wiki.WikiToolWindowFactory"/>
</extensions>

<actions>
    <action
        id="com.dmc.GenerateWiki"
        class="com.dmc.actions.GenerateWikiAction"
        text="生成 Wiki 文档"
        icon="AllIcons.Actions.Refresh">
        <add-to-group group-id="ProjectViewPopupMenu" anchor="last"/>
    </action>
</actions>
```

### 3.3 ToolWindow 面板结构

```kotlin
// WikiToolWindowFactory.kt
class WikiToolWindowFactory : ToolWindowFactory {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = JBTabbedPane()
        panel.addTab("Wiki 文档", WikiBrowserPanel(project))
        panel.addTab("知识卡片", KnowledgeCardPanel(project))
        panel.addTab("记忆", MemoryPanel(project))

        val content = ContentFactory.getInstance()
            .createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }
}
```

### 3.4 PSI 结构扫描器（关键桥接组件）

JetBrains 侧分析代码结构 → 写入 JSON → CLI 插件读取生成 Wiki。

```kotlin
// PsiStructureScanner.kt
object PsiStructureScanner {

    data class PsiModule(
        val name: String,
        val classes: List<PsiClassInfo>,
        val interfaces: List<PsiInterfaceInfo>,
    )

    fun scan(project: Project, targetDir: VirtualFile): PsiModule {
        val psiManager = PsiManager.getInstance(project)
        val classes = mutableListOf<PsiClassInfo>()
        val interfaces = mutableListOf<PsiInterfaceInfo>()

        VfsUtilCore.iterateChildrenRecursively(targetDir, {
            it.isDirectory && it.name !in IGNORED_DIRS || it.extension in SOURCE_EXTENSIONS
        }) { file ->
            if (!file.isDirectory && file.extension in SOURCE_EXTENSIONS) {
                val psiFile = psiManager.findFile(file)
                if (psiFile != null) {
                    extractStructure(psiFile, classes, interfaces)
                }
            }
            true
        }

        return PsiModule(targetDir.name, classes, interfaces)
    }

    fun writeToJson(project: Project, modules: Map<String, PsiModule>) {
        val wikiBase = "${project.basePath}/.kilo/repowiki"
        val cacheDir = "$wikiBase/.cache"
        File(cacheDir).mkdirs()
        val json = GsonBuilder().setPrettyPrinting().create().toJson(modules)
        File("$cacheDir/psi-structure.json").writeText(json, Charsets.UTF_8)
    }

    private val IGNORED_DIRS = setOf(
        ".git", "node_modules", "dist", "build", "out", "target",
        ".idea", ".vscode", "__generated__", ".gradle",
    )
    private val SOURCE_EXTENSIONS = setOf("kt", "java", "ts", "tsx", "js", "py", "go")
}
```

### 3.5 "生成 Wiki" Action 流程

```kotlin
// GenerateWikiAction.kt
class GenerateWikiAction : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
        e.presentation.isEnabledAndVisible = !files.isNullOrEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY) ?: return

        // 1. 后台线程：PSI 扫描
        ApplicationManager.getApplication().executeOnPooledThread {
            val modules = mutableMapOf<String, PsiStructureScanner.PsiModule>()
            for (file in files) {
                if (file.isDirectory) {
                    val module = PsiStructureScanner.scan(project, file)
                    modules[module.name] = module
                }
            }

            // 2. 写入 JSON（WriteAction）
            PsiStructureScanner.writeToJson(project, modules)

            // 3. EDT：通知 + 提示用户
            ApplicationManager.getApplication().invokeLater {
                notify(project,
                    "已扫描 ${modules.size} 个模块的结构。\n" +
                    "在 Kilo 对话中输入「请使用 wiki-generate 工具生成文档」触发 AI 生成。",
                    NotificationType.INFORMATION)
            }
        }
    }
}
```

---

## 四、实施阶段

### 阶段 1：CLI 插件 — 核心能力（3 天）

| 天 | 交付物 | 验收标准 |
|---|---|---|
| 1 | `repowiki.ts` 骨架 + `system.transform` | 对话时 system prompt 含知识库内容 |
| 2 | `wiki-generate` + `wiki-query` 工具 | AI 可调用工具生成/检索 Wiki |
| 3 | `event` 监听 + `chat.message` + `session.compacting` | 文件变更追踪 + `/remember` + 压缩保留 |

**验收方式**：创建 `.kilo/repowiki/zh/knowledge_cards/test.md` 写入测试内容 → 在 Kilo 对话提问 → 确认 AI 回答含注入的知识。

### 阶段 2：JetBrains 插件 — UI 面板（5 天）

| 天 | 交付物 | 验收标准 |
|---|---|---|
| 1 | ToolWindow 注册 + 空 Tab 面板 | IDE 右侧出现"KiloWiki"面板 |
| 2 | WikiBrowserPanel（列表 + 预览） | 可浏览 `.kilo/repowiki/zh/wiki/` 下的 md |
| 3 | MemoryPanel（搜索 + 编辑） | 可搜索/编辑/删除记忆文件 |
| 4 | KnowledgeCardPanel + SettingsConfigurable | 卡片列表 + 设置面板 |
| 5 | PsiStructureScanner + GenerateWikiAction | 右键目录 → 扫描 → 写 JSON → 通知提示 |

### 阶段 3：联调 + 完善（3 天）

| 天 | 交付物 | 验收标准 |
|---|---|---|
| 1 | PSI JSON → CLI wiki-generate 联调 | 右键生成 → AI 调用工具 → Wiki 写入本地 |
| 2 | 增量更新 + 人工编辑保护标记 | 文件变更后 AI 增量更新，标记段不被覆盖 |
| 3 | Git 同步 + 多语言切换 + 异常兜底 | 团队成员 git pull 后自动加载，切语言自动生成 |

---

## 五、技术风险与缓解

| 风险 | 等级 | 缓解 |
|---|---|---|
| `experimental.*` API 变更 | 中 | 引擎版本锁定 `engines.opencode`；降级用 `chat.message` 注入 |
| System Prompt Token 溢出 | 高 | 注入内容截断至 6000 字符；按优先级排序（卡片 > 记忆） |
| PSI 扫描大项目卡顿 | 中 | `executeOnPooledThread` + 进度条 + 文件数上限 |
| CLI 插件加载失败 | 低 | `try/catch` 包裹所有 Hook；失败时静默降级 |
| JSON 文件读写竞态 | 中 | 写入用原子操作（临时文件 + rename） |
| `bun install` 网络 | 低 | `@kilocode/plugin` 随 CLI 内置，通常已缓存 |

---

## 六、关键注意事项

### 6.1 CLI 插件不需要编译

`.kilo/plugin/repowiki.ts` 是 TypeScript 源文件，CLI（Bun 运行时）直接加载执行，不需要 `tsc` 编译。只需 `.kilo/package.json` 声明 `@kilocode/plugin` 依赖即可获得类型提示。

### 6.2 JetBrains 插件不调用 LLM

JetBrains 插件**只负责**：
- PSI 结构分析 → 写 JSON
- UI 面板展示
- 右键 Action 触发

**不负责**：
- Wiki 内容生成（由 AI 通过 CLI `tool` Hook 完成）
- 知识检索（由 AI 通过 CLI `tool` Hook 完成）
- System Prompt 注入（由 CLI `system.transform` Hook 完成）

### 6.3 人工编辑保护

生成的 Wiki 文档中，用户手动编辑的段落用 HTML 注释标记保护：

```markdown
<!-- kilocode-manual-edit-start -->
这是我手动写的架构说明，不会被 AI 覆盖。
<!-- kilocode-manual-edit-end -->
```

CLI 插件的 `wiki-generate` 工具在重写时识别标记，仅替换未标记的段落。

### 6.4 零源码修改

整个方案**不修改任何上游源码文件**：
- CLI 插件 → 放在 `.kilo/plugin/`，CLI 自动发现
- JetBrains 插件 → 全部在 `custom/` 模块内
- 通信 → 通过文件系统 `.kilo/repowiki/` 目录

---

## 七、文件交付物总表

### 7.1 CLI 插件（TypeScript）

| 文件 | 说明 |
|---|---|
| `.kilo/plugin/repowiki.ts` | CLI 插件主体（~300 行） |
| `.kilo/package.json` | 依赖声明 |
| `.kilo/repowiki/wiki_plan.yaml` | 配置文件模板 |

### 7.2 JetBrains 插件（Kotlin，custom/ 内）

| 文件 | 说明 |
|---|---|
| `wiki/WikiToolWindowFactory.kt` | ToolWindow 工厂 |
| `wiki/WikiBrowserPanel.kt` | Wiki 浏览面板 |
| `wiki/MemoryPanel.kt` | 记忆管理面板 |
| `wiki/KnowledgeCardPanel.kt` | 知识卡片面板 |
| `wiki/PsiStructureScanner.kt` | PSI 结构扫描器 |
| `wiki/WikiConfigParser.kt` | YAML 配置解析 |
| `wiki/WikiSettings.kt` | 持久化设置 |
| `wiki/WikiSettingsConfigurable.kt` | 设置面板 |
| `actions/GenerateWikiAction.kt` | 右键生成 Action |
| `kilo.jetbrains.custom.xml` | XML 注册（ToolWindow + Action） |

### 7.3 上游文件改动

**0 处。**
