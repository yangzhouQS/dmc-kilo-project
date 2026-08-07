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
import ai.kilocode.rpc.dto.PartSourceDto
import ai.kilocode.rpc.dto.PartSourceTextDto
import ai.kilocode.rpc.dto.PromptPartDto

private val LOG = logger<SendSelectionAction>()

private const val NOTIFICATION_GROUP = "Kilo Code"

class SendSelectionAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val vFile = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return

        val selection = editor.selectionModel
        val selectedText = selection.selectedText
        if (selectedText.isNullOrEmpty()) return

        val startLine = selection.selectionStartPosition?.line ?: 0
        val endLine = selection.selectionEndPosition?.line ?: 0
        val relativePath = toRelativePath(project, vFile)

        val part = PromptPartDto(
            type = "file",
            mime = "text/plain",
            url = "file://$relativePath",
            filename = vFile.name,
            source = PartSourceDto(
                type = "file",
                path = relativePath,
                text = PartSourceTextDto(
                    value = selectedText,
                    start = startLine.toDouble(),
                    end = (endLine + 1).toDouble(),
                ),
            ),
        )

        val bridge = DmcBridgeService.getInstance()
        val sent = bridge.sendToSession(project, "Review the following code selection:", listOf(part))

        if (sent) {
            notify(project, "Selection sent to Kilo", NotificationType.INFORMATION)
        } else {
            notify(project, "No active Kilo session. Open the Kilo tool window first.", NotificationType.WARNING)
        }
    }

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible = editor?.selectionModel?.hasSelection() == true
    }

    private fun toRelativePath(project: Project, vFile: VirtualFile): String {
        val basePath = project.basePath ?: return vFile.path
        return vFile.path.removePrefix(basePath).removePrefix("/")
    }

    private fun notify(project: Project, message: String, type: NotificationType) {
        Notification(NOTIFICATION_GROUP, message, type).notify(project)
    }
}
