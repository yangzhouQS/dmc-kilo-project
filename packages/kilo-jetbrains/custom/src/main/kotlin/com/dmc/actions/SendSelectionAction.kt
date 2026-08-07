package com.dmc.actions

import com.dmc.bridge.DmcBridgeService
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import ai.kilocode.rpc.dto.PartSourceDto
import ai.kilocode.rpc.dto.PartSourceTextDto
import ai.kilocode.rpc.dto.PromptPartDto

private val LOG = logger<SendSelectionAction>()

/**
 * Action: right-click in editor -> "Send Selection to Kilo".
 *
 * Reads the current text selection, builds a [PromptPartDto] with source
 * range information, and sends it to the active Kilo session.
 */
class SendSelectionAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: run {
            LOG.warn("No active editor")
            return
        }
        val vFile = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: run {
            LOG.warn("No virtual file")
            return
        }

        val selection = editor.selectionModel
        val selectedText = selection.selectedText
        if (selectedText.isNullOrEmpty()) {
            LOG.info("No text selected")
            return
        }

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
        if (!bridge.isReady) {
            LOG.warn("Kilo backend not connected")
            return
        }

        bridge.sendToSession("Fix the following code:", listOf(part))
    }

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible = editor?.selectionModel?.hasSelection() == true
    }

    private fun toRelativePath(project: Project, vFile: VirtualFile): String {
        val basePath = project.basePath ?: return vFile.path
        return vFile.path.removePrefix(basePath).removePrefix("/")
    }
}
