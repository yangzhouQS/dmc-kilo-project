package com.dmc.tools

import com.google.gson.JsonParser
import com.intellij.icons.AllIcons
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.io.File
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants

class McpToolsPanel(private val project: Project) : JPanel(BorderLayout()) {

    private data class McpTool(val name: String, val description: String)
    private data class McpServer(val name: String, val tools: List<McpTool>)

    private val listModel = DefaultListModel<String>()
    private val toolList = JBList(listModel)
    private val selectedTools = mutableSetOf<String>()

    init {
        border = JBUI.Borders.empty(8)

        toolList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        toolList.cellRenderer = McpToolRenderer(selectedTools)

        toolList.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                val row = toolList.locationToIndex(e.point)
                if (row < 0) return
                val toolName = listModel.getElementAt(row)
                if (selectedTools.contains(toolName)) selectedTools.remove(toolName)
                else selectedTools.add(toolName)
                toolList.repaint()
            }
        })

        val scrollPane = JBScrollPane(toolList)
        add(scrollPane, BorderLayout.CENTER)

        val bottomPanel = JPanel(FlowLayout(FlowLayout.RIGHT))
        val refreshBtn = JButton("刷新", AllIcons.Actions.Refresh)
        refreshBtn.addActionListener { loadTools() }
        val saveBtn = JButton("保存选择", AllIcons.Actions.MenuSaveall)
        saveBtn.addActionListener { saveSelection() }
        bottomPanel.add(refreshBtn)
        bottomPanel.add(saveBtn)
        add(bottomPanel, BorderLayout.SOUTH)

        loadTools()
        loadSelection()
    }

    private fun loadTools() {
        listModel.clear()
        val servers = readMcpServers()
        for (server in servers) {
            for (tool in server.tools) {
                listModel.addElement(tool.name)
            }
        }
        if (listModel.isEmpty()) {
            toolList.emptyText.text = "未检测到 MCP 工具。发送一条消息触发 CLI 工具发现。"
        }
    }

    private fun loadSelection() {
        val file = File(cacheDir(), "active-mcp.json")
        if (!file.exists()) return
        try {
            val root = JsonParser.parseString(file.readText()).asJsonObject
            root.getAsJsonArray("selectedTools")?.forEach { elem ->
                selectedTools.add(elem.asString)
            }
        } catch (_: Exception) {}
    }

    private fun saveSelection() {
        val dir = cacheDir()
        if (!dir.exists()) dir.mkdirs()

        val tools = selectedTools.toList()
        if (tools.isEmpty()) {
            File(dir, "active-mcp.json").delete()
        } else {
            val data = com.google.gson.JsonObject().apply {
                addProperty("updatedAt", java.util.Date().toString())
                add("selectedTools", com.google.gson.JsonArray().apply { tools.forEach { add(it) } })
                addProperty("instruction", "请优先使用以下工具检索相关信息后再回答")
            }
            File(dir, "active-mcp.json").writeText(com.google.gson.Gson().toJson(data))
        }

        Notification(
            "Kilo Code",
            "已${if (tools.isEmpty()) "清除" else "保存"} ${tools.size} 个 MCP 工具选择",
            NotificationType.INFORMATION,
        ).notify(project)
    }

    private fun cacheDir(): File {
        val home = System.getProperty("user.home")
        return File("$home/.config/kilo/.cache")
    }

    private fun readMcpServers(): List<McpServer> {
        val file = File(cacheDir(), "mcp-tools.json")
        if (!file.exists()) return emptyList()
        return try {
            val root = JsonParser.parseString(file.readText()).asJsonObject
            root.getAsJsonArray("servers")?.map { serverElem ->
                val s = serverElem.asJsonObject
                McpServer(
                    name = s.get("name")?.asString ?: "",
                    tools = s.getAsJsonArray("tools")?.map { t ->
                        val to = t.asJsonObject
                        McpTool(
                            name = to.get("name")?.asString ?: "",
                            description = to.get("description")?.asString ?: "",
                        )
                    } ?: emptyList(),
                )
            } ?: emptyList()
        } catch (_: Exception) { emptyList() }
    }
}

private class McpToolRenderer(private val selected: Set<String>) :
    ColoredListCellRenderer<String>() {
    override fun customizeCellRenderer(
        list: JList<out String>,
        value: String,
        index: Int,
        isSelected: Boolean,
        hasFocus: Boolean,
    ) {
        border = JBUI.Borders.empty(6, 8)
        icon = if (this.selected.contains(value)) AllIcons.Actions.Checked else AllIcons.Actions.Cancel
        append(value, SimpleTextAttributes.REGULAR_ATTRIBUTES)
    }
}
