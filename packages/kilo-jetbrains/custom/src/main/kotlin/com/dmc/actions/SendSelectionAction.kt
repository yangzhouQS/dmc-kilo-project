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

        LOG.info("SendSelection triggered: file=${vFile.name}, ${selectedText.length} chars")

        val manager = DmcSessionResolver.getSessionManager(project)
        if (manager == null) {
            LOG.warn("No Kilo session manager found")
            notify(project, "No active Kilo session. Open the Kilo tool window first.", NotificationType.WARNING)
            return
        }

        try {
            manager.insertPromptText(buildSelectionText(relativePath, vFile.name, selectedText, startLine, endLine))
            notify(project, "Code inserted into Kilo prompt", NotificationType.INFORMATION)
        } catch (ex: Exception) {
            LOG.warn("Send failed: ${ex.message}", ex)
            notify(project, "Error: ${ex.message}", NotificationType.ERROR)
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

    private fun buildSelectionText(path: String, filename: String, code: String, startLine: Int, endLine: Int): String {
        return buildString {
            appendLine("// $filename (lines ${startLine + 1}-${endLine + 1})")
            append("```")
            appendLine()
            appendLine(code)
            append("```")
            appendLine()
            appendLine("请基于以上代码片段分析，处理一下问题")
        }
    }

    private fun notify(project: Project, message: String, type: NotificationType) {
        Notification(NOTIFICATION_GROUP, message, type).notify(project)
    }
}
