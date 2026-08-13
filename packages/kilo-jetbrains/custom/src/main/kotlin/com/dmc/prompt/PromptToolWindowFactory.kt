package com.dmc.prompt

import com.dmc.bridge.DmcSessionResolver
import com.intellij.icons.AllIcons
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBSplitter
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.awt.GridLayout
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.io.File
import java.text.SimpleDateFormat
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JFileChooser
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.SwingConstants

class PromptToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = PromptMainPanel(project)
        val content = com.intellij.ui.content.ContentFactory.getInstance().createContent(panel, "", false)
        toolWindow.contentManager.addContent(content)
    }
}

class PromptMainPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val listModel = DefaultListModel<PromptItem>()
    private val promptList = JBList(listModel)
    private val searchField = JBTextField()

    private val nameField = JBTextField()
    private val categoryCombo = JComboBox<String>()
    private val contentArea = JBTextArea()
    private val timeLabel = JBLabel("")
    private var currentId: String? = null

    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm")

    init {
        border = JBUI.Borders.empty(8)

        // 左侧面板
        val leftPanel = JPanel(BorderLayout())
        leftPanel.border = JBUI.Borders.emptyRight(4)

        // 搜索框
        searchField.emptyText.text = "搜索提示词..."
        searchField.border = JBUI.Borders.empty(4, 6)
        searchField.addKeyListener(object : KeyAdapter() {
            override fun keyReleased(e: KeyEvent) = refreshList(searchField.text.trim())
        })
        leftPanel.add(searchField, BorderLayout.NORTH)

        // 列表
        promptList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        promptList.cellRenderer = PromptCellRenderer()
        leftPanel.add(JBScrollPane(promptList), BorderLayout.CENTER)

        // 左侧底部按钮栏
        val leftButtons = JPanel(FlowLayout(FlowLayout.LEFT, 4, 4))
        leftButtons.add(createButton("新增", AllIcons.General.Add) { addNew() })
        leftButtons.add(createButton("删除", AllIcons.Actions.Cancel) { deleteSelected() })
        leftButtons.add(createButton("置顶", AllIcons.Actions.MoveUp) { toggleTopSelected() })
        leftButtons.add(createButton("导出", AllIcons.Actions.Download) { exportPrompts() })
        leftButtons.add(createButton("导入", AllIcons.Actions.Upload) { importPrompts() })
        leftPanel.add(leftButtons, BorderLayout.SOUTH)

        // 右侧面板
        val rightPanel = JPanel(BorderLayout())
        rightPanel.border = JBUI.Borders.emptyLeft(4)

        // 右侧顶部：名称 + 分类 + 时间
        val topPanel = JPanel(BorderLayout(8, 4))
        topPanel.border = JBUI.Borders.emptyBottom(6)

        val formPanel = JPanel(GridLayout(2, 2, 8, 4))
        formPanel.add(JBLabel("名称:").apply { horizontalAlignment = SwingConstants.RIGHT })
        formPanel.add(nameField)

        categoryCombo.isEditable = true
        categoryCombo prototypeDisplayValue = "代码生成"
        formPanel.add(JBLabel("分类:").apply { horizontalAlignment = SwingConstants.RIGHT })
        formPanel.add(categoryCombo)

        topPanel.add(formPanel, BorderLayout.CENTER)
        topPanel.add(timeLabel, BorderLayout.SOUTH)
        rightPanel.add(topPanel, BorderLayout.NORTH)

        // 内容编辑区
        val contentPanel = JPanel(BorderLayout())
        contentPanel.border = JBUI.Borders.emptyTop(4)
        contentPanel.add(JBLabel("提示词内容:"), BorderLayout.NORTH)
        contentArea.lineWrap = true
        contentArea.wrapStyleWord = true
        contentArea.border = JBUI.Borders.empty(6)
        contentPanel.add(JBScrollPane(contentArea), BorderLayout.CENTER)
        rightPanel.add(contentPanel, BorderLayout.CENTER)

        // 右侧底部操作按钮
        val bottomButtons = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 4))
        bottomButtons.border = JBUI.Borders.emptyTop(4)
        bottomButtons.add(createButton("保存", AllIcons.Actions.MenuSaveall) { saveCurrent() })
        bottomButtons.add(createButton("AI 优化", AllIcons.Actions.InlayGlobe) { optimizeCurrent() })
        bottomButtons.add(createButton("插入到 Kilo", AllIcons.Actions.Forward) { insertToKilo() })
        rightPanel.add(bottomButtons, BorderLayout.SOUTH)

        // 分割
        val splitter = JBSplitter(false, 0.38f)
        splitter.firstComponent = leftPanel
        splitter.secondComponent = rightPanel
        add(splitter, BorderLayout.CENTER)

        // 事件
        promptList.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) {
                promptList.selectedValue?.let { loadItem(it) }
            }
        }

        refreshList("")
    }

    private fun createButton(text: String, icon: javax.swing.Icon, action: () -> Unit): JButton {
        return JButton(text, icon).apply {
            border = JBUI.Borders.empty(3, 8)
            addActionListener { action() }
        }
    }

    private fun refreshList(query: String) {
        val items = if (query.isEmpty()) PromptManager.getAll() else PromptManager.search(query)
        listModel.clear()
        items.forEach { listModel.addElement(it) }
        updateCategoryCombo()
    }

    private fun updateCategoryCombo() {
        val categories = PromptManager.getCategories().toMutableList()
        if (categories.isEmpty()) categories.add("通用")
        val currentSelection = categoryCombo.selectedItem as? String
        categoryCombo.model = DefaultComboBoxModel(categories.toTypedArray())
        if (currentSelection != null && categories.contains(currentSelection)) {
            categoryCombo.selectedItem = currentSelection
        }
    }

    private fun loadItem(item: PromptItem) {
        currentId = item.id
        nameField.text = item.name
        if (categoryCombo.model as? DefaultComboBoxModel<*> != null) {
            val model = categoryCombo.model as DefaultComboBoxModel<String>
            if (model.getIndexOf(item.category) < 0) {
                model.addElement(item.category)
            }
        }
        categoryCombo.selectedItem = item.category
        contentArea.text = item.content

        val created = dateFormat.format(java.util.Date(item.createTime))
        val updated = dateFormat.format(java.util.Date(item.updateTime))
        timeLabel.text = if (item.createTime == item.updateTime) {
            "创建于 $created"
        } else {
            "创建于 $created · 更新于 $updated"
        }
    }

    private fun addNew() {
        currentId = null
        nameField.text = ""
        contentArea.text = ""
        categoryCombo.selectedItem = "通用"
        timeLabel.text = ""
        nameField.requestFocus()
    }

    private fun saveCurrent() {
        val name = nameField.text.trim()
        val content = contentArea.text.trim()
        val category = (categoryCombo.editor.item as? String)?.trim()?.ifEmpty { "通用" }
            ?: (categoryCombo.selectedItem as? String)?.ifEmpty { "通用" } ?: "通用"

        if (name.isEmpty()) {
            notify("名称不能为空")
            return
        }

        if (currentId != null) {
            PromptManager.update(currentId!!, name = name, content = content, category = category)
            notify("已更新「$name」")
        } else {
            PromptManager.add(name, content, category)
            notify("已新增「$name」")
        }
        refreshList(searchField.text.trim())
    }

    private fun deleteSelected() {
        val item = promptList.selectedValue ?: return
        val confirmed = Messages.showYesNoDialog(
            project, "确认删除「${item.name}」？", "删除提示词", Messages.getWarningIcon()
        )
        if (confirmed == Messages.YES) {
            PromptManager.remove(item.id)
            refreshList(searchField.text.trim())
            if (currentId == item.id) addNew()
            notify("已删除「${item.name}」")
        }
    }

    private fun toggleTopSelected() {
        val item = promptList.selectedValue ?: return
        PromptManager.toggleTop(item.id)
        refreshList(searchField.text.trim())
        notify(if (item.isTop) "已取消置顶" else "已置顶「${item.name}」")
    }

    private fun optimizeCurrent() {
        val content = contentArea.text.trim()
        if (content.isEmpty()) {
            notify("内容为空，无法优化")
            return
        }

        val manager = DmcSessionResolver.getSessionManager(project)
        if (manager == null) {
            notify("请先打开 Kilo 工具窗口")
            return
        }

        val prompt = buildString {
            appendLine("你是专业 AI 提示词优化专家。请优化以下提示词，不改变核心诉求：")
            appendLine()
            appendLine("---")
            appendLine(content)
            appendLine("---")
            appendLine()
            appendLine("优化要求：")
            appendLine("1. 逻辑分层清晰，消除歧义")
            appendLine("2. 增加约束条件（输出格式、返回要求）")
            appendLine("3. 适配代码开发场景")
            appendLine("4. 返回优化后的完整提示词")
        }

        try {
            manager.sendPrompt(prompt)
            notify("已发送到 Kilo，AI 正在优化，请在对话区查看结果")
        } catch (ex: Exception) {
            notify("优化失败：${ex.message}")
        }
    }

    private fun insertToKilo() {
        val content = contentArea.text.trim()
        if (content.isEmpty()) {
            notify("内容为空")
            return
        }

        val manager = DmcSessionResolver.getSessionManager(project)
        if (manager == null) {
            notify("请先打开 Kilo 工具窗口")
            return
        }

        manager.insertPromptText(content)
        notify("已插入到 Kilo 输入框")
    }

    private fun exportPrompts() {
        val json = PromptManager.exportToJson()
        val chooser = JFileChooser()
        chooser.selectedFile = File("kilo-prompts.json")
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            chooser.selectedFile.writeText(json, Charsets.UTF_8)
            val count = PromptManager.getAll().size
            notify("已导出 $count 条提示词到 ${chooser.selectedFile.name}")
        }
    }

    private fun importPrompts() {
        val chooser = JFileChooser()
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            try {
                val json = chooser.selectedFile.readText(Charsets.UTF_8)
                val result = PromptManager.importFromJson(json)
                refreshList(searchField.text.trim())
                notify("导入完成：新增 ${result.added} 条，重名 ${result.skipped} 条${result.error?.let { "，$it" } ?: ""}")
            } catch (e: Exception) {
                notify("导入失败：${e.message}")
            }
        }
    }

    private fun notify(message: String) {
        Notification("Kilo Code", message, NotificationType.INFORMATION).notify(project)
    }
}

private class PromptCellRenderer : ColoredListCellRenderer<PromptItem>() {

    private val dateFormat = SimpleDateFormat("MM-dd HH:mm")

    override fun customizeCellRenderer(
        list: JList<out PromptItem>,
        value: PromptItem?,
        index: Int,
        selected: Boolean,
        hasFocus: Boolean,
    ) {
        if (value == null) return
        border = JBUI.Borders.empty(6, 8)

        // 置顶图标
        icon = if (value.isTop) AllIcons.Actions.MoveUp else AllIcons.FileTypes.Text

        // 名称
        append(value.name, SimpleTextAttributes.REGULAR_ATTRIBUTES)

        // 分类标签
        append("  [${value.category}]", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)

        // 换行：创建时间
        append("\n", SimpleTextAttributes.REGULAR_ATTRIBUTES)
        append(dateFormat.format(java.util.Date(value.createTime)), SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)

        // 内容预览（前 40 字符）
        if (value.content.isNotBlank()) {
            append("  ·  ", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            val preview = value.content.lines().firstOrNull()?.take(40)
            if (preview != null) {
                append(preview, SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
            }
        }
    }
}
