package com.dmc.tools

import com.dmc.wiki.KnowledgeCardPanel
import com.dmc.wiki.MemoryPanel
import com.dmc.wiki.WikiBrowserPanel
import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JTabbedPane

class WikiTabsPanel(project: Project) : JTabbedPane() {

    init {
        border = JBUI.Borders.emptyTop(4)

        val wikiPanel = WikiBrowserPanel(project)
        val cardPanel = KnowledgeCardPanel(project)
        val memoryPanel = MemoryPanel(project)

        addTab("Wiki 文档", AllIcons.FileTypes.Text, wikiPanel)
        addTab("知识卡片", AllIcons.Nodes.Folder, cardPanel)
        addTab("记忆", AllIcons.Actions.Preview, memoryPanel)
    }
}
