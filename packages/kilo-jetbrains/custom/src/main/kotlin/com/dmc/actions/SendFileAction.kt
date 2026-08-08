package com.dmc.actions

import com.dmc.bridge.DmcSessionResolver
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import ai.kilocode.rpc.dto.PromptPartDto

private val LOG = logger<SendFileAction>()

private const val NOTIFICATION_GROUP = "Kilo Code"

class SendFileAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val vFile = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return

        val relativePath = toRelativePath(project, vFile)

        val part = PromptPartDto(
            type = "file",
            mime = "text/plain",
            url = "file://$relativePath",
            filename = vFile.name,
        )

        LOG.info("SendFile triggered: file=${vFile.name}")

        val manager = DmcSessionResolver.getSessionManager(project)
        if (manager == null) {
            LOG.warn("No Kilo session manager found")
            notify(project, "No active Kilo session. Open the Kilo tool window first.", NotificationType.WARNING)
            return
        }

        try {
            manager.insertPromptText(buildFileText(relativePath, vFile.name))
            notify(project, "File inserted into Kilo prompt", NotificationType.INFORMATION)
        } catch (ex: Exception) {
            LOG.warn("Send failed: ${ex.message}", ex)
            notify(project, "Error: ${ex.message}", NotificationType.ERROR)
        }
    }

    override fun update(e: AnActionEvent) {
        val vFile = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible = vFile != null && !vFile.isDirectory
    }

    private fun toRelativePath(project: Project, vFile: VirtualFile): String {
        val basePath = project.basePath ?: return vFile.path
        return vFile.path.removePrefix(basePath).removePrefix("/")
    }

    private fun buildFileText(path: String, filename: String): String {
        val content = java.io.File(path).takeIf { it.exists() }?.readText() ?: ""
        return buildString {
            appendLine("// $filename")
            appendLine("```")
            appendLine(content)
            append("```")
        }
    }

    private fun notify(project: Project, message: String, type: NotificationType) {
        Notification(NOTIFICATION_GROUP, message, type).notify(project)
    }
}
