package com.dmc.actions

import com.dmc.wiki.PsiStructureScanner
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

private val LOG = logger<GenerateWikiAction>()

private const val NOTIFICATION_GROUP = "Kilo Code"

class GenerateWikiAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)
        e.presentation.isEnabledAndVisible = !files.isNullOrEmpty()
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY) ?: return

        ApplicationManager.getApplication().executeOnPooledThread {
            val modules = mutableMapOf<String, PsiStructureScanner.Module>()

            for (file in files) {
                if (file.isDirectory) {
                    try {
                        val module = PsiStructureScanner.scan(file)
                        modules[module.name] = module
                    } catch (ex: Exception) {
                        LOG.warn("Failed to scan ${file.name}: ${ex.message}")
                    }
                }
            }

            if (modules.isEmpty()) {
                notify(project, "请选择目录进行扫描（不支持单文件）", NotificationType.WARNING)
                return@executeOnPooledThread
            }

            PsiStructureScanner.writeToJson(project, modules)

            ApplicationManager.getApplication().invokeLater {
                val totalFiles = modules.values.sumOf { it.files.size }
                val totalLines = modules.values.sumOf { it.totalLines }
                notify(
                    project,
                    "已扫描 ${modules.size} 个模块（${totalFiles} 个文件，${totalLines} 行代码）。\n" +
                        "在 Kilo 对话中输入「请使用 wiki-generate 工具生成文档」触发 AI 生成。",
                    NotificationType.INFORMATION,
                )
            }
        }
    }

    private fun notify(project: Project, message: String, type: NotificationType) {
        Notification(NOTIFICATION_GROUP, message, type).notify(project)
    }
}
