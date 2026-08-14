package com.dmc.tools

import com.google.gson.JsonParser
import com.intellij.icons.AllIcons
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.JButton
import javax.swing.JPanel

class JsonToTsPanel : JPanel(BorderLayout()) {

    private val inputArea = JBTextArea()
    private val outputArea = JBTextArea()
    private val statusLabel = JBLabel("粘贴 JSON 后点击转换")

    private val exampleJson = """{
  "name": "用户服务",
  "version": "1.0.0",
  "enabled": true,
  "port": 8080,
  "database": {
    "host": "localhost",
    "port": 3306,
    "name": "mydb",
    "ssl": false
  },
  "features": ["auth", "logging", "cache"],
  "cache": {
    "ttl": 3600,
    "maxSize": null
  },
  "nodes": [
    { "id": 1, "label": "master", "active": true },
    { "id": 2, "label": "slave", "active": false }
  ]
}"""

    init {
        border = JBUI.Borders.empty(8)

        inputArea.lineWrap = true
        inputArea.wrapStyleWord = true
        inputArea.text = exampleJson

        outputArea.lineWrap = true
        outputArea.wrapStyleWord = true
        outputArea.isEditable = true

        val splitter = JBSplitter(false, 0.5f)

        val leftPanel = JPanel(BorderLayout())
        leftPanel.add(JBLabel("JSON 输入:"), BorderLayout.NORTH)
        leftPanel.add(JBScrollPane(inputArea), BorderLayout.CENTER)

        val rightPanel = JPanel(BorderLayout())
        rightPanel.add(JBLabel("TypeScript 输出:"), BorderLayout.NORTH)
        rightPanel.add(JBScrollPane(outputArea), BorderLayout.CENTER)

        splitter.firstComponent = leftPanel
        splitter.secondComponent = rightPanel
        add(splitter, BorderLayout.CENTER)

        val bottomPanel = JPanel(FlowLayout(FlowLayout.LEFT))
        val convertBtn = JButton("转换", AllIcons.Actions.Forward)
        convertBtn.addActionListener { convert() }
        val copyBtn = JButton("复制结果", AllIcons.Actions.Copy)
        copyBtn.addActionListener { copyResult() }
        val clearBtn = JButton("清空", AllIcons.Actions.Cancel)
        clearBtn.addActionListener {
            inputArea.text = ""
            outputArea.text = ""
            statusLabel.text = "已清空"
        }
        bottomPanel.add(convertBtn)
        bottomPanel.add(copyBtn)
        bottomPanel.add(clearBtn)
        bottomPanel.add(statusLabel)
        add(bottomPanel, BorderLayout.SOUTH)
    }

    private fun convert() {
        val json = inputArea.text.trim()
        if (json.isEmpty()) {
            statusLabel.text = "请输入 JSON"
            return
        }

        try {
            val element = JsonParser.parseString(json)
            val generator = TsInterfaceGenerator()
            val result = generator.generate(element)
            outputArea.text = result
            statusLabel.text = "转换成功：${generator.interfaceCount} 个接口"
        } catch (e: Exception) {
            outputArea.text = ""
            statusLabel.text = "JSON 解析失败：${e.message}"
        }
    }

    private fun copyResult() {
        val text = outputArea.text
        if (text.isEmpty()) {
            statusLabel.text = "无内容可复制"
            return
        }
        val selection = java.awt.Toolkit.getDefaultToolkit().systemClipboard
        selection.setContents(java.awt.datatransfer.StringSelection(text), null)
        statusLabel.text = "已复制到剪贴板"
    }
}

private class TsInterfaceGenerator {

    private val interfaces = mutableMapOf<String, MutableMap<String, String>>()
    private val usedNames = mutableSetOf<String>()
    var interfaceCount = 0
        private set

    fun generate(element: com.google.gson.JsonElement): String {
        interfaces.clear()
        usedNames.clear()
        interfaceCount = 0

        val rootName = "RootObject"
        processValue(rootName, element)

        return buildString {
            for ((name, fields) in interfaces) {
                appendLine("/**")
                appendLine(" * $name")
                appendLine(" */")
                appendLine("export interface $name {")
                for ((key, type) in fields) {
                    val comment = generateComment(key, type)
                    if (comment.isNotEmpty()) {
                        appendLine("  /** $comment */")
                    }
                    val optional = if (type.endsWith(" | null")) "?" else ""
                    appendLine("  ${key}$optional: ${type.replace(" | null", " | null")};")
                }
                appendLine("}")
                appendLine()
            }
        }
    }

    private fun processValue(name: String, element: com.google.gson.JsonElement): String {
        return when {
            element.isJsonObject -> {
                val fieldName = name.replaceFirstChar { it.uppercase() }
                val ifaceName = uniqueName(fieldName)
                processObject(ifaceName, element.asJsonObject)
                ifaceName
            }
            element.isJsonArray -> {
                processArray(name, element.asJsonArray)
            }
            element.isJsonNull -> "null"
            element.asString.matches(Regex("-?\\d+")) -> "number"
            element.asString.matches(Regex("-?\\d+\\.\\d+")) -> "number"
            element.asString == "true" || element.asString == "false" -> "boolean"
            else -> "string"
        }
    }

    private fun processObject(name: String, obj: com.google.gson.JsonObject) {
        if (!interfaces.containsKey(name)) {
            interfaces[name] = mutableMapOf()
            interfaceCount++
        }
        val fields = interfaces[name]!!

        for ((key, value) in obj.entrySet()) {
            val type = processValue(key, value)
            if (value.isJsonNull && !fields.containsKey(key)) {
                fields[key] = "${guessTypeFromKey(key)} | null"
            } else if (value.isJsonNull) {
                fields[key] = fields[key]!! + " | null"
            } else {
                fields[key] = type
            }
        }
    }

    private fun processArray(name: String, array: com.google.gson.JsonArray): String {
        if (array.isEmpty()) return "any[]"

        val first = array[0]
        return when {
            first.isJsonObject -> {
                val elemName = uniqueName(name.replaceFirstChar { it.uppercase() }.let {
                    if (it.endsWith("s")) it.dropLast(1) else it
                } + "Item")
                // Merge all array elements for union type
                for (item in array) {
                    if (item.isJsonObject) {
                        processObject(elemName, item.asJsonObject)
                    }
                }
                "$elemName[]"
            }
            first.isJsonArray -> "${processArray(name, first.asJsonArray)}[]"
            else -> "${processValue(name, first)}[]"
        }
    }

    private fun uniqueName(base: String): String {
        var name = base
        var counter = 2
        while (usedNames.contains(name)) {
            name = "$base$counter"
            counter++
        }
        usedNames.add(name)
        return name
    }

    private fun guessTypeFromKey(key: String): String {
        val lower = key.lowercase()
        return when {
            lower.startsWith("is") || lower.startsWith("has") || lower.startsWith("can") -> "boolean"
            lower.contains("count") || lower.contains("size") || lower.contains("port") ||
                lower.contains("id") || lower.contains("num") || lower.contains("age") ||
                lower.contains("index") || lower.contains("page") -> "number"
            lower == "data" || lower == "items" || lower == "list" || lower == "array" -> "any[]"
            else -> "string"
        }
    }

    private fun generateComment(key: String, type: String): String {
        val lower = key.lowercase()
        return when {
            lower.startsWith("is") && type.contains("boolean") -> "是否${key.substring(2).lowercase()}"
            lower.startsWith("has") && type.contains("boolean") -> "是否有${key.substring(3).lowercase()}"
            lower.contains("id") -> "唯一标识符"
            lower == "name" -> "名称"
            lower == "port" -> "端口号"
            lower == "host" -> "主机地址"
            lower == "version" -> "版本号"
            lower == "enabled" -> "是否启用"
            lower.contains("url") -> "链接地址"
            lower.contains("time") || lower.contains("date") -> "时间"
            lower.contains("desc") -> "描述信息"
            lower.contains("count") -> "数量"
            lower.contains("size") -> "大小"
            lower == "ttl" -> "过期时间（秒）"
            else -> ""
        }
    }
}
