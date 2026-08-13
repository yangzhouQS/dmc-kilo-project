package com.dmc.prompt

import com.google.gson.Gson
import com.google.gson.JsonParser
import java.io.File

data class McpToolInfo(
    val name: String,
    val description: String = "",
)

data class McpServerInfo(
    val name: String,
    val tools: List<McpToolInfo> = emptyList(),
)

data class ActiveMcpSelection(
    val selectedTools: List<String> = emptyList(),
    val instruction: String = "",
)

object McpToolCache {

    private val gson = Gson()

    private fun globalCacheDir(): File {
        val home = System.getProperty("user.home")
        return File("$home/.config/kilo/.cache")
    }

    fun readTools(): List<McpServerInfo> {
        val file = File(globalCacheDir(), "mcp-tools.json")
        if (!file.exists()) return emptyList()
        return try {
            val root = JsonParser.parseString(file.readText()).asJsonObject
            val serversArray = root.getAsJsonArray("servers") ?: return emptyList()
            serversArray.map { serverElem ->
                val serverObj = serverElem.asJsonObject
                McpServerInfo(
                    name = serverObj.get("name")?.asString ?: "",
                    tools = serverObj.getAsJsonArray("tools")?.map { toolElem ->
                        val toolObj = toolElem.asJsonObject
                        McpToolInfo(
                            name = toolObj.get("name")?.asString ?: "",
                            description = toolObj.get("description")?.asString ?: "",
                        )
                    } ?: emptyList(),
                )
            }.filter { it.name.isNotEmpty() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun readActive(): ActiveMcpSelection? {
        val file = File(globalCacheDir(), "active-mcp.json")
        if (!file.exists()) return null
        return try {
            val root = JsonParser.parseString(file.readText()).asJsonObject
            ActiveMcpSelection(
                selectedTools = root.getAsJsonArray("selectedTools")
                    ?.map { it.asString } ?: emptyList(),
                instruction = root.get("instruction")?.asString ?: "",
            )
        } catch (e: Exception) {
            null
        }
    }

    fun writeActive(selectedTools: List<String>, instruction: String) {
        val dir = globalCacheDir()
        if (!dir.exists()) dir.mkdirs()
        val data = mapOf(
            "updatedAt" to java.util.Date().toString(),
            "selectedTools" to selectedTools,
            "instruction" to instruction,
        )
        File(dir, "active-mcp.json").writeText(gson.toJson(data), Charsets.UTF_8)
    }

    fun clearActive() {
        File(globalCacheDir(), "active-mcp.json").delete()
    }
}
