package com.dmc.tools

import com.dmc.prompt.PromptMainPanel
import com.dmc.wiki.KnowledgeCardPanel
import com.dmc.wiki.MemoryPanel
import com.dmc.wiki.WikiBrowserPanel
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Dimension
import javax.swing.DefaultListModel
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants

class KiloToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = KiloToolsPanel(project)
        val content = com.intellij.ui.content.ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }
}

class KiloToolsPanel(project: Project) : JPanel(BorderLayout()) {

    private data class NavItem(
        val label: String,
        val icon: javax.swing.Icon,
        val panelSupplier: () -> JPanel,
    )

    private val cardLayout = CardLayout()
    private val contentPanel = JPanel(cardLayout)
    private val navModel = DefaultListModel<NavItem>()

    init {
        border = JBUI.Borders.empty()

        val navItems = listOf(
            NavItem("Wiki 文档", AllIcons.FileTypes.Text) { WikiBrowserPanel(project) },
            NavItem("知识卡片", AllIcons.Nodes.Folder) { KnowledgeCardPanel(project) },
            NavItem("记忆", AllIcons.Actions.Preview) { MemoryPanel(project) },
            NavItem("提示词库", AllIcons.Actions.EditSource) { PromptMainPanel(project) },
            NavItem("MCP 工具", AllIcons.Nodes.DataTables) { McpToolsPanel(project) },
            NavItem("JSON→TS", AllIcons.FileTypes.Json) { JsonToTsPanel() },
        )

        navItems.forEach { item ->
            navModel.addElement(item)
            contentPanel.add(item.panelSupplier(), item.label)
        }

        val navList = JList(navModel)
        navList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        navList.cellRenderer = NavItemRenderer()
        navList.selectedIndex = 0

        navList.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) {
                val item = navList.selectedValue ?: return@addListSelectionListener
                cardLayout.show(contentPanel, item.label)
            }
        }

        val navPanel = JPanel(BorderLayout())
        navPanel.border = JBUI.Borders.emptyRight(1)
        navPanel.preferredSize = Dimension(150, 0)

        val titleLabel = JLabel("Kilo 工具")
        titleLabel.border = JBUI.Borders.empty(8, 10)
        titleLabel.font = titleLabel.font.deriveFont(java.awt.Font.BOLD, titleLabel.font.size + 1f)
        navPanel.add(titleLabel, BorderLayout.NORTH)
        navPanel.add(JBScrollPane(navList), BorderLayout.CENTER)

        val splitter = JBSplitter(false, 0.16f)
        splitter.firstComponent = navPanel
        splitter.secondComponent = contentPanel

        add(splitter, BorderLayout.CENTER)
    }

    private class NavItemRenderer : ListCellRenderer<NavItem> {
        override fun getListCellRendererComponent(
            list: JList<out NavItem>?,
            value: NavItem?,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean,
        ): java.awt.Component {
            val label = JLabel(value?.label ?: "")
            label.icon = value?.icon
            label.isOpaque = true
            label.border = JBUI.Borders.empty(8, 12)
            label.iconTextGap = JBUI.scale(6)

            if (selected) {
                label.background = list?.selectionBackground ?: java.awt.Color(0x3870B3)
                label.foreground = list?.selectionForeground ?: java.awt.Color.WHITE
            } else {
                label.background = list?.background ?: java.awt.Color.WHITE
                label.foreground = list?.foreground ?: java.awt.Color.BLACK
            }
            return label
        }
    }
}
