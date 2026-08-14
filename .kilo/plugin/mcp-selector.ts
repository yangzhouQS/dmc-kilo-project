/**
 * MCP 知识库选择器 CLI 插件
 *
 * 功能（v2）：
 * - system.transform: 读取用户选择，注入优先使用引导
 *
 * 工具清单缓存（~/.config/kilo/.cache/mcp-tools.json）已改由 JetBrains 插件
 * 直连 MCP 服务器探测写入（custom/com/dmc/mcp/McpToolProbe.kt）。
 * 本插件不再通过 tool.definition 捕获工具（该链路捕获不到 MCP 工具，
 * 且每次触发都会用本地工具覆盖缓存，破坏探测结果）。
 *
 * 缓存位置：~/.config/kilo/.cache/（全局，跨项目共享）
 * 放置位置：.kilo/plugin/mcp-selector.ts
 */

import type { Plugin } from "@kilocode/plugin"
import { readFileSync } from "node:fs"
import { join } from "node:path"
import { homedir } from "node:os"

function getGlobalCacheDir(): string {
  const xdg = process.env.XDG_CONFIG_HOME || process.env.KILO_CONFIG_DIR
  if (xdg) return join(xdg, "kilo", ".cache")
  return join(homedir(), ".config", "kilo", ".cache")
}

function readJson(path: string): any | null {
  try {
    return JSON.parse(readFileSync(path, "utf-8"))
  } catch {
    return null
  }
}

const activePath = join(getGlobalCacheDir(), "active-mcp.json")

const McpSelectorPlugin: Plugin = async () => {
  return {
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
