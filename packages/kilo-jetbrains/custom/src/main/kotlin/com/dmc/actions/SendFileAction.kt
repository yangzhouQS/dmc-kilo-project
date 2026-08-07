package com.dmc.actions

import com.dmc.bridge.DmcBridgeService
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import ai.kilocode.rpc.dto.PromptPartDto

private val LOG = logger<SendFileAction>()

/**
 * Action: right-click in editor -> "Send File to Kilo".
 *
 * Sends the current file as a [PromptPartDto] file reference to the
 * active Kilo session. The CLI will read the file from disk.
 */
class SendFileAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val vFile = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: run {
            LOG.warn("No virtual file")
            return
        }

        val relativePath = toRelativePath(project, vFile)

        val part = PromptPartDto(
            type = "file",
            mime = "text/plain",
            url = "file://$relativePath",
            filename = vFile.name,
        )

        val bridge = DmcBridgeService.getInstance()
        if (!bridge.isReady) {
            LOG.warn("Kilo backend not connected")
            return
        }

        bridge.sendToSession("Review this file:", listOf(part))
    }

    override fun update(e: AnActionEvent) {
        val vFile = e.getData(CommonDataKeys.VIRTUAL_FILE)
        e.presentation.isEnabledAndVisible = vFile != null && !vFile.isDirectory
    }

    private fun toRelativePath(project: Project, vFile: VirtualFile): String {
        val basePath = project.basePath ?: return vFile.path
        return vFile.path.removePrefix(basePath).removePrefix("/")
    }
}
