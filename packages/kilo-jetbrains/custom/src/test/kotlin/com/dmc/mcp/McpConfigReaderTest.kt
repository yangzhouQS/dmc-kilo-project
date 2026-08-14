package com.dmc.mcp

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class McpConfigReaderTest {

    private fun parse(raw: String): Map<String, McpServerConfig> {
        val dir = createTempDirectory("mcp-test").toFile()
        try {
            val file = File(dir, "kilo.jsonc")
            file.writeText(raw, Charsets.UTF_8)
            return McpConfigReader.parseMcpSection(file)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `stripJsonc removes line and block comments outside strings`() {
        val raw = """
            {
              // line comment with url http://example.com
              "name": "a", /* block
              comment */ "url": "https://x/y?a=1&b=2",
              "path": "C:\\dir\\file.txt"
            }
        """.trimIndent()
        val stripped = McpConfigReader.stripJsonc(raw)
        val obj = com.google.gson.JsonParser.parseString(stripped).asJsonObject
        assertEquals("a", obj.get("name").asString)
        assertEquals("https://x/y?a=1&b=2", obj.get("url").asString)
        assertEquals("C:\\dir\\file.txt", obj.get("path").asString)
    }

    @Test
    fun `stripJsonc keeps slashes and commas inside strings and drops trailing commas`() {
        val raw = """{"a": "x//y,]", "b": [1,2,],}"""
        val stripped = McpConfigReader.stripJsonc(raw)
        val obj = com.google.gson.JsonParser.parseString(stripped).asJsonObject
        assertEquals("x//y,]", obj.get("a").asString)
        assertEquals(2, obj.getAsJsonArray("b").size())
    }

    @Test
    fun `parseMcpSection handles local remote and defaults`() {
        val servers = parse(
            """
            {
              // comments allowed
              "mcp": {
                "alpha": {
                  "type": "local",
                  "command": ["node", "alpha.js"],
                  "environment": {"K": "V"},
                  "timeout": 5000
                },
                "beta": {
                  "type": "remote",
                  "url": "https://beta.example.com/mcp",
                  "headers": {"Authorization": "Bearer t"},
                  "enabled": false
                }
              }
            }
            """.trimIndent(),
        )
        assertEquals(2, servers.size)

        val alpha = servers.getValue("alpha")
        assertEquals("local", alpha.type)
        assertTrue(alpha.enabled)
        assertEquals(listOf("node", "alpha.js"), alpha.command)
        assertEquals(mapOf("K" to "V"), alpha.environment)
        assertEquals(5000L, alpha.timeoutMs)

        val beta = servers.getValue("beta")
        assertEquals("remote", beta.type)
        assertEquals("https://beta.example.com/mcp", beta.url)
        assertEquals(false, beta.enabled)
        assertEquals(mapOf("Authorization" to "Bearer t"), beta.headers)
    }

    @Test
    fun `readServers merges workspace override over global`() {
        val dir = createTempDirectory("mcp-test").toFile()
        try {
            val globalFile = File(dir, "global.jsonc")
            globalFile.writeText(
                """
                {
                  "mcp": {
                    "alpha": { "type": "local", "command": ["node", "alpha.js"] },
                    "beta": { "type": "remote", "url": "https://beta.example.com/mcp" }
                  }
                }
                """.trimIndent(),
            )
            val workspaceDir = File(dir, "ws")
            val workspaceFile = File(workspaceDir, ".kilo/kilo.jsonc")
            workspaceFile.parentFile.mkdirs()
            workspaceFile.writeText(
                """
                {
                  "mcp": {
                    "alpha": { "type": "local", "command": ["node", "workspace-alpha.js"] }
                  }
                }
                """.trimIndent(),
            )

            val servers = McpConfigReader.readServers(globalFile, workspaceDir)
            assertEquals(2, servers.size)
            val alpha = servers.first { it.name == "alpha" }
            assertEquals(listOf("node", "workspace-alpha.js"), alpha.command)
            val beta = servers.first { it.name == "beta" }
            assertEquals("remote", beta.type)
        } finally {
            dir.deleteRecursively()
        }
    }
}
