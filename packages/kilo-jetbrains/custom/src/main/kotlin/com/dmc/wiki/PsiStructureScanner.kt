package com.dmc.wiki

import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.readText
import java.io.File

private val LOG = logger<PsiStructureScanner>()

object PsiStructureScanner {

    private val IGNORED_DIRS = setOf(
        ".git", "node_modules", "dist", "build", "out", "target",
        ".idea", ".vscode", "__generated__", ".gradle", ".kilo",
    )

    private val SOURCE_EXTENSIONS = setOf("kt", "java", "ts", "tsx", "js", "py", "go")

    data class Module(
        val name: String,
        val files: List<FileInfo>,
        val totalLines: Int,
    )

    data class FileInfo(
        val path: String,
        val name: String,
        val lines: Int,
        val exports: List<String>,
    )

    fun scan(targetDir: VirtualFile): Module {
        val files = mutableListOf<FileInfo>()
        var totalLines = 0

        VfsUtilCore.iterateChildrenRecursively(targetDir, { file ->
            if (file.isDirectory) {
                file.name !in IGNORED_DIRS
            } else {
                SOURCE_EXTENSIONS.contains(file.extension)
            }
        }) { file ->
            if (!file.isDirectory && SOURCE_EXTENSIONS.contains(file.extension)) {
                try {
                    val content = file.readText()
                    val lineCount = content.lines().size
                    val exports = extractExports(content, file.extension ?: "")
                    files.add(FileInfo(file.path, file.name, lineCount, exports))
                    totalLines += lineCount
                } catch (e: Exception) {
                    LOG.warn("Failed to read ${file.name}: ${e.message}")
                }
            }
            true
        }

        return Module(targetDir.name, files, totalLines)
    }

    private fun extractExports(content: String, ext: String): List<String> {
        val exports = mutableListOf<String>()
        when (ext) {
            "kt", "java" -> {
                val classPattern = Regex("""(?:class|interface|object|enum\s+class)\s+(\w+)""")
                val funcPattern = Regex("""fun\s+(\w+)\s*\""")
                classPattern.findAll(content).forEach { exports.add("class ${it.groupValues[1]}") }
                funcPattern.findAll(content).forEach { exports.add("fun ${it.groupValues[1]}()") }
            }
            "ts", "tsx", "js" -> {
                Regex("""(?:export\s+)?(?:class|interface|function|const)\s+(\w+)""").findAll(content).forEach {
                    exports.add(it.value.trim())
                }
            }
            "py" -> {
                Regex("""(?:class|def)\s+(\w+)""").findAll(content).forEach {
                    exports.add(it.value.trim())
                }
            }
            "go" -> {
                Regex("""func\s+(\w+)""").findAll(content).forEach {
                    exports.add(it.value.trim())
                }
            }
        }
        return exports.distinct().take(50)
    }

    fun writeToJson(project: Project, modules: Map<String, Module>) {
        val basePath = project.basePath ?: return
        val cacheDir = File("$basePath/.kilo/repowiki/.cache")
        if (!cacheDir.exists()) cacheDir.mkdirs()

        val sb = StringBuilder()
        sb.append("{\n")
        modules.entries.forEachIndexed { index, (key, module) ->
            sb.append("  ").append(quote(key)).append(": {\n")
            sb.append("    \"name\": ").append(quote(module.name)).append(",\n")
            sb.append("    \"totalLines\": ").append(module.totalLines).append(",\n")
            sb.append("    \"files\": [\n")
            module.files.forEachIndexed { fi, f ->
                sb.append("      { \"path\": ").append(quote(f.path))
                sb.append(", \"name\": ").append(quote(f.name))
                sb.append(", \"lines\": ").append(f.lines)
                sb.append(", \"exports\": [")
                f.exports.forEachIndexed { ei, e ->
                    sb.append(quote(e))
                    if (ei < f.exports.lastIndex) sb.append(", ")
                }
                sb.append("] }")
                if (fi < module.files.lastIndex) sb.append(",")
                sb.append("\n")
            }
            sb.append("    ]\n")
            sb.append("  }")
            if (index < modules.size - 1) sb.append(",")
            sb.append("\n")
        }
        sb.append("}\n")

        File(cacheDir, "psi-structure.json").writeText(sb.toString(), Charsets.UTF_8)
    }

    private fun quote(s: String): String {
        return "\"${s.replace("\\", "\\\\").replace("\"", "\\\"")}\""
    }
}
