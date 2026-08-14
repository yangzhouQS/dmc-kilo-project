package com.dmc.tools

import com.dmc.mcp.McpConfigReader
import com.dmc.mcp.McpToolProbe
import com.dmc.prompt.McpServerInfo
import com.dmc.prompt.McpToolCache
import com.dmc.prompt.McpToolInfo
import com.intellij.icons.AllIcons
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JButton
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.JTree
import javax.swing.SwingConstants
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeSelectionModel

/**
 * MCP 工具面板：展示全局/工作区注册的 MCP 服务器及其工具清单。
 *
 * 数据源（实时探测，替代旧的 tool.definition 缓存链路）：
 * kilo.jsonc 的 mcp 段 -> IDE 直连各服务器（stdio / streamable HTTP）执行 tools/list
 * -> 写入 ~/.config/kilo/.cache/mcp-tools.json 供 McpSelectorButton 等复用。
 */
class McpToolsPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val selectedTools = mutableSetOf<String>()
    private val probing = AtomicBoolean(false)
    private val statusLabel = JLabel()
    private var tree: Tree? = null
    private val emptyPane = JLabel("未加载。点击\"刷新\"探测已注册的 MCP 服务器。", SwingConstants.CENTER)

    init {
        border = JBUI.Borders.empty(8)
        add(JBScrollPane(emptyPane), BorderLayout.CENTER)

        val bottomPanel = JPanel(BorderLayout())
        bottomPanel.add(statusLabel, BorderLayout.CENTER)
        val buttonRow = JPanel(FlowLayout(FlowLayout.RIGHT))
        val refreshBtn = JButton("刷新（实时探测）", AllIcons.Actions.Refresh)
        refreshBtn.addActionListener { refresh() }
        val saveBtn = JButton("保存选择", AllIcons.Actions.MenuSaveall)
        saveBtn.addActionListener { saveSelection() }
        buttonRow.add(refreshBtn)
        buttonRow.add(saveBtn)
        bottomPanel.add(buttonRow, BorderLayout.EAST)
        add(bottomPanel, BorderLayout.SOUTH)

        loadFromCache()
        loadSelection()
        // 打开面板即自动探测：旧缓存（tool.definition 链路写入的本地工具伪分组）会被立即修正
        refresh()
    }

    private fun refresh() {
        if (!probing.compareAndSet(false, true)) return
        statusLabel.text = "探测中..."
        val projectDir = project.basePath?.let { File(it) }
        ApplicationManager.getApplication().executeOnPooledThread {
            val configs = McpConfigReader.readServers(projectDir)
            val results = McpToolProbe.probeAll(configs)
            val servers = results.map { r ->
                val fullIdTools = r.tools.map { (name, desc) -> McpToolInfo("${r.server.name}_$name", desc) }
                McpServerInfo(
                    name = r.server.name,
                    tools = fullIdTools,
                    status = when {
                        r.error == "disabled" -> "disabled"
                        r.error != null -> "error"
                        else -> "ok"
                    },
                    error = if (r.error == "disabled") "" else (r.error ?: ""),
                )
            }
            McpToolCache.writeTools(servers)
            ApplicationManager.getApplication().invokeLater {
                probing.set(false)
                renderServers(servers)
                val okCount = servers.count { it.status == "ok" }
                val toolCount = servers.sumOf { it.tools.size }
                statusLabel.text = "${okCount}/${servers.size} 个服务器在线，共 ${toolCount} 个工具"
            }
        }
    }

    private fun loadFromCache() {
        renderServers(McpToolCache.readTools())
    }

    private fun renderServers(servers: List<McpServerInfo>) {
        if (servers.isEmpty()) {
            removeAll()
            add(JBScrollPane(emptyPane), BorderLayout.CENTER)
            revalidate()
            repaint()
            return
        }
        val root = DefaultMutableTreeNode("MCP 服务器")
        for (server in servers) {
            val serverNode = DefaultMutableTreeNode(server)
            for (tool in server.tools) {
                serverNode.add(DefaultMutableTreeNode(tool))
            }
            root.add(serverNode)
        }
        val newTree = Tree(DefaultTreeModel(root))
        newTree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        newTree.cellRenderer = McpToolRenderer(selectedTools)
        newTree.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                val path = newTree.getPathForLocation(e.x, e.y) ?: return
                val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
                val tool = node.userObject as? McpToolInfo ?: return
                if (selectedTools.contains(tool.name)) selectedTools.remove(tool.name) else selectedTools.add(tool.name)
                newTree.repaint()
            }
        })

        removeAll()
        add(JBScrollPane(newTree), BorderLayout.CENTER)
        // 底部按钮栏需要重新挂载（removeAll 清掉了）
        val bottomPanel = JPanel(BorderLayout())
        bottomPanel.add(statusLabel, BorderLayout.CENTER)
        val buttonRow = JPanel(FlowLayout(FlowLayout.RIGHT))
        val refreshBtn = JButton("刷新（实时探测）", AllIcons.Actions.Refresh)
        refreshBtn.addActionListener { refresh() }
        val saveBtn = JButton("保存选择", AllIcons.Actions.MenuSaveall)
        saveBtn.addActionListener { saveSelection() }
        buttonRow.add(refreshBtn)
        buttonRow.add(saveBtn)
        bottomPanel.add(buttonRow, BorderLayout.EAST)
        add(bottomPanel, BorderLayout.SOUTH)

        tree = newTree
        revalidate()
        repaint()
    }

    private fun loadSelection() {
        val active = McpToolCache.readActive() ?: return
        selectedTools.addAll(active.selectedTools)
    }

    private fun saveSelection() {
        val tools = selectedTools.toList()
        if (tools.isEmpty()) {
            McpToolCache.clearActive()
        } else {
            McpToolCache.writeActive(tools, "请优先使用以下工具检索相关信息后再回答")
        }
        Notification(
            "Kilo Code",
            "已${if (tools.isEmpty()) "清除" else "保存"} ${tools.size} 个 MCP 工具选择",
            NotificationType.INFORMATION,
        ).notify(project)
    }
}

private class McpToolRenderer(private val selected: Set<String>) :
    ColoredTreeCellRenderer() {
    override fun customizeCellRenderer(
        tree: JTree,
        value: Any,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean,
    ) {
        val node = value as? DefaultMutableTreeNode ?: return
        when (val obj = node.userObject) {
            is McpServerInfo -> {
                when (obj.status) {
                    "ok" -> {
                        icon = AllIcons.Nodes.Folder
                        append(obj.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                        append("  (${obj.tools.size} 工具)", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    }
                    "disabled" -> {
                        icon = AllIcons.Nodes.Folder
                        append(obj.name, SimpleTextAttributes.GRAYED_ATTRIBUTES)
                        append("  已禁用", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    }
                    else -> {
                        icon = AllIcons.Nodes.Folder
                        append(obj.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                        append("  连接失败", SimpleTextAttributes.ERROR_ATTRIBUTES)
                        if (obj.error.isNotEmpty()) {
                            append("  ${obj.error}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                        }
                    }
                }
            }
            is McpToolInfo -> {
                val isChecked = this.selected.contains(obj.name)
                icon = if (isChecked) AllIcons.Actions.Checked else AllIcons.Actions.Cancel
                append(obj.name, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                if (obj.description.isNotEmpty()) {
                    append("  — ${obj.description.lineSequence().first()}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                }
            }
            is String -> {
                icon = AllIcons.Nodes.DataTables
                append(obj, SimpleTextAttributes.REGULAR_ATTRIBUTES)
            }
        }
    }
}
