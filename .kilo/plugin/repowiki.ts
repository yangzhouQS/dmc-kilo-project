/**
 * RepoWiki CLI 插件
 *
 * 功能：
 * - 每次对话前自动注入项目知识库上下文 (experimental.chat.system.transform)
 * - AI 可调用 wiki-generate / wiki-query / wiki-remember 工具
 * - 文件变更监听 + /remember 指令处理
 *
 * 放置位置: .kilo/plugin/repowiki.ts
 * 加载方式: CLI 启动时自动发现，无需配置
 */

import type { Plugin } from "@kilocode/plugin"
import { tool } from "@kilocode/plugin/tool"
import {
  readFileSync,
  writeFileSync,
  readdirSync,
  existsSync,
  mkdirSync,
} from "node:fs"
import { join, basename } from "node:path"

// ============================================================
// 常量
// ============================================================

const MAX_CONTEXT_CHARS = 6000
const MAX_SEARCH_RESULTS = 10
const IGNORED_DIRS = new Set([
  ".git",
  "node_modules",
  "dist",
  "build",
  "out",
  "target",
  ".idea",
  ".vscode",
  "__generated__",
  ".gradle",
])

// ============================================================
// 文件工具
// ============================================================

function ensureDir(dir: string): void {
  if (!existsSync(dir)) mkdirSync(dir, { recursive: true })
}

function readText(path: string): string | null {
  try {
    return readFileSync(path, "utf-8")
  } catch {
    return null
  }
}

function writeText(path: string, content: string): void {
  const dir = join(path, "..")
  ensureDir(dir)
  writeFileSync(path, content, "utf-8")
}

function listMarkdownFiles(dir: string): string[] {
  if (!existsSync(dir)) return []
  try {
    return readdirSync(dir)
      .filter((f) => f.endsWith(".md"))
      .map((f) => join(dir, f))
  } catch {
    return []
  }
}

function truncate(text: string, maxChars: number): string {
  if (text.length <= maxChars) return text
  return text.substring(0, maxChars) + "\n...（已截断）"
}

function detectLanguage(wikiBase: string): string {
	// 默认中文
	return "zh"
  // 优先读 wiki_plan.yaml
  const plan = readText(join(wikiBase, "wiki_plan.yaml"))
  if (plan) {
    const match = plan.match(/^language:\s*(\w+)/m)
    if (match) return match[1]
  }
  // 其次检查目录是否存在
  if (existsSync(join(wikiBase, "en"))) return "en"
  // 默认中文
  return "zh"
}

// ============================================================
// 知识收集 — System Prompt 注入用
// ============================================================

function collectKnowledge(wikiBase: string): string {
  const lang = detectLanguage(wikiBase)
  const parts: string[] = []

  // 1. 知识卡片（优先级最高，最多 3 张）
  const cardsDir = join(wikiBase, lang, "knowledge_cards")
  for (const f of listMarkdownFiles(cardsDir).slice(0, 3)) {
    const text = readText(f)
    if (text) parts.push(truncate(text, 2000))
  }

  // 2. 项目记忆（最多 5 条）
  const memDir = join(wikiBase, lang, "memory")
  for (const f of listMarkdownFiles(memDir).slice(0, 5)) {
    const text = readText(f)
    if (text) parts.push(truncate(text, 500))
  }

  if (parts.length === 0) return ""

  const result = "## 项目知识库上下文\n\n" + parts.join("\n\n---\n\n")
  return truncate(result, MAX_CONTEXT_CHARS)
}

// ============================================================
// 全文搜索
// ============================================================

function searchMarkdown(
  dir: string,
  query: string,
  results: Array<{ file: string; snippet: string }>,
): void {
  const q = query.toLowerCase()
  for (const filePath of listMarkdownFiles(dir)) {
    if (results.length >= MAX_SEARCH_RESULTS) return
    const text = readText(filePath)
    if (!text) continue
    const lower = text.toLowerCase()
    const idx = lower.indexOf(q)
    if (idx >= 0) {
      const start = Math.max(0, idx - 100)
      const end = Math.min(text.length, idx + 200)
      results.push({
        file: basename(filePath),
        snippet: text.substring(start, end),
      })
    }
  }
}

function searchAllKnowledge(wikiBase: string, query: string): string {
  const lang = detectLanguage(wikiBase)
  const results: Array<{ file: string; snippet: string }> = []

  searchMarkdown(join(wikiBase, lang, "wiki"), query, results)
  searchMarkdown(join(wikiBase, lang, "knowledge_cards"), query, results)
  searchMarkdown(join(wikiBase, lang, "memory"), query, results)

  if (results.length === 0) return `未找到与 "${query}" 相关的知识库内容`

  return results
    .map((r, i) => `### ${i + 1}. ${r.file}\n\n\`\`\`\n${r.snippet}\n\`\`\``)
    .join("\n\n")
}

// ============================================================
// Wiki 生成
// ============================================================

function generateWikiPage(module: string, psiData: any): string {
  const lines: string[] = []
  lines.push(`# ${module} 模块文档`)
  lines.push("")
  lines.push(
    "> 本文档由 RepoWiki 自动生成，人工编辑请使用 `<!-- kilocode-manual-edit-start/end -->` 标记保护。",
  )
  lines.push("")

  const data = psiData?.[module] || psiData || {}

  if (data.classes?.length) {
    lines.push("## 类结构")
    for (const cls of data.classes) {
      lines.push(`### ${cls.name}`)
      if (cls.methods?.length) {
        lines.push("方法：")
        for (const m of cls.methods) {
          lines.push(`- \`${m.signature || m.name + "()"}\``)
        }
      }
      lines.push("")
    }
  }

  if (data.interfaces?.length) {
    lines.push("## 接口定义")
    for (const iface of data.interfaces) {
      lines.push(`### ${iface.name}`)
      if (iface.methods?.length) {
        for (const m of iface.methods) {
          lines.push(`- \`${m.signature || m.name + "()"}\``)
        }
      }
      lines.push("")
    }
  }

  if (data.functions?.length) {
    lines.push("## 公共函数")
    for (const fn of data.functions) {
      lines.push(`- \`${fn.signature || fn.name + "()"}\``)
    }
    lines.push("")
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
// 文件变更追踪
// ============================================================

function markFileChanged(wikiBase: string, filePath: string): void {
  const metaFile = join(wikiBase, ".meta", "changed-files.json")
  let changed: string[] = []
  try {
    changed = JSON.parse(readText(metaFile) || "[]")
  } catch {
    changed = []
  }
  if (!changed.includes(filePath)) {
    changed.push(filePath)
    writeText(metaFile, JSON.stringify(changed, null, 2))
  }
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

    // ---- AI 可调用工具 ----
    tool: {
      "wiki-generate": tool({
        description:
          "扫描项目代码结构并生成 Wiki 文档。" +
          "JetBrains 插件会将 PSI 结构写入 .kilo/repowiki/.cache/psi-structure.json。" +
          "生成的文档写入 .kilo/repowiki/{lang}/wiki/ 目录。",
        args: {
          module: tool.schema
            .string()
            .describe("要生成文档的模块/目录名"),
          description: tool.schema
            .string()
            .optional()
            .describe("模块功能简述（可选）"),
        },
        async execute(args) {
          const psiPath = join(wikiBase, ".cache", "psi-structure.json")
          const psiText = readText(psiPath)
          let psiData: any = {}
          try {
            psiData = JSON.parse(psiText || "{}")
          } catch {
            // JSON 解析失败，用空数据
          }

          const content = generateWikiPage(args.module, psiData)
          const lang = detectLanguage(wikiBase)
          const wikiDir = join(wikiBase, lang, "wiki")
          ensureDir(wikiDir)
          const filePath = join(wikiDir, `${args.module}.md`)
          writeText(filePath, content)

          const lineCount = content.split("\n").length
          return {
            title: `Wiki 生成完成: ${args.module}`,
            output: `已生成 ${args.module}.md（${lineCount} 行）。文件路径: ${filePath}`,
            metadata: { module: args.module, file: `${args.module}.md` },
          }
        },
      }),

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

      "wiki-remember": tool({
        description:
          "将关键决策/踩坑/约束保存到项目记忆。" +
          "完成任务后主动调用，记录有价值的信息供后续对话使用。",
        args: {
          text: tool.schema.string().describe("要保存的记忆内容"),
          tag: tool.schema
            .enum(["architecture", "bug_fix", "spec", "general"])
            .describe("记忆标签"),
        },
        async execute(args) {
          saveMemory(wikiBase, args.text, args.tag)
          return {
            title: "记忆已保存",
            output: `已保存 [${args.tag}] 记忆: ${args.text.substring(0, 80)}${args.text.length > 80 ? "..." : ""}`,
          }
        },
      }),
    },

    // ---- 文件变更监听 ----
    event: async ({ event }) => {
      if (event.type === "file.edited") {
        const filePath = (event as any).properties?.path || ""
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
        textPart.text = `已保存到项目记忆: ${match[1].trim().substring(0, 80)}...`
      }
    },

    // ---- 会话压缩时保留关键记忆 ----
    "experimental.session.compacting": async (_input, output) => {
      try {
        const knowledge = collectKnowledge(wikiBase)
        if (knowledge) {
          output.context.push(knowledge)
        }
      } catch {
        // 静默失败
      }
    },
  }
}

export default { id: "repowiki", server: RepowikiPlugin }
