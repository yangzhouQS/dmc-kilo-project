package com.dmc.wiki

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import com.intellij.util.messages.MessageBusConnection
import javax.swing.JTabbedPane
import javax.swing.SwingUtilities

class WikiToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val wikiBrowser = WikiBrowserPanel(project)
        val cardPanel = KnowledgeCardPanel(project)
        val memoryPanel = MemoryPanel(project)

        val tabs = JTabbedPane()
        tabs.addTab("Wiki 文档", wikiBrowser)
        tabs.addTab("知识卡片", cardPanel)
        tabs.addTab("记忆", memoryPanel)

        val content = ContentFactory.getInstance().createContent(tabs, "", false)
        toolWindow.contentManager.addContent(content)

        setupFileWatcher(project, wikiBrowser, cardPanel, memoryPanel)
    }

    private fun setupFileWatcher(
        project: Project,
        vararg panels: Refreshable,
    ) {
        val basePath = project.basePath ?: return
        val wikiRoot = "$basePath/.kilo/repowiki"

        val connection: MessageBusConnection = project.messageBus.connect()
        connection.subscribe(
            VirtualFileManager.VFS_CHANGES,
            object : BulkFileListener {
                override fun after(events: List<VFileEvent>) {
                    var needRefresh = false
                    for (event in events) {
                        val path = event.path ?: continue
                        if (path.contains(".kilo/repowiki") && path.endsWith(".md")) {
                            needRefresh = true
                            break
                        }
                    }
                    if (needRefresh) {
                        SwingUtilities.invokeLater {
                            panels.forEach { it.refresh() }
                        }
                    }
                }
            },
        )

        // 初次加载时也刷新一次
        LocalFileSystem.getInstance().refreshAndFindFileByPath(wikiRoot)
    }

    interface Refreshable {
        fun refresh()
    }
}
