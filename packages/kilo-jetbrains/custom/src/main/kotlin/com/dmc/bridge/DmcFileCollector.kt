package com.dmc.bridge

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.vfs.VirtualFile
import ai.kilocode.client.session.model.PromptAttachment
import ai.kilocode.client.session.model.PromptAttachmentExtractor
import java.io.File

private val LOG = logger<DmcFileCollector>()

private val IGNORED_DIRS = setOf(
    ".git", "node_modules", "dist", "build", "out", "target",
    ".idea", ".vscode", "__generated__", ".gradle", ".kilo-dev",
)

private const val MAX_FILE_SIZE = 500 * 1024L
private const val MAX_FILES = 200

object DmcFileCollector {

    data class Result(
        val attachments: List<PromptAttachment>,
        val skipped: List<String>,
        val truncated: Boolean,
    )

    fun collect(files: Array<VirtualFile>): Result {
        val javaFiles = mutableListOf<File>()
        val skipped = mutableListOf<String>()

        for (vFile in files) {
            if (!vFile.isValid) {
                skipped.add("${vFile.name}（无效文件）")
                continue
            }
            if (vFile.isDirectory) {
                scanDirectory(vFile, javaFiles, skipped)
            } else {
                val ioFile = vFile.toNioPath()?.toFile()
                if (ioFile == null) {
                    skipped.add("${vFile.name}（无法读取路径）")
                } else if (ioFile.length() > MAX_FILE_SIZE) {
                    skipped.add("${vFile.name}（文件过大）")
                } else {
                    javaFiles.add(ioFile)
                }
            }
        }

        val truncated = javaFiles.size > MAX_FILES
        if (truncated) {
            LOG.warn("File count ${javaFiles.size} exceeds limit $MAX_FILES, truncating")
            javaFiles.subList(MAX_FILES, javaFiles.size).clear()
        }

        val attachments = PromptAttachmentExtractor.files(javaFiles)

        if (attachments.size < javaFiles.size) {
            val filtered = javaFiles.size - attachments.size
            skipped.add("$filtered 个文件被过滤（二进制或不可读）")
        }

        return Result(attachments, skipped, truncated)
    }

    private fun scanDirectory(dir: VirtualFile, collected: MutableList<File>, skipped: MutableList<String>) {
        val children = dir.children ?: return
        for (child in children) {
            if (!child.isValid) continue
            if (child.isDirectory) {
                if (child.name in IGNORED_DIRS) continue
                if (collected.size >= MAX_FILES) return
                scanDirectory(child, collected, skipped)
            } else {
                if (collected.size >= MAX_FILES) return
                val ioFile = child.toNioPath()?.toFile() ?: continue
                if (ioFile.length() > MAX_FILE_SIZE) {
                    skipped.add("${child.name}（文件过大）")
                    continue
                }
                collected.add(ioFile)
            }
        }
    }
}
