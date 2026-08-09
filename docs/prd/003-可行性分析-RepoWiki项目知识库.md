# 可行性分析：RepoWiki 项目知识库（v2 — 基于 CLI 插件体系更新）

> **v2 更新说明**：原 v1 分析仅评估了 JetBrains 插件层（Kotlin/JVM）的能力限制。
> 补充研究了 Kilo CLI 官方插件文档后，发现 **CLI 插件系统**（TypeScript）提供了
> System Prompt 注入、自定义工具注册、事件订阅等关键能力，从根本上改变了可行性评估。

---

## 一、总体结论：可通过混合架构完整实现

| 维度 | v1 评估 | v2 评估（修正后） |
|---|---|---|
| **完整实现** | ❌ 不可行（4 硬阻断） | ✅ **可行**（CLI 插件 + JetBrains 插件混合） |
| **源码修改** | 需要 10+ 处 | **0 处**（两层插件均不改源码） |
| **预估工作量** | 4-8 周 | 2-3 周（分阶段） |
| **核心价值** | 缺失 | **完整保留** |

### 关键转变

v1 认定的 4 个"硬阻断"中，3 个已被 CLI 插件 Hook 解决：

| v1 硬阻断 | CLI 插件解决方案 | 状态 |
|---|---|---|
| 无 System Prompt 注入钩子 | `experimental.chat.system.transform` | ✅ 已解决 |
| 无 LLM 推理 API | `tool` Hook 注册自定义工具 + `chat.message` 拦截 | ✅ 已解决 |
| 无 CLI wiki 命令 | `tool` Hook 注册 `wiki-generate` 自定义工具 | ✅ 已解决 |
| Slash 命令不可扩展 | TUI `api.command.register`（仅 CLI/TUI 侧） | ⚠️ JetBrains 侧仍需上游改动 |

---

## 二、两层插件体系

Kilo Code 有**两个独立的扩展层**，分别运行在不同进程中：

### 2.1 体系对比

| 维度 | CLI 插件 | JetBrains 插件（我们的 custom/） |
|---|---|---|
| **语言** | TypeScript / JavaScript | Kotlin |
| **运行进程** | CLI 子进程（`kilo serve`） | IDE JVM |
| **加载方式** | `.kilo/plugin/*.ts` 自动加载 | Gradle 编译进插件 JAR |
| **AI 访问** | ✅ Hook 拦截/修改 LLM 请求 | ❌ 无直接 LLM API |
| **IDE API** | ❌ 不能 | ✅ PSI、VFS、ToolWindow、Git |
| **文件系统** | ✅ Node.js fs | ✅ VirtualFile |
| **源码修改** | 不需要 | 不需要（custom/ 隔离 + custom_change 标记） |

### 2.2 CLI 插件核心 Hook（RepoWiki 可用）

| Hook | 用途 | RepoWiki 模块 |
|---|---|---|
| `experimental.chat.system.transform` | **修改 System Prompt**，注入知识库上下文 | KnowledgeRetriever |
| `chat.message` | 拦截用户消息，检测 `/remember` 等指令 | Memory 手动录入 |
| `tool` | 注册自定义工具（如 `wiki-generate`、`wiki-query`） | Wiki 生成 + 知识检索 |
| `event` | 订阅文件变更、会话事件 | FileWatch 增量更新 |
| `experimental.session.compacting` | 会话压缩时注入持久化上下文 | 记忆保留 |
| `experimental.chat.messages.transform` | 重写消息历史 | 高级上下文管理 |

---

## 三、混合架构设计

### 3.1 架构图

```
┌─ JetBrains IDE (JVM) ────────────────────────────┐
│                                                    │
│  ┌─ custom/ 插件 (Kotlin) ─────────────────────┐  │
│  │                                              │  │
│  │  WikiToolWindowFactory    知识库 UI 面板      │  │
│  │  ├── Wiki Tab          Wiki 文档浏览/预览     │  │
│  │  ├── Card Tab          知识卡片管理           │  │
│  │  └── Memory Tab        记忆搜索/编辑          │  │
│  │                                              │  │
│  │  PsiStructureScanner     代码结构分析         │  │
│  │  ├── 解析模块/类/函数/接口                    │  │
│  │  └── 输出 JSON → .kilo/repowiki/.cache/     │  │
│  │                                              │  │
│  │  ProjectViewAction       右键菜单集成         │  │
│  │  SettingsPanel           配置面板             │  │
│  └──────────────────────────────────────────────┘  │
│                        │                           │
│          文件系统通信（.kilo/repowiki/）             │
│                        ▼                           │
└────────────────────────│───────────────────────────┘
                         │
┌─ CLI 子进程 (Node.js) ──│───────────────────────────┐
│                        ▼                           │
│  ┌─ .kilo/plugin/repowiki.ts (TypeScript) ──────┐  │
│  │                                              │  │
│  │  experimental.chat.system.transform          │  │
│  │  └── 每次发送前注入知识库上下文到 system prompt │  │
│  │                                              │  │
│  │  tool: { "wiki-generate" }                   │  │
│  │  └── AI 可主动调用生成 Wiki 文档              │  │
│  │                                              │  │
│  │  tool: { "wiki-query" }                      │  │
│  │  └── AI 可检索知识库内容                      │  │
│  │                                              │  │
│  │  event: { "file.edited" }                    │  │
│  │  └── 文件变更 → 触发增量更新                  │  │
│  │                                              │  │
│  │  chat.message                                │  │
│  │  └── 检测 /remember 指令 → 写入记忆           │  │
│  └──────────────────────────────────────────────┘  │
│                                                    │
│  ┌─ LLM (通过 CLI 内置推理) ────────────────────┐  │
│  │  system: [..., "## 项目知识库\n..."]          │  │
│  │  user: "修复这个 bug"                         │  │
│  └──────────────────────────────────────────────┘  │
└────────────────────────────────────────────────────┘
```

### 3.2 职责分工

| 能力 | 归属 | 技术方案 |
|---|---|---|
| **UI 面板**（Wiki/卡片/记忆浏览） | JetBrains 插件 | ToolWindow + Swing |
| **代码结构分析**（PSI 解析） | JetBrains 插件 | `PsiManager` + `JavaPsiFacade` |
| **ProjectView 右键** | JetBrains 插件 | Action 注册 |
| **设置面板** | JetBrains 插件 | `Configurable` API |
| **Git 集成** | JetBrains 插件 | `git4idea` |
| **System Prompt 注入** | CLI 插件 | `experimental.chat.system.transform` |
| **Wiki 生成工具** | CLI 插件 | `tool` Hook |
| **知识检索** | CLI 插件 | `tool` Hook + 本地文件搜索 |
| **文件变更监听** | CLI 插件 | `event` Hook (`file.edited`) |
| **记忆命令** | CLI 插件 | `chat.message` Hook |
| **通信桥梁** | 文件系统 | `.kilo/repowiki/` 共享目录 |

---

## 四、PRD 各模块可行性逐项评估（v2 修正）

| PRD 模块 | v1 评估 | v2 评估 | 实现方式 |
|---|---|---|---|
| **RepoWiki 文档生成** | ❌ 无 LLM | ✅ | CLI `tool` Hook 注册 `wiki-generate` |
| **wiki_plan.yaml 解析** | ✅ | ✅ | JetBrains `snakeyaml` 或 CLI `js-yaml` |
| **FileWatch 文件监听** | ✅ | ✅ | CLI `event` Hook + JetBrains `VirtualFileListener` |
| **KnowledgeCard 生成** | ❌ 无 LLM | ✅ | CLI `tool` Hook，复用 Wiki 生成链路 |
| **项目/全局记忆存储** | ✅ | ✅ | 本地文件读写 |
| **记忆手动录入** | ⚠️ Slash 硬编码 | ✅ | CLI `chat.message` Hook 检测 `/remember` |
| **KnowledgeRetriever** | ❌ 无钩子 | ✅ | CLI `experimental.chat.system.transform` |
| **ToolWindow 面板** | ✅ | ✅ | JetBrains ToolWindow |
| **Git 分支隔离** | ✅ | ✅ | JetBrains `git4idea` |
| **多语言切换** | ✅ | ✅ | 目录隔离 |
| **设置面板** | ✅ | ✅ | JetBrains `Configurable` |
| **斜杠命令** (`/wiki` 等) | ⚠️ 硬编码 | ⚠️ | TUI 侧可用，JetBrains 需上游改动或 Action 替代 |
| **增量 Wiki 更新** | ❌ | ✅ | CLI `event` Hook 监听 + 增量生成 |
| **Git 团队同步** | ✅ | ✅ | Git 文件提交 |

---

## 五、实现路线图（分三阶段）

### 阶段 1：CLI 插件 — AI 核心能力（1 周）

**目标**：实现知识库的 AI 交互能力，这是之前 v1 认定不可行的核心部分。

| 交付物 | 文件 | 说明 |
|---|---|---|
| CLI 插件骨架 | `.kilo/plugin/repowiki.ts` | Plugin 入口 |
| System Prompt 注入 | 同上 | `experimental.chat.system.transform` Hook |
| wiki-generate 工具 | 同上 | `tool` Hook，AI 可调用生成文档 |
| wiki-query 工具 | 同上 | `tool` Hook，AI 可检索知识库 |
| 文件变更监听 | 同上 | `event` Hook 触发增量标记 |
| `/remember` 命令 | 同上 | `chat.message` Hook 检测指令 |

**CLI 插件示例骨架**：

```typescript
// .kilo/plugin/repowiki.ts
import type { Plugin } from "@kilocode/plugin"
import { tool } from "@kilocode/plugin/tool"
import { readFileSync, writeFileSync, readdirSync, existsSync, mkdirSync } from "node:fs"
import { join } from "node:path"

const RepowikiPlugin: Plugin = async ({ directory }) => {
  const wikiDir = join(directory, ".kilo", "repowiki")

  return {
    // 每次对话前注入知识库上下文
    "experimental.chat.system.transform": async (input, output) => {
      const knowledge = collectKnowledge(wikiDir)
      if (knowledge) {
        output.system.push(`## 项目知识库\n${knowledge}`)
      }
    },

    // AI 可调用的 Wiki 生成工具
    tool: {
      "wiki-generate": tool({
        description: "扫描项目代码结构并生成 Wiki 文档",
        args: {
          module: tool.schema.string().describe("要生成文档的模块名"),
        },
        async execute(args) {
          const content = `# ${args.module} 模块文档\n\n（由 AI 生成）`
          const filePath = join(wikiDir, "zh", "wiki", `${args.module}.md`)
          writeFileSync(filePath, content, "utf-8")
          return `已生成 Wiki 文档: ${filePath}`
        },
      }),

      // AI 可调用的知识检索工具
      "wiki-query": tool({
        description: "检索项目知识库中的相关内容",
        args: {
          keyword: tool.schema.string().describe("搜索关键词"),
        },
        async execute(args) {
          return searchWiki(wikiDir, args.keyword)
        },
      }),
    },

    // 文件变更监听
    event: async ({ event }) => {
      if (event.type === "file.edited") {
        markFileDirty(wikiDir, event.properties?.path)
      }
    },

    // /remember 指令处理
    "chat.message": async (input, output) => {
      const text = output.parts?.find((p: any) => p.type === "text")?.text || ""
      if (text.startsWith("/remember ")) {
        const memory = text.substring("/remember ".length)
        saveMemory(wikiDir, memory)
        // 移除命令，仅保留空消息或确认
      }
    },
  }
}

function collectKnowledge(dir: string): string | null {
  if (!existsSync(dir)) return null
  // 读取 wiki/ + knowledge_cards/ + memory/ 下的 md 文件，拼接为上下文
  // 控制 Token 上限（可配置）
  return "..."
}

function searchWiki(dir: string, keyword: string): string {
  // 全文搜索本地 Markdown 文件
  return "..."
}

function saveMemory(dir: string, text: string): void {
  const memDir = join(dir, "zh", "memory")
  if (!existsSync(memDir)) mkdirSync(memDir, { recursive: true })
  writeFileSync(join(memDir, `memory-${Date.now()}.md`), text, "utf-8")
}

function markFileDirty(dir: string, path: string): void {
  // 记录变更文件，供增量更新使用
}

export default { id: "repowiki", server: RepowikiPlugin }
```

### 阶段 2：JetBrains 插件 — IDE UI 面板（1-1.5 周）

| 交付物 | 文件（custom/） | 说明 |
|---|---|---|
| ToolWindow 工厂 | `WikiToolWindowFactory.kt` | 注册 `KiloWiki` 侧边面板 |
| Wiki 浏览面板 | `WikiBrowserPanel.kt` | Markdown 文件列表 + 预览 |
| 卡片管理面板 | `KnowledgeCardPanel.kt` | 知识卡片列表 + 编辑 |
| 记忆管理面板 | `MemoryPanel.kt` | 记忆搜索 + 编辑 + 删除 |
| YAML 配置解析 | `WikiPlanParser.kt` | 解析 wiki_plan.yaml |
| PSI 结构扫描 | `PsiStructureScanner.kt` | 解析代码结构，输出 JSON |
| 设置面板 | `WikiSettingsConfigurable.kt` | 语言/阈值/黑名单配置 |
| 右键 Action | `RefreshWikiAction.kt` | ProjectView 刷新 Wiki |

### 阶段 3：联调 + 增量更新 + Git 同步（0.5-1 周）

| 功能 | 说明 |
|---|---|
| PSI → CLI 联动 | JetBrains 写 JSON → CLI 插件读取生成 Wiki |
| 增量更新 | CLI `event` Hook 标记变更 + AI 增量重写 |
| Git 同步 | `.kilo/repowiki/` 提交 Git，团队共享 |
| 分支隔离 | JetBrains 检测分支切换 → 加载对应知识库 |
| 人工编辑保护 | `<!-- kilocode-manual-edit-start/end -->` 标记 |

---

## 六、技术风险

| 风险 | 等级 | 缓解方案 |
|---|---|---|
| `experimental.*` Hooks 可能变更 | 中 | API 版本锁定 + 降级方案 |
| CLI 插件性能（大项目检索） | 中 | 缓存 + 增量 + 文件数量上限 |
| System Prompt Token 溢出 | 高 | 检索结果截断 + 按相关性排序 |
| JetBrains ↔ CLI 文件同步竞态 | 中 | 文件锁 + 重试机制 |
| Slash 命令在 JetBrains 侧不可用 | 低 | 用 Action/通知替代，核心逻辑在 CLI |

---

## 七、与 v1 分析的差异总结

| 对比项 | v1 结论 | v2 结论 |
|---|---|---|
| 完整实现 | ❌ 不可行 | ✅ 可行（混合架构） |
| 源码修改 | 10+ 处 | 0 处 |
| System Prompt 注入 | ❌ 无钩子 | ✅ `experimental.chat.system.transform` |
| Wiki 生成 | ❌ 无 LLM | ✅ CLI `tool` Hook |
| 知识检索注入 | ❌ 无拦截点 | ✅ CLI System Prompt Hook |
| 核心价值 | 缺失 | 完整保留 |
| 预估工作量 | 4-8 周 | 2-3 周（分阶段） |

**v1 的局限性**：仅评估了 JetBrains 插件层的能力，忽略了 CLI 插件系统这一完全独立的扩展层。CLI 插件提供了 `experimental.chat.system.transform`、`tool`、`event` 等 Hook，恰好填补了 v1 认定的全部硬阻断。
