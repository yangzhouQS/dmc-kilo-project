package com.dmc.wiki

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import java.io.File
import javax.swing.DefaultListModel
import javax.swing.JPanel
import javax.swing.ListSelectionModel

class KnowledgeCardPanel(private val project: Project) : JPanel(BorderLayout()), Refreshable {

    private val listModel = DefaultListModel<String>()
    private val fileList = mutableListOf<File>()
    private val cardList = JBList(listModel)

    init {
        cardList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        add(JBScrollPane(cardList), BorderLayout.CENTER)

        cardList.addListSelectionListener {
            if (!it.valueIsAdjusting) openSelectedFile()
        }

        refresh()
    }

    override fun refresh() {
        ApplicationManager.getApplication().executeOnPooledThread {
            val cardDir = getCardDir()
            val files = cardDir?.listFiles { f -> f.extension == "md" }?.sortedBy { it.name }
                ?: emptyList()

            ApplicationManager.getApplication().invokeLater {
                listModel.clear()
                fileList.clear()
                for (file in files) {
                    listModel.addElement(file.nameWithoutExtension)
                    fileList.add(file)
                }
                if (files.isEmpty()) {
                    listModel.addElement("（暂无知识卡片）")
                }
            }
        }
    }

    private fun openSelectedFile() {
        val index = cardList.selectedIndex
        if (index < 0 || index >= fileList.size) return

        val file = fileList[index]
        val vFile = LocalFileSystem.getInstance().findFileByIoFile(file) ?: return

        ApplicationManager.getApplication().invokeLater {
            FileEditorManager.getInstance(project).openFile(vFile, true)
        }
    }

    private fun getCardDir(): File? {
        val basePath = project.basePath ?: return null
        val lang = if (File("$basePath/.kilo/repowiki/zh").exists()) "zh" else "en"
        val dir = File("$basePath/.kilo/repowiki/$lang/knowledge_cards")
        return if (dir.exists()) dir else null
    }
}
