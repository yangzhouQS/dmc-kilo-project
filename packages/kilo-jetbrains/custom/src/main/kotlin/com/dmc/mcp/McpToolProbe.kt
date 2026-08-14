package com.dmc.mcp

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * MCP 工具探测器：直连 MCP 服务器执行 initialize + tools/list。
 *
 * - local (stdio): spawn 进程，行分隔 JSON-RPC over stdin/stdout
 * - remote (streamable HTTP): POST JSON-RPC，兼容 JSON 与 SSE 两种响应体
 *
 * 工具命名与 CLI 一致：<server>_<tool>，保证 active-mcp.json 注入的引导与
 * CLI 会话内的工具 ID 匹配。
 *
 * 必须在后台线程调用（阻塞 IO）。
 */
object McpToolProbe {

    data class ProbeResult(
        val server: McpServerConfig,
        val tools: List<Pair<String, String>>, // toolName -> description
        val error: String? = null,
    )

    private val JSON = "application/json; charset=utf-8".toMediaType()
    private const val PROTOCOL_VERSION = "2024-11-05"
    private const val CLIENT_NAME = "kilo-jetbrains-custom"

    private fun client(timeoutMs: Long): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .build()

    fun probeAll(servers: List<McpServerConfig>): List<ProbeResult> {
        if (servers.isEmpty()) return emptyList()
        val pool = java.util.concurrent.Executors.newFixedThreadPool(minOf(servers.size, 8)) { r ->
            Thread(r, "mcp-tool-probe").apply { isDaemon = true }
        }
        try {
            return servers.map { server ->
                pool.submit(java.util.concurrent.Callable { probe(server) })
            }.map { it.get() }
        } finally {
            pool.shutdownNow()
        }
    }

    fun probe(server: McpServerConfig): ProbeResult {
        if (!server.enabled) {
            return ProbeResult(server, emptyList(), "disabled")
        }
        return try {
            when (server.type) {
                "remote" -> probeRemote(server)
                else -> probeStdio(server)
            }
        } catch (e: Exception) {
            ProbeResult(server, emptyList(), e.message ?: e.javaClass.simpleName)
        }
    }

    // ---------------- stdio ----------------

    private fun probeStdio(server: McpServerConfig): ProbeResult {
        if (server.command.isEmpty()) {
            return ProbeResult(server, emptyList(), "missing command")
        }
        val cmds = resolveCommand(server.command)
        val pb = ProcessBuilder(cmds).redirectErrorStream(false)
        pb.environment().putAll(server.environment)
        val proc = pb.start()
        try {
            val initRequest = rpcRequest(
                1, "initialize",
                mapOf(
                    "protocolVersion" to PROTOCOL_VERSION,
                    "capabilities" to mapOf<String, Any>(),
                    "clientInfo" to mapOf("name" to CLIENT_NAME, "version" to "1.0"),
                ),
            )
            proc.outputStream.apply {
                write((initRequest + "\n").toByteArray(Charsets.UTF_8))
                flush()
            }
            readRpcResponse(proc.inputStream.bufferedReader(), 1, server.timeoutMs)
                ?: return ProbeResult(server, emptyList(), "initialize timeout")

            proc.outputStream.apply {
                write((rpcNotification("notifications/initialized") + "\n").toByteArray(Charsets.UTF_8))
                write((rpcRequest(2, "tools/list", emptyMap<String, Any>()) + "\n").toByteArray(Charsets.UTF_8))
                flush()
            }
            val toolsResp = readRpcResponse(proc.inputStream.bufferedReader(), 2, server.timeoutMs)
                ?: return ProbeResult(server, emptyList(), "tools/list timeout")
            return ProbeResult(server, parseTools(toolsResp), null)
        } finally {
            proc.destroyForcibly()
        }
    }

    private fun readRpcResponse(reader: BufferedReader, id: Int, timeoutMs: Long): JsonObject? {
        val deadline = System.currentTimeMillis() + timeoutMs + 10_000
        while (System.currentTimeMillis() < deadline) {
            val line = reader.readLine() ?: return null
            val trimmed = line.trim()
            if (trimmed.isEmpty() || !trimmed.startsWith("{")) continue
            val obj = try {
                JsonParser.parseString(trimmed).asJsonObject
            } catch (e: Exception) {
                continue
            }
            val respId = obj.get("id")?.takeIf { it.isJsonPrimitive }
            if (respId?.asNumber?.toInt() == id) return obj
        }
        return null
    }

    /**
     * Windows 上无扩展名命令（npx/pnpm 等）是 .cmd 脚本，ProcessBuilder 无法直接解析，
     * 用 cmd /c 包装。
     */
    private fun resolveCommand(command: List<String>): List<String> {
        val isWindows = File.separatorChar == '\\'
        val first = command.first()
        if (isWindows && !first.contains('.')) {
            return listOf("cmd", "/c") + command
        }
        return command
    }

    // ---------------- remote (streamable HTTP) ----------------

    private fun probeRemote(server: McpServerConfig): ProbeResult {
        if (server.url.isEmpty()) {
            return ProbeResult(server, emptyList(), "missing url")
        }
        val http = client(server.timeoutMs)

        val initBody = rpcRequest(
            1, "initialize",
            mapOf(
                "protocolVersion" to PROTOCOL_VERSION,
                "capabilities" to mapOf<String, Any>(),
                "clientInfo" to mapOf("name" to CLIENT_NAME, "version" to "1.0"),
            ),
        ).toRequestBody(JSON)

        val initBuilder = Request.Builder()
            .url(server.url)
            .post(initBody)
            .header("Accept", "application/json, text/event-stream")
        server.headers.forEach { (k, v) -> initBuilder.header(k, v) }
        val sessionId = http.newCall(initBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                return ProbeResult(server, emptyList(), "HTTP ${resp.code} at initialize")
            }
            val body = resp.body?.string().orEmpty()
            val parsed = parseRpcBody(body) ?: return ProbeResult(server, emptyList(), "invalid initialize response")
            resp.header("mcp-session-id") ?: parsed.get("result")?.asJsonObject?.get("sessionId")?.takeIf { it.isJsonPrimitive }?.asString
        }

        val toolsBuilder = Request.Builder()
            .url(server.url)
            .post(rpcRequest(2, "tools/list", mapOf("cursor" to null)).toRequestBody(JSON))
            .header("Accept", "application/json, text/event-stream")
        server.headers.forEach { (k, v) -> toolsBuilder.header(k, v) }
        sessionId?.let { toolsBuilder.header("Mcp-Session-Id", it) }
        http.newCall(toolsBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful) {
                return ProbeResult(server, emptyList(), "HTTP ${resp.code} at tools/list")
            }
            val body = resp.body?.string().orEmpty()
            val parsed = parseRpcBody(body) ?: return ProbeResult(server, emptyList(), "invalid tools/list response")
            if (parsed.get("error") != null && parsed["error"].isJsonObject) {
                return ProbeResult(server, emptyList(), parsed["error"].asJsonObject.get("message")?.asString ?: "rpc error")
            }
            return ProbeResult(server, parseTools(parsed), null)
        }
    }

    /** 解析 JSON 或 SSE（data: 行）响应体中的 JSON-RPC 对象。 */
    private fun parseRpcBody(body: String): JsonObject? {
        val trimmed = body.trim()
        if (trimmed.isEmpty()) return null
        return try {
            JsonParser.parseString(trimmed).asJsonObject
        } catch (e: Exception) {
            // SSE: 提取所有 data: 行内容后解析
            val data = trimmed.lineSequence()
                .filter { it.startsWith("data:") }
                .joinToString("") { it.removePrefix("data:").trim() }
            if (data.isEmpty()) return null
            return try {
                JsonParser.parseString(data).asJsonObject
            } catch (e2: Exception) {
                null
            }
        }
    }

    // ---------------- common ----------------

    private fun parseTools(resp: JsonObject): List<Pair<String, String>> {
        val tools = resp.getAsJsonObject("result")?.getAsJsonArray("tools") ?: return emptyList()
        return tools.mapNotNull { elem ->
            val t = elem.asJsonObject
            val name = t.get("name")?.takeIf { it.isJsonPrimitive }?.asString ?: return@mapNotNull null
            val desc = t.get("description")?.takeIf { it.isJsonPrimitive }?.asString ?: ""
            name to desc
        }
    }

    private fun rpcRequest(id: Int, method: String, params: Any): String {
        val gson = com.google.gson.Gson()
        return gson.toJson(mapOf("jsonrpc" to "2.0", "id" to id, "method" to method, "params" to params))
    }

    private fun rpcNotification(method: String): String {
        val gson = com.google.gson.Gson()
        return gson.toJson(mapOf("jsonrpc" to "2.0", "method" to method))
    }
}
