package com.dmc.prompt

import com.intellij.icons.AllIcons
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.ui.JBUI
import javax.swing.JButton

class McpSelectorButton(private val project: Project) : JButton() {

    init {
        icon = AllIcons.Nodes.DataTables
        toolTipText = "MCP 知识库选择"
        isFocusPainted = false
        isContentAreaFilled = false
        isBorderPainted = false
        border = JBUI.Borders.empty(2)
        addActionListener { showPopup() }
    }

    private fun showPopup() {
        val servers = McpToolCache.readTools()

        if (servers.isEmpty()) {
            Notification(
                "Kilo Code",
                "未检测到 MCP 工具。请在 kilo.jsonc 中配置 MCP 服务器并重启会话。",
                NotificationType.WARNING,
            ).notify(project)
            return
        }

        val active = McpToolCache.readActive()
        val selectedSet = active?.selectedTools?.toSet() ?: emptySet()

        val panel = McpSelectorDialog(servers, selectedSet) { selectedTools, instruction ->
            if (selectedTools.isEmpty()) {
                McpToolCache.clearActive()
            } else {
                McpToolCache.writeActive(selectedTools, instruction)
            }
        }

        panel.setPopup(
            JBPopupFactory.getInstance()
                .createComponentPopupBuilder(panel, null)
                .setTitle("MCP 知识库选择")
                .setResizable(true)
                .setFocusable(true)
                .createPopup()
                .also { popup -> popup.show(RelativePoint.getSouthWestOf(this)) }
        )
    }
}
