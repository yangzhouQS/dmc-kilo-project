package com.dmc.actions

import com.dmc.bridge.DmcBridgeService
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

        val bridge = DmcBridgeService.getInstance()
        val sent = bridge.sendToSession(project, "Review this file:", listOf(part))

        if (sent) {
            notify(project, "File sent to Kilo", NotificationType.INFORMATION)
        } else {
            notify(project, "No active Kilo session. Open the Kilo tool window first.", NotificationType.WARNING)
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

    private fun notify(project: Project, message: String, type: NotificationType) {
        Notification(NOTIFICATION_GROUP, message, type).notify(project)
    }
}
