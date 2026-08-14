package com.dmc.wiki

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import java.awt.BorderLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.io.File
import javax.swing.DefaultListModel
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities

class MemoryPanel(private val project: Project) : JPanel(BorderLayout()), Refreshable {

    private val listModel = DefaultListModel<String>()
    private val fileList = mutableListOf<File>()
    private val memoryList = JBList(listModel)
    private val searchField = JBTextField()

    init {
        memoryList.selectionMode = ListSelectionModel.SINGLE_SELECTION

        val topPanel = JPanel(BorderLayout())
        topPanel.add(searchField, BorderLayout.CENTER)
        add(topPanel, BorderLayout.NORTH)
        add(JBScrollPane(memoryList), BorderLayout.CENTER)

        searchField.emptyText.text = "搜索记忆..."
        searchField.addKeyListener(object : KeyAdapter() {
            override fun keyReleased(e: KeyEvent) {
                refresh(searchField.text.trim())
            }
        })

        memoryList.addListSelectionListener {
            if (!it.valueIsAdjusting) openSelectedFile()
        }

        refresh("")
    }

    override fun refresh() = refresh("")

    fun refresh(query: String) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val memDir = getMemoryDir()
            var files = memDir?.listFiles { f -> f.extension == "md" }?.sortedByDescending { it.lastModified() }
                ?: emptyList()

            if (query.isNotEmpty()) {
                files = files.filter { f ->
                    f.readText().contains(query, ignoreCase = true)
                }
            }

            val finalFiles = files
            SwingUtilities.invokeLater {
                listModel.clear()
                fileList.clear()
                for (file in finalFiles) {
                    listModel.addElement(file.nameWithoutExtension)
                    fileList.add(file)
                }
                if (finalFiles.isEmpty()) {
                    listModel.addElement("（暂无记忆，在对话中输入 /remember <内容>）")
                }
            }
        }
    }

    private fun openSelectedFile() {
        val index = memoryList.selectedIndex
        if (index < 0 || index >= fileList.size) return

        val file = fileList[index]
        val vFile = LocalFileSystem.getInstance().findFileByIoFile(file) ?: return

        ApplicationManager.getApplication().invokeLater {
            FileEditorManager.getInstance(project).openFile(vFile, true)
        }
    }

    private fun getMemoryDir(): File? {
        val basePath = project.basePath ?: return null
        val lang = if (File("$basePath/.kilo/repowiki/zh").exists()) "zh" else "en"
        val dir = File("$basePath/.kilo/repowiki/$lang/memory")
        return if (dir.exists()) dir else null
    }
}
