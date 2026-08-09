/**
 * RepoWiki CLI 插件 v2
 *
 * 改进：不再依赖 JetBrains PSI JSON，AI 使用内置工具（read/glob/grep/semantic_search）
 * 自行分析代码后，通过 wiki-save 工具写入文档。
 *
 * 工具列表：
 *   wiki-save     — AI 生成文档后写入知识库
 *   wiki-list     — 列出已有 Wiki 文档
 *   wiki-query    — 全文搜索知识库
 *   wiki-remember — 保存记忆
 *
 * Hook：
 *   experimental.chat.system.transform — 每次对话注入知识库上下文
 *   chat.message — /remember 指令拦截
 *   event — 文件变更监听
 *   experimental.session.compacting — 压缩保留知识
 */

import type { Plugin } from "@kilocode/plugin"
import { tool } from "@kilocode/plugin/tool"
import {
  readFileSync,
  writeFileSync,
  readdirSync,
  existsSync,
  mkdirSync,
  statSync,
} from "node:fs"
import { join, basename } from "node:path"

// ============================================================
// 常量
// ============================================================

const MAX_CONTEXT_CHARS = 6000
const MAX_SEARCH_RESULTS = 10
const MAX_CARD_CHARS = 2000
const MAX_MEMORY_CHARS = 500

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
  const plan = readText(join(wikiBase, "wiki_plan.yaml"))
  if (plan) {
    const match = plan.match(/^language:\s*(\w+)/m)
    if (match) return match[1]
  }
  if (existsSync(join(wikiBase, "en"))) return "en"
  return "zh"
}

// ============================================================
// 知识收集 — System Prompt 注入
// ============================================================

function collectKnowledge(wikiBase: string): string {
  const lang = detectLanguage(wikiBase)
  const parts: string[] = []

  const cardsDir = join(wikiBase, lang, "knowledge_cards")
  for (const f of listMarkdownFiles(cardsDir).slice(0, 3)) {
    const text = readText(f)
    if (text) parts.push(truncate(text, MAX_CARD_CHARS))
  }

  const memDir = join(wikiBase, lang, "memory")
  for (const f of listMarkdownFiles(memDir).slice(0, 5)) {
    const text = readText(f)
    if (text) parts.push(truncate(text, MAX_MEMORY_CHARS))
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
      results.push({ file: basename(filePath), snippet: text.substring(start, end) })
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
// 列出已有文档
// ============================================================

function listWikiFiles(wikiBase: string): string {
  const lang = detectLanguage(wikiBase)
  const dirs = [
    { label: "Wiki 文档", dir: join(wikiBase, lang, "wiki") },
    { label: "知识卡片", dir: join(wikiBase, lang, "knowledge_cards") },
    { label: "项目记忆", dir: join(wikiBase, lang, "memory") },
  ]

  const sections: string[] = []
  for (const { label, dir } of dirs) {
    const files = listMarkdownFiles(dir)
    if (files.length === 0) continue
    const names = files.map((f) => `- ${basename(f, ".md")}`).join("\n")
    sections.push(`**${label}** (${files.length}):\n${names}`)
  }

  if (sections.length === 0) {
    return "知识库为空。请使用 read/glob/grep 工具分析项目代码后，调用 wiki-save 生成文档。"
  }
  return sections.join("\n\n")
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
        // 静默失败
      }
    },

    // ---- AI 可调用工具 ----
    tool: {
      // 保存 Wiki 文档（AI 生成内容后调用）
      "wiki-save": tool({
        description:
          "将 AI 生成的 Wiki 文档/知识卡片保存到项目知识库。\n" +
          "工作流程：\n" +
          "1. 先用 read/glob/grep/semantic_search 分析项目代码\n" +
          "2. 生成结构化 Markdown 文档\n" +
          "3. 调用此工具保存\n\n" +
          "保存后文档会自动注入到后续对话的上下文中。",
        args: {
          filename: tool.schema
            .string()
            .describe("文件名（不含 .md 后缀），如 'user-service' 或 'tech-stack'"),
          content: tool.schema
            .string()
            .describe("完整的 Markdown 文档内容"),
          category: tool.schema
            .enum(["wiki", "knowledge_cards"])
            .describe("文档类别：wiki=模块文档，knowledge_cards=知识卡片")
            .default("wiki"),
        },
        async execute(args) {
          const lang = detectLanguage(wikiBase)
          const targetDir = join(wikiBase, lang, args.category)
          ensureDir(targetDir)

          const safeName = args.filename.replace(/[^a-zA-Z0-9_\-\.]/g, "-")
          const filePath = join(targetDir, `${safeName}.md`)
          writeText(filePath, args.content)

          const lines = args.content.split("\n").length
          return {
            title: `已保存 ${args.category}/${safeName}.md`,
            output: `文档已保存到知识库。\n文件: ${args.category}/${safeName}.md\n行数: ${lines}\n后续对话将自动注入此文档作为上下文。`,
            metadata: { file: safeName, category: args.category, lines },
          }
        },
      }),

      // 列出已有知识库文档
      "wiki-list": tool({
        description:
          "列出项目知识库中已有的 Wiki 文档、知识卡片和记忆。\n" +
          "在生成新文档前调用此工具，避免重复生成。",
        args: {},
        async execute() {
          const listing = listWikiFiles(wikiBase)
          return {
            title: "知识库文档列表",
            output: listing,
          }
        },
      }),

      // 搜索知识库
      "wiki-query": tool({
        description:
          "全文搜索项目知识库（Wiki 文档 + 知识卡片 + 项目记忆）。\n" +
          "注意：此工具搜索 .kilo/repowiki/ 目录下的 Markdown 文件，\n" +
          "不搜索源代码。搜索源代码请使用 semantic_search 或 grep。",
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

      // 保存记忆
      "wiki-remember": tool({
        description:
          "将关键决策/踩坑/约束保存到项目记忆。\n" +
          "完成任务后主动调用，记录有价值的信息供后续对话使用。",
        args: {
          text: tool.schema.string().describe("要保存的记忆内容"),
          tag: tool.schema
            .enum(["architecture", "bug_fix", "spec", "general"])
            .describe("记忆标签")
            .default("general"),
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
