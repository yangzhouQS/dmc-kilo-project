# Plugin 自定义能力完整文档

> 源码位置：`packages/plugin/src/`（接口定义）、`packages/opencode/src/plugin/`（加载消费）

---

## 一、两代 Plugin API

| 代次 | 路径 | 形式 | 状态 |
|---|---|---|---|
| **V1（Hooks）** | `packages/plugin/src/index.ts` | `async (input, options) => Hooks` | 主力 API，当前完全可用 |
| **V2（Registration）** | `packages/plugin/src/v2/promise/` | `{ id, setup(ctx) }` | 新型 API，注册式，支持热重载 |

两者可共存于同一项目。

---

## 二、V1 Plugin API（Hooks 模式）

### 2.1 插件签名

```typescript
// packages/plugin/src/index.ts:74
export type Plugin = (input: PluginInput, options?: PluginOptions) => Promise<Hooks>
```

### 2.2 PluginInput — 插件可用的运行时上下文

```typescript
// packages/plugin/src/index.ts:56-66
export type PluginInput = {
  client: ReturnType<typeof createKiloClient>  // Kilo SDK 客户端
  project: Project                              // 项目信息
  directory: string                             // 当前项目目录
  worktree: string                              // worktree 根路径
  experimental_workspace: {
    register(type: string, adapter: WorkspaceAdapter): void  // 注册 workspace 适配器
  }
  serverUrl: URL                                // CLI 服务器地址
  $: BunShell                                   // Bun shell（可在插件中执行命令）
}
```

#### `$` Shell API（`packages/plugin/src/shell.ts`）

```typescript
const { $ } = input

// 基本用法
const result = await $`git status --porcelain`.text()

// 读取 stdout 为 JSON
const pkg = await $`cat package.json`.json()

// 设置 cwd 和 env
const out = await $`npm test`.cwd(input.directory).env({ CI: "true" }).quiet().text()

// 逐行读取
for await (const line of $`npm run build`.lines()) {
  console.log(line)
}

// 不抛异常
const r = await $`some-cmd`.nothrow()
console.log(r.exitCode)
```

#### `client` SDK 客户端

插件可通过 `input.client` 调用 CLI 所有 HTTP API（创建会话、发送消息、查询模型等），等同于直接操作 `@kilocode/sdk`。

#### `experimental_workspace.register` — Workspace 适配器

```typescript
// packages/plugin/src/example-workspace.ts
experimental_workspace.register("folder", {
  name: "Folder",
  description: "Create a blank folder",
  configure(config) { return { ...config, directory: "/tmp/folder/x" } },
  async create(config) { await mkdir(config.directory!, { recursive: true }) },
  async remove(config) { await rm(config.directory!, { recursive: true }) },
  target(config) { return { type: "local", directory: config.directory! } },
})
```

### 2.3 Hooks 完整目录

所有 Hook 定义在 `packages/plugin/src/index.ts:222-335`。Hook 函数签名统一为 `(input, output) => Promise<void>`，通过修改 `output` 对象生效。

#### 核心 Hook

| Hook | 触发时机 | input | output 可修改 |
|---|---|---|---|
| `dispose` | 插件卸载 | — | — |
| `event` | 任何事件触发 | `{ event }` | — |
| `config` | 配置加载后 | `Config` | — |
| `tool` | **注册自定义工具** | — | `{ [toolName]: ToolDefinition }` |
| `auth` | **注册认证 Provider** | — | `AuthHook` |
| `provider` | **注册模型 Provider** | — | `ProviderHook` |

#### 对话 Hook

| Hook | 触发时机 | input | output 可修改 |
|---|---|---|---|
| `chat.message` | 新用户消息接收 | `{ sessionID, agent?, model?, messageID? }` | `{ message, parts }` |
| `chat.params` | LLM 参数构建 | `{ sessionID, agent, model, provider, message }` | `temperature, topP, topK, maxOutputTokens, options` |
| `chat.headers` | LLM HTTP 请求头 | `{ sessionID, agent, model, provider, message }` | `{ headers }` |

#### 工具 Hook

| Hook | 触发时机 | input | output 可修改 |
|---|---|---|---|
| `tool.execute.before` | 工具执行前 | `{ tool, sessionID, callID }` | `{ args }` |
| `tool.execute.after` | 工具执行后 | `{ tool, sessionID, callID, args }` | `title, output, metadata` |
| `tool.definition` | 工具定义发给 LLM | `{ toolID }` | `description, parameters` |

#### 命令/Shell Hook

| Hook | 触发时机 | input | output 可修改 |
|---|---|---|---|
| `command.execute.before` | 斜杠命令执行前 | `{ command, sessionID, arguments }` | `{ parts }` |
| `shell.env` | shell 工具环境变量 | `{ cwd, sessionID?, callID? }` | `{ env }` |

#### 权限 Hook

| Hook | 触发时机 | input | output 可修改 |
|---|---|---|---|
| `permission.ask` | 权限提示拦截 | `Permission` | `{ status: "ask" \| "deny" \| "allow" }` |

#### Experimental Hook（最强大）

| Hook | 触发时机 | input | output 可修改 |
|---|---|---|---|
| **`experimental.chat.system.transform`** | **System Prompt 构建** | `{ sessionID?, model }` | `{ system: string[] }` |
| `experimental.chat.messages.transform` | 消息转换 | `{}` | `{ messages }` |
| `experimental.session.compacting` | 会话压缩前 | `{ sessionID }` | `{ context: string[], prompt? }` |
| `experimental.compaction.autocontinue` | 压缩后自动续行 | `{ sessionID, agent, model, ... }` | `{ enabled }` |
| `experimental.provider.small_model` | 小模型选择 | `{ provider }` | `{ model? }` |
| `experimental.text.complete` | 自定义文本补全 | `{ sessionID, messageID, partID }` | `{ text }` |

### 2.4 ToolDefinition — 自定义工具

```typescript
// packages/plugin/src/tool.ts:3-51
export type ToolContext = {
  sessionID: string
  messageID: string
  agent: string
  directory: string       // 当前项目目录
  worktree: string        // worktree 根路径
  abort: AbortSignal      // 中止信号
  metadata(input: { title?: string; metadata?: Record<string, any> }): void
  ask(input: AskInput): Promise<void>  // 请求用户权限
}

export type ToolResult =
  | string
  | {
      title?: string
      output: string
      metadata?: Record<string, any>
      attachments?: ToolAttachment[]  // 文件附件
    }

export function tool<Args extends z.ZodRawShape>(input: {
  description: string
  args: Args                                    // Zod schema 定义参数
  execute(args: z.infer<z.ZodObject<Args>>, context: ToolContext): Promise<ToolResult>
})
```

`ToolAttachment`（返回文件给 AI）：

```typescript
export type ToolAttachment = {
  type: "file"
  mime: string       // "text/plain"、"image/png" 等
  url: string        // "file://path/to/file"
  filename?: string
}
```

---

## 三、V2 Plugin API（Registration 模式）

V2 是注册式 API，支持热重载。定义在 `packages/plugin/src/v2/promise/`。

### 3.1 插件签名

```typescript
// packages/plugin/src/v2/promise/plugin.ts
export interface Plugin {
  readonly id: string
  readonly setup: (context: PluginContext) => Promise<void> | void
}

export function define(plugin: Plugin) { return plugin }
```

### 3.2 PluginContext — 注册式子系统

```typescript
// packages/plugin/src/v2/promise/context.ts
export interface PluginContext {
  readonly options: PluginOptions
  readonly agent: AgentHooks & Reload      // Agent 变换
  readonly aisdk: AISDKHooks              // AI SDK 拦截
  readonly catalog: CatalogHooks & Reload  // 模型目录变换
  readonly command: CommandHooks & Reload  // 命令变换
  readonly integration: IntegrationHooks & Reload  // 集成连接
  readonly plugin: PluginDomain            // 插件域（动态 add/remove）
  readonly reference: ReferenceHooks & Reload  // 引用 Agent
  readonly skill: SkillHooks & Reload     // Skill 变换
}
```

每个子系统的 `transform` Hook 接收一个 draft 对象，修改后自动生效：

| 子系统 | draft 类型 | 可操作 |
|---|---|---|
| `agent` | `AgentDraft` | 添加/修改/删除 Agent 定义 |
| `catalog` | `CatalogDraft` | 修改 Provider/Model 列表，设置默认模型 |
| `command` | `CommandDraft` | 添加/修改斜杠命令 |
| `skill` | `SkillDraft` | 添加/修改 Skill |
| `integration` | `IntegrationDraft` | 注册第三方集成连接 |
| `reference` | `ReferenceDraft` | 注册引用 Agent |
| `aisdk` | `AISDKHooks` | 拦截 AI SDK model/language 创建 |

`Reload` 接口提供 `reload()` 方法支持热重载。

### 3.3 CatalogDraft 示例（修改模型列表）

```typescript
// packages/plugin/src/v2/effect/catalog.ts
export interface CatalogDraft {
  readonly provider: {
    list(): readonly CatalogProviderRecord[]
    get(providerID: string): CatalogProviderRecord | undefined
    update(providerID: string, update: (provider: ProviderV2Info) => void): void
    remove(providerID: string): void
  }
  readonly model: {
    get(providerID: string, modelID: string): ModelV2Info | undefined
    update(providerID: string, modelID: string, update: (model: ModelV2Info) => void): void
    remove(providerID: string, modelID: string): void
    readonly default: {
      get(): { providerID: string; modelID: string } | undefined
      set(providerID: string, modelID: string): void
    }
  }
}
```

---

## 四、插件注册方式

### 4.1 kilo.json 配置（三种格式）

```jsonc
// packages/core/src/v1/config/plugin.ts
{
  "plugin": [
    "package-name",                          // npm 包名（自动安装）
    ["./local-plugin.ts", { "opt": true }],  // 本地文件 + 选项
    "@scope/plugin@1.2.0"                    // npm 带版本
  ]
}
```

### 4.2 本地文件插件

```jsonc
// kilo.json
{
  "plugin": ["./plugins/my-plugin.ts"]
}
```

文件路径相对于项目根目录。支持 `.ts` 和 `.js`。

### 4.3 自动发现的工具文件（无需注册插件）

`.kilo/tools/` 或 `.kilocode/tools/` 目录下的 `.ts`/`.js` 文件会被自动扫描（`packages/opencode/src/tool/registry.ts:207-221`），无需在 `kilo.json` 中声明：

```
.kilo/tools/
├── wiki-scan.ts       ← 自动注册为 "wiki-scan" 工具
├── knowledge-search.ts ← 自动注册为 "knowledge-search" 工具
└── my-tool.ts          ← 自动注册为 "my-tool" 工具
```

每个文件导出符合 `ToolDefinition` 形状的对象即可。

### 4.4 加载管线

```
kilo.json → plugin 数组
  ↓
PluginLoader.resolve()                    // packages/opencode/src/plugin/loader.ts:88
  ├─ resolvePluginTarget()                // 解析 npm 包或本地文件
  ├─ createPluginEntry()                  // 检测 server/tui entrypoint
  └─ checkPluginCompatibility()           // npm 包兼容性检查
  ↓
PluginLoader.load()                       // loader.ts:138 — dynamic import()
  ↓
plugin(input, options) → Hooks           // 调用插件函数，获取 Hooks
  ↓
Plugin.index.hooks.push(hooks)            // 存入 hooks 数组
  ↓
Plugin.trigger(name, input, output)       // 触发对应 Hook
```

---

## 五、开发示例

### 示例 1：最小工具插件

```typescript
// packages/plugin/src/example.ts
import { Plugin } from "./index.js"
import { tool } from "./tool.js"

export const ExamplePlugin: Plugin = async (_ctx) => {
  return {
    tool: {
      mytool: tool({
        description: "This is a custom tool",
        args: {
          foo: tool.schema.string().describe("foo"),
        },
        async execute(args) {
          return `Hello ${args.foo}!`
        },
      }),
    },
  }
}
```

### 示例 2：知识自动注入插件（System Prompt 拦截）

```typescript
// plugins/repowiki-plugin.ts
import { Plugin } from "@kilocode/plugin"
import { readFile } from "node:fs/promises"
import { join } from "node:path"

export const RepoWikiPlugin: Plugin = async ({ directory }) => {
  return {
    // 自动注入项目知识上下文到每次对话
    "experimental.chat.system.transform": async (input, output) => {
      try {
        const cardsDir = join(directory, ".kilocode", "repowiki", "zh", "knowledge_cards")
        const { readdir } = await import("node:fs/promises")
        const files = await readdir(cardsDir).catch(() => [])
        if (files.length === 0) return

        const contents: string[] = []
        for (const f of files.slice(0, 5)) {
          const text = await readFile(join(cardsDir, f), "utf-8")
          contents.push(text)
        }
        output.system.push(`# 项目知识库上下文\n\n${contents.join("\n\n---\n\n")}`)
      } catch {
        // 静默失败，不阻塞对话
      }
    },

    // 注册知识搜索工具
    tool: {
      "wiki-search": {
        description: "Search the project knowledge base (Wiki + Knowledge Cards + Memory)",
        args: {
          query: { type: "string", description: "Search query" },
        },
        async execute(args, ctx) {
          const { $ } = { query: args.query }
          // 实现搜索逻辑...
          return {
            title: "Wiki Search",
            output: `Search results for: ${args.query}`,
            metadata: { source: "repowiki" },
          }
        },
      },
    },

    // 工具执行后自动沉淀记忆
    "tool.execute.after": async (input, output) => {
      if (input.tool === "edit" || input.tool === "write") {
        // 记录文件修改，用于增量 Wiki 更新
        console.log(`[RepoWiki] File modified: ${output.metadata}`)
      }
    },
  }
}

export default RepoWikiPlugin
```

kilo.json 配置：

```json
{
  "plugin": ["./plugins/repowiki-plugin.ts"]
}
```

### 示例 3：认证 Provider 插件

```typescript
// plugins/custom-auth.ts
import { Plugin } from "@kilocode/plugin"

export const CustomAuthPlugin: Plugin = async () => {
  return {
    auth: {
      provider: "my-provider",
      methods: [
        {
          type: "api",
          label: "My API Service",
          prompts: [
            {
              type: "text",
              key: "apiKey",
              message: "Enter your API key:",
              validate: (v) => v.length < 10 ? "Key too short" : undefined,
            },
          ],
          async authorize(inputs) {
            return {
              type: "success",
              key: inputs!.apiKey,
              provider: "my-provider",
            }
          },
        },
      ],
    },
  }
}

export default CustomAuthPlugin
```

### 示例 4：模型 Provider 插件

```typescript
// plugins/custom-model.ts
import { Plugin } from "@kilocode/plugin"

export const CustomModelPlugin: Plugin = async () => {
  return {
    provider: {
      id: "my-custom-provider",
      async models(provider, ctx) {
        const models: Record<string, any> = {
          "my-model-v1": {
            name: "My Custom Model v1",
            attachment: false,
            reasoning: false,
            tool_call: true,
            cost: { input: 0.001, output: 0.002 },
            limit: { context: 128000, output: 8192 },
          },
        }
        return models
      },
    },
  }
}

export default CustomModelPlugin
```

### 示例 5：Workspace 适配器插件

```typescript
// plugins/folder-workspace.ts
import { Plugin } from "@kilocode/plugin"
import { mkdir, rm } from "node:fs/promises"

export const FolderWorkspacePlugin: Plugin = async ({ experimental_workspace }) => {
  experimental_workspace.register("folder", {
    name: "Folder",
    description: "Create a blank folder workspace",
    configure(config) {
      return { ...config, directory: `/tmp/folder/folder-${Math.random()}` }
    },
    async create(config) {
      if (!config.directory) return
      await mkdir(config.directory, { recursive: true })
    },
    async remove(config) {
      await rm(config.directory!, { recursive: true, force: true })
    },
    target(config) {
      return { type: "local", directory: config.directory! }
    },
  })
  return {}
}

export default FolderWorkspacePlugin
```

### 示例 6：自动发现工具文件（无需注册插件）

```typescript
// .kilo/tools/project-stats.ts
import { tool } from "@kilocode/plugin"

export default tool({
  description: "Get project statistics (file count, LOC, dependencies)",
  args: {},
  async execute(_args, ctx) {
    const { directory } = ctx
    const { $ } = await import("bun")

    const fileCount = await $`find ${directory}/src -type f | wc -l`.text()
    const depCount = await $`cat ${directory}/package.json | jq '.dependencies | length'`.text()

    ctx.metadata({
      title: "Project Statistics",
      metadata: { fileCount: fileCount.trim(), depCount: depCount.trim() },
    })

    return `Files: ${fileCount.trim()}, Dependencies: ${depCount.trim()}`
  },
})
```

无需在 `kilo.json` 中声明任何内容，CLI 启动时自动发现。

### 示例 7：对话消息拦截（修改用户输入）

```typescript
// plugins/prompt-enhancer.ts
import { Plugin } from "@kilocode/plugin"

export const PromptEnhancerPlugin: Plugin = async () => {
  return {
    "chat.message": async (input, output) => {
      // 在用户消息前注入当前编辑器文件信息
      if (output.message.role === "user") {
        // 修改消息内容...
        // output.parts 可以被修改
      }
    },
  }
}

export default PromptEnhancerPlugin
```

---

## 六、插件可操作的完整 API 总结

### 输入能力（PluginInput）

| API | 用途 | 来源 |
|---|---|---|
| `$` BunShell | 执行任意 shell 命令 | `shell.ts` |
| `client` Kilo SDK | 调用 CLI 所有 HTTP API | `@kilocode/sdk` |
| `project` | 项目信息 | SDK 类型 |
| `directory` | 当前项目目录 | 字符串 |
| `worktree` | worktree 根路径 | 字符串 |
| `serverUrl` | CLI 服务器 URL | URL 对象 |
| `experimental_workspace.register` | 注册 workspace 适配器 | 函数 |

### 注册能力（Hooks 返回值）

| 能力 | Hook Key | 说明 |
|---|---|---|
| 自定义工具 | `tool` | AI 可调用的工具 |
| 认证 Provider | `auth` | OAuth/API Key 认证流程 |
| 模型 Provider | `provider` | 自定义 LLM 模型 |
| Workspace | `experimental_workspace.register` | 工作区适配器 |

### 拦截能力（Hook 回调）

| 拦截点 | Hook Key | 能修改什么 |
|---|---|---|
| System Prompt | `experimental.chat.system.transform` | 追加知识上下文 |
| 消息内容 | `chat.message` | 修改用户消息 |
| LLM 参数 | `chat.params` | temperature、maxTokens 等 |
| HTTP 头 | `chat.headers` | 自定义请求头 |
| 消息流 | `experimental.chat.messages.transform` | 变换完整消息序列 |
| 工具执行前 | `tool.execute.before` | 修改工具参数 |
| 工具执行后 | `tool.execute.after` | 修改工具输出 |
| 工具定义 | `tool.definition` | 修改工具描述/参数 |
| 斜杠命令前 | `command.execute.before` | 修改命令模板 |
| Shell 环境 | `shell.env` | 注入环境变量 |
| 权限 | `permission.ask` | 自动批准/拒绝 |
| 会话压缩 | `experimental.session.compacting` | 自定义压缩 prompt |
| 自动续行 | `experimental.compaction.autocontinue` | 控制续行行为 |
| 小模型 | `experimental.provider.small_model` | 覆盖小模型 |
| 文本补全 | `experimental.text.complete` | 自定义补全 |
| 所有事件 | `event` | 监听全局事件 |
| 配置变更 | `config` | 响应配置 |

### 工具执行上下文（ToolContext）

| API | 用途 |
|---|---|
| `sessionID` | 当前会话 ID |
| `messageID` | 触发消息 ID |
| `agent` | 当前 agent 名称 |
| `directory` | 项目目录 |
| `worktree` | worktree 路径 |
| `abort` | 中止信号（用户取消时触发） |
| `metadata()` | 设置工具元数据（标题、附加数据） |
| `ask()` | 请求用户权限确认 |
| 返回 `ToolAttachment[]` | 返回文件附件给 AI |

---

## 七、工具返回值类型

```typescript
// 字符串（最简单）
return "result text"

// 带元数据
return {
  title: "Wiki Generated",
  output: markdownContent,
  metadata: { files: 120, duration: "5s" },
  attachments: [
    { type: "file", mime: "text/markdown", url: "file://wiki/output.md", filename: "output.md" },
    { type: "file", mime: "image/png", url: "file://screenshots/flow.png" },
  ],
}
```

---

## 八、文件位置速查

| 内容 | 路径 |
|---|---|
| V1 Plugin 接口 | `packages/plugin/src/index.ts` |
| ToolDefinition | `packages/plugin/src/tool.ts` |
| BunShell | `packages/plugin/src/shell.ts` |
| V1 示例（工具） | `packages/plugin/src/example.ts` |
| V1 示例（workspace） | `packages/plugin/src/example-workspace.ts` |
| V2 Plugin 接口 | `packages/plugin/src/v2/promise/plugin.ts` |
| V2 PluginContext | `packages/plugin/src/v2/promise/context.ts` |
| V2 全部导出 | `packages/plugin/src/v2/promise/index.ts` |
| 插件加载器 | `packages/opencode/src/plugin/loader.ts` |
| 插件 Hooks 触发 | `packages/opencode/src/plugin/index.ts:290` |
| 工具自动发现 | `packages/opencode/src/tool/registry.ts:207` |
| 工具消费 Hook | `packages/opencode/src/tool/registry.ts:223` |
| System Prompt Hook 消费 | `packages/opencode/src/session/llm/request.ts:86` |
| 配置 schema | `packages/core/src/v1/config/plugin.ts` |
