package com.dmc.actions

import com.dmc.bridge.DmcSessionResolver
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListManager

private val LOG = logger<GenerateCommitMessageAction>()

private const val NOTIFICATION_GROUP = "Kilo Code"
private const val MAX_FILES = 20
private const val MAX_FILE_LINES = 500
private const val MAX_KEEP = 50
private const val MAX_PROMPT = 8000

class GenerateCommitMessageAction : AnAction() {

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = true
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        val allChanges = getChanges(e, project)
        if (allChanges.isEmpty()) {
            notify(project, "当前没有检测到文件变更", NotificationType.WARNING)
            return
        }

        val changes = allChanges.take(MAX_FILES)
        val truncated = allChanges.size > MAX_FILES
        val prompt = buildPrompt(project, changes, truncated)

        LOG.info("GenerateCommitMessage: ${changes.size} files, ${prompt.length} chars")

        val manager = DmcSessionResolver.getSessionManager(project)
        if (manager == null) {
            notify(project, "请先打开 Kilo 工具窗口", NotificationType.WARNING)
            return
        }

        try {
            manager.insertPromptText(prompt)
            notify(project, "提交信息 Prompt 已填充到 Kilo（${changes.size} 个文件）", NotificationType.INFORMATION)
        } catch (ex: Exception) {
            LOG.warn("Commit generation failed: ${ex.message}", ex)
            notify(project, "Error: ${ex.message}", NotificationType.ERROR)
        }
    }

    private fun getChanges(e: AnActionEvent, project: Project): List<Change> {
        val fromContext = e.getData(VcsDataKeys.CHANGES)?.toList()
        if (!fromContext.isNullOrEmpty()) return fromContext

        val fromManager = ChangeListManager.getInstance(project).allChanges.toList()
        if (fromManager.isNotEmpty()) return fromManager

        return emptyList()
    }

    private fun buildPrompt(project: Project, changes: List<Change>, truncated: Boolean): String {
        val sb = StringBuilder()
        sb.appendLine("请根据以下代码变更生成规范的 Git Commit Message（遵循 Conventional Commits 规范）")
        sb.appendLine()
        val countText = if (truncated) "${MAX_FILES}（共 ${changes.size} 个，已截断）" else "${changes.size}"
        sb.appendLine("【变更文件数】：$countText 个文件")
        sb.appendLine("【变更概览】：")

        val basePath = project.basePath ?: ""
        changes.forEach { c ->
            val path = filePath(c, basePath)
            sb.appendLine("  - $path (${typeText(c.type)})")
        }

        sb.appendLine()
        sb.appendLine("【详细变更内容】：")
        sb.appendLine()

        for (change in changes) {
            if (sb.length >= MAX_PROMPT) {
                sb.appendLine("（更多变更已省略，Prompt 总长度超限）")
                break
            }

            val path = filePath(change, basePath)
            val type = change.type
            sb.appendLine("--- $path (${typeText(type)}) ---")

            val after = safeContent { change.afterRevision?.content }
            val before = safeContent { change.beforeRevision?.content }

            when (type) {
                Change.Type.NEW -> appendCode(sb, after)
                Change.Type.DELETED -> sb.appendLine("（文件已删除）")
                Change.Type.MOVED -> { sb.appendLine("（文件移动）"); appendCode(sb, after) }
                else -> appendDiff(sb, before, after)
            }
            sb.appendLine()
        }

        return sb.toString()
    }

    private fun filePath(change: Change, basePath: String): String {
        val path = change.afterRevision?.file?.path
            ?: change.beforeRevision?.file?.path
            ?: return "unknown"
        return if (basePath.isNotEmpty()) path.removePrefix(basePath).removePrefix("/") else path
    }

    private fun safeContent(provider: () -> String?): String {
        return try { provider() ?: "" } catch (_: Exception) { "" }
    }

    private fun appendCode(sb: StringBuilder, content: String) {
        if (content.isEmpty()) { sb.appendLine("（内容为空）"); return }
        sb.appendLine("```"); sb.appendLine(truncateLines(content)); sb.append("```"); sb.appendLine()
    }

    private fun appendDiff(sb: StringBuilder, before: String, after: String) {
        if (before.isEmpty() && after.isEmpty()) { sb.appendLine("（无法读取变更内容）"); return }
        sb.appendLine("```diff")
        val bLines = before.lines()
        val aLines = after.lines()

        if (bLines.size <= MAX_FILE_LINES && aLines.size <= MAX_FILE_LINES) {
            val bSet = bLines.toSet()
            val aSet = aLines.toSet()
            aLines.filter { it.isNotBlank() && it !in bSet }.forEach { sb.append("+ "); sb.appendLine(it) }
            bLines.filter { it.isNotBlank() && it !in aSet }.forEach { sb.append("- "); sb.appendLine(it) }
        } else {
            sb.appendLine("// 文件过大，仅显示前 $MAX_KEEP 行")
            aLines.take(MAX_KEEP).forEach { sb.append("+ "); sb.appendLine(it) }
        }
        sb.append("```"); sb.appendLine()
    }

    private fun truncateLines(content: String): String {
        val lines = content.lines()
        if (lines.size <= MAX_FILE_LINES) return content
        return buildString {
            appendLine(lines.take(MAX_KEEP).joinToString("\n"))
            appendLine("// ... (${lines.size - MAX_KEEP * 2} 行已省略) ...")
            append(lines.takeLast(MAX_KEEP).joinToString("\n"))
        }
    }

    private fun typeText(type: Change.Type?): String = when (type) {
        Change.Type.NEW -> "新增"
        Change.Type.DELETED -> "删除"
        Change.Type.MOVED -> "移动"
        else -> "修改"
    }

    private fun notify(project: Project, message: String, type: NotificationType) {
        Notification(NOTIFICATION_GROUP, message, type).notify(project)
    }
}
