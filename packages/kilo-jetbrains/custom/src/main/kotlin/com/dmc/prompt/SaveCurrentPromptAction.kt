package com.dmc.prompt

import com.intellij.icons.AllIcons
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages

class SaveCurrentPromptAction : AnAction("收藏提示词", "收藏当前输入框内容到提示词库", AllIcons.Actions.AddList) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val text = editor.document.text.trim()

        if (text.isEmpty()) return

        val name = Messages.showInputDialog(
            project, "提示词名称：", "收藏提示词", Messages.getQuestionIcon()
        ) ?: return

        if (name.isBlank()) return

        PromptManager.add(name.trim(), text)
        Notification("Kilo Code", "已收藏「${name.trim()}」", NotificationType.INFORMATION).notify(project)
    }

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible = editor != null && editor.document.text.isNotBlank()
    }
}
