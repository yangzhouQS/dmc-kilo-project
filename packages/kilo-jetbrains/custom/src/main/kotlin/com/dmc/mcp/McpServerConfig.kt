package com.dmc.mcp

import com.google.gson.JsonParser
import java.io.File

/**
 * MCP 服务器连接配置，来源：kilo.jsonc 的 "mcp" 段。
 * 与 CLI 的 GET /config 返回结构一致：type=local 走 stdio，type=remote 走 streamable HTTP。
 */
data class McpServerConfig(
    val name: String,
    val type: String, // "local" | "remote"
    val enabled: Boolean = true,
    val command: List<String> = emptyList(),
    val environment: Map<String, String> = emptyMap(),
    val url: String = "",
    val headers: Map<String, String> = emptyMap(),
    val timeoutMs: Long = 30_000,
)

object McpConfigReader {

    /**
     * 读取 MCP 服务器配置：全局 ~/.config/kilo/kilo.jsonc + 工作区 {project}/.kilo/kilo.jsonc 合并。
     * 工作区同名服务器覆盖全局。
     */
    fun readServers(projectDir: File?): List<McpServerConfig> =
        readServers(globalConfigFile(), projectDir)

    internal fun readServers(globalFile: File, projectDir: File?): List<McpServerConfig> {
        val global = parseMcpSection(globalFile)
        val workspace = projectDir?.let { parseMcpSection(workspaceConfigFile(it)) } ?: emptyMap()
        return (global + workspace).map { (name, cfg) ->
            cfg.copy(name = name)
        }.sortedBy { it.name }
    }

    fun globalConfigFile(): File {
        val home = System.getProperty("user.home")
        return File("$home/.config/kilo/kilo.jsonc")
    }

    fun workspaceConfigFile(projectDir: File): File = File(projectDir, ".kilo/kilo.jsonc")

    internal fun parseMcpSection(file: File): Map<String, McpServerConfig> {
        if (!file.isFile) return emptyMap()
        return try {
            val root = JsonParser.parseString(stripJsonc(file.readText(Charsets.UTF_8))).asJsonObject
            val mcp = root.getAsJsonObject("mcp") ?: return emptyMap()
            mcp.entrySet().associate { (name, elem) ->
                val obj = elem.asJsonObject
                name to McpServerConfig(
                    name = name,
                    type = obj.get("type")?.takeIf { it.isJsonPrimitive }?.asString ?: "local",
                    enabled = obj.get("enabled")?.takeIf { it.isJsonPrimitive }?.asBoolean ?: true,
                    command = obj.getAsJsonArray("command")?.map { it.asString } ?: emptyList(),
                    environment = obj.getAsJsonObject("environment")?.entrySet()?.associate { (k, v) -> k to v.asString } ?: emptyMap(),
                    url = obj.get("url")?.takeIf { it.isJsonPrimitive }?.asString ?: "",
                    headers = obj.getAsJsonObject("headers")?.entrySet()?.associate { (k, v) -> k to v.asString } ?: emptyMap(),
                    timeoutMs = obj.get("timeout")?.takeIf { it.isJsonPrimitive }?.asLong ?: 30_000,
                )
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    /**
     * 剥离 JSONC 注释（// 与 块注释）及字符串外尾逗号，输出可被 Gson 解析的 JSON。
     * 使用状态机保证字符串内的 // 与 逗号不受影响。
     */
    internal fun stripJsonc(text: String): String {
        val sb = StringBuilder(text.length)
        var i = 0
        val n = text.length
        var inString = false
        var stringEscape = false
        while (i < n) {
            val c = text[i]
            if (inString) {
                sb.append(c)
                if (stringEscape) {
                    stringEscape = false
                } else if (c == '\\') {
                    stringEscape = true
                } else if (c == '"') {
                    inString = false
                }
                i++
                continue
            }
            when {
                c == '"' -> {
                    inString = true
                    sb.append(c)
                    i++
                }
                c == '/' && i + 1 < n && text[i + 1] == '/' -> {
                    while (i < n && text[i] != '\n') i++
                }
                c == '/' && i + 1 < n && text[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < n && !(text[i] == '*' && text[i + 1] == '/')) i++
                    i = (i + 2).coerceAtMost(n)
                }
                else -> {
                    sb.append(c)
                    i++
                }
            }
        }
        // 去掉字符串外尾逗号：仅处理 ",\s*}" 或 ",\s*]" 形式（前面的状态机已保证引号配对完整，
        // 此处基于引号计数做保守替换）
        val raw = sb.toString()
        val out = StringBuilder(raw.length)
        var quoteCount = 0
        var j = 0
        while (j < raw.length) {
            val ch = raw[j]
            if (ch == '"') quoteCount++
            if (ch == ',' && quoteCount % 2 == 0) {
                var k = j + 1
                while (k < raw.length && raw[k].isWhitespace()) k++
                if (k < raw.length && (raw[k] == '}' || raw[k] == ']')) {
                    j = k
                    continue
                }
            }
            out.append(ch)
            j++
        }
        return out.toString()
    }
}
