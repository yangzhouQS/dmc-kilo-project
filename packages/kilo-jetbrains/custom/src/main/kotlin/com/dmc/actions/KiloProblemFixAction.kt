package com.dmc.actions

import com.dmc.bridge.DmcSessionResolver
import com.intellij.analysis.problemsView.Problem
import com.intellij.analysis.problemsView.toolWindow.ProblemNodeI
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.PlatformCoreDataKeys
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile

private val LOG = logger<KiloProblemFixAction>()

private const val NOTIFICATION_GROUP = "Kilo Code"

class KiloProblemFixAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        val nodes = e.getData(PlatformCoreDataKeys.SELECTED_ITEMS) as? Array<*>
        val problem = nodes?.mapNotNull { (it as? ProblemNodeI)?.problem }?.firstOrNull()

        if (problem == null) {
            notify(project, "无法获取当前错误代码信息", NotificationType.WARNING)
            return
        }

        val vFile = e.getData(CommonDataKeys.VIRTUAL_FILE)
        val line = (e.getData(CommonDataKeys.NAVIGATABLE) as? OpenFileDescriptor)?.line ?: -1
        val description = problem.description ?: problem.text ?: "Unknown error"
        val errorCode = extractErrorCode(description)

        val codeSnippet = extractCodeSnippet(vFile, line)
        val filePath = vFile?.path ?: "Unknown file"

        val prompt = buildPrompt(filePath, errorCode, description, codeSnippet)

        LOG.info("KiloProblemFix: file=$filePath, line=$line, error=$errorCode")

        val manager = DmcSessionResolver.getSessionManager(project)
        if (manager == null) {
            notify(project, "No active Kilo session. Open the Kilo tool window first.", NotificationType.WARNING)
            return
        }

        try {
            manager.insertPromptText(prompt)
            notify(project, "问题已填充到 Kilo 输入框", NotificationType.INFORMATION)
        } catch (ex: Exception) {
            LOG.warn("Problem fix failed: ${ex.message}", ex)
            notify(project, "Error: ${ex.message}", NotificationType.ERROR)
        }
    }

    override fun update(e: AnActionEvent) {
        val nodes = e.getData(PlatformCoreDataKeys.SELECTED_ITEMS) as? Array<*>
        val problemCount = nodes?.count { it is ProblemNodeI } ?: 0
        e.presentation.isEnabledAndVisible = problemCount == 1
    }

    private fun extractCodeSnippet(vFile: VirtualFile?, line: Int): String {
        if (vFile == null || line < 0) return ""
        return try {
            val content = VfsUtilCore.loadText(vFile)
            val lines = content.lines()
            if (lines.isEmpty()) return ""
            val start = (line - 2).coerceAtLeast(0)
            val end = (line + 2).coerceAtMost(lines.lastIndex)
            lines.subList(start, end + 1).joinToString("\n")
        } catch (ex: Exception) {
            ""
        }
    }

    private fun extractErrorCode(description: String): String {
        val tsMatch = Regex("""(TS\d+)""").find(description)
        if (tsMatch != null) return tsMatch.value
        val eslintMatch = Regex("""(eslint/[a-z-]+)""", RegexOption.IGNORE_CASE).find(description)
        if (eslintMatch != null) return eslintMatch.value
        return "N/A"
    }

    private fun buildPrompt(filePath: String, errorCode: String, description: String, codeSnippet: String): String {
        return buildString {
            appendLine("请解释以下代码问题并进行修复")
            appendLine()
            appendLine("【文件路径】：$filePath")
            appendLine()
            appendLine("【错误代码】：$errorCode")
            appendLine()
            appendLine("【错误描述】：$description")
            appendLine()
            appendLine("【出错代码片段】")
            appendLine("```")
            if (codeSnippet.isNotEmpty()) {
                appendLine(codeSnippet)
            }
            append("```")
            appendLine()
        }
    }

    private fun notify(project: Project, message: String, type: NotificationType) {
        Notification(NOTIFICATION_GROUP, message, type).notify(project)
    }
}
