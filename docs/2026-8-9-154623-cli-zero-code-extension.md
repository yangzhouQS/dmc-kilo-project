# CLI 零源码修改扩充方案

## 总览

kilo CLI（`packages/opencode/`）提供 8 种扩充机制，全部通过配置文件和约定目录实现，**无需修改 CLI 源码**。

| 方式 | 能力 | 配置位置 | 难度 |
|---|---|---|---|
| **Skills** | 注入提示词/指令模板 | `.kilo/skills/*/SKILL.md` | 最低 |
| **自定义工具** | 新增 AI 可调用工具 | `.kilo/tools/*.ts` | 低 |
| **MCP Server** | 外部工具/资源/提示 | `kilo.json → mcp` | 中 |
| **自定义命令** | 斜杠命令 `/xxx` | `kilo.json → command` 或 `.kilo/command/*.md` | 低 |
| **自定义 Agent** | 新增子 Agent/主 Agent | `kilo.json → agent` 或 `.kilo/agent/*.md` | 低 |
| **System Prompt 扩展** | 追加系统指令 | `kilo.json → instructions` 或 `AGENTS.md` | 最低 |
| **Plugin（最强大）** | 工具+Hook+Provider 全覆盖 | `kilo.json → plugin` 或本地 `.ts` | 中高 |
| **权限/工具开关** | 精确控制工具行为 | `kilo.json → permission / tools` | 低 |

---

## 一、Skills — Markdown 指令包

**能做什么**：将专业指令/上下文注入模型，支持 `` !`cmd` `` shell 注入（受信任标记控制）。Skills 同时作为斜杠命令暴露。

**类型定义**：`packages/opencode/src/skill/index.ts:34-41`

```ts
export const Info = Schema.Struct({
  name: Schema.String,
  description: Schema.optional(Schema.String),
  location: Schema.String,
  content: Schema.String,
  trusted: Schema.optional(Schema.Boolean),
})
```

**发现目录**（`packages/opencode/src/skill/index.ts:200-294`）：

| 来源 | 路径 |
|---|---|
| 内置 | 二进制内置（`kilocode/skills/builtin.ts`） |
| 全局 | `~/.claude/skills/`、`~/.agents/skills/` |
| 项目 | `.kilo/skills/`、`.kilocode/skills/` |
| 配置声明 | `kilo.json → skills.paths`（目录数组）、`skills.urls`（远程 URL） |

**配置 schema**：`packages/core/src/v1/config/skills.ts:5-12`

```ts
export const Info = Schema.Struct({
  paths: Schema.optional(Schema.Array(Schema.String)),
  urls: Schema.optional(Schema.Array(Schema.String)),
})
```

**Shell 注入**：`packages/opencode/src/kilocode/skills/inject.ts:41-54` — 受信任 skill 中的 `` !`cmd` `` 在到达模型前被执行并替换为 stdout。限制：32 命令上限、32KB 输出上限、5 分钟预算。

**示例**：

```markdown
<!-- .kilo/skills/wiki-generate/SKILL.md -->
---
description: Generate RepoWiki documentation for the current project
---
请扫描当前项目代码结构，生成完整的 RepoWiki 文档...
```

用户在对话中输入 `/wiki-generate` 即可触发。

**需要改源码**：否。

---

## 二、自定义工具 — `.kilo/tools/*.ts` 自动发现

**能做什么**：注册新的 AI 可调用工具。工具接收 Zod schema 参数，返回结果。

**工具接口**：`packages/plugin/src/tool.ts:45-51`

```ts
export function tool<Args extends z.ZodRawShape>(input: {
  description: string
  args: Args
  execute(args, context: ToolContext): Promise<ToolResult>
}) { return input }
```

**两种注册方式**：

### 方式 A：自动发现（推荐）

`packages/opencode/src/tool/registry.ts:207-221` — 扫描配置目录中的 `{tool,tools}/*.{js,ts}`，动态 import 并注册：

```ts
const matches = dirs.flatMap((dir) =>
  Glob.scanSync("{tool,tools}/*.{js,ts}", { cwd: dir, absolute: true, dot: true, symlink: true }),
)
for (const match of matches) {
  const namespace = path.basename(match, path.extname(match))
  const mod = yield* Effect.promise(() => import(pathToFileURL(match).href))
  for (const [id, def] of Object.entries(mod)) {
    if (!isPluginTool(def)) continue
    custom.push(fromPlugin(id === "default" ? namespace : `${namespace}_${id}`, def))
  }
}
```

**方式 B：Plugin `tool` hook**

`packages/plugin/src/index.ts:226-228`：

```ts
export interface Hooks {
  tool?: { [key: string]: ToolDefinition }
}
```

在 `packages/opencode/src/tool/registry.ts:223-228` 消费。

**示例**：

```typescript
// .kilo/tools/wiki-scan.ts
import { tool } from "@kilocode/plugin"
import { z } from "zod"

export default tool({
  description: "Scan project and generate structural wiki documentation",
  args: {
    directory: z.string().describe("Project root path"),
  },
  async execute(args, context) {
    const { $ } = context
    const files = await $`find ${args.directory}/src -name "*.kt"`.text()
    return { title: "Wiki scan", output: files, metadata: {} }
  },
})
```

**需要改源码**：否。一个 `.ts`/`.js` 文件放入 `.kilo/tools/` 即可。

---

## 三、MCP Server — 外部工具/资源/提示

**能做什么**：连接外部 MCP server，提供工具（AI 可调用）、提示（斜杠命令）、资源（可读数据）、资源模板。支持 OAuth 远程认证。

**两种类型**（`packages/core/src/v1/config/mcp.ts`）：

- **Local**：`{ type: "local", command: [...], cwd?, environment?, enabled?, timeout? }`
- **Remote**：`{ type: "remote", url, headers?, oauth?, enabled?, timeout? }`

**配置示例**：

```json
{
  "mcp": {
    "repowiki": {
      "type": "local",
      "command": ["npx", "-y", "@dmc/repowiki-mcp-server"],
      "environment": { "PROJECT_ROOT": "." }
    }
  }
}
```

**加载逻辑**：
- State init：`packages/opencode/src/mcp/index.ts:517-585`
- Local connect：`index.ts:357-395`（StdioClientTransport）
- Remote connect：`index.ts:253-355`（StreamableHTTP + SSE fallback）
- 工具转换：`packages/opencode/src/mcp/catalog.ts:42-83`
- 工具命名：`catalog.ts:119` — `sanitize(clientName) + "_" + sanitize(toolName)`
- Prompt → 命令：`index.ts:747-749`
- 资源：`index.ts:751-769`
- Server 指令注入 system prompt：`index.ts:640-650`

**需要改源码**：否。

---

## 四、自定义命令 — 斜杠命令

**能做什么**：`/mycommand` 注入模板（prompt）到会话，支持指定 agent、model、variant。模板支持 `$1`、`$2` 参数占位符和 `$ARGUMENTS`。

**四个来源**（均不需要改源码）：

| 来源 | 配置方式 |
|---|---|
| JSON 配置 | `kilo.json → command`（`packages/core/src/v1/config/command.ts:5-12`） |
| Markdown 文件 | `.kilo/command/*.md`（`packages/opencode/src/config/command.ts:23-84`） |
| MCP prompts | `command/index.ts:129-156` |
| Skills | `command/index.ts:158-161`（每个 skill 自动暴露为命令） |

**JSON 配置示例**：

```json
{
  "command": {
    "wiki": {
      "description": "Generate wiki",
      "agent": "build",
      "template": "请分析当前项目并生成 RepoWiki 文档"
    }
  }
}
```

**Markdown 示例**：

```markdown
<!-- .kilo/command/wiki.md -->
---
description: Generate wiki documentation
agent: build
---
请分析当前项目代码结构，生成 RepoWiki 文档...
```

**需要改源码**：否。

---

## 五、自定义 Agent — 新角色

**能做什么**：定义新 agent（primary 或 subagent），自定义 system prompt、model、temperature、permission、color、steps 上限。Subagent 通过 `task` 工具调用。

**两种配置方式**：

### JSON 配置（`kilo.json → agent`）

Schema：`packages/core/src/v1/config/agent.ts:68-114`。处理在 `packages/opencode/src/agent/agent.ts:349-386`：

```json
{
  "agent": {
    "wiki-writer": {
      "description": "Documentation specialist",
      "model": "anthropic/claude-sonnet-4",
      "mode": "subagent",
      "prompt": "You are a technical documentation writer...",
      "permission": { "*": "deny", "read": "allow", "edit": "allow" }
    }
  }
}
```

### Markdown 文件

`packages/opencode/src/config/agent.ts:22-100` — 扫描 `{agent,agents}/**/*.md`。Frontmatter = 配置字段，body = prompt。

```markdown
<!-- .kilo/agent/wiki-writer.md -->
---
description: Documentation specialist
model: anthropic/claude-sonnet-4
mode: subagent
---
You are a technical documentation writer specializing in...
```

**需要改源码**：否。

---

## 六、System Prompt 扩展

**能做什么**：向 system prompt 追加指令文件。

**配置方式**：

| 方式 | 来源 |
|---|---|
| `kilo.json → instructions` | glob 模式或路径数组或 HTTP URL |
| 约定文件 | `AGENTS.md`、`CLAUDE.md`、`.cursorrules` 等自动发现 |

加载逻辑：`packages/opencode/src/session/instruction.ts:140-178`。

```json
{
  "instructions": [
    ".kilo/rules/upstream-sync.md",
    ".kilo/rules/build-and-run.md"
  ]
}
```

**需要改源码**：否。

---

## 七、Plugin — 最强大的扩充（完整 Hook 体系）

**能做什么**：Plugin 是函数 `(input: PluginInput, options?) => Promise<Hooks>`，可以：
- 注册自定义工具（`tool` hook）
- 添加认证 Provider（`auth` hook）
- 添加模型 Provider（`provider` hook）
- 实现**任意 Hook**（见下方完整目录）
- 响应**所有事件**（`event` hook）

**Plugin 接口**：`packages/plugin/src/index.ts:74`

```ts
export type Plugin = (input: PluginInput, options?: PluginOptions) => Promise<Hooks>
```

**配置方式**：`kilo.json → plugin` 数组

```json
{
  "plugin": [
    "opencode-gitlab-auth",
    ["./plugins/repowiki-plugin.ts", { "option": true }],
    "@scope/my-plugin@1.2.0"
  ]
}
```

加载管线：`packages/opencode/src/plugin/loader.ts:211-239`

### 完整 Hook 目录

| Hook | 触发时机 | 能修改什么 |
|---|---|---|
| `tool.execute.before` | 任何工具执行前 | 工具参数 |
| `tool.execute.after` | 任何工具执行后 | 工具输出 |
| `tool.definition` | 工具定义发给 LLM 时 | 描述、参数 |
| `chat.message` | 新消息接收时 | 消息内容 |
| `chat.params` | LLM 参数 | temperature、topP、maxOutputTokens |
| `chat.headers` | LLM HTTP 请求头 | headers |
| `command.execute.before` | 斜杠命令执行前 | 模板 parts |
| `shell.env` | shell 工具环境变量 | env |
| `permission.ask` | 权限提示拦截 | allow/deny/ask |
| **`experimental.chat.system.transform`** | **System prompt 构建时** | **注入知识上下文** |
| `experimental.chat.messages.transform` | 消息转换 | messages |
| `experimental.session.compacting` | 会话压缩 | 压缩 prompt |
| `experimental.compaction.autocontinue` | 压缩后自动续行 | enabled |
| `experimental.provider.small_model` | 小模型覆盖 | model |
| `experimental.text.complete` | 自定义文本补全 | text |
| `config` | 配置通知 | Config |
| `event` | 所有事件 | events |
| `dispose` | 插件清理 | — |

**触发机制**：`packages/opencode/src/plugin/index.ts:290-303`

**示例**：

```typescript
// plugins/repowiki-plugin.ts
import { z } from "zod"

export default async (input, options) => {
  return {
    // 1. 注册自定义工具
    tool: {
      "wiki-search": {
        description: "Search project knowledge base",
        args: { query: z.string() },
        async execute(args) {
          const results = await searchKnowledgeBase(args.query)
          return { title: "Wiki Search", output: results, metadata: {} }
        },
      },
    },

    // 2. 自动注入知识上下文（KnowledgeRetriever 的实现）
    "experimental.chat.system.transform": async (input, output) => {
      const knowledge = await loadRelevantKnowledge(input.sessionID)
      if (knowledge) {
        output.system.push(`项目知识库上下文：\n${knowledge}`)
      }
      return output
    },

    // 3. 对话消息拦截（记忆自动沉淀）
    "chat.message": async (input, output) => {
      // 检测关键信息，保存到记忆
      return output
    },
  }
}
```

**需要改源码**：否。

---

## 八、权限/工具开关

**能做什么**：精确控制每个工具的权限（allow/ask/deny），支持 glob 模式。

**配置**：`kilo.json → permission` 和 `kilo.json → tools`

```json
{
  "permission": {
    "bash(npm install*)": "allow",
    "bash(rm -rf*)": "deny",
    "edit(.kilocode/**)": "deny"
  },
  "tools": {
    "wiki-search": true,
    "task": false
  }
}
```

**需要改源码**：否。

---

## 完整对比矩阵

| 方式 | 配置 Key | 目录扫描 | 新增工具 | 新增提示 | 改源码 |
|---|---|---|---|---|---|
| Skills | `skills.paths/urls` | `SKILL.md` | 否（仅 shell 注入） | 是 | 否 |
| 自定义工具 | （via plugin） | `tool/*.ts` | 是 | 否 | 否 |
| MCP Server | `mcp` | 否 | 是 | 是 | 否 |
| 自定义命令 | `command` | `command/*.md` | 否 | 是 | 否 |
| 自定义 Agent | `agent` | `agent/*.md` | 否 | 是 | 否 |
| System Prompt | `instructions` | `AGENTS.md` 等 | 否 | 是 | 否 |
| Plugin | `plugin` | `./file.ts` | 是 | 是 | 否 |
| 权限/开关 | `permission/tools` | 否 | 控制 | 否 | 否 |

---

## 关键发现：`experimental.chat.system.transform` Hook

这是实现 RepoWiki PRD 中「KnowledgeRetriever 自动注入知识上下文」的**核心机制**：

```
用户发送消息
  ↓
Plugin Hook: experimental.chat.system.transform
  ↓ 拦截！读取知识库，注入相关上下文
  ↓
System Prompt = [原始prompt] + [自动检索的知识卡片/Wiki片段/记忆]
  ↓
发送给 LLM
```

**完全不需要修改 SessionController、不需要改 frontend 模块、不需要改 CLI 源码**。只需要一个本地 Plugin 文件。

---

## RepoWiki 落地路径

### 阶段 1（纯文件，10 分钟）

```
.kilo/skills/
├── wiki-generate/SKILL.md      ← /wiki-generate 斜杠命令
├── wiki-update/SKILL.md        ← /wiki-update 斜杠命令
├── wiki-edit/SKILL.md          ← /wiki-edit 斜杠命令
└── remember/SKILL.md           ← /remember 记忆命令
```

### 阶段 2（自定义工具，1 天）

```
.kilo/tools/
├── wiki-scan.ts                ← 项目结构扫描（文件系统分析）
├── knowledge-search.ts          ← 知识库全文检索
└── commit-enhance.ts           ← commit message 增强（注入技术栈上下文）
```

### 阶段 3（Plugin + Hooks，2-3 天）

```
plugins/
└── repowiki-plugin.ts          ← 知识自动注入 + 记忆自动沉淀
```

全部三个阶段**零 CLI 源码修改**。
