package com.dmc.actions

import com.dmc.bridge.DmcFileCollector
import com.dmc.bridge.DmcSessionResolver
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

private val LOG = logger<AddToSessionAction>()

private const val NOTIFICATION_GROUP = "Kilo Code"

class AddToSessionAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
        e.presentation.isEnabledAndVisible = !files.isNullOrEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
        if (files.isNullOrEmpty()) return

        ApplicationManager.getApplication().executeOnPooledThread {
            val result = DmcFileCollector.collect(files)
            LOG.info("AddToSession: ${result.attachments.size} files collected, ${result.skipped.size} skipped")

            ApplicationManager.getApplication().invokeLater {
                val manager = DmcSessionResolver.getSessionManager(project)
                if (manager == null) {
                    notify(project, "请先打开 Kilo 工具窗口", NotificationType.WARNING)
                    return@invokeLater
                }

                if (result.attachments.isEmpty()) {
                    notify(project, "没有可加载的文本文件", NotificationType.WARNING)
                    return@invokeLater
                }

                try {
                    if (manager.activeSessionId() == null) {
                        manager.newSession()
                    }
                    manager.addFileAttachments(result.attachments)
                    val msg = buildString {
                        append("已添加 ${result.attachments.size} 个文件到当前会话")
                        if (result.truncated) append("（已截断至 200 个）")
                    }
                    notify(project, msg, NotificationType.INFORMATION)
                } catch (ex: Exception) {
                    LOG.warn("AddToSession failed: ${ex.message}", ex)
                    notify(project, "添加失败：${ex.message}", NotificationType.ERROR)
                }
            }
        }
    }

    private fun notify(project: Project, message: String, type: NotificationType) {
        Notification(NOTIFICATION_GROUP, message, type).notify(project)
    }
}
