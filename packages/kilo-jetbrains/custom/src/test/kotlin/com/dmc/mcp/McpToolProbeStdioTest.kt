package com.dmc.mcp

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * stdio 协议探测集成测试：用 node 起一个内联 mini MCP server。
 * 机器上无 node 时跳过。
 */
class McpToolProbeStdioTest {

    private val nodeAvailable: Boolean by lazy {
        try {
            val probe = ProcessBuilder("cmd", "/c", "node", "--version")
                .redirectErrorStream(true)
                .start()
            probe.outputStream.close()
            val out = probe.inputStream.bufferedReader().readText()
            probe.waitFor()
            out.trim().startsWith("v")
        } catch (e: Exception) {
            false
        }
    }

    @Test
    fun `probe stdio server returns tools`() {
        if (!nodeAvailable) return
        val dir = createTempDirectory("mcp-probe-test").toFile()
        try {
            val script = File(dir, "mini-server.js")
            script.writeText(
                """
                const rl = require('readline').createInterface({ input: process.stdin });
                rl.on('line', (line) => {
                  if (!line.trim()) return;
                  let msg;
                  try { msg = JSON.parse(line); } catch { return; }
                  if (msg.id === 1 && msg.method === 'initialize') {
                    process.stdout.write(JSON.stringify({ jsonrpc: '2.0', id: 1, result: { protocolVersion: '2024-11-05', capabilities: { tools: {} }, serverInfo: { name: 'mini' } } }) + '\n');
                  } else if (msg.id === 2 && msg.method === 'tools/list') {
                    process.stdout.write(JSON.stringify({ jsonrpc: '2.0', id: 2, result: { tools: [
                      { name: 'echo', description: 'Echo input' },
                      { name: 'ping', description: 'Ping the server' },
                    ] } }) + '\n');
                  }
                });
                """.trimIndent(),
            )
            val config = McpServerConfig(
                name = "testsrv",
                type = "local",
                command = listOf("node", script.absolutePath),
                timeoutMs = 15_000,
            )
            val result = McpToolProbe.probe(config)
            assertEquals(null, result.error)
            assertEquals(2, result.tools.size)
            assertEquals("echo", result.tools[0].first)
            assertEquals("Echo input", result.tools[0].second)
            assertEquals("ping", result.tools[1].first)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `probe reports error for missing command`() {
        val result = McpToolProbe.probe(McpServerConfig(name = "bad", type = "local", command = emptyList()))
        assertEquals("missing command", result.error)
        assertTrue(result.tools.isEmpty())
    }

    @Test
    fun `probe skips disabled server`() {
        val result = McpToolProbe.probe(McpServerConfig(name = "off", type = "local", enabled = false, command = listOf("node", "x.js")))
        assertEquals("disabled", result.error)
    }
}
