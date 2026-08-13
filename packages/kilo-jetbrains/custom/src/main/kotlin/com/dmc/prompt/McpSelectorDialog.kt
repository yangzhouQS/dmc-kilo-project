package com.dmc.prompt

import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.JTextArea
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeSelectionModel

class McpSelectorDialog(
    servers: List<McpServerInfo>,
    initiallySelected: Set<String>,
    private val onConfirm: (selectedTools: List<String>, instruction: String) -> Unit,
) : JPanel(BorderLayout()) {

    private val toolStates = mutableMapOf<String, Boolean>()
    private val instructionArea = JTextArea(2, 40)
    private var popup: JBPopup? = null

    init {
        val root = DefaultMutableTreeNode("MCP 服务器")
        for (server in servers) {
            val serverNode = DefaultMutableTreeNode(server)
            for (tool in server.tools) {
                toolStates[tool.name] = initiallySelected.contains(tool.name)
                serverNode.add(DefaultMutableTreeNode(tool))
            }
            root.add(serverNode)
        }

        val tree = Tree(DefaultTreeModel(root))
        tree.selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
        tree.cellRenderer = McpTreeCellRenderer(toolStates)
        tree.isRootVisible = true

        tree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val path = tree.getPathForLocation(e.x, e.y) ?: return
                val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
                val tool = node.userObject as? McpToolInfo ?: return
                toolStates[tool.name] = !(toolStates[tool.name] ?: false)
                tree.repaint()
            }
        })

        val scrollPane = JBScrollPane(tree)
        scrollPane.preferredSize = Dimension(500, 350)
        add(scrollPane, BorderLayout.CENTER)

        val bottomPanel = JPanel(BorderLayout())
        instructionArea.text = "请优先使用以下工具检索相关信息后再回答"
        instructionArea.lineWrap = true
        instructionArea.wrapStyleWord = true
        bottomPanel.add(javax.swing.JLabel("引导指令:"), BorderLayout.NORTH)
        bottomPanel.add(instructionArea, BorderLayout.CENTER)

        val buttonRow = JPanel(FlowLayout(FlowLayout.RIGHT))
        val selectAllBtn = JButton("全选")
        selectAllBtn.addActionListener {
            toolStates.keys.forEach { toolStates[it] = true }
            tree.repaint()
        }
        val clearAllBtn = JButton("清除")
        clearAllBtn.addActionListener {
            toolStates.keys.forEach { toolStates[it] = false }
            tree.repaint()
        }
        val confirmBtn = JButton("确定", AllIcons.Actions.MenuSaveall)
        confirmBtn.addActionListener { confirm() }
        val cancelBtn = JButton("取消", AllIcons.Actions.Cancel)
        cancelBtn.addActionListener { popup?.closeOk(null) }

        buttonRow.add(selectAllBtn)
        buttonRow.add(clearAllBtn)
        buttonRow.add(confirmBtn)
        buttonRow.add(cancelBtn)
        bottomPanel.add(buttonRow, BorderLayout.SOUTH)

        add(bottomPanel, BorderLayout.SOUTH)
    }

    fun setPopup(popup: JBPopup) {
        this.popup = popup
    }

    private fun confirm() {
        val selected = toolStates.filter { it.value }.keys.toList()
        onConfirm(selected, instructionArea.text.trim())
        popup?.closeOk(null)
    }

    private class McpTreeCellRenderer(
        private val checkBoxes: Map<String, Boolean>,
    ) : ColoredTreeCellRenderer() {
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
                    icon = AllIcons.Nodes.Folder
                    append(obj.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    append("  (${obj.tools.size} 工具)", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
                is McpToolInfo -> {
                    val isChecked = checkBoxes[obj.name] ?: false
                    icon = if (isChecked) AllIcons.Actions.Checked else AllIcons.Actions.Cancel
                    append(obj.name, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    if (obj.description.isNotEmpty()) {
                        append("  — ${obj.description}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                    }
                }
                is String -> {
                    icon = AllIcons.Nodes.DataTables
                    append(obj, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                }
            }
        }
    }
}
