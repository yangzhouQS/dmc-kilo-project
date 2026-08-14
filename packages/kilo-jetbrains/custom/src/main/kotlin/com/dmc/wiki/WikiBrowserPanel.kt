package com.dmc.wiki

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.io.File
import javax.swing.DefaultListModel
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.event.ListSelectionListener

private val LOG = logger<WikiBrowserPanel>()

class WikiBrowserPanel(private val project: Project) : JPanel(BorderLayout()), Refreshable {

    private val listModel = DefaultListModel<String>()
    private val fileList = mutableListOf<File>()
    private val wikiList = JBList(listModel)

    init {
        wikiList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        wikiList.cellRenderer = WikiFileCellRenderer()

        val scrollPane = JBScrollPane(wikiList)

        wikiList.addListSelectionListener(ListSelectionListener { e ->
            if (!e.valueIsAdjusting) {
                openSelectedFile()
            }
        })

        add(scrollPane, BorderLayout.CENTER)
        refresh()
    }

    override fun refresh() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val wikiDir = getWikiDir()
            val files = wikiDir?.listFiles { f -> f.extension == "md" }?.sortedBy { it.name }
                ?: emptyList()

            ApplicationManager.getApplication().invokeLater {
                listModel.clear()
                fileList.clear()
                for (file in files) {
                    listModel.addElement(file.nameWithoutExtension)
                    fileList.add(file)
                }
                if (files.isEmpty()) {
                    listModel.addElement("（暂无 Wiki 文档，右键项目目录 → 生成 Wiki）")
                }
            }
        }
    }

    private fun openSelectedFile() {
        val index = wikiList.selectedIndex
        if (index < 0 || index >= fileList.size) return

        val file = fileList[index]
        val vFile = LocalFileSystem.getInstance().findFileByIoFile(file) ?: return

        ApplicationManager.getApplication().invokeLater {
            FileEditorManager.getInstance(project).openFile(vFile, true)
        }
    }

    private fun getWikiDir(): File? {
        val basePath = project.basePath ?: return null
        val lang = detectLanguage(basePath)
        val dir = File("$basePath/.kilo/repowiki/$lang/wiki")
        return if (dir.exists()) dir else null
    }

    private fun detectLanguage(basePath: String): String {
        return if (File("$basePath/.kilo/repowiki/zh").exists()) "zh" else "en"
    }
}
