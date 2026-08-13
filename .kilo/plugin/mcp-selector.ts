/**
 * MCP 知识库选择器 CLI 插件
 *
 * 功能：
 * - tool.definition: 捕获所有 MCP 工具，写入全局缓存
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
  "kilo-playwright_navigate", "kilo-playwright_click",
  "kilo-playwright_fill", "kilo-playwright_screenshot",
  "kilo-playwright_evaluate", "kilo-playwright_close",
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

function writeJson(path: string, data: unknown) {
  ensureDir(join(path, ".."))
  writeFileSync(path, JSON.stringify(data, null, 2), "utf-8")
}

function readJson(path: string): any | null {
  try {
    return JSON.parse(readFileSync(path, "utf-8"))
  } catch {
    return null
  }
}

const McpSelectorPlugin: Plugin = async () => {
  const cacheDir = getGlobalCacheDir()
  const toolsPath = join(cacheDir, "mcp-tools.json")
  const activePath = join(cacheDir, "active-mcp.json")

  return {
    "tool.definition": async (tools) => {
      try {
        const mcpTools = tools.filter((t) => !isBuiltin(t.name))

        const serverMap = new Map<string, Array<{ name: string; description: string }>>()
        for (const tool of mcpTools) {
          const idx = tool.name.indexOf("_")
          const serverName = idx > 0 ? tool.name.substring(0, idx) : tool.name
          if (!serverMap.has(serverName)) {
            serverMap.set(serverName, [])
          }
          serverMap.get(serverName)!.push({
            name: tool.name,
            description: tool.description || "",
          })
        }

        const data = {
          updatedAt: new Date().toISOString(),
          servers: Array.from(serverMap.entries()).map(([name, tools]) => ({
            name,
            tools,
          })),
        }
        writeJson(toolsPath, data)
      } catch {
        // 静默失败
      }

      return tools
    },

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
