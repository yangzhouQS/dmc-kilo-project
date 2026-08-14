/**
 * MCP 知识库选择器 CLI 插件
 *
 * 功能：
 * - tool.definition: 逐个工具累积，写入全局缓存
 * - system.transform: 读取用户选择，注入优先使用引导
 *
 * 缓存位置：~/.config/kilo/.cache/（全局，跨项目共享）
 * 放置位置：.kilo/plugin/mcp-selector.ts
 */

import type { Plugin } from "@kilocode/plugin"
import { writeFileSync, readFileSync, existsSync, mkdirSync } from "node:fs"
import { join } from "node:path"
import { homedir } from "node:os"

const BUILTIN_TOOLS = new Set([
  "read", "write", "edit", "bash", "grep", "glob",
  "webfetch", "websearch", "question", "task",
  "todowrite", "todoread", "plan", "skill",
  "agent_manager", "notify_user",
])

const BUILTIN_PREFIXES = ["kilo-playwright_", "playwright_"]

function isBuiltin(name: string): boolean {
  if (BUILTIN_TOOLS.has(name)) return true
  return BUILTIN_PREFIXES.some((prefix) => name.startsWith(prefix))
}

function getGlobalCacheDir(): string {
  const xdg = process.env.XDG_CONFIG_HOME || process.env.KILO_CONFIG_DIR
  if (xdg) return join(xdg, "kilo", ".cache")
  return join(homedir(), ".config", "kilo", ".cache")
}

function ensureDir(dir: string) {
  if (!existsSync(dir)) mkdirSync(dir, { recursive: true })
}

const cacheDir = getGlobalCacheDir()
const toolsPath = join(cacheDir, "mcp-tools.json")
const activePath = join(cacheDir, "active-mcp.json")

// 内存中累积已见工具（避免频繁读写文件）
const seenTools = new Map<string, string>() // toolName -> description
let cacheDirty = false

function flushToolsCache() {
  if (!cacheDirty) return
  cacheDirty = false
  try {
    ensureDir(cacheDir)

    // 按 server 分组
    const serverMap = new Map<string, Array<{ name: string; description: string }>>()
    for (const [name, desc] of seenTools) {
      if (isBuiltin(name)) continue
      const idx = name.indexOf("_")
      const serverName = idx > 0 ? name.substring(0, idx) : name
      if (!serverMap.has(serverName)) {
        serverMap.set(serverName, [])
      }
      serverMap.get(serverName)!.push({ name, description: desc })
    }

    const data = {
      updatedAt: new Date().toISOString(),
      servers: Array.from(serverMap.entries()).map(([name, tools]) => ({ name, tools })),
    }
    writeFileSync(toolsPath, JSON.stringify(data, null, 2), "utf-8")
  } catch {
    // 静默失败
  }
}

function readJson(path: string): any | null {
  try {
    return JSON.parse(readFileSync(path, "utf-8"))
  } catch {
    return null
  }
}

const McpSelectorPlugin: Plugin = async () => {
  return {
    // ---- 逐个工具累积，写入缓存 ----
    "tool.definition": async (input, output) => {
      try {
        const toolID = input.toolID
        const desc = output.description || ""
        if (!seenTools.has(toolID) || seenTools.get(toolID) !== desc) {
          seenTools.set(toolID, desc)
          cacheDirty = true
        }
        flushToolsCache()
      } catch {
        // 静默失败
      }
    },

    // ---- 每次对话前注入 MCP 选择引导 ----
    "experimental.chat.system.transform": async (_input, output) => {
      try {
        const active = readJson(activePath)
        if (active && active.selectedTools && active.selectedTools.length > 0) {
          const toolList = active.selectedTools.join(", ")
          const instruction = active.instruction || "请优先使用以下工具"
          output.system.push(`## MCP 工具优先使用规则\n${instruction}：${toolList}`)
        }
      } catch {
        // 静默失败
      }
    },
  }
}

export default { id: "mcp-selector", server: McpSelectorPlugin }
